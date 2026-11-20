package io.github.alexanderwilhelmsenberg.modular.addon.proxmox

import android.app.AlertDialog
import android.os.SystemClock
import android.text.InputType
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import io.github.alexanderwilhelmsenberg.modular.modulesdk.WorkspaceSurfaceScope
import io.github.alexanderwilhelmsenberg.modular.webui.PageAddress
import java.io.ByteArrayInputStream
import java.net.URI
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection
import org.json.JSONObject

internal class ProxmoxBrowser(
    private val web: WebView,
    private val address: PageAddress,
    private val vault: ProxmoxCredentials,
    private val scope: WorkspaceSurfaceScope,
) : WebViewClient() {
    private val worker = ThreadPoolExecutor(2, 2, 15, TimeUnit.SECONDS, ArrayBlockingQueue(8))
        .apply { allowCoreThreadTimeOut(true) }
    private var dialog: AlertDialog? = null
    @Volatile private var closed = false
    private val permit = java.util.concurrent.atomic.AtomicReference<Pair<String, Long>?>(null)

    init {
        web.addJavascriptInterface(this, "ModularProxmox")
        scope.registerOnClose {
            closed = true
            dialog?.dismiss()
            permit.set(null)
            worker.shutdownNow()
        }
    }

    @JavascriptInterface
    fun hasToken() = vault.tokenId(address.origin) != null

    /** The page supplies a request ID. Only the native transport has access to the token secret. */
    @JavascriptInterface
    fun request(id: String, method: String, path: String) {
        if (!validId(id) || closed) return
        if (!ProxmoxTokenRoutes.allowed(method, path)) {
            reply(id, 403, "Action is unavailable.")
            return
        }
        runCatching { worker.execute {
            try {
                val token = vault.token(address.origin)
                if (token == null) reply(id, 401, "Add an API token in Connection.")
                else {
                    val connection = URI.create(address.origin + "/api2/json" + path).toURL()
                        .openConnection() as HttpsURLConnection
                    try {
                        connection.instanceFollowRedirects = false
                        connection.connectTimeout = 8_000
                        connection.readTimeout = 12_000
                        connection.requestMethod = method
                        connection.setRequestProperty("Authorization", token)
                        connection.setRequestProperty("Accept", "application/json")
                        if (method == "POST") {
                            connection.doOutput = true
                            connection.setFixedLengthStreamingMode(0)
                            connection.outputStream.close()
                        }
                        val status = connection.responseCode
                        val body = if (status in 200..299) connection.inputStream.bufferedReader().use { reader ->
                            val output = StringBuilder()
                            val buffer = CharArray(4096)
                            while (output.length <= 2_000_000) {
                                val count = reader.read(buffer)
                                if (count < 0) break
                                output.append(buffer, 0, count)
                            }
                            check(output.length <= 2_000_000)
                            output.toString()
                        } else "Proxmox returned HTTP $status."
                        reply(id, status, body)
                    } finally { connection.disconnect() }
                }
            } catch (_: javax.net.ssl.SSLException) {
                reply(id, 503, "Android could not verify the Proxmox certificate.")
            } catch (_: Exception) {
                reply(id, 503, "Could not read from the configured Proxmox server or credential store.")
            }
        } }.onFailure { reply(id, 503, "The server connection is busy. Try again.") }
    }

    @JavascriptInterface
    fun unlockNode(id: String, node: String) {
        if (!validId(id) || !node.matches(ProxmoxTokenRoutes.node)) return
        web.post {
            if (closed) return@post
            if (dialog?.isShowing == true) { reply(id, 409, "Another prompt is open."); return@post }
            fun unlock() {
                permit.set(node to (SystemClock.elapsedRealtime() + 12_000))
                reply(id, 200, "")
            }
            if (!vault.hasPin(address.origin)) { unlock(); return@post }
            val input = EditText(web.context).apply {
                contentDescription = "Node shell PIN"
                hint = "PIN"
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
                filters = arrayOf(android.text.InputFilter.LengthFilter(12))
                importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO
            }
            var accepted = false
            val prompt = AlertDialog.Builder(web.context).setTitle("Unlock $node")
                .setMessage("Enter your Proxmox node shell PIN.").setView(input)
                .setPositiveButton("Unlock", null).setNegativeButton("Cancel", null).create()
            dialog = prompt
            prompt.setOnDismissListener {
                dialog = null
                if (!accepted) reply(id, 403, "Node shell stayed locked.")
            }
            val shown = runCatching {
                scope.configureDialog(prompt)
                prompt.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
                prompt.show()
            }
            if (shown.isFailure) {
                dialog = null
                reply(id, 503, "Could not open PIN prompt. Try again.")
                return@post
            }
            input.requestFocus()
            val button = prompt.getButton(AlertDialog.BUTTON_POSITIVE)
            button.setOnClickListener {
                val pin = input.text.toString()
                button.isEnabled = false
                runCatching { worker.execute {
                    val error = runCatching { vault.verifyPin(address.origin, pin) }
                        .getOrElse { "Could not read the saved PIN." }
                    web.post {
                        if (!prompt.isShowing) return@post
                        if (error == null) { accepted = true; prompt.dismiss(); unlock() }
                        else { input.error = error; button.isEnabled = true }
                    }
                } }.onFailure { button.isEnabled = true; input.error = "Try again." }
            }
        }
    }

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        val uri = runCatching { URI(request.url.toString()) }.getOrNull() ?: return null
        if (request.method != "POST" || !address.sameOrigin(uri)) return null
        val node = ProxmoxTokenRoutes.nodeShell.matchEntire(uri.path)?.groupValues?.get(1) ?: return null
        val approval = permit.getAndSet(null)
        if (!vault.hasPin(address.origin) || approval?.first == node &&
            approval.second > SystemClock.elapsedRealtime()) return null
        return WebResourceResponse("text/plain", "utf-8", 403, "Node PIN required", emptyMap(),
            ByteArrayInputStream("Open the node shell to enter your PIN.".encodeToByteArray()))
    }

    private fun validId(id: String) = id.length in 1..10 && id.all { it in '0'..'9' }

    private fun reply(id: String, status: Int, result: String) {
        if (closed) return
        web.post {
            if (!closed) web.evaluateJavascript(
                "window.modularResult?.(${JSONObject.quote(id)}, $status, ${JSONObject.quote(result)})", null)
        }
    }
}

internal object ProxmoxTokenRoutes {
    val node = Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,99}")
    val nodeShell = Regex("/api2/json/nodes/(${node.pattern})/termproxy")
    private val power = Regex("/nodes/${node.pattern}/lxc/[0-9]{1,9}/status/(start|stop|reboot)")
    private val task = Regex("/nodes/${node.pattern}/tasks/(UPID[A-Za-z0-9._@!:%-]{1,350})/status")
    private val taskId = Regex("UPID:[A-Za-z0-9._@!:-]+")

    fun allowed(method: String, path: String): Boolean = when (method) {
        "GET" -> path == "/cluster/resources" || task.matchEntire(path)?.groupValues?.get(1)?.let { raw ->
            runCatching { taskId.matches(java.net.URLDecoder.decode(raw, "UTF-8")) }.getOrDefault(false)
        } == true
        "POST" -> power.matches(path)
        else -> false
    }
}
