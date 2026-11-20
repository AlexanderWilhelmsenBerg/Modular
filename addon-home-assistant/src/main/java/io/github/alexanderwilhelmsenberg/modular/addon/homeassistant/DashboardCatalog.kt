package io.github.alexanderwilhelmsenberg.modular.addon.homeassistant

import android.content.Context
import io.github.alexanderwilhelmsenberg.modular.webui.PageAddress
import org.json.JSONArray

internal data class Dashboard(val title: String, val path: String)

internal class DashboardCatalog(context: Context) {
    private val prefs = context.getSharedPreferences("dashboard-catalog", Context.MODE_PRIVATE)
    private val addresses = context.getSharedPreferences("workspace-addresses", Context.MODE_PRIVATE)

    var server: PageAddress?
        get() = prefs.getString("server", null)?.let { PageAddress.parse(it, true) }
        set(value) { prefs.edit().putString("server", value?.origin).apply() }

    fun selected(placement: String) = addresses.getString(placement, null)?.let { PageAddress.parse(it, true) }

    fun select(placement: String, address: PageAddress, dashboard: Dashboard) {
        addresses.edit().putString(placement, address.origin + dashboard.path).apply()
    }

    fun read(address: PageAddress) = decode(prefs.getString("panels:${address.origin}", "[]").orEmpty())

    fun refresh(address: PageAddress, raw: String): List<Dashboard> {
        val items = decode(raw)
        prefs.edit().putString("panels:${address.origin}", raw).apply()
        return items
    }

    private fun decode(raw: String): List<Dashboard> = runCatching {
        val list = JSONArray(raw)
        buildList {
            for (index in 0 until list.length().coerceAtMost(200)) {
                val row = list.optJSONObject(index) ?: continue
                val path = row.optString("path")
                if (!path.matches(Regex("/[A-Za-z0-9_-]{1,128}"))) continue
                add(Dashboard(row.optString("title").trim().take(90).ifEmpty {
                    path.removePrefix("/").replace('-', ' ').replaceFirstChar { it.uppercase() }
                }, path))
            }
        }.distinctBy { it.path }.sortedBy { it.title.lowercase() }
    }.getOrDefault(emptyList())
}
