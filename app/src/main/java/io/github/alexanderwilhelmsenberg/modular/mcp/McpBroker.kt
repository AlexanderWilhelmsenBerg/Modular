package io.github.alexanderwilhelmsenberg.modular.mcp

import android.content.Context
import io.github.alexanderwilhelmsenberg.modular.apps.AppKey
import io.github.alexanderwilhelmsenberg.modular.apps.AppRepository
import io.github.alexanderwilhelmsenberg.modular.composition.CompositionStateStore
import io.github.alexanderwilhelmsenberg.modular.composition.ContextManager
import io.github.alexanderwilhelmsenberg.modular.modules.ModuleRegistry
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.bodylimit.RequestBodyLimit
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.*
import io.ktor.serialization.kotlinx.json.json
import io.modelcontextprotocol.kotlin.sdk.server.*
import io.modelcontextprotocol.kotlin.sdk.types.*
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

data class BrokerAudit(
    val time: Long, val correlationId: String, val client: String,
    val tool: String, val decision: String, val durationMs: Long,
    val targetModule: String? = null, val targetInstance: String? = null,
)

data class BrokerState(
    val endpoint: String? = null,
    val token: String? = null,
    val policy: BrokerPolicy = BrokerPolicy(),
    val tools: List<ModuleToolCandidate> = emptyList(),
    val audit: List<BrokerAudit> = emptyList(),
    val error: String? = null,
)

/** Local bearer-authenticated, default-off external boundary. Never a Binder bypass. */
class McpBroker(
    context: Context,
    private val contexts: ContextManager,
    private val composition: CompositionStateStore,
    private val apps: AppRepository,
    private val modules: ModuleRegistry,
    private val moduleTools: ModuleToolRegistry,
    preferencesName: String = "mcp-policy",
) : AutoCloseable {
    private val prefs = context.applicationContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    private val lock = Mutex()
    private val auditLock = Mutex()
    private val lastAuthAudit = AtomicLong(0)
    private var stopServer: (() -> Unit)? = null
    private val lifecycleLock = Any()
    private var closed = false
    private val mutableState = MutableStateFlow(BrokerState(policy = BrokerPolicy(
        prefs.getBoolean("contextChanges", false), prefs.getBoolean("appLaunch", false),
        prefs.getStringSet("approvedModuleTools", emptySet()).orEmpty().toSet(),
    ), audit = readAudit()))
    val state: StateFlow<BrokerState> = mutableState.asStateFlow()

    suspend fun setPolicy(policy: BrokerPolicy) = lock.withLock {
        withContext(Dispatchers.IO) {
            check(prefs.edit().putBoolean("contextChanges", policy.allowContextChanges)
                .putBoolean("appLaunch", policy.allowAppLaunch)
                .putStringSet("approvedModuleTools", policy.approvedModuleTools).commit()) { "MCP policy could not be saved" }
        }
        mutableState.update { it.copy(policy = policy, error = null) }
    }

    suspend fun refreshTools() {
        try {
            val tools = moduleTools.discover()
            mutableState.update { it.copy(tools = tools, error = null) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { mutableState.update { it.copy(tools = emptyList(), error = failure.message) } }
    }

    suspend fun start(port: Int = 8765) = lock.withLock {
        synchronized(lifecycleLock) { check(!closed) { "Core broker is closed" } }
        if (stopServer != null) return@withLock
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })
        var boundPort = port
        val engine = embeddedServer(CIO, host = "127.0.0.1", port = port) {
            install(ContentNegotiation) { json(McpJson) }
            install(RequestBodyLimit) { bodyLimit { 65_536L } }
            intercept(ApplicationCallPipeline.Call) {
                val host = call.request.header(HttpHeaders.Host)
                if (call.request.header(HttpHeaders.Origin) != null ||
                    host !in setOf("127.0.0.1:$boundPort", "localhost:$boundPort")) {
                    call.respondText("Forbidden", status = HttpStatusCode.Forbidden)
                    finish()
                } else {
                    val currentToken = state.value.token
                    val supplied = call.request.header(HttpHeaders.Authorization).orEmpty()
                    val valid = currentToken != null && state.value.endpoint != null &&
                        MessageDigest.isEqual(("Bearer " + currentToken).toByteArray(), supplied.toByteArray())
                    if (!valid) {
                        val now = System.nanoTime()
                        val previous = lastAuthAudit.get()
                        if (now - previous > 1_000_000_000 && lastAuthAudit.compareAndSet(previous, now)) {
                            audit("http.authentication", "denied", 0)
                        }
                        call.respondText("Unauthorized", status = HttpStatusCode.Unauthorized)
                        finish()
                    }
                }
            }
            routing {
                route("/mcp") {
                    post {
                        val transport = StreamableHttpServerTransport(StreamableHttpServerTransport.Configuration(
                            enableJsonResponse = true, enableDnsRebindingProtection = true,
                            allowedHosts = listOf("127.0.0.1:$boundPort", "localhost:$boundPort"),
                            // SDK 0.9 rejects an absent Origin for any non-null list.
                            // The outer interceptor rejects every supplied Origin.
                            allowedOrigins = null,
                        )).also { it.setSessionIdGenerator(null) }
                        val handler = server(call.request.header(HttpHeaders.Authorization))
                        try {
                            handler.createSession(transport)
                            transport.handleRequest(null, call)
                        } finally {
                            // SDK 0.9's stateless convenience route omits this cleanup.
                            withContext(NonCancellable) { handler.close() }
                        }
                    }
                    get { call.respondText("Method not allowed", status = HttpStatusCode.MethodNotAllowed) }
                    delete { call.respondText("Method not allowed", status = HttpStatusCode.MethodNotAllowed) }
                }
            }
        }
        try {
            withContext(Dispatchers.IO) { engine.start(wait = false) }
            boundPort = engine.engine.resolvedConnectors().first().port
            synchronized(lifecycleLock) {
                check(!closed) { "Core broker closed during startup" }
                stopServer = { engine.stop(0, 1000) }
                mutableState.update { it.copy(endpoint = "http://127.0.0.1:$boundPort/mcp", token = token, error = null) }
            }
        } catch (failure: Exception) {
            withContext(NonCancellable + Dispatchers.IO) { engine.stop(0, 0) }
            throw failure
        }
    }

    suspend fun stop() = lock.withLock {
        val stop = synchronized(lifecycleLock) {
            mutableState.update { it.copy(endpoint = null, token = null) }
            stopServer.also { stopServer = null }
        }
        withContext(NonCancellable + Dispatchers.IO) { stop?.invoke() }
    }

    override fun close() {
        val stop = synchronized(lifecycleLock) {
            closed = true
            mutableState.update { it.copy(endpoint = null, token = null) }
            stopServer.also { stopServer = null }
        }
        if (stop != null) Thread({ stop.invoke() }, "mcp-shutdown").start()
    }

    private fun server(credential: String?): Server {
        val server = Server(Implementation("Modular Core", "0.1.0"),
            ServerOptions(capabilities = ServerCapabilities(
                tools = ServerCapabilities.Tools(listChanged = false),
                resources = ServerCapabilities.Resources(subscribe = false, listChanged = false),
            )))
        val tools = listOf(
            Triple("launcher.context.current", "Read the active launcher context", emptyMap<String, String>()),
            Triple("launcher.context.list", "List saved contexts", emptyMap()),
            Triple("launcher.modules.list", "List discovered modules and compatibility status", emptyMap()),
            Triple("launcher.apps.search", "Search launcher app metadata (at most 50 results)", mapOf("query" to "string")),
            Triple("launcher.context.activate", "Activate a context if local policy permits", mapOf("contextId" to "string")),
            Triple("launcher.apps.launch", "Launch an app if local policy permits", mapOf("packageName" to "string", "activityName" to "string", "profileSerial" to "integer")),
            Triple("launcher.module.tools", "List module tool proposals; presence does not grant execution", emptyMap()),
            Triple("launcher.module.invoke", "Invoke an individually approved read-only module tool", mapOf("approvalKey" to "string", "arguments" to "object")),
        )
        tools.filter { state.value.policy.allows(it.first) }.forEach { (name, description, fields) ->
            server.addTool(name, description, inputSchema = ToolSchema(
                properties = buildJsonObject { fields.forEach { (key, type) -> put(key, buildJsonObject { put("type", type) }) } },
                required = fields.keys.toList(),
            )) { request ->
                val args = request.arguments ?: JsonObject(emptyMap())
                try {
                    require(args.keys == fields.keys) { "Unexpected or missing arguments" }
                    CallToolResult(content = listOf(TextContent(execute(name, args, credential))))
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { CallToolResult(content = listOf(TextContent("Request denied or failed. Check local Core diagnostics.")), isError = true) }
            }
        }
        server.addResource(uri = "launcher://context/current", name = "Current launcher context",
            description = "Active Core composition", mimeType = "application/json") { request ->
            ReadResourceResult(contents = listOf(TextResourceContents(
                text = execute("launcher.context.current", JsonObject(emptyMap()), credential),
                uri = request.uri, mimeType = "application/json")))
        }
        state.value.tools.filter { it.approvalKey in state.value.policy.approvedModuleTools &&
            it.schema["required"]?.jsonArray.orEmpty().isEmpty() }.forEach { tool ->
            server.addResource(uri = "module://" + tool.approvalKey, name = tool.name,
                description = tool.description, mimeType = "application/json") { request ->
                ReadResourceResult(contents = listOf(TextResourceContents(
                    text = execute("launcher.module.invoke", buildJsonObject {
                        put("approvalKey", tool.approvalKey); put("arguments", JsonObject(emptyMap()))
                    }, credential), uri = request.uri, mimeType = "application/json")))
            }
        }
        return server
    }

    internal suspend fun execute(tool: String, args: JsonObject, credential: String? = null): String {
        val began = System.nanoTime()
        val correlation = UUID.randomUUID().toString()
        val target = if (tool == "launcher.module.invoke") state.value.tools.singleOrNull {
            it.approvalKey == (args["approvalKey"] as? JsonPrimitive)?.contentOrNull
        } else null
        fun authorized(): Boolean = state.value.endpoint != null && state.value.policy.allows(tool) &&
            (credential == null || credential == "Bearer " + state.value.token)
        if (!authorized()) {
            audit(tool, "denied", 0, correlation, target)
            throw SecurityException("Tool is not exposed by local policy")
        }
        // Persist an attempt before executing, without payloads, secrets or labels.
        audit(tool, "attempt", 0, correlation, target)
        return try {
            if (!authorized()) throw SecurityException("Credential or policy was revoked")
            val result = when (tool) {
                "launcher.context.current" -> buildJsonObject {
                    val current = composition.state.value
                    put("contextId", current.activeContextId?.let(::JsonPrimitive) ?: JsonNull)
                    put("ready", contexts.active.value.isReady)
                }.toString()
                "launcher.context.list" -> buildJsonArray {
                    composition.state.value.contexts.forEach { add(buildJsonObject { put("id", it.id); put("name", it.name) }) }
                }.toString()
                "launcher.modules.list" -> buildJsonArray {
                    modules.modules.value.take(128).forEach { add(buildJsonObject {
                        put("id", it.label); put("status", it.status.name); put("version", it.moduleVersion.orEmpty())
                    }) }
                }.toString()
                "launcher.apps.search" -> {
                    val query = args.string("query", 128)
                    buildJsonArray { apps.catalog.value.apps.filter { it.label.contains(query, ignoreCase = true) }.take(50)
                        .forEach { app -> add(buildJsonObject {
                            put("label", app.label.take(512)); put("packageName", app.key.packageName)
                            put("activityName", app.key.activityName); put("profileSerial", app.key.profileSerial)
                        }) } }.toString()
                }
                "launcher.context.activate" -> {
                    contexts.activate(args.string("contextId", 64)).getOrThrow()
                    "{\"activated\":true}"
                }
                "launcher.apps.launch" -> {
                    val profile = args.getValue("profileSerial").jsonPrimitive
                    require(!profile.isString)
                    val key = AppKey(args.string("packageName", 512), args.string("activityName", 512),
                        requireNotNull(profile.longOrNull))
                    require(apps.catalog.value.apps.any { it.key == key && it.enabled }) { "App is unavailable" }
                    apps.launch(key).getOrThrow()
                    "{\"launched\":true}"
                }
                "launcher.module.tools" -> buildJsonArray {
                    moduleTools.discover().forEach { candidate -> add(buildJsonObject {
                        put("approvalKey", candidate.approvalKey); put("instanceId", candidate.instanceId)
                        put("provider", candidate.providerId); put("name", candidate.name)
                        put("description", candidate.description); put("inputSchema", candidate.schema)
                        put("approved", candidate.approvalKey in state.value.policy.approvedModuleTools)
                    }) }
                }.toString()
                "launcher.module.invoke" -> moduleTools.invoke(args.string("approvalKey", 64),
                    args.getValue("arguments").jsonObject) {
                        if (authorized()) state.value.policy.approvedModuleTools else emptySet()
                    }
                else -> throw SecurityException("Unsupported tool")
            }
            require(result.length <= 65_536) { "Response exceeds the broker budget" }
            audit(tool, "success", (System.nanoTime() - began) / 1_000_000, correlation, target)
            result
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            audit(tool, if (failure is SecurityException) "denied" else "failed", (System.nanoTime() - began) / 1_000_000, correlation, target)
            throw failure
        }
    }

    private fun JsonObject.string(name: String, limit: Int): String {
        val value = getValue(name).jsonPrimitive
        require(value.isString && value.content.length in 1..limit)
        return value.content
    }

    private fun readAudit(): List<BrokerAudit> = runCatching {
        Json.parseToJsonElement(prefs.getString("audit", "[]") ?: "[]").jsonArray.takeLast(128).map { value ->
            val item = value.jsonObject
            BrokerAudit(item.getValue("time").jsonPrimitive.long, item.getValue("correlationId").jsonPrimitive.content,
                item.getValue("client").jsonPrimitive.content, item.getValue("tool").jsonPrimitive.content,
                item.getValue("decision").jsonPrimitive.content, item.getValue("durationMs").jsonPrimitive.long,
                item["targetModule"]?.jsonPrimitive?.contentOrNull, item["targetInstance"]?.jsonPrimitive?.contentOrNull)
        }
    }.getOrDefault(emptyList())

    private suspend fun audit(tool: String, decision: String, duration: Long,
        correlation: String = UUID.randomUUID().toString(), target: ModuleToolCandidate? = null) = auditLock.withLock {
        val entry = BrokerAudit(System.currentTimeMillis(), correlation, "local-paired-client",
            tool.take(96), decision, duration, target?.providerId, target?.instanceId)
        val entries = (state.value.audit + entry).takeLast(128)
        withContext(Dispatchers.IO) {
            val json = buildJsonArray { entries.forEach { item -> add(buildJsonObject {
                put("time", item.time); put("correlationId", item.correlationId); put("client", item.client)
                put("tool", item.tool); put("decision", item.decision); put("durationMs", item.durationMs)
                item.targetModule?.let { put("targetModule", it) }
                item.targetInstance?.let { put("targetInstance", it) }
            }) } }
            check(prefs.edit().putString("audit", json.toString()).commit()) { "Audit could not be saved" }
        }
        mutableState.update { it.copy(audit = entries) }
    }
}
