package io.github.alexanderwilhelmsenberg.modular.modules

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.RemoteException
import io.github.alexanderwilhelmsenberg.modular.capabilities.CoreHostFactory
import io.github.alexanderwilhelmsenberg.modular.capabilities.CoreHostSession
import io.github.alexanderwilhelmsenberg.modular.moduleapi.CapabilityId
import io.github.alexanderwilhelmsenberg.modular.moduleapi.IModularModuleService
import io.github.alexanderwilhelmsenberg.modular.moduleapi.ModuleContract
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Discovers external Modular module services and negotiates their root session.
 *
 * Protocol v1 remains a metadata-only discovery contract.
 * Protocol v2 receives a per-module, revocable Core host Binder.
 */
class ModuleRegistry(
    context: Context,
    private val hostFactory: CoreHostFactory,
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val packageManager = appContext.packageManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val connections = mutableMapOf<ComponentName, ServiceConnection>()
    private val sessions = ConcurrentHashMap<ComponentName, SessionBinding>()
    private val preferences = appContext.getSharedPreferences("module-policy", Context.MODE_PRIVATE)
    private var disabled = preferences.getStringSet("disabled", emptySet()).orEmpty().toSet()
    private val policyLock = Mutex()

    suspend fun setEnabled(component: ComponentName, enabled: Boolean): Result<Unit> =
        withContext(Dispatchers.Main.immediate) {
            policyLock.withLock {
                runCatching {
                    val key = component.flattenToString()
                    val next = if (enabled) disabled - key else disabled + key
                    withContext(Dispatchers.IO) {
                        check(preferences.edit().putStringSet("disabled", next).commit()) { "Module policy could not be saved" }
                    }
                    disabled = next
                    if (!enabled) {
                        unbind(component)
                        update(component) { it.copy(status = ModuleConnectionStatus.DISABLED, sessionId = null) }
                    } else {
                        update(component) { it.copy(status = ModuleConnectionStatus.DISCOVERED, error = null) }
                        refresh()
                    }
                }
            }
        }

    private val _modules = MutableStateFlow<List<ModuleRecord>>(emptyList())
    val modules: StateFlow<List<ModuleRecord>> = _modules.asStateFlow()

    fun refresh() {
        val discovered = queryModuleServices()
            .mapNotNull { resolveInfo ->
                val serviceInfo = resolveInfo.serviceInfo ?: return@mapNotNull null
                val component = ComponentName(serviceInfo.packageName, serviceInfo.name)
                ModuleRecord(component = component)
            }
            .distinctBy { it.component }
            .sortedBy { it.component.flattenToShortString() }

        val discoveredComponents = discovered.map { it.component }.toSet()
        val previousByComponent = _modules.value.associateBy { it.component }

        connections.keys
            .filterNot { it in discoveredComponents }
            .toList()
            .forEach(::unbind)

        _modules.value = discovered.map { candidate ->
            val record = previousByComponent[candidate.component] ?: candidate
            if (candidate.component.flattenToString() in disabled) {
                record.copy(status = ModuleConnectionStatus.DISABLED, sessionId = null)
            } else record
        }

        discovered.forEach { candidate ->
            if (candidate.component !in connections && candidate.component.flattenToString() !in disabled) {
                bind(candidate.component)
            }
        }
    }

    suspend fun acquireCapabilityBinder(
        component: ComponentName,
        capability: CapabilityId,
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

        return withContext(Dispatchers.IO) {
            runCatching {
                requireNotNull(
                    session.remote.getCapabilityBinder(
                        session.sessionId,
                        capability.wireValue,
                    ),
                ) {
                    "Module returned no Binder for " + capability.wireValue
                }
            }
        }
    }

    private fun queryModuleServices() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.queryIntentServices(
                Intent(ModuleContract.SERVICE_ACTION),
                PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong()),
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.queryIntentServices(
                Intent(ModuleContract.SERVICE_ACTION),
                PackageManager.MATCH_ALL,
            )
        }

    private fun bind(component: ComponentName) {
        update(component) {
            it.copy(
                status = ModuleConnectionStatus.CONNECTING,
                error = null,
                sessionId = null,
            )
        }

        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                val boundConnection = this
                val remote = IModularModuleService.Stub.asInterface(service)

                scope.launch {
                    val result = runCatching {
                        withContext(Dispatchers.IO) {
                            performHandshake(name, remote)
                        }
                    }

                    result.fold(
                        onSuccess = { handshake ->
                            if (connections[name] !== boundConnection || name.flattenToString() in disabled) {
                                handshake.coreHostSession?.revoke()
                                handshake.sessionId?.let { sessionId ->
                                    withContext(Dispatchers.IO) { runCatching { remote.closeSession(sessionId) } }
                                }
                                return@fold
                            }
                            if (handshake.record.status ==
                                ModuleConnectionStatus.CONNECTED &&
                                handshake.sessionId != null &&
                                handshake.coreHostSession != null
                            ) {
                                val sessionEstablished =
                                    establishSessionDeathTracking(
                                        component = name,
                                        remote = remote,
                                        sessionId = handshake.sessionId,
                                        coreHostSession = handshake.coreHostSession,
                                    )

                                if (!sessionEstablished) {
                                    return@fold
                                }
                            }

                            replace(name, handshake.record)
                        },
                        onFailure = { throwable ->
                            if (connections[name] !== boundConnection) return@fold
                            update(name) {
                                it.copy(
                                    status = ModuleConnectionStatus.FAILED,
                                    sessionId = null,
                                    error = throwable.message
                                        ?: throwable::class.java.simpleName,
                                )
                            }
                        },
                    )
                }
            }

            override fun onServiceDisconnected(name: ComponentName) {
                dropDeadSession(name)
                update(name) {
                    it.copy(
                        status = ModuleConnectionStatus.DISCONNECTED,
                        sessionId = null,
                    )
                }
            }

            override fun onBindingDied(name: ComponentName) {
                dropDeadSession(name)
                update(name) {
                    it.copy(
                        status = ModuleConnectionStatus.FAILED,
                        sessionId = null,
                        error = "Module binding died",
                    )
                }
                unbind(name, closeSession = false)
            }

            override fun onNullBinding(name: ComponentName) {
                update(name) {
                    it.copy(
                        status = ModuleConnectionStatus.FAILED,
                        sessionId = null,
                        error = "Module returned a null binding",
                    )
                }
                unbind(name, closeSession = false)
            }
        }

        connections[component] = connection

        val bound = try {
            appContext.bindService(
                Intent(ModuleContract.SERVICE_ACTION).setComponent(component),
                connection,
                Context.BIND_AUTO_CREATE,
            )
        } catch (securityException: SecurityException) {
            update(component) {
                it.copy(
                    status = ModuleConnectionStatus.FAILED,
                    error = securityException.message ?: "SecurityException",
                )
            }
            false
        }

        if (!bound) {
            connections.remove(component)
            update(component) {
                it.copy(
                    status = ModuleConnectionStatus.FAILED,
                    error = "Android refused the service binding",
                )
            }
        }
    }

    private suspend fun performHandshake(
        component: ComponentName,
        remote: IModularModuleService,
    ): HandshakeResult {
        val protocolVersion = remote.protocolVersion
        val supported = ModuleContract.isProtocolSupported(protocolVersion)

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

        if (!supported) {
            return HandshakeResult(
                record = ModuleRecord(
                    component = component,
                    moduleId = remote.moduleId,
                    moduleVersion = remote.moduleVersion,
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
                coreHostSession = hostFactory.create(
                    component = component,
                    sessionId = id,
                    requestedCapabilities =
                        requestedCoreCapabilityIds,
                )

                try {
                    remote.openSession(
                        id,
                        requireNotNull(coreHostSession).host,
                    )
                } catch (throwable: Throwable) {
                    coreHostSession?.revoke()
                    throw throwable
                }
            }
        } else {
            null
        }

        return HandshakeResult(
            record = ModuleRecord(
                component = component,
                moduleId = remote.moduleId,
                moduleVersion = remote.moduleVersion,
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
                dropDeadSession(component)
                update(component) {
                    it.copy(
                        status = ModuleConnectionStatus.DISCONNECTED,
                        sessionId = null,
                        error = "Module process died",
                    )
                }
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
            runCatching {
                remote.closeSession(sessionId)
            }
            update(component) {
                it.copy(
                    status = ModuleConnectionStatus.FAILED,
                    sessionId = null,
                    error = remoteException.message
                        ?: "Module died while opening session",
                )
            }
            false
        }
    }

    private fun replace(component: ComponentName, replacement: ModuleRecord) {
        _modules.update { records ->
            records.map { record ->
                if (record.component == component && component.flattenToString() !in disabled) replacement else record
            }
        }
    }

    private fun update(
        component: ComponentName,
        transform: (ModuleRecord) -> ModuleRecord,
    ) {
        _modules.update { records ->
            records.map { record ->
                if (record.component == component) {
                    if (component.flattenToString() in disabled) {
                        record.copy(status = ModuleConnectionStatus.DISABLED, sessionId = null)
                    } else transform(record)
                } else record
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

        runCatching {
            session.remote.closeSession(session.sessionId)
        }
    }

    private fun dropDeadSession(component: ComponentName) {
        sessions.remove(component)?.coreHostSession?.revoke()
    }

    private fun unbind(
        component: ComponentName,
        closeSession: Boolean = true,
    ) {
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
}
