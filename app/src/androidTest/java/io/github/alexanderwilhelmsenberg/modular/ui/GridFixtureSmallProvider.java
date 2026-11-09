package io.github.alexanderwilhelmsenberg.modular.ui;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.widget.RemoteViews;

/** Small provider whose four instances fit across a compact Home grid. */
public class GridFixtureSmallProvider extends AppWidgetProvider {
    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        for (int id : ids) {
            RemoteViews views = new RemoteViews("android", android.R.layout.simple_list_item_1);
            views.setTextViewText(android.R.id.text1, "1 × 1");
            views.setInt(android.R.id.text1, "setBackgroundColor", 0xFF394F88);
            views.setTextColor(android.R.id.text1, 0xFFFFFFFF);
            manager.updateAppWidget(id, views);
        }
    }
}
