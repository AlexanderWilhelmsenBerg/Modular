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
import io.github.alexanderwilhelmsenberg.modular.moduleapi.ModuleContract
import io.github.alexanderwilhelmsenberg.modular.webui.AddonScreen
import io.github.alexanderwilhelmsenberg.modular.webui.PageAddress
import java.util.UUID

/** Only a live embedded page can select which known server the Activity opens. */
internal object DashboardSignIn {
    private var pending: Triple<String, PageAddress, Long>? = null
    fun offer(address: PageAddress?): String = UUID.randomUUID().toString().also { nonce ->
        pending = address?.let { Triple(nonce, it, SystemClock.elapsedRealtime() + 30_000) }
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
        ui.install(this, root)
        val incoming = DashboardSignIn.take(intent.getStringExtra(ModuleContract.EXTRA_HOST_HANDOFF_NONCE))
        (incoming ?: catalog.server)?.let { open(it) }
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
        val view = WebView(this)
        web = view
        root.addView(view, LinearLayout.LayoutParams(-1, 0, 1f))
        browser = DashboardBrowser(view, address, catalog, onSignedIn = { dashboards ->
            if (dashboards != shownCatalog) {
                shownCatalog = dashboards
                notice(if (dashboards.isEmpty()) "Signed in · no dashboards available" else
                    "${dashboards.size} dashboards ready · choose one on each Home screen")
            }
        }, onLogin = {
            shownCatalog = null
            notice("Sign in below to connect your dashboards.")
        }, onError = {
            notice(it)
            prompt.visibility = View.VISIBLE
        }).also { it.open() }
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
