package io.github.alexanderwilhelmsenberg.modular.widgets

import android.app.Activity
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle

/** AppWidgetHost uses the platform configure sender, including non-exported provider activities. */
class WidgetConfigurationActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return // Android will deliver the in-flight result.
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1)
        val expected = ComponentName.unflattenFromString(intent.getStringExtra(EXTRA_PROVIDER).orEmpty())
        val info = if (id > 0) AppWidgetManager.getInstance(this).getAppWidgetInfo(id) else null
        if (id <= 0 || expected == null || info?.provider != expected || info.configure == null) {
            setResult(RESULT_CANCELED, resultData(id))
            finish()
            return
        }
        try {
            AppWidgetHost(this, HOST_ID).startAppWidgetConfigureActivityForResult(
                this, id, 0, REQUEST_CONFIGURE, null)
        } catch (_: Exception) {
            setResult(RESULT_CANCELED, resultData(id))
            finish()
        }
    }

    @Deprecated("Android Activity result callback for provider configuration")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CONFIGURE) {
            val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1)
            setResult(resultCode, resultData(id))
            finish()
        }
    }

    private fun resultData(id: Int) = Intent().apply {
        putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
    }

    companion object {
        const val EXTRA_PROVIDER = "provider"
        private const val REQUEST_CONFIGURE = 1
    }
}
