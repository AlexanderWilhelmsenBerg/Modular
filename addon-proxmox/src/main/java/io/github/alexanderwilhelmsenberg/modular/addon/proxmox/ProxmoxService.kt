package io.github.alexanderwilhelmsenberg.modular.addon.proxmox

import io.github.alexanderwilhelmsenberg.modular.webui.WebWorkspaceService

class ProxmoxService : WebWorkspaceService() {
    override val descriptorResource = R.raw.module_descriptor
    override val pageTitle = "Proxmox"
    override val addressLabel = "Server URL"
    override val addressHint = "https://pve.example.net:8006"
    override val workspaceAsset = "cluster.html"
}
