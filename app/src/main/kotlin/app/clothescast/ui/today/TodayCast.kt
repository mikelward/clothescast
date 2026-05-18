package app.clothescast.ui.today

import android.content.Context
import app.clothescast.ClothesCastApplication
import app.clothescast.R
import app.clothescast.cast.CastInsightController
import app.clothescast.core.domain.model.ForecastPeriod
import app.clothescast.insight.InsightFormatter
import app.clothescast.ui.garment.outfitCardInfoLines
import app.clothescast.ui.garment.renderOutfitCard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.util.Locale

/**
 * Renders the currently-displayed insight's outfit card to a PNG, formats
 * the matching prose, and asks [controller] to push them to the connected
 * Cast device. No-op when there's no insight loaded yet or no outfit
 * resolved for the current period — the user can re-cast (disconnect /
 * reconnect, or tap Cast again) once data arrives.
 *
 * Mirrors the worker's MQTT image-publish path
 * ([FetchAndNotifyWorker.deliver]) so the TV sees byte-identical visuals
 * to what Home Assistant gets — same `renderOutfitCard` call, same
 * `outfitCardInfoLines` aggregation, same theme-overlaid colour maps.
 */
internal fun dispatchCastFromState(
    scope: CoroutineScope,
    context: Context,
    app: ClothesCastApplication,
    controller: CastInsightController,
    state: TodayState,
    locale: Locale,
) {
    val insight = state.thisPeriodInsight ?: return
    val outfit = insight.outfit ?: return
    scope.launch {
        val prefs = app.settingsRepository.preferences.first()
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
                topColors = state.outfitTopColors,
                bottomColors = state.outfitBottomColors,
                topStrokes = state.outfitTopStrokes,
                bottomStrokes = state.outfitBottomStrokes,
            )
        }
        controller.cast(
            prose = prose,
            locale = locale,
            voiceName = prefs.geminiVoice,
            style = prefs.ttsStyle,
            outfitPng = png,
            title = context.getString(R.string.app_name),
            subtitle = insight.location?.displayName,
        )
    }
}
