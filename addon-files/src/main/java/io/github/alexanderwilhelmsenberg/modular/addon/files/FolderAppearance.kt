package io.github.alexanderwilhelmsenberg.modular.addon.files

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/** Per-folder decoration keyed by the repository's opaque identity, never by a path or display name. */
internal class FolderAppearance(private val preferences: SharedPreferences) {
    data class Option(val id: String, val label: String)

    companion object {
        val icons = listOf(
            Option("folder", "Folder"), Option("game", "Games"), Option("audio", "Audio"),
            Option("music", "Music"), Option("pictures", "Pictures"), Option("video", "Video"),
            Option("downloads", "Downloads"), Option("documents", "Documents"),
            Option("archive", "Archives"), Option("camera", "Camera"), Option("work", "Work"),
            Option("home", "Home"), Option("books", "Books"), Option("code", "Code"),
            Option("apps", "Apps"), Option("star", "Favorites")
        )
        val colors = listOf(
            Option("auto", "Automatic"), Option("#E7B93F", "Yellow"),
            Option("#E77D68", "Coral"), Option("#D98DBA", "Pink"),
            Option("#A58BD3", "Purple"), Option("#6795D4", "Blue"),
            Option("#63BDB3", "Teal"), Option("#78B778", "Green"),
            Option("#A8A8A8", "Grey")
        )

        fun key(folderId: String): String {
            require(folderId.isNotEmpty()) { "Folder identity is missing" }
            val hash = MessageDigest.getInstance("SHA-256")
                .digest(folderId.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            return "folderAppearance:$hash"
        }

        fun optionsJson(): JSONObject = JSONObject()
            .put("icons", JSONArray().also { array -> icons.forEach { array.put(JSONObject().put("id", it.id).put("label", it.label)) } })
            .put("colors", JSONArray().also { array -> colors.forEach { array.put(JSONObject().put("id", it.id).put("label", it.label)) } })
    }

    fun json(folderId: String, name: String): JSONObject {
        val saved = runCatching { JSONObject(preferences.getString(key(folderId), "{}") ?: "{}") }.getOrDefault(JSONObject())
        val icon = saved.optString("icon").takeIf { candidate -> icons.any { it.id == candidate } } ?: "folder"
        val color = saved.optString("color").takeIf { candidate -> colors.any { it.id == candidate } } ?: "auto"
        return JSONObject().put("id", folderId).put("name", name)
            .put("icon", icon).put("color", color)
            .put("customIcon", saved.has("icon") && icon != "folder")
            .put("customColor", saved.has("color") && color != "auto")
    }

    fun setIcon(folderId: String, icon: String) {
        require(icons.any { it.id == icon }) { "Choose a listed folder icon" }
        update(folderId, "icon", icon.takeUnless { it == "folder" })
    }

    fun setColor(folderId: String, color: String) {
        require(colors.any { it.id == color }) { "Choose a listed folder color" }
        update(folderId, "color", color.takeUnless { it == "auto" })
    }

    fun reset(folderId: String) {
        check(preferences.edit().remove(key(folderId)).commit()) { "Could not save folder appearance" }
    }

    private fun update(folderId: String, field: String, value: String?) {
        val stored = runCatching { JSONObject(preferences.getString(key(folderId), "{}") ?: "{}") }
            .getOrDefault(JSONObject())
        if (value == null) stored.remove(field) else stored.put(field, value)
        if (stored.length() == 0) reset(folderId)
        else check(preferences.edit().putString(key(folderId), stored.toString()).commit()) {
            "Could not save folder appearance"
        }
    }
}
