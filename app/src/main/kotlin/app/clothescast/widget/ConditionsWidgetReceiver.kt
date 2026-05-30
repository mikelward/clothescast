package app.clothescast.widget

import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver

/**
 * AppWidgetProvider entry point for the at-a-glance conditions widget. The OS
 * routes APPWIDGET_UPDATE / -ENABLED / -DISABLED broadcasts here; Glance does
 * the real work via [ConditionsWidget.provideGlance].
 */
class ConditionsWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ConditionsWidget()
}
