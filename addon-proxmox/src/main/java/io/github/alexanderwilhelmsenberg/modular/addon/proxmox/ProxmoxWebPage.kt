package io.github.alexanderwilhelmsenberg.modular.addon.proxmox

import android.annotation.SuppressLint
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import io.github.alexanderwilhelmsenberg.modular.webui.PageAddress
import java.net.URI

/** Both pages use the same private WebView origin and console session. */
@SuppressLint("SetJavaScriptEnabled")
internal fun openProxmoxPage(
    web: WebView,
    address: PageAddress,
    accountOnly: Boolean,
    browser: ProxmoxBrowser? = null,
    onError: (String) -> Unit,
) {
    val assetPath = "/.modular/cluster.html"
    val query = if (accountOnly) "account=1" else null
    val url = address.origin + assetPath + (query?.let { "?$it" } ?: "")
    fun isPage(uri: URI) = address.sameOrigin(uri) && uri.rawPath == assetPath && uri.rawQuery == query
    web.settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        allowFileAccess = false
        allowContentAccess = false
        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
    }
    web.webViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
            request.isForMainFrame && !runCatching { isPage(URI(request.url.toString())) }.getOrDefault(false)

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            val uri = runCatching { URI(request.url.toString()) }.getOrNull()
            if (uri != null && address.sameOrigin(uri)) {
                val type = when (uri.rawPath) {
                    assetPath -> "text/html"
                    "/.modular/cluster.css" -> "text/css"
                    "/.modular/cluster.js" -> "application/javascript"
                    else -> null
                }
                if (type != null && (!request.isForMainFrame || isPage(uri))) {
                    val asset = uri.rawPath.substringAfterLast('/')
                    return WebResourceResponse(type, "utf-8", web.context.assets.open(asset))
                }
                if (!request.isForMainFrame) return browser?.shouldInterceptRequest(view, request)
            }
            return WebResourceResponse("text/plain", "utf-8", 403, "Forbidden", emptyMap(), null)
        }

        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: android.net.http.SslError) {
            handler.cancel()
            onError("Android could not verify the Proxmox server certificate. Check the hostname and trusted CA.")
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame) onError(error.description.toString())
        }
    }
    web.loadUrl(url)
}
