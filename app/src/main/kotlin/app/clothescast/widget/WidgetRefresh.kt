package app.clothescast.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import app.clothescast.core.domain.model.ClothesRule
import app.clothescast.core.domain.model.ColorPalette
import app.clothescast.core.domain.model.OutfitSuggestion
import app.clothescast.core.domain.model.Region
import app.clothescast.core.domain.model.TemperatureUnit
import app.clothescast.core.domain.model.ThemeMode
import app.clothescast.core.domain.model.TimeFormat
import app.clothescast.core.domain.model.UserPreferences
import app.clothescast.core.domain.model.WindSpeedUnit

/**
 * The slice of [UserPreferences] that changes what a placed home-screen widget
 * draws. Glance widgets render from a one-shot snapshot and don't observe the
 * preferences flow (unlike the Today screen), so something has to call
 * [updateAllClothesCastWidgets] when one of these fields changes. Rather than
 * making every settings setter remember to poke the launcher,
 * `ClothesCastApplication` watches `preferences.map { it.toWidgetInputs() }`
 * with `distinctUntilChanged` and repaints on any change.
 *
 * Keep this in sync with what the widgets actually read:
 *  - [ConditionsWidget]: [region] (the locale formatter), [temperatureUnit],
 *    [windSpeedUnit].
 *  - [FeelsLikeWidget]: [temperatureUnit], [timeFormat], [colorPalette],
 *    [themeMode].
 *  - The outfit icon ([OutfitWidget]): [clothesRules], [defaultTop] /
 *    [defaultBottom], and the per-tier colour overrides.
 *
 * Deliberately excludes the schedule: which period the widget shows is derived
 * from the clock at render time, and a schedule edit doesn't repaint the
 * launcher today — only the next scheduled run picks it up. Adding a field the
 * widgets don't render just causes needless repaints; omitting one the widgets
 * do render reintroduces the stale-widget bug, so treat this list as the
 * contract.
 */
data class WidgetInputs(
    val region: Region,
    val temperatureUnit: TemperatureUnit,
    val windSpeedUnit: WindSpeedUnit,
    val timeFormat: TimeFormat,
    val colorPalette: ColorPalette,
    val themeMode: ThemeMode,
    val clothesRules: List<ClothesRule>,
    val defaultTop: OutfitSuggestion.Top,
    val defaultBottom: OutfitSuggestion.Bottom,
    val outfitTopColors: Map<OutfitSuggestion.Top, Long>,
    val outfitBottomColors: Map<OutfitSuggestion.Bottom, Long>,
    val outfitHandsColors: Map<OutfitSuggestion.Hands, Long>,
    val outfitCarriedColors: Map<OutfitSuggestion.Carried, Long>,
    val outfitOuterColors: Map<OutfitSuggestion.Outer, Long>,
)

/**
 * True when at least one ClothesCast widget (any of the four providers) is
 * currently placed on a launcher. Gates the widget-only refresh alarm chain
 * (see [app.clothescast.alarm.WidgetRefreshScheduler]): armed while this is
 * true, cancelled once the last widget is removed.
 */
internal fun hasPlacedClothesCastWidgets(context: Context): Boolean {
    val manager = AppWidgetManager.getInstance(context) ?: return false
    return listOf(
        OutfitWidgetReceiver::class.java,
        FeelsLikeWidgetReceiver::class.java,
        SevenDayFeelsLikeWidgetReceiver::class.java,
        ConditionsWidgetReceiver::class.java,
    ).any { manager.getAppWidgetIds(ComponentName(context, it)).isNotEmpty() }
}

/** Projects the widget-relevant fields out of the full preferences. */
fun UserPreferences.toWidgetInputs(): WidgetInputs = WidgetInputs(
    region = region,
    temperatureUnit = temperatureUnit,
    windSpeedUnit = windSpeedUnit,
    timeFormat = timeFormat,
    colorPalette = colorPalette,
    themeMode = themeMode,
    clothesRules = clothesRules,
    defaultTop = defaultTop,
    defaultBottom = defaultBottom,
    outfitTopColors = outfitTopColors,
    outfitBottomColors = outfitBottomColors,
    outfitHandsColors = outfitHandsColors,
    outfitCarriedColors = outfitCarriedColors,
    outfitOuterColors = outfitOuterColors,
)
