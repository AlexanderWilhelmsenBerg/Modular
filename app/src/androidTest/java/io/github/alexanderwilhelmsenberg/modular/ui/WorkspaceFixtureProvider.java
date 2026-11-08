package io.github.alexanderwilhelmsenberg.modular.ui;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.widget.RemoteViews;

/** RemoteViews provider that can run in the standalone instrumentation APK process. */
public class WorkspaceFixtureProvider extends AppWidgetProvider {
    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        for (int id : ids) {
            RemoteViews views = new RemoteViews("android", android.R.layout.simple_list_item_1);
            views.setTextViewText(android.R.id.text1, "Workspace fixture widget");
            manager.updateAppWidget(id, views);
        }
    }
}
