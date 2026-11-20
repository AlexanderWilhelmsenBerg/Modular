package io.github.alexanderwilhelmsenberg.modular.webui

import android.annotation.SuppressLint
import android.content.Context
import android.os.IBinder
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import io.github.alexanderwilhelmsenberg.modular.moduleapi.StandardCapabilities
import io.github.alexanderwilhelmsenberg.modular.modulesdk.BaseModuleService
import io.github.alexanderwilhelmsenberg.modular.modulesdk.ModuleInstanceContext
import io.github.alexanderwilhelmsenberg.modular.modulesdk.WorkspaceSurfaceEndpoint
import io.github.alexanderwilhelmsenberg.modular.modulesdk.WorkspaceSurfaceScope
import java.net.URI

abstract class WebWorkspaceService : BaseModuleService() {
    protected abstract val descriptorResource: Int
    protected abstract val pageTitle: String
    protected abstract val addressLabel: String
    protected abstract val addressHint: String
    protected open val allowLocalHttp = false
    protected open val workspaceAsset: String? = null

    override val descriptorJson by lazy {
        resources.openRawResource(descriptorResource).bufferedReader().use { it.readText() }
    }

    final override fun createCapability(context: ModuleInstanceContext, capabilityId: String): IBinder? {
        if (capabilityId != StandardCapabilities.WorkspaceSurface.wireValue) return null
        return object : WorkspaceSurfaceEndpoint(this@WebWorkspaceService, context) {
            override fun onCreateView(scope: WorkspaceSurfaceScope): View = page(scope, context.instanceId)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun page(scope: WorkspaceSurfaceScope, placement: String): View {
        val context = ContextThemeWrapper(scope.context, if (scope.hostState.value.theme == "light")
            android.R.style.Theme_Material_Light_NoActionBar else android.R.style.Theme_Material_NoActionBar)
        val prefs = getSharedPreferences("workspace-addresses", Context.MODE_PRIVATE)
        fun dp(size: Int) = (size * context.resources.displayMetrics.density + .5f).toInt()
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(scope.hostState.value.colors["background"] ?: android.graphics.Color.DKGRAY)
        }
        val header = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(4), 0)
        }
        val title = TextView(context).apply { text = pageTitle; textSize = 17f }
        header.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        val change = Button(context, null, android.R.attr.borderlessButtonStyle).apply {
            text = if (workspaceAsset == null) "Dashboard" else "Server"
            contentDescription = "Change ${addressLabel.lowercase()}"
        }
        header.addView(change)
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        val addressInput = EditText(context).apply {
            hint = addressHint
            contentDescription = addressLabel
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
            setText(prefs.getString(placement, ""))
        }
        panel.addView(TextView(context).apply { text = addressLabel })
        panel.addView(addressInput)
        val open = Button(context).apply { text = if (workspaceAsset == null) "Open dashboard" else "Connect" }
        panel.addView(open)
        val status = TextView(context).apply { visibility = View.GONE; setPadding(dp(12), dp(6), dp(12), dp(6)) }
        root.addView(header); root.addView(panel); root.addView(status)
        var browser: WebView? = null
        fun disposeBrowser() {
            browser?.let { view ->
                root.removeView(view)
                view.stopLoading()
                view.destroy()
            }
            browser = null
        }
        val bundle = workspaceAsset
        fun show(address: PageAddress) {
            disposeBrowser()
            panel.visibility = View.GONE
            status.visibility = View.GONE
            val url = if (bundle == null) address.uri.toASCIIString() else address.origin + "/.modular/cluster.html"
            val web = WebView(context)
            browser = web
            web.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = false
                allowContentAccess = false
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            }
            web.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val allowed = runCatching { address.sameOrigin(URI(request.url.toString())) }.getOrDefault(false)
                    if (!allowed && request.isForMainFrame) {
                        status.text = "This link opens outside the configured server."
                        status.visibility = View.VISIBLE
                    }
                    return !allowed
                }

                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    val uri = runCatching { URI(request.url.toString()) }.getOrNull() ?: return null
                    if (bundle != null && address.sameOrigin(uri) && uri.rawPath.startsWith("/.modular/")) {
                        val file = uri.rawPath.removePrefix("/.modular/")
                        if (file in setOf(bundle, "cluster.js", "cluster.css")) {
                            val type = when (file.substringAfterLast('.')) {
                                "html" -> "text/html"
                                "js" -> "application/javascript"
                                else -> "text/css"
                            }
                            return WebResourceResponse(type, "utf-8", assets.open(file))
                        }
                    }
                    if ((request.isForMainFrame || uri.scheme == "http") && !address.sameOrigin(uri))
                        return WebResourceResponse("text/plain", "utf-8", 403, "Forbidden", emptyMap(), null)
                    return null
                }

                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame && browser === view) {
                        status.text = error.description
                        status.visibility = View.VISIBLE
                        panel.visibility = View.VISIBLE
                    }
                }
            }
            root.addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            web.loadUrl(url)
        }
        open.setOnClickListener {
            val address = PageAddress.parse(addressInput.text.toString(), allowLocalHttp)
            if (address == null) {
                addressInput.error = if (allowLocalHttp) "Enter an HTTPS URL or a local HTTP dashboard URL."
                    else "Enter the HTTPS address of your Proxmox server."
            } else {
                val saved = if (bundle == null) address.uri.toASCIIString() else address.origin
                prefs.edit().putString(placement, saved).apply()
                (context.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                    .hideSoftInputFromWindow(addressInput.windowToken, 0)
                show(address)
            }
        }
        change.setOnClickListener {
            panel.visibility = if (panel.visibility == View.VISIBLE && browser != null) View.GONE else View.VISIBLE
        }
        scope.updateState(canGoBack = false, exclusiveGestures = false)
        scope.onBackRequested = {
            if (panel.visibility == View.VISIBLE && browser != null) { panel.visibility = View.GONE; true }
            else if (browser?.canGoBack() == true) { browser?.goBack(); true } else false
        }
        var retired = false
        scope.registerOnClose { retired = true; disposeBrowser() }
        PageAddress.parse(addressInput.text.toString(), allowLocalHttp)?.let { saved ->
            // Chromium initialization can take longer than the SDK's surface creation deadline.
            root.post { if (!retired && browser == null) show(saved) }
        }
        return root
    }
}
