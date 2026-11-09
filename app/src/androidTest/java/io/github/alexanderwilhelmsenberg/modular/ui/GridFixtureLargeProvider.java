package io.github.alexanderwilhelmsenberg.modular.ui;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.widget.RemoteViews;

/** Native 4 by 4 provider hosted from the standalone instrumentation APK. */
public class GridFixtureLargeProvider extends AppWidgetProvider {
    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        for (int id : ids) {
            RemoteViews views = new RemoteViews("android", android.R.layout.simple_list_item_1);
            views.setTextViewText(android.R.id.text1, "4 × 4 grid fixture");
            views.setInt(android.R.id.text1, "setBackgroundColor", 0xFF285A65);
            views.setTextColor(android.R.id.text1, 0xFFFFFFFF);
            manager.updateAppWidget(id, views);
        }
    }
}
