package io.github.alexanderwilhelmsenberg.modular.addon.homeassistant

import io.github.alexanderwilhelmsenberg.modular.webui.WebWorkspaceService

class DashboardService : WebWorkspaceService() {
    override val descriptorResource = R.raw.module_descriptor
    override val pageTitle = "Home Assistant"
    override val addressLabel = "Dashboard URL"
    override val addressHint = "http://homeassistant.local:8123/lovelace/home"
    override val allowLocalHttp = true
}
