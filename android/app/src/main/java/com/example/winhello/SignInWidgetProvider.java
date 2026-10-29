package com.example.winhello;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

public final class SignInWidgetProvider extends AppWidgetProvider {
    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] widgetIds) {
        Intent launchApp = new Intent(context, MainActivity.class);
        launchApp.setAction(MainActivity.ACTION_SCAN);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                context,
                0,
                launchApp,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        for (int widgetId : widgetIds) {
            RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.sign_in_widget);
            views.setOnClickPendingIntent(R.id.request_sign_in, pendingIntent);
            manager.updateAppWidget(widgetId, views);
        }
    }
}
