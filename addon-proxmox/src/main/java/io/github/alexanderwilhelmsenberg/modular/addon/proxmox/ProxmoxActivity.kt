package io.github.alexanderwilhelmsenberg.modular.addon.proxmox

import android.app.Activity
import android.content.res.Configuration
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.ScrollView
import android.widget.LinearLayout
import android.webkit.WebView
import android.webkit.CookieManager
import io.github.alexanderwilhelmsenberg.modular.moduleapi.ModuleContract
import io.github.alexanderwilhelmsenberg.modular.webui.AddonScreen
import io.github.alexanderwilhelmsenberg.modular.webui.PageAddress

internal object ProxmoxAccountLaunch {
    private var request: Pair<String, Long>? = null

    fun offer(nonce: String) {
        request = nonce to (android.os.SystemClock.elapsedRealtime() + 30_000)
    }

    fun consume(nonce: String?): Boolean {
        val pending = request ?: return false
        if (pending.first != nonce || pending.second < android.os.SystemClock.elapsedRealtime()) return false
        request = null
        return true
    }
}

class ProxmoxActivity : Activity() {
    private var accountWeb: WebView? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val vault = ProxmoxCredentials(this)
        val ui = AddonScreen(this, resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES)
        val root = ui.column()
        ui.bar(root, "Proxmox").addView(ui.button("Done") { finish() })
        val content = ui.column()
        val scroller = ScrollView(this).apply { addView(content) }
        root.addView(scroller, LinearLayout.LayoutParams(-1, 0, 1f))
        val connection = ui.section(content, "Server connection", "Use an API token to keep nodes, metrics and container controls signed in.")
        val server = field("HTTPS server address", InputType.TYPE_TEXT_VARIATION_URI).apply { setText(vault.server) }
        val tokenId = field("Token ID (user@realm!name)").apply {
            vault.server?.let { setText(vault.tokenId(it)) }
        }
        val secret = field("Token secret", InputType.TYPE_TEXT_VARIATION_PASSWORD).apply { isSaveEnabled = false }
        connection.addView(server); connection.addView(tokenId); connection.addView(secret)
        val feedback = ui.text("", secondary = true)
        connection.addView(feedback)
        connection.addView(ui.button("Save connection", primary = true) {
            val address = PageAddress.parse(server.text.toString(), false)
            val id = tokenId.text.toString().trim()
            val value = secret.text.toString().trim()
            val problem = when {
                address == null -> "Enter the HTTPS address of your Proxmox server."
                value.isEmpty() && id.isNotEmpty() && id != vault.tokenId(address.origin) -> "Enter the token secret."
                value.isNotEmpty() && !id.matches(Regex("[^@!=\\s]+@[^@!=\\s]+![^@!=\\s]+")) -> "Enter the full token ID."
                value.isNotEmpty() && !value.matches(Regex("[A-Za-z0-9_-]{8,300}")) -> "Check the token secret."
                else -> null
            }
            if (problem != null) feedback.text = problem
            else if (address != null) {
                feedback.text = runCatching {
                    if (value.isNotEmpty()) vault.setToken(address.origin, id, value) else vault.server = address.origin
                    secret.text.clear()
                    "Connection saved. Open your Proxmox Home screen to connect."
                }.getOrDefault("Android could not save the credentials.")
            }
        })
        connection.addView(ui.button("Forget API token") {
            PageAddress.parse(server.text.toString(), false)?.let {
                feedback.text = runCatching {
                    vault.forgetToken(it.origin)
                    tokenId.text.clear()
                    secret.text.clear()
                    "Token removed from this device."
                }.getOrDefault("Could not remove the saved token.")
            }
        })
        val account = ui.section(content, "Proxmox account", "Sign in to use the console or to connect without an API token.")
        fun openAccount() {
            val address = PageAddress.parse(vault.server.orEmpty(), false)
            if (address == null) { feedback.text = "Save the server address first."; return }
            val pane = ui.column()
            val error = ui.text("", secondary = true)
            val browser = WebView(this)
            accountWeb = browser
            scroller.visibility = android.view.View.GONE
            ui.bar(pane, "Proxmox account").addView(ui.button("Settings") {
                CookieManager.getInstance().flush()
                root.removeView(pane)
                pane.removeView(browser)
                browser.destroy()
                accountWeb = null
                scroller.visibility = android.view.View.VISIBLE
            })
            pane.addView(error)
            pane.addView(browser, LinearLayout.LayoutParams(-1, 0, 1f))
            root.addView(pane, LinearLayout.LayoutParams(-1, 0, 1f))
            openProxmoxPage(browser, address, accountOnly = true) { error.text = it }
        }
        account.addView(ui.button("Manage account", click = ::openAccount))
        val shell = ui.section(content, "Node shell PIN", "Add a local PIN before opening a node console. Proxmox still checks the console account.")
        val current = pinField("Current PIN (when set)")
        val next = pinField("New PIN (4–12 digits)")
        val confirm = pinField("Confirm new PIN")
        listOf(current, next, confirm).forEach(shell::addView)
        val pinStatus = ui.text("", secondary = true)
        shell.addView(pinStatus)
        fun updatePin(remove: Boolean) {
            val origin = PageAddress.parse(server.text.toString(), false)?.origin
            if (origin == null || origin != vault.server) { pinStatus.text = "Save this server first."; return }
            val old = current.text.toString()
            val pin = next.text.toString()
            if (!remove && (!pin.matches(Regex("[0-9]{4,12}")) || pin != confirm.text.toString())) {
                pinStatus.text = "Enter and confirm a 4–12 digit PIN."; return
            }
            if (vault.hasPin(origin) && !old.matches(Regex("[0-9]{4,12}"))) {
                pinStatus.text = "Enter the current PIN."; return
            }
            pinStatus.text = runCatching {
                vault.verifyPin(origin, old) ?: run {
                    vault.setPin(origin, if (remove) "" else pin)
                    listOf(current, next, confirm).forEach { it.text.clear() }
                    if (remove) "PIN removed." else "Node shell PIN saved."
                }
            }.getOrDefault("Android could not update the PIN.")
        }
        shell.addView(ui.button("Save PIN", primary = true) { updatePin(false) })
        shell.addView(ui.button("Remove PIN") { updatePin(true) })
        ui.install(this, root)
        if (vault.server != null && (savedInstanceState?.getBoolean("accountOpen") == true ||
                ProxmoxAccountLaunch.consume(intent.getStringExtra(ModuleContract.EXTRA_HOST_HANDOFF_NONCE)))) {
            openAccount()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("accountOpen", accountWeb != null)
        super.onSaveInstanceState(outState)
    }

    override fun onPause() {
        accountWeb?.onPause()
        if (accountWeb != null) CookieManager.getInstance().flush()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        accountWeb?.onResume()
    }

    override fun onDestroy() {
        accountWeb?.let { web ->
            (web.parent as? android.view.ViewGroup)?.removeView(web)
            web.destroy()
        }
        super.onDestroy()
    }

    private fun field(label: String, variation: Int = InputType.TYPE_TEXT_VARIATION_NORMAL) = EditText(this).apply {
        hint = label
        contentDescription = label
        inputType = InputType.TYPE_CLASS_TEXT or variation
        setSingleLine(true)
    }

    private fun pinField(label: String) = EditText(this).apply {
        hint = label
        contentDescription = label
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        isSaveEnabled = false
        importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO
    }
}
