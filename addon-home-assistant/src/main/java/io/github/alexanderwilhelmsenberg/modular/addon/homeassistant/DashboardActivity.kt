package io.github.alexanderwilhelmsenberg.modular.addon.homeassistant

import android.app.Activity
import android.content.res.Configuration
import android.os.Bundle
import android.os.SystemClock
import android.text.InputType
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebView
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import io.github.alexanderwilhelmsenberg.modular.moduleapi.ModuleContract
import io.github.alexanderwilhelmsenberg.modular.webui.AddonScreen
import io.github.alexanderwilhelmsenberg.modular.webui.PageAddress
import java.util.UUID

/** Only a live embedded page can select which known server the Activity opens. */
internal object DashboardSignIn {
    data class Target(val placement: String, val address: PageAddress?)

    private var pending: Triple<String, Target, Long>? = null
    fun offer(placement: String, address: PageAddress?): String = UUID.randomUUID().toString().also { nonce ->
        pending = Triple(nonce, Target(placement, address), SystemClock.elapsedRealtime() + 30_000)
    }
    fun take(nonce: String?) = pending?.takeIf {
        it.first == nonce && it.third > SystemClock.elapsedRealtime()
    }?.also { pending = null }?.second
}

class DashboardActivity : Activity() {
    private lateinit var ui: AddonScreen
    private lateinit var root: LinearLayout
    private lateinit var catalog: DashboardCatalog
    private lateinit var prompt: LinearLayout
    private lateinit var server: EditText
    private lateinit var status: android.widget.TextView
    private var browser: DashboardBrowser? = null
    private var web: WebView? = null
    private var shownCatalog: List<Dashboard>? = null
    private lateinit var assignments: LinearLayout
    private lateinit var assignmentScroll: ScrollView
    private var placement: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        catalog = DashboardCatalog(this)
        ui = AddonScreen(this, resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES)
        root = ui.column()
        ui.bar(root, "Home Assistant").addView(ui.button("Server") {
            prompt.visibility = if (prompt.visibility == View.VISIBLE && web != null) View.GONE else View.VISIBLE
        })
        prompt = ui.section(root, "Connect to your home", "Sign in with your Home Assistant account to find your dashboards.")
        server = EditText(this).apply {
            hint = "https://home.example.com"
            contentDescription = "Server address"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine()
        }
        prompt.addView(server)
        prompt.addView(ui.button("Continue", primary = true) {
            val address = PageAddress.parse(server.text.toString(), true)
            if (address == null) server.error = "Enter an HTTPS address or a local HTTP address."
            else {
                (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                    .hideSoftInputFromWindow(server.windowToken, 0)
                open(address)
            }
        })
        status = ui.text("", secondary = true).apply {
            visibility = View.GONE
            setPadding(ui.dp(20), ui.dp(8), ui.dp(20), ui.dp(8))
        }
        root.addView(status)
        assignments = ui.column()
        assignmentScroll = ScrollView(this).apply {
            visibility = View.GONE
            addView(assignments)
        }
        root.addView(assignmentScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        ui.install(this, root)
        val incoming = DashboardSignIn.take(intent.getStringExtra(ModuleContract.EXTRA_HOST_HANDOFF_NONCE))
        placement = incoming?.placement ?: savedInstanceState?.getString("placement")
        (incoming?.address ?: catalog.server)?.let { open(it) }
    }

    private fun notice(message: String) {
        status.text = message
        status.visibility = if (message.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun open(input: PageAddress) {
        val address = requireNotNull(PageAddress.parse(input.origin, true))
        catalog.server = address
        server.setText(address.origin)
        prompt.visibility = View.GONE
        val cached = catalog.read(address)
        notice(if (cached.isEmpty()) "Opening Home Assistant…" else
            "${cached.size} dashboards saved · checking your session")
        web?.let(root::removeView)
        browser?.close()
        shownCatalog = null
        assignmentScroll.visibility = View.GONE
        val view = WebView(this)
        web = view
        root.addView(view, LinearLayout.LayoutParams(-1, 0, 1f))
        browser = DashboardBrowser(view, address, catalog, onSignedIn = { dashboards ->
            if (dashboards != shownCatalog) {
                shownCatalog = dashboards
                notice(if (dashboards.isEmpty()) "Signed in · no dashboards available" else "")
                view.visibility = View.GONE
                showAssignments(address, dashboards)
            }
        }, onLogin = {
            shownCatalog = null
            view.visibility = View.VISIBLE
            assignmentScroll.visibility = View.GONE
            notice("Sign in below to connect your dashboards.")
        }, onError = {
            notice(it)
            prompt.visibility = View.VISIBLE
        }).also { it.open() }
    }

    private fun showAssignments(address: PageAddress, dashboards: List<Dashboard>) {
        assignments.removeAllViews()
        assignmentScroll.visibility = View.VISIBLE
        val placements = catalog.placements()
        val target = placement
        if (target == null) {
            val panel = ui.section(assignments, "Home dashboards", if (placements.isEmpty())
                "Add a Home Assistant screen in Modular and swipe to it to assign a dashboard." else null)
            placements.forEachIndexed { index, id ->
                val path = catalog.selected(id)?.uri?.path?.trimEnd('/')
                val current = dashboards.firstOrNull { it.path == path }?.title ?: path?.substringAfterLast('/')
                panel.addView(ui.button("Home screen ${index + 1}" + (current?.let { " · $it" } ?: "")) {
                    placement = id
                    showAssignments(address, dashboards)
                })
            }
        } else {
            val panel = ui.section(assignments, "Choose a dashboard")
            dashboards.forEach { dashboard ->
                panel.addView(ui.button(dashboard.title) {
                    catalog.select(target, address, dashboard)
                    finish()
                }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(4) })
            }
            if (catalog.selected(target) != null) panel.addView(ui.button("Done") { finish() })
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("placement", placement)
        super.onSaveInstanceState(outState)
    }

    override fun onPause() {
        web?.onPause()
        if (web != null) CookieManager.getInstance().flush()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        web?.onResume()
    }

    override fun onDestroy() {
        web?.let(root::removeView)
        browser?.close()
        super.onDestroy()
    }
}
