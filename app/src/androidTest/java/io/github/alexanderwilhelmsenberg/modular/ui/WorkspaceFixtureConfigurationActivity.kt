package io.github.alexanderwilhelmsenberg.modular.ui

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.os.Bundle

/** Provider-owned configuration step; the test sets its next result before launch. */
class WorkspaceFixtureConfigurationActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        val preferences = getSharedPreferences("workspace_fixture_config", MODE_PRIVATE)
        preferences.edit().putInt("last_id", id).putInt("launches", preferences.getInt("launches", 0) + 1).commit()
        val accepted = preferences.getBoolean("accept_next", true) && id != AppWidgetManager.INVALID_APPWIDGET_ID
        setResult(if (accepted) RESULT_OK else RESULT_CANCELED)
        finish()
    }
}
