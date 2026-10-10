package app.clothescast.widget

import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver

/**
 * AppWidgetProvider entry point for the chance-of-rain chart widget. The OS
 * routes APPWIDGET_UPDATE / -ENABLED / -DISABLED broadcasts here; Glance does
 * the real work via [ChanceOfRainWidget.provideGlance].
 */
class ChanceOfRainWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ChanceOfRainWidget()
}
