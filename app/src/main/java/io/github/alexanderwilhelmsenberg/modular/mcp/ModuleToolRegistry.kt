package io.github.alexanderwilhelmsenberg.modular.mcp

import android.content.Context

import io.github.alexanderwilhelmsenberg.modular.capabilities.BoundedBinderCalls
import io.github.alexanderwilhelmsenberg.modular.capabilities.CapabilityRouter
import io.github.alexanderwilhelmsenberg.modular.composition.ContextManager
import io.github.alexanderwilhelmsenberg.modular.moduleapi.StandardCapabilities
import io.github.alexanderwilhelmsenberg.modular.moduleapi.capabilities.IMcpToolsCapability
import io.github.alexanderwilhelmsenberg.modular.modules.ModuleRegistry
import io.github.alexanderwilhelmsenberg.modular.modules.ModuleConnectionStatus
import io.github.alexanderwilhelmsenberg.modular.security.ModuleSecurityManager
import kotlinx.serialization.json.*

class ModuleToolRegistry(
    private val contexts: ContextManager,
    private val router: CapabilityRouter,
    private val registry: ModuleRegistry,
    private val security: ModuleSecurityManager,
    private val context: Context,
) {
    @Suppress("DEPRECATION")
    private fun generation(packageName: String): String {
        val info = context.packageManager.getPackageInfo(packageName, 0)
        return info.longVersionCode.toString() + "|" + info.lastUpdateTime
    }
    suspend fun discover(): List<ModuleToolCandidate> {
        val active = contexts.active.value
        val result = mutableListOf<ModuleToolCandidate>()
        for (binding in active.resolvedBindings.distinctBy { it.instance.id }.take(16)) {
            val route = router.providersFor(StandardCapabilities.McpTools)
                .singleOrNull { it.providerId == binding.instance.providerId } ?: continue
            val module = registry.modules.value.singleOrNull { it.component == route.component } ?: continue
            val status = security.status(module.component.packageName, router.requestedCoreCapabilities(route))
            val identity = status.identity ?: continue
            if (status.error != null) continue
            val binder = router.acquireModuleBinder(route).getOrThrow()
            val raw = BoundedBinderCalls.call("mcp-list:${module.component}") {
                IMcpToolsCapability.Stub.asInterface(binder).listTools()
            }.getOrThrow()
            val tools = McpModulePolicy.descriptors(raw,
                identity.key + "|" + module.moduleVersion + "|" + module.protocolVersion + "|" + generation(module.component.packageName),
                binding.instance.id, route.providerId)
            result += tools.filter { tool ->
                tool.requiredCapabilities.all { it in status.effectiveGrants }
            }
        }
        return result
    }

    suspend fun invoke(approvalKey: String, args: JsonObject, approved: () -> Set<String>): String {
        require(approvalKey in approved()) { "Module tool has not been approved" }
        val tool = discover().singleOrNull { it.approvalKey == approvalKey }
            ?: error("Module identity, tool schema, instance or grants changed; approve the current tool again")
        McpModulePolicy.validateArguments(tool, args)
        val route = requireNotNull(router.providersFor(StandardCapabilities.McpTools)
            .singleOrNull { it.providerId == tool.providerId })
        val binder = router.acquireModuleBinder(route).getOrThrow()
        return BoundedBinderCalls.call("mcp-call:${route.component}") {
            require(approvalKey in approved()) { "Module tool approval was revoked" }
            require(contexts.active.value.resolvedBindings.any { it.instance.id == tool.instanceId }) {
                "The module instance is no longer active"
            }
            val module = requireNotNull(registry.modules.value.singleOrNull { it.component == route.component })
            require(module.status == ModuleConnectionStatus.CONNECTED)
            val current = security.status(module.component.packageName, router.requestedCoreCapabilities(route))
            require(current.error == null && current.identity != null)
            require(current.identity.key + "|" + module.moduleVersion + "|" + module.protocolVersion + "|" + generation(module.component.packageName) == tool.identityEvidence) {
                "The module signing identity or version changed"
            }
            require(tool.requiredCapabilities.all { it in current.effectiveGrants }) { "A required capability was revoked" }
            val raw = IMcpToolsCapability.Stub.asInterface(binder).callTool(tool.instanceId, tool.name, args.toString())
            require(raw.length <= 32_768) { "Module tool response is too large" }
            Json.parseToJsonElement(raw).toString()
        }.getOrThrow()
    }
}
