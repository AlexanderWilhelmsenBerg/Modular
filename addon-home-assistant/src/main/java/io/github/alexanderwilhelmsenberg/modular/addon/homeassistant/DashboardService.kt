package io.github.alexanderwilhelmsenberg.modular.addon.homeassistant

import android.view.ContextThemeWrapper
import android.view.View
import android.webkit.WebView
import android.widget.LinearLayout
import io.github.alexanderwilhelmsenberg.modular.modulesdk.WorkspaceSurfaceScope
import io.github.alexanderwilhelmsenberg.modular.webui.AddonScreen
import io.github.alexanderwilhelmsenberg.modular.webui.WebWorkspaceService

class DashboardService : WebWorkspaceService() {
    override val descriptorResource = R.raw.module_descriptor

    override fun page(scope: WorkspaceSurfaceScope, placement: String): View {
        val dark = scope.hostState.value.theme != "light"
        val context = ContextThemeWrapper(scope.context, if (dark)
            android.R.style.Theme_Material_NoActionBar else android.R.style.Theme_Material_Light_NoActionBar)
        val ui = AddonScreen(context, dark, scope.hostState.value.colors["background"])
        val catalog = DashboardCatalog(this)
        catalog.visit(placement)
        val root = ui.column()
        val saved = catalog.selected(placement)
        var browser: DashboardBrowser? = null
        var closed = false

        fun prompt(title: String, action: String, detail: String? = null) {
            root.removeAllViews()
            ui.section(root, title, detail).addView(ui.button(action, primary = true) {
                scope.requestActivity(DashboardSignIn.offer(placement, saved ?: catalog.server))
            })
        }

        scope.updateState(canGoBack = false, exclusiveGestures = false)
        scope.registerOnClose {
            closed = true
            root.removeAllViews()
            browser?.close()
        }
        if (saved == null) {
            prompt("Choose a dashboard", "Open Home Assistant")
        } else {
            // Start Chromium after the remote surface has attached.
            root.post {
                if (closed) return@post
                val view = WebView(context)
                view.visibility = View.INVISIBLE
                root.addView(view, LinearLayout.LayoutParams(-1, 0, 1f))
                browser = DashboardBrowser(view, saved, catalog, embedded = true, onSignedIn = {
                    view.visibility = View.VISIBLE
                }, onLogin = {
                    browser?.close()
                    browser = null
                    prompt("Sign in to Home Assistant", "Sign in")
                }, onError = { detail ->
                    browser?.close()
                    browser = null
                    prompt("Dashboard unavailable", "Open Home Assistant", detail)
                }).also { it.open() }
            }
        }
        return root
    }
}
