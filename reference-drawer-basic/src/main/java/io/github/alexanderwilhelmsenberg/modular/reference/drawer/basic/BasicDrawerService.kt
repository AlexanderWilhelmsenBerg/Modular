package io.github.alexanderwilhelmsenberg.modular.reference.drawer.basic

import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Binder
import android.os.IBinder
import io.github.alexanderwilhelmsenberg.modular.moduleapi.CoreCapabilities
import io.github.alexanderwilhelmsenberg.modular.moduleapi.IModularHost
import io.github.alexanderwilhelmsenberg.modular.moduleapi.IModularModuleService
import io.github.alexanderwilhelmsenberg.modular.moduleapi.ModuleContract
import io.github.alexanderwilhelmsenberg.modular.moduleapi.StandardCapabilities
import io.github.alexanderwilhelmsenberg.modular.moduleapi.capabilities.IAppLaunchCapability
import io.github.alexanderwilhelmsenberg.modular.moduleapi.capabilities.ISurfaceDrawerCapability
import io.github.alexanderwilhelmsenberg.modular.moduleapi.capabilities.IMcpToolsCapability
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import io.github.alexanderwilhelmsenberg.modular.moduleapi.model.AppSortRequestParcel
import io.github.alexanderwilhelmsenberg.modular.moduleapi.model.AppLaunchResultParcel
import io.github.alexanderwilhelmsenberg.modular.moduleapi.model.SurfaceActionResultParcel
import io.github.alexanderwilhelmsenberg.modular.moduleapi.model.SurfaceDocumentParcel
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class BasicDrawerService : Service() {
    private val sessions = ConcurrentHashMap<String, Session>()
    private val root = object : IModularModuleService.Stub() {
        override fun getProtocolVersion() = ModuleContract.CURRENT_PROTOCOL_VERSION
        override fun getModuleId() = "modular.reference.drawer.basic"
        override fun getModuleVersion() = "0.1.0"
        override fun getCapabilities() = arrayOf(StandardCapabilities.SurfaceDrawer.wireValue, StandardCapabilities.McpTools.wireValue)
        override fun getRequestedCoreCapabilities() = arrayOf(CoreCapabilities.AppsCatalog.wireValue, CoreCapabilities.AppsLaunch.wireValue)
        override fun openSession(sessionId: String, host: IModularHost) {
            enforceTrustedCaller()
            require(sessionId.isNotBlank())
            val session = Session(host, Binder.getCallingUid())
            sessions.put(sessionId, session)?.revoke()
            session.death = IBinder.DeathRecipient {
                sessions.remove(sessionId, session)
                session.active.set(false)
            }
            try {
                host.asBinder().linkToDeath(session.death, 0)
            } catch (failure: Exception) {
                sessions.remove(sessionId, session)
                session.revoke()
                throw failure
            }
        }
        override fun closeSession(sessionId: String) {
            enforceTrustedCaller()
            val session = sessions[sessionId] ?: return
            session.enforce()
            sessions.remove(sessionId, session)
            session.revoke()
        }
        override fun getCapabilityBinder(sessionId: String, capabilityId: String): IBinder? {
            enforceTrustedCaller()
            val session = sessions[sessionId] ?: return null
            session.enforce()
            return when (capabilityId) {
                StandardCapabilities.SurfaceDrawer.wireValue -> session.surface
                StandardCapabilities.McpTools.wireValue -> session.tools
                else -> null
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = root
    override fun onDestroy() {
        sessions.values.forEach { it.revoke() }
        sessions.clear()
        super.onDestroy()
    }
    private fun enforceTrustedCaller() {
        val packages = packageManager.getPackagesForUid(Binder.getCallingUid()).orEmpty()
        if (packages.none { packageManager.checkSignatures(packageName, it) == PackageManager.SIGNATURE_MATCH }) {
            throw SecurityException("Caller does not share the trusted signer")
        }
    }

    private inner class Session(val host: IModularHost, val uid: Int) {
        val active = AtomicBoolean(true)
        lateinit var death: IBinder.DeathRecipient
        private val instances = mutableMapOf<String, DrawerSurface>()
        fun enforce() {
            check(active.get()) { "Drawer session is closed" }
            if (Binder.getCallingUid() != uid) throw SecurityException("Wrong session caller")
        }
        fun revoke() {
            active.set(false)
            if (::death.isInitialized) runCatching { host.asBinder().unlinkToDeath(death, 0) }
            synchronized(instances) { instances.clear() }
        }
        val surface = object : ISurfaceDrawerCapability.Stub() {
            override fun render(instanceId: String, configurationJson: String, apps: AppSortRequestParcel): SurfaceDocumentParcel {
                enforce()
                require(instanceId.isNotBlank())
                require(apps.items.size <= AppSortRequestParcel.MAX_ITEMS)
                val model = synchronized(instances) {
                    instances[instanceId] ?: run {
                        require(instances.size < 128) { "Too many drawer instances; reconnect module" }
                        DrawerSurface().also { instances[instanceId] = it }
                    }
                }
                return model.render(configurationJson, apps.items)
            }
            override fun performAction(instanceId: String, actionId: String): SurfaceActionResultParcel {
                enforce()
                val model = synchronized(instances) { instances[instanceId] }
                    ?: return SurfaceActionResultParcel(SurfaceActionResultParcel.STATUS_INVALID_INSTANCE)
                return try {
                    val handled = model.action(actionId) { key ->
                        // Reacquire for every action. Core checks the current
                        // identity, session and grant even on retained Binders.
                        val launchBinder = requireNotNull(host.getCapabilityBinder(CoreCapabilities.AppsLaunch.wireValue)) {
                            "App launch permission is not granted"
                        }
                        val result = IAppLaunchCapability.Stub.asInterface(launchBinder).launch(key)
                        require(result.status == AppLaunchResultParcel.STATUS_SUCCESS) {
                            result.message ?: "Application could not be launched"
                        }
                    }
                    SurfaceActionResultParcel(if (handled) SurfaceActionResultParcel.STATUS_SUCCESS
                        else SurfaceActionResultParcel.STATUS_UNKNOWN_ACTION)
                } catch (failure: Exception) {
                    SurfaceActionResultParcel(SurfaceActionResultParcel.STATUS_FAILED, failure.message?.take(512))
                }
            }
        }
        val tools = object : IMcpToolsCapability.Stub() {
            override fun listTools(): String {
                enforce()
                return """[{"name":"drawer.status","description":"Read this Drawer instance's page status","readOnly":true,"inputSchema":{"type":"object","properties":{},"additionalProperties":false}}]"""
            }
            override fun callTool(instanceId: String, name: String, argumentsJson: String): String {
                enforce()
                require(name == "drawer.status")
                require(argumentsJson.length <= 8192 && Json.parseToJsonElement(argumentsJson).jsonObject.isEmpty())
                return synchronized(instances) { instances[instanceId]?.status() ?: "{\"ready\":false}" }
            }
        }
    }
}
