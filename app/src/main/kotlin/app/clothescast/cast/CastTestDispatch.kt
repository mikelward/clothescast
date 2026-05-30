package app.clothescast.cast

import android.content.Context
import app.clothescast.R
import app.clothescast.calendar.resolveHolidayTheme
import app.clothescast.core.domain.model.ForecastPeriod
import app.clothescast.core.domain.model.windSpeedUnit
import app.clothescast.core.domain.repository.CalendarEventReader
import app.clothescast.core.domain.usecase.DeriveInsight
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
 * Outcome of a "Cast now" test: carries the same (error, publishedAt,
 * fetchedAt) shape the worker uses, so the Settings VM can call
 * [SettingsRepository.setCastResult] with the matching highwater
 * updates and the status row can differentiate "load failed" from
 * "load succeeded but the display never fetched the video."
 */
data class CastTestOutcome(
    val errorMessage: String?,
    val publishedAtMs: Long?,
    val fetchedAtMs: Long?,
) {
    companion object {
        fun success(atMs: Long = System.currentTimeMillis()): CastTestOutcome =
            CastTestOutcome(errorMessage = null, publishedAtMs = atMs, fetchedAtMs = atMs)

        fun publishedButNotFetched(
            reason: String,
            atMs: Long = System.currentTimeMillis(),
        ): CastTestOutcome =
            CastTestOutcome(errorMessage = reason, publishedAtMs = atMs, fetchedAtMs = null)

        fun failed(reason: String): CastTestOutcome =
            CastTestOutcome(errorMessage = reason, publishedAtMs = null, fetchedAtMs = null)
    }
}

/**
 * Builds the inputs for a "Cast now" test — current insight + outfit
 * PNG + prose — and asks [CastInsightController.castToSavedRoute] to
 * dispatch them to the saved smart display. Mirrors the worker's MQTT
 * image-publish path so the test cast looks identical to what the
 * scheduled cast will (in PR B) produce.
 *
 * Returns a [CastTestOutcome] carrying the error message (if any) plus
 * which highwater timestamps to advance. The caller persists via
 * [SettingsRepository.setCastResult] so the status row can show both
 * "Sent at X" and "Played at Y" lines independently.
 */
internal suspend fun castCurrentInsight(
    context: Context,
    settingsRepository: SettingsRepository,
    insightCache: InsightCache,
    deriveInsight: DeriveInsight,
    calendarEventReader: CalendarEventReader,
    controller: CastInsightController,
    locale: java.util.Locale,
): CastTestOutcome {
    val prefs = settingsRepository.preferences.first()
    val routeId = prefs.castRouteId
        ?: return CastTestOutcome.failed(context.getString(R.string.cast_error_no_route_picked))
    val snapshot = insightCache.thisPeriod.first()
        ?: return CastTestOutcome.failed(context.getString(R.string.cast_error_no_insight_yet))
    val insight = deriveInsight(snapshot, prefs).insight
    val outfit = insight.outfit
        ?: return CastTestOutcome.failed(context.getString(R.string.cast_error_no_insight_yet))

    val formatter = InsightFormatter.forRegion(
        context,
        prefs.region,
        prefs.temperatureUnit,
        prefs.rangeFormat,
        prefs.clothesFormat,
        prefs.bottomsFormat,
        prefs.periodPreamble,
        prefs.wearPreamble,
    )
    val isFutureDay = insight.forDate.isAfter(LocalDate.now())
    val prose = formatter.format(insight.summary, isFutureDay = isFutureDay)
    val info = outfitCardInfoLines(
        context = context,
        formatter = formatter,
        hourly = insight.hourly,
        temperatureUnit = prefs.temperatureUnit,
        windSpeedUnit = prefs.distanceUnit.windSpeedUnit(),
    )
    val header = context.getString(
        if (insight.period == ForecastPeriod.TODAY) R.string.outfit_card_header_today
        else R.string.outfit_card_header_tonight,
    )

    val theme = resolveHolidayTheme(prefs, calendarEventReader)
    val topColors = prefs.outfitTopColors + (theme?.topOverrides ?: emptyMap())
    val bottomColors = prefs.outfitBottomColors + (theme?.bottomOverrides ?: emptyMap())
    val handsColors = prefs.outfitHandsColors
    val topStrokes = theme?.topStrokeOverrides ?: emptyMap()
    val bottomStrokes = theme?.bottomStrokeOverrides ?: emptyMap()

    val png = withContext(Dispatchers.Default) {
        renderOutfitCard(
            context = context,
            outfit = outfit,
            header = header,
            prose = prose,
            info = info,
            topColors = topColors,
            bottomColors = bottomColors,
            handsColors = handsColors,
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
        CastTestOutcome.success()
    } catch (e: CastInsightController.CastFailure.FetchNotConfirmed) {
        // Load was accepted by the receiver — the failure is strictly on
        // the bytes-fetch leg. Advance "last published" but not "last
        // fetched" so the status row reads "published at X but the
        // display didn't fetch it" rather than the misleading flat-
        // failure of every other CastFailure.
        CastTestOutcome.publishedButNotFetched(reason = e.message ?: "")
    } catch (e: CastInsightController.CastFailure) {
        CastTestOutcome.failed(e.message ?: context.getString(R.string.cast_error_unknown))
    } catch (t: Throwable) {
        CastTestOutcome.failed(t.message ?: context.getString(R.string.cast_error_unknown))
    }
}
