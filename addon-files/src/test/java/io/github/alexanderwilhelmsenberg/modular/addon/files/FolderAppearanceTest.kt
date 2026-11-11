package io.github.alexanderwilhelmsenberg.modular.addon.files

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

class FolderAppearanceTest {
    @Test fun appearanceFollowsOpaqueIdentityAcrossNamesAndCanReset() {
        val values = mutableMapOf<String, String>()
        val preferences = fakePreferences(values)
        val first = FolderAppearance(preferences)
        assertEquals("folder", first.json("opaque:folder-1", "Games").getString("icon"))
        assertEquals("auto", first.json("opaque:folder-1", "Games").getString("color"))
        first.setIcon("opaque:folder-1", "game")
        first.setColor("opaque:folder-1", "#6795D4")

        val reloaded = FolderAppearance(preferences)
        val renamed = reloaded.json("opaque:folder-1", "Renamed")
        assertEquals("game", renamed.getString("icon"))
        assertEquals("#6795D4", renamed.getString("color"))
        assertTrue(renamed.getBoolean("customIcon"))
        assertTrue(renamed.getBoolean("customColor"))
        assertEquals("folder", reloaded.json("opaque:folder-2", "Games").getString("icon"))
        assertNotEquals(FolderAppearance.key("opaque:folder-1"), FolderAppearance.key("opaque:folder-2"))
        assertFalse(values.keys.first().contains("opaque:folder-1"))

        reloaded.reset("opaque:folder-1")
        val reset = reloaded.json("opaque:folder-1", "Renamed")
        assertEquals("folder", reset.getString("icon"))
        assertEquals("auto", reset.getString("color"))
        assertFalse(reset.getBoolean("customIcon"))
        assertFalse(reset.getBoolean("customColor"))
    }

    @Test fun onlyListedAppearanceValuesCanBeSaved() {
        val values = mutableMapOf(FolderAppearance.key("id") to "not-json")
        val appearance = FolderAppearance(fakePreferences(values))
        assertThrows(IllegalArgumentException::class.java) { appearance.setIcon("id", "untrusted-icon") }
        assertThrows(IllegalArgumentException::class.java) { appearance.setColor("id", "#123456;bad") }
        appearance.setIcon("id", "music")
        assertEquals("music", FolderAppearance(fakePreferences(values)).json("id", "Music").getString("icon"))
        assertTrue(FolderAppearance.optionsJson().getJSONArray("icons").length() >= 12)
        assertTrue(FolderAppearance.optionsJson().getJSONArray("colors").length() >= 8)
    }

    private fun fakePreferences(values: MutableMap<String, String>): SharedPreferences {
        val editor = Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java)) { proxy, method, args ->
            when (method.name) {
                "putString" -> { values[args!![0] as String] = args[1] as String; proxy }
                "remove" -> { values.remove(args!![0] as String); proxy }
                "apply" -> null
                "commit" -> true
                else -> throw UnsupportedOperationException(method.name)
            }
        } as SharedPreferences.Editor
        return Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)) { _, method, args ->
            when (method.name) {
                "getString" -> values[args!![0] as String] ?: args[1]
                "edit" -> editor
                else -> throw UnsupportedOperationException(method.name)
            }
        } as SharedPreferences
    }
}
