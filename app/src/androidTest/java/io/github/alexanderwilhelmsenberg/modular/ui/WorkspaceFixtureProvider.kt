package io.github.alexanderwilhelmsenberg.modular.ui

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.widget.RemoteViews

/** Actual Android RemoteViews provider installed with the instrumentation APK. */
open class WorkspaceFixtureProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { id ->
            manager.updateAppWidget(id, RemoteViews("android", android.R.layout.simple_list_item_1).apply {
                setTextViewText(android.R.id.text1, "Workspace fixture widget")
            })
        }
    }
}

class ConfiguredWorkspaceFixtureProvider : WorkspaceFixtureProvider()
