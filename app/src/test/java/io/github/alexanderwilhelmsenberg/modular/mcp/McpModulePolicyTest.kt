package io.github.alexanderwilhelmsenberg.modular.mcp

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class McpModulePolicyTest {
    private val descriptor = """[{"name":"status","description":"Read status","readOnly":true,"inputSchema":{"type":"object","properties":{"count":{"type":"integer"}},"required":["count"],"additionalProperties":false}}]"""
    private fun tool(identity: String = "signer-v1", instance: String = "instance-1") =
        McpModulePolicy.descriptors(descriptor, identity, instance, "module").single()

    @Test fun approvalIsBoundToSignerVersionInstanceAndSchema() {
        assertNotEquals(tool().approvalKey, tool(identity = "signer-v2").approvalKey)
        assertNotEquals(tool().approvalKey, tool(instance = "instance-2").approvalKey)
        assertNotEquals(tool().approvalKey, McpModulePolicy.descriptors(
            descriptor.replace("Read status", "Changed contract"), "signer-v1", "instance-1", "module").single().approvalKey)
    }
    @Test fun onlyDeclaredCorrectlyTypedArgumentsAreAccepted() {
        McpModulePolicy.validateArguments(tool(), buildJsonObject { put("count", 3) })
        for (args in listOf("{}", "{\"count\":\"3\"}", "{\"count\":3,\"command\":\"exec\"}", "{\"count\":[]}")) {
            try { McpModulePolicy.validateArguments(tool(), Json.parseToJsonElement(args).jsonObject); fail("Must reject") }
            catch (_: IllegalArgumentException) { }
            catch (_: IllegalStateException) { }
        }
    }
    @Test fun unknownAndMutatingExportsAreDeniedByDefault() {
        val policy = BrokerPolicy()
        assertTrue(policy.allows("launcher.context.current"))
        assertFalse(policy.allows("launcher.context.activate"))
        assertFalse(policy.allows("launcher.apps.launch"))
        assertFalse(policy.allows("launcher.module.invoke"))
        assertFalse(BrokerPolicy(true, true, setOf("approved")).allows("terminal.exec"))
        try { McpModulePolicy.descriptors(descriptor.replace("\"readOnly\":true", "\"readOnly\":false"),
            "signer", "instance", "module"); fail("Must reject mutating exports") }
        catch (_: IllegalArgumentException) { }
    }
}
