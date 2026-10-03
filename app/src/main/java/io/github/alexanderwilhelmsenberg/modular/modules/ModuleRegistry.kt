package io.github.alexanderwilhelmsenberg.modular.modules

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import io.github.alexanderwilhelmsenberg.modular.moduleapi.IModularModuleService
import io.github.alexanderwilhelmsenberg.modular.moduleapi.ModuleContract
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

/**
 * Discovers external Modular module services and performs the minimal v1 AIDL
 * handshake.
 *
 * Capability authorization and third-party caller trust are intentionally not
 * part of this first slice.
 */
class ModuleRegistry(context: Context) : AutoCloseable {
    private val appContext = context.applicationContext
    private val packageManager = appContext.packageManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val connections = mutableMapOf<ComponentName, ServiceConnection>()

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
            previousByComponent[candidate.component] ?: candidate
        }

        discovered.forEach { candidate ->
            if (candidate.component !in connections) {
                bind(candidate.component)
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
            it.copy(status = ModuleConnectionStatus.CONNECTING, error = null)
        }

        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                val remote = IModularModuleService.Stub.asInterface(service)
                scope.launch {
                    val result = runCatching {
                        withContext(Dispatchers.IO) {
                            val protocolVersion = remote.getProtocolVersion()
                            val supported =
                                ModuleContract.isProtocolSupported(protocolVersion)

                            ModuleRecord(
                                component = name,
                                moduleId = remote.getModuleId(),
                                moduleVersion = remote.getModuleVersion(),
                                protocolVersion = protocolVersion,
                                capabilities = remote.getCapabilities()
                                    ?.toList()
                                    .orEmpty()
                                    .sorted(),
                                status = if (supported) {
                                    ModuleConnectionStatus.CONNECTED
                                } else {
                                    ModuleConnectionStatus.INCOMPATIBLE
                                },
                                error = if (supported) {
                                    null
                                } else {
                                    "Unsupported protocol version " + protocolVersion
                                },
                            )
                        }
                    }

                    result.fold(
                        onSuccess = { record -> replace(name, record) },
                        onFailure = { throwable ->
                            update(name) {
                                it.copy(
                                    status = ModuleConnectionStatus.FAILED,
                                    error = throwable.message
                                        ?: throwable::class.java.simpleName,
                                )
                            }
                        },
                    )
                }
            }

            override fun onServiceDisconnected(name: ComponentName) {
                update(name) {
                    it.copy(status = ModuleConnectionStatus.DISCONNECTED)
                }
            }

            override fun onBindingDied(name: ComponentName) {
                update(name) {
                    it.copy(
                        status = ModuleConnectionStatus.FAILED,
                        error = "Module binding died",
                    )
                }
                unbind(name)
            }

            override fun onNullBinding(name: ComponentName) {
                update(name) {
                    it.copy(
                        status = ModuleConnectionStatus.FAILED,
                        error = "Module returned a null binding",
                    )
                }
                unbind(name)
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

    private fun unbind(component: ComponentName) {
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
}
