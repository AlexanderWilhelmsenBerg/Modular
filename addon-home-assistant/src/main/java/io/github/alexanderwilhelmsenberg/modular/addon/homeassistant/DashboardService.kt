package io.github.alexanderwilhelmsenberg.modular.addon.homeassistant

import android.content.Context
import android.view.ContextThemeWrapper
import android.view.View
import android.webkit.WebView
import android.widget.LinearLayout
import android.widget.ScrollView
import io.github.alexanderwilhelmsenberg.modular.modulesdk.WorkspaceSurfaceScope
import io.github.alexanderwilhelmsenberg.modular.webui.AddonScreen
import io.github.alexanderwilhelmsenberg.modular.webui.PageAddress
import io.github.alexanderwilhelmsenberg.modular.webui.WebWorkspaceService

class DashboardService : WebWorkspaceService() {
    override val descriptorResource = R.raw.module_descriptor
    override val pageTitle = "Home Assistant"
    override val addressLabel = "Dashboard"
    override val addressHint = ""

    override fun page(scope: WorkspaceSurfaceScope, placement: String): View {
        val dark = scope.hostState.value.theme != "light"
        val context: Context = ContextThemeWrapper(scope.context, if (dark)
            android.R.style.Theme_Material_NoActionBar else android.R.style.Theme_Material_Light_NoActionBar)
        val ui = AddonScreen(context, dark, scope.hostState.value.colors["background"])
        val catalog = DashboardCatalog(this)
        val root = ui.column(workspace = scope)
        val bar = ui.bar(root, pageTitle)
        val chooser = ScrollView(context)
        val choices = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        chooser.addView(choices)
        root.addView(chooser, LinearLayout.LayoutParams(-1, -2))
        var browser: DashboardBrowser? = null
        var web: WebView? = null
        var selected = catalog.selected(placement)
        var closed = false
        var choosing = selected == null
        var needsLogin = false
        var ready = false
        var message = ""
        lateinit var switch: android.widget.Button
        lateinit var load: (PageAddress) -> Unit
        var lastRender: List<Any?>? = null

        fun render() {
            val source = catalog.server ?: selected
            val dashboards = source?.let(catalog::read).orEmpty()
            val state = listOf(source?.origin, dashboards, choosing, needsLogin, message)
            if (state != lastRender) {
                lastRender = state
                choices.removeAllViews()
                if (choosing || needsLogin || message.isNotEmpty()) {
                    val heading = when {
                        needsLogin -> "Sign in to Home Assistant"
                        message.isNotEmpty() -> "Dashboard unavailable"
                        dashboards.isEmpty() -> "Connect Home Assistant"
                        else -> "Choose a dashboard"
                    }
                    val panel = ui.section(choices, heading, message.ifEmpty { null })
                    if (choosing && !needsLogin) dashboards.forEach { dashboard ->
                        panel.addView(ui.button(dashboard.title) {
                            val address = source ?: return@button
                            catalog.select(placement, address, dashboard)
                            catalog.selected(placement)?.let { load(it) }
                        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(4) })
                    }
                    if (message.isNotEmpty()) selected?.let { current ->
                        panel.addView(ui.button("Retry") { load(current) })
                    }
                    panel.addView(ui.button(if (needsLogin) "Sign in" else "Open Home Assistant",
                        primary = needsLogin || dashboards.isEmpty()) {
                        scope.requestActivity(DashboardSignIn.offer(if (needsLogin) selected else source))
                    })
                }
            }
            chooser.visibility = if (choosing || needsLogin || message.isNotEmpty()) View.VISIBLE else View.GONE
            chooser.layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
            web?.visibility = when {
                choosing || needsLogin || message.isNotEmpty() -> View.GONE
                ready -> View.VISIBLE
                else -> View.INVISIBLE
            }
            switch.visibility = if (selected != null && !needsLogin) View.VISIBLE else View.GONE
            switch.text = if (choosing) "Done" else "Dashboards"
            scope.updateState(canGoBack = choosing && selected != null, exclusiveGestures = false)
        }

        load = { address ->
            selected = address
            choosing = false
            needsLogin = false
            ready = false
            message = ""
            web?.let(root::removeView)
            browser?.close()
            val view = WebView(context)
            web = view
            root.addView(view, LinearLayout.LayoutParams(-1, 0, 1f))
            render()
            browser = DashboardBrowser(view, address, catalog, onSignedIn = {
                ready = true; needsLogin = false; message = ""; render()
            }, onLogin = {
                needsLogin = true; render()
            }, onError = {
                message = it; render()
            }).also { it.open() }
        }
        switch = ui.button("Dashboards") { choosing = !choosing; render() }
        bar.addView(switch)
        scope.onBackRequested = {
            if (choosing && selected != null) { choosing = false; render(); true } else false
        }
        val catalogListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            // Refresh the picker if the account Activity discovered dashboards while this surface was open.
            if (!closed && choosing) render()
        }
        val prefs = getSharedPreferences("dashboard-catalog", MODE_PRIVATE)
        prefs.registerOnSharedPreferenceChangeListener(catalogListener)
        scope.registerOnClose {
            closed = true
            prefs.unregisterOnSharedPreferenceChangeListener(catalogListener)
            web?.let(root::removeView)
            browser?.close()
        }
        render()
        selected?.let { saved -> root.post { if (!closed) load(saved) } }
        return root
    }
}
