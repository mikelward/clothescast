package app.clothescast.widget

import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver

/**
 * AppWidgetProvider entry point for the per-period feels-like chart widget. The
 * OS routes APPWIDGET_UPDATE / -ENABLED / -DISABLED broadcasts here; Glance does
 * the real work via [FeelsLikeWidget.provideGlance].
 */
class FeelsLikeWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = FeelsLikeWidget()
}

/** As [FeelsLikeWidgetReceiver], for the 7-day feels-like chart widget. */
class SevenDayFeelsLikeWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = SevenDayFeelsLikeWidget()
}
