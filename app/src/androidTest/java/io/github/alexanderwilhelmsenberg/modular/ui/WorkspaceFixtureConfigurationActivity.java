package io.github.alexanderwilhelmsenberg.modular.ui;

import android.app.Activity;
import android.appwidget.AppWidgetManager;
import android.content.SharedPreferences;
import android.os.Bundle;

/** Provider-owned configuration step; the test sets its next result before launch. */
public class WorkspaceFixtureConfigurationActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        int id = getIntent().getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID);
        SharedPreferences preferences = getSharedPreferences("workspace_fixture_config", MODE_PRIVATE);
        preferences.edit()
            .putInt("last_id", id)
            .putInt("launches", preferences.getInt("launches", 0) + 1)
            .commit();
        boolean accepted = preferences.getBoolean("accept_next", true)
            && id != AppWidgetManager.INVALID_APPWIDGET_ID;
        setResult(accepted ? RESULT_OK : RESULT_CANCELED);
        finish();
    }
}
