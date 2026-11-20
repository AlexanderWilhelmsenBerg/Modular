package io.github.alexanderwilhelmsenberg.modular.addon.proxmox

import android.webkit.WebView
import io.github.alexanderwilhelmsenberg.modular.modulesdk.WorkspaceSurfaceScope
import io.github.alexanderwilhelmsenberg.modular.webui.PageAddress
import io.github.alexanderwilhelmsenberg.modular.webui.WebWorkspaceService
import java.util.UUID

class ProxmoxService : WebWorkspaceService() {
    override val descriptorResource = R.raw.module_descriptor
    override val pageTitle = "Proxmox"
    override val addressLabel = "Server connection"
    override val addressHint = ""
    override val workspaceAsset = "cluster.html"
    override val managedAddress = true
    private val vault by lazy { ProxmoxCredentials(this) }

    override fun defaultAddress() = vault.server

    override fun manageAddress(scope: WorkspaceSurfaceScope): Boolean {
        scope.requestActivity(UUID.randomUUID().toString())
        return true
    }

    override fun configureBrowser(web: WebView, address: PageAddress, scope: WorkspaceSurfaceScope): android.webkit.WebViewClient {
        if (vault.server == null) runCatching { vault.server = address.origin }
        return ProxmoxBrowser(web, address, vault, scope)
    }
}
