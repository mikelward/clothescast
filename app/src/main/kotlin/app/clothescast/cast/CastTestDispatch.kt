package app.clothescast.cast

import android.content.Context
import app.clothescast.R
import app.clothescast.calendar.resolveHolidayTheme
import app.clothescast.core.domain.model.ForecastPeriod
import app.clothescast.core.domain.repository.CalendarEventReader
import app.clothescast.data.InsightCache
import app.clothescast.data.SettingsRepository
import app.clothescast.insight.InsightFormatter
import app.clothescast.ui.garment.outfitCardInfoLines
import app.clothescast.ui.garment.renderOutfitCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.time.LocalDate

/**
 * Builds the inputs for a "Cast now" test — current insight + outfit
 * PNG + prose — and asks [CastInsightController.castToSavedRoute] to
 * dispatch them to the saved smart display. Mirrors the worker's MQTT
 * image-publish path so the test cast looks identical to what the
 * scheduled cast will (in PR B) produce.
 *
 * Returns `null` on success, or a user-facing error string on failure
 * (no route picked yet, no insight yet, Cast device not found, etc.).
 * The caller persists the string via
 * [SettingsRepository.setCastLastError] and shows it on the Settings
 * status row.
 */
internal suspend fun castCurrentInsight(
    context: Context,
    settingsRepository: SettingsRepository,
    insightCache: InsightCache,
    calendarEventReader: CalendarEventReader,
    controller: CastInsightController,
    locale: java.util.Locale,
): String? {
    val prefs = settingsRepository.preferences.first()
    val routeId = prefs.castRouteId
        ?: return context.getString(R.string.cast_error_no_route_picked)
    val insight = insightCache.thisPeriod.first()
        ?: return context.getString(R.string.cast_error_no_insight_yet)
    val outfit = insight.outfit
        ?: return context.getString(R.string.cast_error_no_insight_yet)

    val formatter = InsightFormatter.forRegion(context, prefs.region, prefs.temperatureUnit)
    val isFutureDay = insight.forDate.isAfter(LocalDate.now())
    val prose = formatter.format(insight.summary, isFutureDay = isFutureDay)
    val info = outfitCardInfoLines(
        context = context,
        formatter = formatter,
        hourly = insight.hourly,
        temperatureUnit = prefs.temperatureUnit,
    )
    val header = context.getString(
        if (insight.period == ForecastPeriod.TODAY) R.string.outfit_card_header_today
        else R.string.outfit_card_header_tonight,
    )

    val theme = resolveHolidayTheme(prefs, calendarEventReader)
    val topColors = prefs.outfitTopColors + (theme?.topOverrides ?: emptyMap())
    val bottomColors = prefs.outfitBottomColors + (theme?.bottomOverrides ?: emptyMap())
    val topStrokes = theme?.topStrokeOverrides ?: emptyMap()
    val bottomStrokes = theme?.bottomStrokeOverrides ?: emptyMap()

    val png = withContext(Dispatchers.Default) {
        renderOutfitCard(
            context = context,
            outfit = outfit,
            header = header,
            prose = prose,
            tempLine = info.tempLine,
            rainLine = info.rainLine,
            tempFillFraction = info.tempFillFraction,
            rainFillFraction = info.rainFillFraction,
            topColors = topColors,
            bottomColors = bottomColors,
            topStrokes = topStrokes,
            bottomStrokes = bottomStrokes,
        )
    }

    return try {
        controller.castToSavedRoute(
            routeId = routeId,
            prose = prose,
            locale = locale,
            voiceName = prefs.geminiVoice,
            style = prefs.ttsStyle,
            outfitPng = png,
            title = context.getString(R.string.app_name),
            subtitle = insight.location?.displayName,
        )
        null
    } catch (e: CastInsightController.CastFailure) {
        e.message
    } catch (t: Throwable) {
        t.message ?: context.getString(R.string.cast_error_unknown)
    }
}
