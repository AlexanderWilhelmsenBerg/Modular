package io.github.alexanderwilhelmsenberg.modular.addon.proxmox

import android.content.Context
import android.view.ContextThemeWrapper
import android.view.View
import android.webkit.WebView
import android.widget.LinearLayout
import io.github.alexanderwilhelmsenberg.modular.modulesdk.WorkspaceSurfaceScope
import io.github.alexanderwilhelmsenberg.modular.webui.AddonScreen
import io.github.alexanderwilhelmsenberg.modular.webui.PageAddress
import io.github.alexanderwilhelmsenberg.modular.webui.WebWorkspaceService
import java.util.UUID

class ProxmoxService : WebWorkspaceService() {
    override val descriptorResource = R.raw.module_descriptor

    override fun page(scope: WorkspaceSurfaceScope, placement: String): View {
        val dark = scope.hostState.value.theme != "light"
        val context = ContextThemeWrapper(scope.context, if (dark)
            android.R.style.Theme_Material_NoActionBar else android.R.style.Theme_Material_Light_NoActionBar)
        val ui = AddonScreen(context, dark, scope.hostState.value.colors["background"])
        val root = ui.column()
        val vault = ProxmoxCredentials(this)
        val previous = getSharedPreferences("workspace-addresses", Context.MODE_PRIVATE).getString(placement, null)
        val address = PageAddress.parse(vault.server ?: previous.orEmpty(), false)
        var web: WebView? = null
        var closed = false
        fun openSettings(account: Boolean) {
            val nonce = UUID.randomUUID().toString()
            if (account) ProxmoxAccountLaunch.offer(nonce)
            scope.requestActivity(nonce)
        }

        fun showConnectionError(title: String, detail: String? = null) {
            root.removeAllViews()
            web?.visibility = View.GONE
            ui.section(root, title, detail).addView(ui.button("Open Proxmox", primary = true, click = { openSettings(false) }))
        }

        scope.updateState(canGoBack = false, exclusiveGestures = false)
        scope.onBackRequested = {
            val view = web
            if (view?.canGoBack() == true) { view.goBack(); true } else false
        }
        scope.registerOnClose {
            closed = true
            web?.let { view ->
                root.removeView(view)
                view.destroy()
            }
        }
        if (address == null) {
            showConnectionError("Connect Proxmox")
        } else {
            if (vault.server == null) runCatching { vault.server = address.origin }
            root.post {
                if (closed) return@post
                val view = WebView(context)
                web = view
                root.addView(view, LinearLayout.LayoutParams(-1, 0, 1f))
                val bridge = ProxmoxBrowser(view, address, vault, scope, ::openSettings)
                openProxmoxPage(view, address, accountOnly = false, browser = bridge) { detail ->
                    if (view.parent === root) showConnectionError("Proxmox unavailable", detail)
                }
            }
        }
        return root
    }
}
