package org.fossify.gallery.helpers

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import org.fossify.gallery.R
import org.fossify.gallery.activities.AiSearchActivity

class AiSearchWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (widgetId in appWidgetIds) {
            val views = RemoteViews(context.packageName, R.layout.widget_ai_search)
            val intent = Intent(context, AiSearchActivity::class.java)
            val pendingIntent = PendingIntent.getActivity(
                context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.ai_widget_root, pendingIntent)
            val accent = AiPrefs(context).accentColor
            if (accent != 0) {
                views.setInt(R.id.ai_widget_root, "setBackgroundColor", accent)
            }
            appWidgetManager.updateAppWidget(widgetId, views)
        }
    }
}
