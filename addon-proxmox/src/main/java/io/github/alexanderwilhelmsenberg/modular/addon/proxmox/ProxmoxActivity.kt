package io.github.alexanderwilhelmsenberg.modular.addon.proxmox

import android.app.Activity
import android.content.res.Configuration
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.ScrollView
import io.github.alexanderwilhelmsenberg.modular.webui.AddonScreen
import io.github.alexanderwilhelmsenberg.modular.webui.PageAddress

class ProxmoxActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val vault = ProxmoxCredentials(this)
        val ui = AddonScreen(this, resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES)
        val root = ui.column()
        ui.bar(root, "Proxmox").addView(ui.button("Done") { finish() })
        val content = ui.column()
        root.addView(ScrollView(this).apply { addView(content) }, android.widget.LinearLayout.LayoutParams(-1, 0, 1f))
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
