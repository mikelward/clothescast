package app.clothescast.cast

import android.content.Context
import app.clothescast.R
import app.clothescast.core.domain.model.ForecastPeriod
import app.clothescast.core.domain.model.UserPreferences
import app.clothescast.diag.DiagLog
import com.google.android.gms.cast.framework.CastContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlin.coroutines.cancellation.CancellationException

/**
 * Outcome of a [CastPublisher.publishIfEnabled] call. Mirrors
 * [app.clothescast.mqtt.MqttPublishOutcome] so the worker / settings UI
 * can persist a uniform "last cast result" status.
 */
sealed interface CastOutcome {
    val isSuccess: Boolean get() = this is Success

    /** No display picked, period toggle off, or Cast framework unavailable — intentional no-op. */
    data object NotConfigured : CastOutcome

    /** Display loaded the media successfully. */
    data object Success : CastOutcome

    /** Cast was attempted but failed — [message] is a short, user-readable description. */
    data class Failure(val message: String) : CastOutcome
}

/**
 * Worker-path entry point for casting the daily forecast to the smart
 * display the user picked in Settings → Smart Home → Cast. Mirrors
 * [app.clothescast.mqtt.MqttPublisher]:
 *
 *  - Reads [UserPreferences] on every call so settings edits take effect
 *    on the next refresh.
 *  - Gates on the per-period toggle ([UserPreferences.castMorningEnabled]
 *    / [castTonightEnabled]) and the wake / interrupt behaviour toggles.
 *  - Reuses the WAV the worker already encoded for the MQTT audio publish
 *    + the PNG it already rendered for the MQTT image publish — no
 *    second Gemini call, no second `renderOutfitCard`.
 *  - Returns a [CastOutcome] for the caller to persist via
 *    [app.clothescast.data.SettingsRepository.setCastLastError].
 *
 * Returns [CastOutcome.NotConfigured] silently when Cast SDK init failed
 * (Cast-less builds — no Google Play services), when the user hasn't
 * picked a display, or when the period toggle is off; those aren't
 * errors and shouldn't surface on the Settings status row.
 */
class CastPublisher(
    private val context: Context,
    private val preferences: Flow<UserPreferences>,
    private val castContext: CastContext?,
    private val controller: CastInsightController?,
) {

    suspend fun publishIfEnabled(
        period: ForecastPeriod,
        wav: ByteArray,
        outfitPng: ByteArray,
        subtitle: String?,
    ): CastOutcome {
        val controller = controller ?: return CastOutcome.NotConfigured
        @Suppress("UNUSED_VARIABLE") // keeps the parity gate visible.
        val context = castContext ?: return CastOutcome.NotConfigured
        val prefs = preferences.first()
        val routeId = prefs.castRouteId ?: return CastOutcome.NotConfigured
        val periodEnabled = when (period) {
            ForecastPeriod.TODAY -> prefs.castMorningEnabled
            ForecastPeriod.TONIGHT -> prefs.castTonightEnabled
        }
        if (!periodEnabled) {
            DiagLog.i(TAG, "Cast skipped for ${period.name.lowercase()} — period toggle is off.")
            return CastOutcome.NotConfigured
        }

        // Wake / interrupt gates live inside [dispatchToSavedRoute] now —
        // checking them upstream of [findRoute] read stale `router.routes`
        // (empty on a cold worker run before discovery had populated the
        // list) and silently let busy / asleep displays through. The
        // controller checks them post-discovery; we just pass the flags.
        val title = this.context.getString(R.string.app_name)
        return try {
            controller.dispatchToSavedRoute(
                routeId = routeId,
                wav = wav,
                outfitPng = outfitPng,
                title = title,
                subtitle = subtitle,
                allowWake = prefs.castWakeDisplay,
                allowInterrupt = prefs.castInterruptPlaying,
            )
            DiagLog.i(TAG, "Cast load issued for ${period.name.lowercase()} insight.")
            CastOutcome.Success
        } catch (e: CastInsightController.CastFailure) {
            DiagLog.i(TAG, "Cast skipped / failed for ${period.name.lowercase()}: ${e.message}")
            CastOutcome.Failure(failureMessageFor(e))
        } catch (t: Throwable) {
            // Worker cancellation (replacement refresh, OS stop, screen
            // navigates away mid-test) must propagate — otherwise a
            // cancelled run records a Failure status and may even fall
            // back to phone playback before unwinding. Mirrors the MQTT
            // publish-path's CancellationException rethrow.
            if (t is CancellationException) throw t
            DiagLog.w(TAG, "Cast failed for ${period.name.lowercase()} (unexpected).", t)
            CastOutcome.Failure(
                t.message ?: this.context.getString(R.string.cast_error_unknown),
            )
        }
    }

    private fun failureMessageFor(failure: CastInsightController.CastFailure): String = when (failure) {
        is CastInsightController.CastFailure.DisplayAsleep ->
            context.getString(R.string.cast_error_skipped_display_asleep)
        is CastInsightController.CastFailure.DisplayInUse ->
            context.getString(R.string.cast_error_skipped_display_in_use)
        else -> failure.message ?: context.getString(R.string.cast_error_unknown)
    }

    companion object {
        private const val TAG = "CastPublisher"
    }
}
