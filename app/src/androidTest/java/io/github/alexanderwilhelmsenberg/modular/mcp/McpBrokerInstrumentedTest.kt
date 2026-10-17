package io.github.alexanderwilhelmsenberg.modular.mcp

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.alexanderwilhelmsenberg.modular.apps.*
import io.github.alexanderwilhelmsenberg.modular.capabilities.*
import io.github.alexanderwilhelmsenberg.modular.composition.*
import io.github.alexanderwilhelmsenberg.modular.modules.ModuleRegistry
import io.github.alexanderwilhelmsenberg.modular.security.*
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class McpBrokerInstrumentedTest {
    @Test fun realSdkEndpointAuthenticatesAppliesPolicyAndServesResources() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repository = EmptyApps()
        val grants = DataStoreModuleGrantStateStore(context, "mcp-grants-" + UUID.randomUUID())
        val security = ModuleSecurityManager(AndroidModuleIdentityResolver(context), grants,
            officialBootstrapCapabilities = OfficialModuleBootstrapPolicy.capabilities)
        val registry = ModuleRegistry(context, CoreHostFactory(context, repository, security))
        val router = CapabilityRouter(registry)
        val store = DataStoreCompositionStateStore(context, "mcp-composition-" + UUID.randomUUID())
        val contexts = ContextManager(store, router.routes)
        val policyFile = "mcp-test-" + UUID.randomUUID()
        val broker = McpBroker(context, contexts, store, repository, registry,
            ModuleToolRegistry(contexts, router, registry, security, context), policyFile)
        try {
            assertNull(broker.state.value.endpoint)
            broker.start(0)
            val endpoint = requireNotNull(broker.state.value.endpoint)
            val token = requireNotNull(broker.state.value.token)
            assertEquals(401, request(endpoint, null, rpc(1, "tools/list")).first)
            val initialized = request(endpoint, token, rpc(2, "initialize", buildJsonObject {
                put("protocolVersion", "2025-11-25"); put("capabilities", JsonObject(emptyMap()))
                put("clientInfo", buildJsonObject { put("name", "Core instrumentation"); put("version", "1") })
            }))
            assertEquals(200, initialized.first)
            assertNotNull(Json.parseToJsonElement(initialized.second).jsonObject["result"])
            val listed = request(endpoint, token, rpc(3, "tools/list")).second
            assertTrue(listed.contains("launcher.context.current"))
            assertFalse(listed.contains("launcher.context.activate"))
            val denied = Json.parseToJsonElement(request(endpoint, token, rpc(4, "tools/call", buildJsonObject {
                put("name", "launcher.context.activate")
                put("arguments", buildJsonObject { put("contextId", "missing") })
            })).second).jsonObject
            assertTrue(denied["error"] != null || denied["result"]?.jsonObject?.get("isError")?.jsonPrimitive?.booleanOrNull == true)
            val resource = request(endpoint, token, rpc(5, "resources/read", buildJsonObject {
                put("uri", "launcher://context/current")
            }))
            assertEquals(200, resource.first)
            assertTrue(resource.second.contains("contextId"))
            broker.setPolicy(BrokerPolicy(allowContextChanges = true))
            assertTrue(request(endpoint, token, rpc(6, "tools/list")).second.contains("launcher.context.activate"))
            val browser = request(endpoint, token, rpc(7, "tools/list"), "https://untrusted.example")
            assertEquals(403, browser.first)
            broker.stop()
            assertNull(broker.state.value.token)
            broker.start(0)
            assertNotEquals(token, broker.state.value.token)
            assertEquals(401, request(requireNotNull(broker.state.value.endpoint), token, rpc(8, "tools/list")).first)
            assertTrue(broker.state.value.audit.isNotEmpty())
            broker.stop()
            val restored = McpBroker(context, contexts, store, repository, registry,
                ModuleToolRegistry(contexts, router, registry, security, context), policyFile)
            assertTrue(restored.state.value.policy.allowContextChanges)
            assertTrue(restored.state.value.audit.isNotEmpty())
            assertNull(restored.state.value.token)
            restored.close()
            assertTrue(runCatching { restored.start(0) }.isFailure)
        } finally {
            broker.stop()
            contexts.close(); store.close(); router.close(); registry.close(); grants.close(); repository.close()
        }
    }

    private fun rpc(id: Int, method: String, params: JsonObject = JsonObject(emptyMap())) =
        buildJsonObject { put("jsonrpc", "2.0"); put("id", id); put("method", method); put("params", params) }.toString()

    private fun request(endpoint: String, token: String?, body: String, origin: String? = null): Pair<Int, String> {
        val connection = URL(endpoint).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.connectTimeout = 5000; connection.readTimeout = 10000
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("Accept", "application/json, text/event-stream")
        connection.setRequestProperty("MCP-Protocol-Version", "2025-11-25")
        if (token != null) connection.setRequestProperty("Authorization", "Bearer $token")
        if (origin != null) connection.setRequestProperty("Origin", origin)
        connection.doOutput = true
        val payload = body.toByteArray()
        connection.setFixedLengthStreamingMode(payload.size)
        return try {
            connection.outputStream.use { it.write(payload) }
            val status = connection.responseCode
            val response = (if (status >= 400) connection.errorStream else connection.inputStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            status to response
        } finally { connection.disconnect() }
    }
    private class EmptyApps : AppRepository {
        override val catalog = MutableStateFlow(AppCatalog())
        override fun refresh() = Unit
        override suspend fun launch(key: AppKey) = Result.failure<Unit>(IllegalStateException("No apps"))
        override fun close() = Unit
    }
}
