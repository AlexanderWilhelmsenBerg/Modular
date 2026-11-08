package io.github.alexanderwilhelmsenberg.modular.ui

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle

/** IPC state for the provider configuration Activity, which runs in the test APK process. */
class WorkspaceFixtureControlProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val preferences = requireNotNull(context).getSharedPreferences("workspace_fixture_config", 0)
        return when (method) {
            "snapshot" -> Bundle().apply {
                putBoolean("accept_next", preferences.getBoolean("accept_next", true))
                putInt("launches", preferences.getInt("launches", 0))
                putInt("last_id", preferences.getInt("last_id", -1))
            }
            "set_accept" -> {
                val value = requireNotNull(extras).getBoolean("accept_next")
                check(preferences.edit().putBoolean("accept_next", value).commit())
                Bundle.EMPTY
            }
            else -> throw IllegalArgumentException("Unknown fixture method: $method")
        }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
