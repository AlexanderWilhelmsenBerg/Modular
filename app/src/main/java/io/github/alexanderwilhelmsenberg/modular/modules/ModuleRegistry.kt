package io.github.alexanderwilhelmsenberg.modular.modules

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Build
import android.os.IBinder
import android.os.RemoteException
import io.github.alexanderwilhelmsenberg.modular.capabilities.CoreHostFactory
import io.github.alexanderwilhelmsenberg.modular.capabilities.CoreHostSession
import io.github.alexanderwilhelmsenberg.modular.moduleapi.CapabilityId
import io.github.alexanderwilhelmsenberg.modular.moduleapi.IModularModuleService
import io.github.alexanderwilhelmsenberg.modular.moduleapi.ModuleContract
import io.github.alexanderwilhelmsenberg.modular.moduleapi.ModuleDescriptorCodec
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Discovers external Modular module services and negotiates their root session.
 *
 * Protocol v1 remains a metadata-only discovery contract.
 * Protocol v2 receives a per-module, revocable Core host Binder.
 */
class ModuleRegistry(
    context: Context,
    private val hostFactory: CoreHostFactory,
    val managementStore: ModuleManagementStore = ModuleManagementStore(context),
    private val handshakeTimeoutMillis: Long = 15_000L,
    private val serviceDiscoveryOverride: (() -> List<ResolveInfo>)? = null,
    private val serviceBindingOverride: ((Intent, ServiceConnection, Int) -> Boolean)? = null,
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val packageManager = appContext.packageManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val connections = mutableMapOf<ComponentName, ServiceConnection>()
    private val sessions = ConcurrentHashMap<ComponentName, SessionBinding>()
    private val attempts = ConcurrentHashMap<ComponentName, HandshakeAttempt>()
    private val deadlines = mutableMapOf<ComponentName, Job>()
    @Volatile private var connectionDemand: Set<ComponentName>? = null

    private val _modules = MutableStateFlow<List<ModuleRecord>>(emptyList())
    val modules: StateFlow<List<ModuleRecord>> = _modules.asStateFlow()

    fun refresh() {
        val services = try {
            queryModuleServices()
        } catch (exception: Exception) {
            val detail = if (exception is SecurityException) {
                "SecurityException: ${exception.message ?: "access denied"}"
            } else {
                "${exception::class.java.simpleName}: ${exception.message ?: "service discovery failed"}"
            }
            managementStore.record("system", "discover", detail, isError = true)
            return
        }
        val discovered = services
            .mapNotNull { resolveInfo ->
                val serviceInfo = resolveInfo.serviceInfo ?: return@mapNotNull null
                val component = ComponentName(serviceInfo.packageName, serviceInfo.name)
                val discovery = ModuleDescriptorDiscovery.inspect(packageManager, serviceInfo)
                ModuleRecord(
                    component = component,
                    descriptor = discovery.descriptor,
                    descriptorError = discovery.error,
                    descriptorFingerprint = discovery.fingerprint,
                    moduleId = discovery.descriptor?.moduleId,
                    moduleVersion = discovery.descriptor?.moduleVersion,
                    status = if (discovery.error != null) ModuleConnectionStatus.INCOMPATIBLE
                        else ModuleConnectionStatus.DISCOVERED,
                    error = discovery.error,
                )
            }
            .distinctBy { it.component }
            .sortedBy { it.component.flattenToShortString() }

        val discoveredComponents = discovered.map { it.component }.toSet()
        val previousByComponent = _modules.value.associateBy { it.component }

        discovered.forEach { candidate ->
            val previous = previousByComponent[candidate.component]
            if (previous != null && (previous.descriptorFingerprint != candidate.descriptorFingerprint ||
                    previous.descriptorError != candidate.descriptorError)) {
                unbind(candidate.component)
            }
        }

        connections.keys
            .filterNot { it in discoveredComponents }
            .toList()
            .forEach(::unbind)

        _modules.value = discovered.map { candidate ->
            val previous = previousByComponent[candidate.component]
            val enabled = managementStore.isEnabled(candidate.component)
            val changed = previous != null && (previous.descriptorFingerprint != candidate.descriptorFingerprint ||
                previous.descriptorError != candidate.descriptorError)
            val base = if (changed) candidate else previous ?: candidate
            if (!enabled) {
                base.copy(
                    moduleId = candidate.moduleId ?: previous?.moduleId ?: managementStore.knownProviderId(candidate.component),
                    enabled = false,
                    status = ModuleConnectionStatus.DISABLED,
                )
            } else if (previous != null && !changed) {
                previous.copy(enabled = true, descriptor = candidate.descriptor,
                    descriptorError = candidate.descriptorError,
                    descriptorFingerprint = candidate.descriptorFingerprint)
            } else {
                candidate.copy(moduleId = candidate.moduleId ?: managementStore.knownProviderId(candidate.component))
            }
        }

        discovered.forEach { candidate ->
            val enabled = managementStore.isEnabled(candidate.component)
            val demanded = connectionDemand?.contains(candidate.component) ?: true
            if (!enabled || !demanded || candidate.descriptorError != null) {
                unbind(candidate.component)
                if (candidate.descriptorError != null && enabled) {
                    update(candidate.component) { it.copy(status = ModuleConnectionStatus.INCOMPATIBLE,
                        error = candidate.descriptorError, sessionId = null) }
                }
                if (enabled && !demanded) {
                    update(candidate.component) {
                        val idleStatus = if (it.status in setOf(
                                ModuleConnectionStatus.FAILED,
                                ModuleConnectionStatus.INCOMPATIBLE,
                            )
                        ) it.status else ModuleConnectionStatus.DISCOVERED
                        it.copy(status = idleStatus, sessionId = null)
                    }
                }
            } else if (candidate.component !in connections &&
                _modules.value.firstOrNull { it.component == candidate.component }?.status !in setOf(
                    ModuleConnectionStatus.FAILED,
                    ModuleConnectionStatus.INCOMPATIBLE,
                )
            ) {
                managementStore.record(candidate.component.flattenToShortString(), "discover", "Module service discovered")
                bind(candidate.component)
            }
        }
        val removed = previousByComponent.keys - discoveredComponents
        removed.forEach {
            managementStore.record(it.flattenToShortString(), "discover", "Module service no longer discovered", isError = true)
        }
    }

    /** null keeps all enabled modules eligible; an empty set releases every module connection. */
    fun setConnectionDemand(components: Set<ComponentName>?) {
        val normalized = components?.toSet()
        if (connectionDemand == normalized) return
        connectionDemand = normalized
        refresh()
    }

    fun setEnabled(component: ComponentName, enabled: Boolean) {
        managementStore.setEnabled(component, enabled)
        if (!enabled) {
            unbind(component)
            update(component) {
                it.copy(enabled = false, status = ModuleConnectionStatus.DISABLED, sessionId = null, error = null)
            }
            managementStore.record(component.flattenToShortString(), "disable", "Module disabled")
            return
        }

        update(component) { it.copy(enabled = true, status = ModuleConnectionStatus.DISCOVERED, error = null) }
        managementStore.record(component.flattenToShortString(), "enable", "Module enabled")
        val demanded = connectionDemand?.contains(component) ?: true
        if (demanded && component !in connections) bind(component)
    }

    fun retry(component: ComponentName) {
        val module = modules.value.firstOrNull { it.component == component } ?: return
        if (!module.enabled || !managementStore.isEnabled(component)) return
        unbind(component)
        update(component) { it.copy(status = ModuleConnectionStatus.DISCOVERED, sessionId = null, error = null) }
        managementStore.record(component.flattenToShortString(), "retry", "Retry requested")
        bind(component)
    }

    suspend fun acquireCapabilityBinder(
        component: ComponentName,
        capability: CapabilityId,
        instanceId: String? = null,
        configurationJson: String = "{}",
        expectedSessionId: String? = null,
    ): Result<IBinder> {
        val module = modules.value.firstOrNull {
            it.component == component &&
                it.status == ModuleConnectionStatus.CONNECTED
        } ?: return Result.failure(
            IllegalStateException("Module is not connected"),
        )

        if (capability.wireValue !in module.capabilities) {
            return Result.failure(
                IllegalArgumentException(
                    "Module did not advertise " + capability.wireValue,
                ),
            )
        }

        val session = sessions[component]
            ?: return Result.failure(
                IllegalStateException(
                    "Module does not have an active Core-host session",
                ),
            )

        if (expectedSessionId != null && expectedSessionId != session.sessionId) {
            return Result.failure(IllegalStateException("Module route belongs to a stale session"))
        }

        val v4 = ModuleContract.supportsInstanceConfiguration(module.protocolVersion ?: 0)
        val normalizedConfiguration = try {
            if (v4) {
                val descriptor = requireNotNull(module.descriptor) { "Module descriptor is missing" }
                require(!instanceId.isNullOrBlank()) { "A module instance is required" }
                ModuleDescriptorCodec.normalizeConfiguration(descriptor, configurationJson)
            } else {
                require(configurationJson.toByteArray(Charsets.UTF_8).size <= ModuleDescriptorCodec.MAX_CONFIGURATION_BYTES) {
                    "Configuration exceeds size limit"
                }
                val legacyConfig = runCatching { Json.parseToJsonElement(configurationJson) }.getOrNull()
                require(legacyConfig is JsonObject && legacyConfig.isEmpty()) {
                    "This legacy module cannot use instance configuration"
                }
                "{}"
            }
        } catch (error: Exception) {
            return Result.failure(error)
        }

        return withContext(Dispatchers.IO) {
            runCatching {
                check(sessions[component] === session) { "Module session changed" }
                val binder = if (v4) {
                    session.remote.getInstanceCapabilityBinder(
                        session.sessionId,
                        requireNotNull(instanceId),
                        normalizedConfiguration,
                        capability.wireValue,
                    )
                } else {
                    session.remote.getCapabilityBinder(session.sessionId, capability.wireValue)
                }
                requireNotNull(binder) {
                    "Module returned no Binder for " + capability.wireValue
                }
                check(sessions[component] === session &&
                    modules.value.any { it.component == component && it.sessionId == session.sessionId &&
                        it.status == ModuleConnectionStatus.CONNECTED }) { "Module session changed" }
                binder
            }
        }
    }

    private fun queryModuleServices() =
        serviceDiscoveryOverride?.invoke() ?: if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.queryIntentServices(
                Intent(ModuleContract.SERVICE_ACTION),
                PackageManager.ResolveInfoFlags.of((PackageManager.MATCH_ALL or PackageManager.GET_META_DATA).toLong()),
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.queryIntentServices(
                Intent(ModuleContract.SERVICE_ACTION),
                PackageManager.MATCH_ALL or PackageManager.GET_META_DATA,
            )
        }

    private fun bind(component: ComponentName) {
        if (!managementStore.isEnabled(component)) return
        val descriptorError = modules.value.firstOrNull { it.component == component }?.descriptorError
        if (descriptorError != null) {
            update(component) { it.copy(status = ModuleConnectionStatus.INCOMPATIBLE, error = descriptorError) }
            return
        }
        val demanded = connectionDemand?.contains(component) ?: true
        if (!demanded) return
        if (component in connections) return
        update(component) {
            it.copy(
                status = ModuleConnectionStatus.CONNECTING,
                error = null,
                sessionId = null,
            )
        }

        lateinit var connection: ServiceConnection
        connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                if (connections[name] !== this || !isEligible(name)) {
                    managementStore.record(name.flattenToShortString(), "bind", "Ignored stale service connection", isError = true)
                    return
                }
                val remote = IModularModuleService.Stub.asInterface(service)

                startHandshake(name, remote, connection)
            }

            override fun onServiceDisconnected(name: ComponentName) {
                if (connections[name] !== connection || !isEligible(name)) return
                dropDeadSession(name)
                update(name) {
                    it.copy(
                        status = ModuleConnectionStatus.DISCONNECTED,
                        sessionId = null,
                    )
                }
                managementStore.record(name.flattenToShortString(), "disconnect", "Module disconnected", isError = true)
            }

            override fun onBindingDied(name: ComponentName) {
                if (connections[name] !== connection) return
                dropDeadSession(name)
                update(name) {
                    it.copy(
                        status = ModuleConnectionStatus.FAILED,
                        sessionId = null,
                        error = "Module binding died",
                    )
                }
                managementStore.record(name.flattenToShortString(), "disconnect", "Module binding died", isError = true)
                unbind(name, closeSession = false)
            }

            override fun onNullBinding(name: ComponentName) {
                if (connections[name] !== connection) return
                update(name) {
                    it.copy(
                        status = ModuleConnectionStatus.FAILED,
                        sessionId = null,
                        error = "Module returned a null binding",
                    )
                }
                managementStore.record(name.flattenToShortString(), "bind", "Module returned a null binding", isError = true)
                unbind(name, closeSession = false)
            }
        }

        connections[component] = connection
        scheduleDeadline(component, connection)

        var bindFailure: String? = null
        val bound = try {
            val intent = Intent(ModuleContract.SERVICE_ACTION).setComponent(component)
            serviceBindingOverride?.invoke(intent, connection, Context.BIND_AUTO_CREATE)
                ?: appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        } catch (exception: RuntimeException) {
            bindFailure = "${exception::class.java.simpleName}: ${exception.message ?: "service binding failed"}"
            update(component) {
                it.copy(
                    status = ModuleConnectionStatus.FAILED,
                    error = bindFailure,
                )
            }
            managementStore.record(component.flattenToShortString(), "bind", bindFailure.orEmpty(), isError = true)
            false
        }

        if (!bound) {
            deadlines.remove(component)?.cancel()
            connections.remove(component)
            val error = bindFailure ?: "Android refused the service binding"
            update(component) {
                it.copy(
                    status = ModuleConnectionStatus.FAILED,
                    error = error,
                )
            }
            if (bindFailure == null) {
                managementStore.record(component.flattenToShortString(), "bind", error, isError = true)
            }
        }
    }

    private fun scheduleDeadline(component: ComponentName, connection: ServiceConnection) {
        deadlines.remove(component)?.cancel()
        deadlines[component] = scope.launch {
            delay(handshakeTimeoutMillis)
            if (connections[component] !== connection) return@launch
            cancelAttempt(component)
            update(component) {
                it.copy(
                    status = ModuleConnectionStatus.FAILED,
                    sessionId = null,
                    error = "Module connection timed out",
                )
            }
            managementStore.record(
                component.flattenToShortString(),
                "handshake",
                "Timed out waiting for module connection or handshake",
                isError = true,
            )
            unbind(component)
        }
    }

    private fun startHandshake(
        component: ComponentName,
        remote: IModularModuleService,
        connection: ServiceConnection,
    ) {
        if (!HANDSHAKES_IN_FLIGHT.add(component)) {
            managementStore.record(component.flattenToShortString(), "handshake", "A previous handshake is still running", isError = true)
            update(component) {
                it.copy(status = ModuleConnectionStatus.FAILED, sessionId = null, error = "A previous handshake is still running")
            }
            unbind(component)
            return
        }
        val attempt = HandshakeAttempt()
        attempts[component] = attempt
        try {
            HANDSHAKE_EXECUTOR.execute {
                val result = try {
                    val handshake = performHandshake(component, remote, attempt)
                    if (attempt.cancelled) {
                        Result.failure(CancellationException("Handshake result is stale"))
                    } else {
                        Result.success(handshake)
                    }
                } catch (throwable: Throwable) {
                    Result.failure(throwable)
                } finally {
                    HANDSHAKES_IN_FLIGHT.remove(component)
                }

                scope.launch {
                    if (connections[component] !== connection || attempt.cancelled) {
                        attempts.remove(component, attempt)
                        return@launch
                    }
                    attempts.remove(component, attempt)
                    deadlines.remove(component)?.cancel()
                    result.fold(
                        onSuccess = { handshake ->
                            if (handshake.record.status == ModuleConnectionStatus.CONNECTED &&
                                handshake.sessionId != null && handshake.coreHostSession != null
                            ) {
                                val sessionEstablished = establishSessionDeathTracking(
                                    component = component,
                                    remote = remote,
                                    sessionId = handshake.sessionId,
                                    coreHostSession = handshake.coreHostSession,
                                )
                                if (!sessionEstablished) {
                                    unbind(component)
                                    return@fold
                                }
                            }
                            replace(component, handshake.record.copy(enabled = true))
                            handshake.record.moduleId?.let { managementStore.rememberProviderId(component, it) }
                            if (handshake.record.status == ModuleConnectionStatus.INCOMPATIBLE) {
                                val error = handshake.record.error ?: "Unsupported module protocol"
                                managementStore.record(component.flattenToShortString(), "handshake", error, isError = true)
                                unbind(component)
                            } else {
                                managementStore.record(component.flattenToShortString(), "handshake", "Module handshake completed")
                            }
                        },
                        onFailure = { throwable ->
                            if (throwable is CancellationException) return@fold
                            val cause = generateSequence(throwable) { it.cause }
                                .firstOrNull { it is SecurityException } ?: throwable
                            val detail = when (cause) {
                                is SecurityException -> "SecurityException: ${cause.message ?: "access denied"}"
                                else -> "${cause::class.java.simpleName}: ${cause.message ?: "handshake failed"}"
                            }.take(MAX_ERROR_LENGTH)
                            update(component) {
                                it.copy(status = ModuleConnectionStatus.FAILED, sessionId = null, error = detail)
                            }
                            managementStore.record(component.flattenToShortString(), "handshake", detail, isError = true)
                            unbind(component)
                        },
                    )
                }
            }
        } catch (exception: RejectedExecutionException) {
            HANDSHAKES_IN_FLIGHT.remove(component)
            attempts.remove(component, attempt)
            deadlines.remove(component)?.cancel()
            update(component) {
                it.copy(status = ModuleConnectionStatus.FAILED, error = "Handshake capacity is full")
            }
            managementStore.record(component.flattenToShortString(), "handshake", "Handshake capacity is full", isError = true)
            unbind(component)
        }
    }

    private fun cancelAttempt(component: ComponentName) {
        attempts.remove(component)?.cancel()
    }

    private fun performHandshake(
        component: ComponentName,
        remote: IModularModuleService,
        attempt: HandshakeAttempt,
    ): HandshakeResult {
        if (attempt.cancelled) throw CancellationException("Handshake cancelled")
        val protocolVersion = remote.protocolVersion
        val supported = ModuleContract.isProtocolSupported(protocolVersion)
        val moduleId = remote.moduleId
        val moduleVersion = remote.moduleVersion

        val capabilities = remote.capabilities
            ?.toList()
            .orEmpty()
            .distinct()
            .sorted()

        val invalidCapabilities = capabilities.filter {
            CapabilityId.parse(it) == null
        }

        if (invalidCapabilities.isNotEmpty()) {
            throw IllegalArgumentException(
                "Invalid capability identifiers: " +
                    invalidCapabilities.joinToString(),
            )
        }

        val requestedCoreCapabilities =
            if (
                ModuleContract
                    .supportsCoreCapabilityRequests(
                        protocolVersion,
                    )
            ) {
                remote.requestedCoreCapabilities
                    ?.toList()
                    .orEmpty()
                    .distinct()
                    .sorted()
            } else {
                emptyList()
            }

        val invalidCoreRequests =
            requestedCoreCapabilities.filter {
                CapabilityId.parse(it) == null
            }

        if (invalidCoreRequests.isNotEmpty()) {
            throw IllegalArgumentException(
                "Invalid requested Core capabilities: " +
                    invalidCoreRequests.joinToString(),
            )
        }

        val requestedCoreCapabilityIds =
            requestedCoreCapabilities
                .map { wireValue ->
                    requireNotNull(
                        CapabilityId.parse(wireValue),
                    )
                }
                .toSet()

        val discovered = modules.value.firstOrNull { it.component == component }
            ?: throw CancellationException("Module discovery changed")
        val descriptor = discovered.descriptor

        if (supported && ModuleContract.supportsInstanceConfiguration(protocolVersion)) {
            val descriptorProblem = when {
                discovered.descriptorError != null -> discovered.descriptorError
                descriptor == null -> "Protocol v4 requires a packaged module descriptor. Add the service raw JSON metadata."
                protocolVersion !in descriptor.coreProtocolMin..descriptor.coreProtocolMax ->
                    "Packaged descriptor does not support protocol version $protocolVersion"
                descriptor.moduleId != moduleId || descriptor.moduleVersion != moduleVersion ->
                    "Packaged descriptor identity or version differs from the service handshake"
                descriptor.providedCapabilities.sorted() != capabilities ->
                    "Packaged descriptor capabilities differ from the service handshake"
                descriptor.requestedCoreCapabilities.sorted() != requestedCoreCapabilities ->
                    "Packaged descriptor Core requests differ from the service handshake"
                else -> {
                    val runtime = runCatching { ModuleDescriptorCodec.parse(remote.descriptorJson) }.getOrNull()
                    when {
                        runtime == null -> "Service returned an invalid runtime descriptor"
                        runtime != descriptor -> "Runtime descriptor differs from the packaged descriptor"
                        else -> null
                    }
                }
            }
            if (descriptorProblem != null) {
                return HandshakeResult(
                    record = discovered.copy(
                        moduleId = moduleId, moduleVersion = moduleVersion,
                        protocolVersion = protocolVersion, capabilities = capabilities,
                        requestedCoreCapabilities = requestedCoreCapabilities,
                        status = ModuleConnectionStatus.INCOMPATIBLE, sessionId = null,
                        error = descriptorProblem,
                    ),
                    sessionId = null, coreHostSession = null,
                )
            }
        }

        if (!supported) {
            return HandshakeResult(
                record = discovered.copy(
                    moduleId = moduleId,
                    moduleVersion = moduleVersion,
                    protocolVersion = protocolVersion,
                    capabilities = capabilities,
                    requestedCoreCapabilities =
                        requestedCoreCapabilities,
                    sessionId = null,
                    status = ModuleConnectionStatus.INCOMPATIBLE,
                    error = "Unsupported protocol version " + protocolVersion,
                ),
                sessionId = null,
                coreHostSession = null,
            )
        }

        var coreHostSession: CoreHostSession? = null

        val sessionId = if (ModuleContract.supportsSessions(protocolVersion)) {
            UUID.randomUUID().toString().also { id ->
                if (attempt.cancelled) throw CancellationException("Handshake cancelled")
                val createdHost = runBlocking {
                    hostFactory.create(
                        component = component,
                        sessionId = id,
                        requestedCapabilities = requestedCoreCapabilityIds,
                    )
                }
                coreHostSession = createdHost
                attempt.attach(createdHost)
                if (attempt.cancelled) {
                    createdHost.revoke()
                    throw CancellationException("Handshake cancelled")
                }

                try {
                    remote.openSession(
                        id,
                        requireNotNull(coreHostSession).host,
                    )
                    attempt.sessionOpened(remote, id)
                    if (attempt.cancelled) throw CancellationException("Handshake cancelled")
                } catch (throwable: Throwable) {
                    coreHostSession?.revoke()
                    throw throwable
                }
            }
        } else {
            null
        }

        return HandshakeResult(
            record = discovered.copy(
                moduleId = moduleId,
                moduleVersion = moduleVersion,
                protocolVersion = protocolVersion,
                capabilities = capabilities,
                requestedCoreCapabilities =
                    requestedCoreCapabilities,
                sessionId = sessionId,
                status = ModuleConnectionStatus.CONNECTED,
                error = null,
            ),
            sessionId = sessionId,
            coreHostSession = coreHostSession,
        )
    }

    private fun establishSessionDeathTracking(
        component: ComponentName,
        remote: IModularModuleService,
        sessionId: String,
        coreHostSession: CoreHostSession,
    ): Boolean {
        val binder = remote.asBinder()
        val deathRecipient = IBinder.DeathRecipient {
            scope.launch {
                if (!isEligible(component) || sessions[component]?.sessionId != sessionId) return@launch
                dropDeadSession(component)
                update(component) {
                    it.copy(
                        status = ModuleConnectionStatus.DISCONNECTED,
                        sessionId = null,
                        error = "Module process died",
                    )
                }
                managementStore.record(component.flattenToShortString(), "disconnect", "Module process died", isError = true)
            }
        }

        return try {
            binder.linkToDeath(deathRecipient, 0)
            sessions[component] = SessionBinding(
                remote = remote,
                sessionId = sessionId,
                deathRecipient = deathRecipient,
                coreHostSession = coreHostSession,
            )
            true
        } catch (remoteException: RemoteException) {
            coreHostSession.revoke()
            scheduleCleanup { remote.closeSession(sessionId) }
            update(component) {
                it.copy(
                    status = ModuleConnectionStatus.FAILED,
                    sessionId = null,
                    error = remoteException.message
                        ?: "Module died while opening session",
                )
            }
            managementStore.record(
                component.flattenToShortString(),
                "handshake",
                "RemoteException: ${remoteException.message ?: "Module died while opening session"}",
                isError = true,
            )
            unbind(component)
            false
        }
    }

    private fun replace(component: ComponentName, replacement: ModuleRecord) {
        _modules.update { records ->
            records.map { record ->
                if (record.component == component) replacement else record
            }
        }
    }

    private fun update(
        component: ComponentName,
        transform: (ModuleRecord) -> ModuleRecord,
    ) {
        _modules.update { records ->
            records.map { record ->
                if (record.component == component) transform(record) else record
            }
        }
    }

    private fun closeLiveSession(component: ComponentName) {
        val session = sessions.remove(component) ?: return

        session.coreHostSession.revoke()

        runCatching {
            session.remote.asBinder().unlinkToDeath(
                session.deathRecipient,
                0,
            )
        }

        scheduleCleanup { session.remote.closeSession(session.sessionId) }
    }

    private fun dropDeadSession(component: ComponentName) {
        sessions.remove(component)?.coreHostSession?.revoke()
    }

    private fun scheduleCleanup(action: () -> Unit) {
        try {
            CLEANUP_EXECUTOR.execute { runCatching(action) }
        } catch (_: RejectedExecutionException) {
            // The local host is revoked synchronously; remote close is best effort under saturation.
        }
    }

    private fun isEligible(component: ComponentName): Boolean =
        managementStore.isEnabled(component) &&
            (connectionDemand?.contains(component) ?: true)

    private fun unbind(
        component: ComponentName,
        closeSession: Boolean = true,
    ) {
        deadlines.remove(component)?.cancel()
        cancelAttempt(component)
        if (closeSession) {
            closeLiveSession(component)
        } else {
            dropDeadSession(component)
        }

        val connection = connections.remove(component) ?: return

        try {
            appContext.unbindService(connection)
        } catch (_: IllegalArgumentException) {
            // Already unbound by Android/process death.
        }
    }

    override fun close() {
        connections.keys.toList().forEach(::unbind)
        attempts.keys.toList().forEach(::cancelAttempt)
        deadlines.values.toList().forEach(Job::cancel)
        deadlines.clear()
        scope.cancel()
    }

    private data class HandshakeResult(
        val record: ModuleRecord,
        val sessionId: String?,
        val coreHostSession: CoreHostSession?,
    )

    private data class SessionBinding(
        val remote: IModularModuleService,
        val sessionId: String,
        val deathRecipient: IBinder.DeathRecipient,
        val coreHostSession: CoreHostSession,
    )

    private class HandshakeAttempt {
        private val host = AtomicReference<CoreHostSession?>(null)
        private val openedSession = AtomicReference<Pair<IModularModuleService, String>?>(null)
        private val remoteCloseScheduled = java.util.concurrent.atomic.AtomicBoolean(false)
        @Volatile var cancelled: Boolean = false
            private set

        fun attach(session: CoreHostSession) {
            host.set(session)
            if (cancelled) session.revoke()
        }

        fun cancel() {
            cancelled = true
            host.get()?.revoke()
            closeOpenedSession()
        }

        fun sessionOpened(remote: IModularModuleService, sessionId: String) {
            openedSession.set(remote to sessionId)
            if (cancelled) closeOpenedSession()
        }

        private fun closeOpenedSession() {
            val opened = openedSession.get() ?: return
            if (!remoteCloseScheduled.compareAndSet(false, true)) return
            try {
                CLEANUP_EXECUTOR.execute { runCatching { opened.first.closeSession(opened.second) } }
            } catch (_: RejectedExecutionException) {
                // The root Binder is already revoked; remote close is best effort under saturation.
            }
        }
    }

    private companion object {
        const val MAX_ERROR_LENGTH = 2_048
        val HANDSHAKES_IN_FLIGHT = ConcurrentHashMap.newKeySet<ComponentName>()
        val HANDSHAKE_EXECUTOR = ThreadPoolExecutor(
            2,
            4,
            30L,
            TimeUnit.SECONDS,
            ArrayBlockingQueue(32),
            { task -> Thread(task, "module-handshake").apply { isDaemon = true } },
            ThreadPoolExecutor.AbortPolicy(),
        )
        val CLEANUP_EXECUTOR = ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            ArrayBlockingQueue(32),
            { task -> Thread(task, "module-cleanup").apply { isDaemon = true } },
            ThreadPoolExecutor.AbortPolicy(),
        )
    }
}

