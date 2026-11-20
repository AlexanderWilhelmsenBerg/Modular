package io.github.alexanderwilhelmsenberg.modular.addon.homeassistant

import android.annotation.SuppressLint
import android.graphics.Color
import android.net.http.SslError
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import io.github.alexanderwilhelmsenberg.modular.webui.PageAddress
import org.json.JSONObject
import java.net.URI

/** The add-on Activity and embedded surfaces share the same, private Android WebView profile. */
internal class DashboardBrowser(
    private val web: WebView,
    private val address: PageAddress,
    private val catalog: DashboardCatalog,
    private val onSignedIn: (List<Dashboard>) -> Unit,
    private val onLogin: () -> Unit,
    private val onError: (String) -> Unit,
) {
    private var closed = false
    private var failed = false
    private var lastPanels: String? = null
    private val poll = Runnable { probe() }

    @SuppressLint("SetJavaScriptEnabled")
    fun open() {
        web.setBackgroundColor(Color.TRANSPARENT)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }
        CookieManager.getInstance().setAcceptCookie(true)
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                !sameOrigin(request.url.toString())

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val url = request.url
                if (!sameOrigin(url.toString()) && (request.isForMainFrame || url.scheme != "https"))
                    return WebResourceResponse("text/plain", "utf-8", 403, "Forbidden", emptyMap(), null)
                return null
            }

            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                web.removeCallbacks(poll)
                failed = false
                if (sameOrigin(url) && isAuthPath(url)) onLogin()
            }

            override fun onPageFinished(view: WebView, url: String) {
                if (!failed && sameOrigin(url) && !closed) schedule(200)
            }

            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                if (!request.isForMainFrame) return
                failed = true
                if (response.statusCode == 401) onLogin()
                else onError("Home Assistant returned HTTP ${response.statusCode}.")
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) {
                    failed = true
                    onError(error.description.toString())
                }
            }

            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                handler.cancel()
                if (sameOrigin(error.url)) {
                    failed = true
                    onError("Android could not verify the Home Assistant certificate.")
                }
            }
        }
        web.loadUrl(address.uri.toASCIIString())
    }

    private fun sameOrigin(url: String) = runCatching { address.sameOrigin(URI(url)) }.getOrDefault(false)

    private fun isAuthPath(url: String): Boolean =
        runCatching { URI(url).path.startsWith("/auth/") }.getOrDefault(false)

    private fun schedule(delay: Long) {
        web.removeCallbacks(poll)
        if (!closed) web.postDelayed(poll, delay)
    }

    private fun probe() {
        if (closed || failed || !sameOrigin(web.url.orEmpty())) return
        web.evaluateJavascript(PROBE) { reply ->
            if (closed || failed || !sameOrigin(web.url.orEmpty())) return@evaluateJavascript
            val result = runCatching { JSONObject(reply) }.getOrNull()
            when (result?.optString("state")) {
                "login" -> { onLogin(); schedule(1_500) }
                "ready" -> {
                    val raw = result.getJSONArray("panels").toString()
                    if (raw != lastPanels) {
                        lastPanels = raw
                        onSignedIn(catalog.refresh(address, raw))
                        CookieManager.getInstance().flush()
                    } else onSignedIn(catalog.read(address))
                    schedule(5_000)
                }
                else -> schedule(1_500)
            }
        }
    }

    fun close() {
        closed = true
        web.removeCallbacks(poll)
        web.stopLoading()
        web.destroy()
    }

    private companion object {
        val PROBE = """
            (() => {
              if (location.pathname.startsWith('/auth/') ||
                  document.querySelector('ha-authorize, ha-login-flow, ha-auth-flow')) {
                return {state:'login'};
              }
              const root = document.querySelector('home-assistant');
              const hass = root?.hass;
              if (!hass?.user || !hass.panels) return {state:'waiting'};
              if (hass.auth?.expired && !hass.connected) return {state:'waiting'};
              const panels = Object.values(hass.panels)
                .filter(panel => panel.component_name === 'lovelace' && (!panel.require_admin || hass.user.is_admin))
                .slice(0, 200).map(panel => ({
                  title: String(panel.title || '').slice(0, 90),
                  path: '/' + panel.url_path
                }));
              return {state:'ready', panels};
            })()
        """.trimIndent()
    }
}
