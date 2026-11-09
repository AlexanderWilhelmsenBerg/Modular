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
            // Provider-owned colors make the actual widget bounds visible in every launcher theme.
            views.setInt(android.R.id.text1, "setBackgroundColor", 0xFF203747);
            views.setTextColor(android.R.id.text1, 0xFFFFFFFF);
            manager.updateAppWidget(id, views);
        }
    }
}
