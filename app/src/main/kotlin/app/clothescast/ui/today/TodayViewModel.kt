package app.clothescast.ui.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.work.Data
import androidx.work.WorkInfo
import androidx.work.WorkManager
import app.clothescast.core.domain.model.BottomsFormat
import app.clothescast.core.domain.model.ClothesFormat
import app.clothescast.core.domain.model.ClothesRule
import app.clothescast.core.domain.model.PreambleVisibility
import app.clothescast.core.domain.model.DistanceUnit
import app.clothescast.core.domain.model.HolidayCatalog
import app.clothescast.core.domain.model.HolidayTheme
import app.clothescast.core.domain.model.HomeSection
import app.clothescast.core.domain.model.Insight
import app.clothescast.core.domain.model.OutfitSuggestion
import app.clothescast.core.domain.model.postsNotification
import app.clothescast.core.domain.model.RangeFormat
import app.clothescast.core.domain.model.Region
import app.clothescast.core.domain.model.TemperatureUnit
import app.clothescast.core.domain.model.TimeFormat
import app.clothescast.core.domain.model.UserPreferences
import app.clothescast.core.domain.model.CalendarEvent
import app.clothescast.core.domain.repository.CalendarEventReader
import app.clothescast.core.domain.usecase.DeriveInsight
import app.clothescast.core.domain.usecase.HolidayResolver
import app.clothescast.core.domain.usecase.ThemeForToday
import app.clothescast.tts.toJavaLocale
import java.util.Locale
import app.clothescast.data.InsightCache
import app.clothescast.data.SettingsRepository
import app.clothescast.work.FetchAndNotifyWorker
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

data class TodayState(
    /**
     * The insight shown on page 1 of the pager — the 12-hour window the user
     * is currently in. Backed by [InsightCache.thisPeriod], written by the
     * worker on each alarm.
     */
    val thisPeriodInsight: Insight? = null,
    /**
     * The insight shown on page 2 of the pager — the next 12-hour window
     * (tonight on a morning alarm; tomorrow's daytime on an evening alarm).
     * Backed by [InsightCache.nextPeriod], pre-rendered off the same fetch
     * that produced `thisPeriodInsight`. Null when the worker hasn't run yet
     * or the pre-render failed; the screen surfaces a
     * [MissingPeriodPlaceholder] in that case.
     */
    val nextPeriodInsight: Insight? = null,
    val workStatus: WorkStatus = WorkStatus.Idle,
    /**
     * True iff any worker on the daily / tonight / replay queues is in an
     * active state (ENQUEUED / RUNNING / BLOCKED) — independent of the
     * [FetchAndNotifyWorker.KEY_FETCH_COMPLETE] progress flag that
     * [workStatus] suppresses. The Today screen reads this on the Play
     * button so a tap can't double-deliver against an in-flight Refresh
     * (now in a separate queue from replay), can't kick off a second
     * concurrent Replay, and can't race a scheduled alarm fire that's
     * actively writing to [InsightCache.thisPeriod].
     */
    val anyWorkActive: Boolean = false,
    val temperatureUnit: TemperatureUnit = TemperatureUnit.CELSIUS,
    /**
     * Feels-like delta (°C) the today / tonight delta clause and the 7-day
     * page's week-ahead temperature-shift rule must clear before emitting.
     * Mirrors `UserPreferences.deltaThresholdC` so the same Settings toggle
     * gates both surfaces; `null` disables the rule entirely (the user's
     * "Temperature change: Off" setting).
     */
    val deltaThresholdC: Double? = 3.0,
    val rangeFormat: RangeFormat = RangeFormat.DEGREES,
    val clothesFormat: ClothesFormat = ClothesFormat.ITEMS,
    val bottomsFormat: BottomsFormat = BottomsFormat.IF_GARMENTS,
    val periodPreamble: PreambleVisibility = PreambleVisibility.ALWAYS,
    val wearPreamble: PreambleVisibility = PreambleVisibility.ALWAYS,
    val distanceUnit: DistanceUnit = DistanceUnit.KILOMETERS,
    val region: Region = Region.SYSTEM,
    val timeFormat: TimeFormat = TimeFormat.TWENTY_FOUR_HOUR,
    // Window boundaries used by manual Refresh to decide TODAY vs TONIGHT.
    // Default to the same 7am / 7pm boundaries Schedule uses out of the box;
    // the ViewModel overwrites these with the user's actual schedule times.
    val morningTime: LocalTime = LocalTime.of(7, 0),
    val tonightTime: LocalTime = LocalTime.of(19, 0),
    // Inputs the Today screen needs to compute whether a "set up location"
    // prompt should appear. Permission state is checked in the Composable
    // (it depends on Context and needs an on-resume re-check); the ViewModel
    // just exposes the prefs side of the equation.
    val useDeviceLocation: Boolean = false,
    val hasFallbackLocation: Boolean = false,
    /**
     * True iff at least one enabled schedule (morning / tonight) delivers via a
     * notification-posting mode. The Today screen reads this to decide whether
     * the missing-notification-permission warning is worth surfacing: a user
     * whose schedules are all off, SILENT, or TTS-only won't have a notification
     * dropped, so there's nothing to warn about. The permission state itself is
     * checked live in the banner composable (it needs Context + an on-resume
     * re-check), so this is just the prefs side of the gate.
     */
    val notifyScheduleEnabled: Boolean = false,
    /** Live clothes rules the rationale dialog reads to render the current threshold
     * value and the `−1°` / `+1°` controls. The cached [Insight.outfitRationale]
     * still carries the rule values that *were* in effect at insight-generation
     * time (which can differ from these if the user has nudged a knob since); the
     * dialog prefers these for display so the controls stay honest. */
    val clothesRules: List<ClothesRule> = emptyList(),
    /** Top-to-bottom order of the reorderable home-screen sections. */
    val homeSectionOrder: List<HomeSection> = HomeSection.DEFAULTS,
    /**
     * Whether to overlay each major weather model's hourly curve on the
     * forecast / feels-like / precipitation charts. Session-scoped (lives on
     * the ViewModel, not in DataStore) — toggled by tapping the confidence
     * chip, the low-confidence callout, or any of the three temperature /
     * precip cards. Resets to off when the Activity's ViewModelStore is
     * cleared (config change preserves it; process death does not). The
     * diagnostic cards below (wind / humidity / cloud / solar / UV /
     * sunshine) ignore this flag — they're per-model-only and render whenever
     * [Insight.perModelHourly] is present.
     */
    val showModelSpread: Boolean = false,
    /**
     * Fill colour overrides for each top-icon tier. Empty = baked-in
     * defaults. On a date matching an enabled holiday this map carries the
     * holiday's palette overlaid on top of the user's [UserPreferences.outfitTopColors]
     * — see [TodayViewModel.state]'s combine builder. Holiday wins for the
     * day; the user's custom colour is restored at the next render after
     * the calendar rolls over.
     */
    val outfitTopColors: Map<OutfitSuggestion.Top, Long> = emptyMap(),
    /** Sibling of [outfitTopColors] for the bottom-icon tier. */
    val outfitBottomColors: Map<OutfitSuggestion.Bottom, Long> = emptyMap(),
    /** Sibling of [outfitTopColors] for the optional gloves (hands) overlay.
     *  No holiday theming today, so this is just the user's picked colour. */
    val outfitHandsColors: Map<OutfitSuggestion.Hands, Long> = emptyMap(),
    /** Sibling of [outfitTopColors] for the optional carried umbrella overlay.
     *  No holiday theming today, so this is just the user's picked colour. */
    val outfitCarriedColors: Map<OutfitSuggestion.Carried, Long> = emptyMap(),
    /** Sibling of [outfitTopColors] for the optional rain-jacket outer overlay.
     *  No holiday theming today, so this is just the user's picked colour. */
    val outfitOuterColors: Map<OutfitSuggestion.Outer, Long> = emptyMap(),
    /**
     * Holiday-theme accent colour overrides for the *stroke* / outline of
     * each top tier. Empty = no override; the renderer auto-derives a
     * darker shade of the fill (the long-standing two-tone look). Used by
     * 2-colour holidays to put the alternate colour on top-icon outlines
     * (e.g. yellow Australia-Day shirt with green collar) and by 3-colour
     * holidays to put the unifying third colour on both top + bottom (e.g.
     * white trim on US Independence Day's red top + blue bottom).
     */
    val outfitTopStrokes: Map<OutfitSuggestion.Top, Long> = emptyMap(),
    /** Sibling of [outfitTopStrokes] for the bottom-icon tier. */
    val outfitBottomStrokes: Map<OutfitSuggestion.Bottom, Long> = emptyMap(),
    /**
     * The holiday theme firing on today's calendar date — `null` when no
     * enabled holiday matches. The Today screen reads this to render a small
     * themed banner above the outfit preview row.
     */
    val activeHoliday: HolidayTheme? = null,
    /**
     * Whether the Today-screen "Celebration themes" promo card should
     * render. True iff the user hasn't dismissed it AND neither of the
     * two calendar-sourced theming toggles is on yet — the moment either
     * goes on, the card hides regardless of dismissal state.
     */
    val celebrationCardVisible: Boolean = false,
    /**
     * Whether the Today-screen "Customize your clothes" promo card
     * should render. True iff the user hasn't dismissed it AND their
     * clothes rules still match [ClothesRule.DEFAULTS] — the moment
     * they edit, add, delete, or nudge a rule's threshold, the card
     * auto-hides regardless of dismissal state (they've found the
     * thing the card was pointing at).
     */
    val clothesPromoCardVisible: Boolean = false,
    /**
     * Whether the Today-screen "Set up a schedule" promo card should
     * render. True iff the user hasn't dismissed it AND neither master
     * schedule switch ([UserPreferences.dailyEnabled] /
     * [UserPreferences.tonightEnabled]) is on yet — the moment either goes
     * on, the card hides regardless of dismissal state (the user set up the
     * thing the card was pointing at).
     */
    val schedulePromoCardVisible: Boolean = false,
    /**
     * Whether the "Preview your daily ClothesCast now" promo card is eligible —
     * true iff the user hasn't dismissed it AND at least one cast slot is
     * enabled (the morning slot is on by default, so this normally holds). It
     * points at the top-bar play button so the user can hear a cast on demand.
     */
    val playPromoCardVisible: Boolean = false,
    /**
     * Whether the "Try high quality voices" promo card is eligible — true iff
     * the user hasn't dismissed it AND no Gemini API key is configured yet. It
     * points at Voice settings so the user can add a (BYOK) Gemini key and
     * upgrade their cast from the device's built-in TTS to the natural Gemini
     * voices. Hides the moment a key lands, regardless of the dismissal flag.
     */
    val geminiTtsPromoCardVisible: Boolean = false,
    /**
     * Whether the "free voice limit reached" card is showing — true iff a synth
     * hit the shared-key daily Gemini TTS allowance, the user hasn't dismissed
     * that exceedance, AND no BYOK Gemini key is configured (a configured key
     * bypasses the shared proxy, so the card hides the instant one is added).
     * Points at Speech settings so the user can add their own Gemini key for
     * unlimited casts. Mutually exclusive with
     * [geminiTtsPromoCardVisible]: the promo pitches voices the user has just
     * found a limit on, so it stands down while this shows.
     */
    val geminiTtsLimitCardVisible: Boolean = false,
    /**
     * [SettingsRepository.GeminiTtsLimitStatus.exceededAtMs] backing
     * [geminiTtsLimitCardVisible], so dismissal marks exactly the on-screen
     * exceedance as seen (and lets a newer one resurface).
     */
    val geminiTtsLimitExceededAt: Long = 0L,
    /**
     * True after a configured Gemini BYOK value failed local decrypt. It stays
     * true until the user re-enters or clears the key, preserving the user's
     * private BYOK choice across retries.
     */
    val geminiKeyNeedsReentry: Boolean = false,
    /**
     * Whether the one-time telemetry/privacy disclosure is still pending
     * (the user hasn't acked it). Lifted out of the banner so [BannerStack]
     * can fold it into the capped promo pool alongside the location, clothes,
     * and celebration prompts.
     */
    val telemetryNoticeVisible: Boolean = false,
    /**
     * True iff either calendar-sourced theming toggle is on. The Today
     * screen reads this to decide whether an ON_RESUME nudge of the
     * permission-recheck tick is worth its DataStore-write cost: if no
     * calendar theming is on, there's no point invalidating an event
     * read that wouldn't fire anyway.
     */
    val usesCalendarThemes: Boolean = false,
    /**
     * Human-readable reason the most recent MQTT bridge publish failed, or null
     * when the last attempt succeeded, none has run, or the user dismissed it.
     * The Today banner stack surfaces this as a top-of-page error card —
     * mirroring the fetch-failure banner — so a broken Home Assistant bridge is
     * visible without opening Smart Home settings. Shown for any failed delivery
     * attempt (scheduled or on-demand Play); cleared the moment a later publish
     * succeeds (the worker records a null error) or the user taps dismiss.
     */
    val mqttPublishError: String? = null,
    /**
     * [SettingsRepository.MqttPublishStatus.recordedAtMs] of the failure backing
     * [mqttPublishError], so [TodayViewModel.dismissMqttPublishError] can mark
     * exactly the on-screen failure as seen (and let a newer one resurface).
     */
    val mqttPublishErrorAt: Long = 0L,
    /** Sibling of [mqttPublishError] for the Cast / smart-display destination. */
    val castPublishError: String? = null,
    /** Sibling of [mqttPublishErrorAt] for the cast destination. */
    val castPublishErrorAt: Long = 0L,
)

sealed class WorkStatus {
    data object Idle : WorkStatus()
    /** A fresh enqueue is being run for the first time. */
    data object Running : WorkStatus()
    /**
     * The worker returned [androidx.work.ListenableWorker.Result.retry] at
     * least once and is now waiting on backoff / connectivity for another
     * attempt. Distinct from [Running] so the banner can say "last attempt
     * failed — retrying" instead of pretending the user just tapped Refresh.
     */
    data object Retrying : WorkStatus()
    data class Failed(val reason: String?, val detail: String?) : WorkStatus()
}

/**
 * The just-the-fields-we-need view of a [WorkInfo]. We map to this before
 * running [selectStatus] so the selection logic can be unit-tested as pure
 * data without constructing real [WorkInfo] instances (its public constructor
 * takes ~12 params and varies subtly between WorkManager versions, so a
 * direct unit test against [WorkInfo] is brittle).
 */
internal data class WorkInfoLite(
    val state: WorkInfo.State,
    val runAttemptCount: Int,
    val outputData: Data,
    /**
     * Live [WorkInfo.progress] payload from the running worker. The only
     * key [selectStatus] inspects is [FetchAndNotifyWorker.KEY_FETCH_COMPLETE]
     * — set once the fetch + cache write are done and the worker has moved
     * on to alignment wait / notification post / TTS playback, at which
     * point the banner should stop saying "fetching" even though the
     * WorkInfo is still RUNNING.
     */
    val progress: Data = Data.EMPTY,
)

internal fun List<WorkInfo>.toLite(): List<WorkInfoLite> =
    map { WorkInfoLite(it.state, it.runAttemptCount, it.outputData, it.progress) }

/**
 * Maps a WorkManager unique-work history to the state the Today banner cares
 * about. Pulled out as a top-level function (rather than a private method on
 * [TodayViewModel]) so it's directly unit-testable without spinning up the
 * full ViewModel + DataStore plumbing.
 *
 * Selection rules, in priority order:
 *  1. **Active wins.** If any entry is ENQUEUED/RUNNING/BLOCKED, that's the
 *     live run; ignore terminal history. If [WorkInfo.runAttemptCount] > 1
 *     it's a post-`Result.retry()` reattempt — surface as [WorkStatus.Retrying].
 *     (WorkManager sets runAttemptCount = 1 on the very first execution, so
 *     the threshold is > 1, not > 0.)
 *  2. **Most-recent terminal otherwise.** Pick the SUCCEEDED/FAILED entry with
 *     the highest [FetchAndNotifyWorker.KEY_COMPLETED_AT] timestamp. The
 *     previous heuristic (`maxByOrNull { runAttemptCount }`) was wrong: a
 *     stale FAILED entry from days ago, with a high retry count from when it
 *     died, would mask a freshly successful run that had `runAttemptCount=0`.
 *     CANCELLED entries are ignored — they exist transiently after a REPLACE
 *     enqueue and aren't the run the user wants to know about.
 *  3. **Tie-break in favour of SUCCEEDED.** Pre-upgrade WorkInfos lack the
 *     completion timestamp and tie at 0; if a fresh success ties with a stale
 *     failure, the success wins. (Once any new run completes, its non-zero
 *     timestamp dominates.)
 */
internal fun selectStatus(infos: List<WorkInfoLite>): WorkStatus {
    if (infos.isEmpty()) return WorkStatus.Idle
    // Only entries still in the "fetching" phase count as active for the
    // banner. The worker calls setProgress(KEY_FETCH_COMPLETE) once the
    // fetch + cache are done and it's about to enter deliver() (alignment
    // wait → notification → TTS); past that point the fresh data is on
    // screen and the spinner shouldn't keep claiming "Fetching" while
    // TTS speaks. A terminal SUCCEEDED/FAILED entry from the same run
    // still surfaces normally below.
    //
    // Retry attempts (runAttemptCount > 1) stay in the active set
    // regardless of the progress flag: if deliver() failed with a
    // retryable exception after fetch-complete (network blip during
    // notification/MQTT/cast, transient TTS error), WorkManager
    // re-enqueues the same WorkSpec for backoff. The next attempt will
    // refetch — so the user should see "Retrying" rather than have the
    // banner go silent and pretend nothing's pending. WorkManager's
    // behaviour around whether progress data is cleared between
    // attempts isn't something we want to rely on; treating any
    // post-first-attempt entry as active is the robust answer.
    val active = infos.firstOrNull {
        (it.state == WorkInfo.State.ENQUEUED ||
            it.state == WorkInfo.State.RUNNING ||
            it.state == WorkInfo.State.BLOCKED) &&
            (it.runAttemptCount > 1 ||
                !it.progress.getBoolean(FetchAndNotifyWorker.KEY_FETCH_COMPLETE, false))
    }
    if (active != null) {
        return if (active.runAttemptCount > 1) WorkStatus.Retrying else WorkStatus.Running
    }
    val completed = infos.filter {
        it.state == WorkInfo.State.SUCCEEDED || it.state == WorkInfo.State.FAILED
    }
    if (completed.isEmpty()) return WorkStatus.Idle
    val latest = completed.maxWithOrNull(
        compareBy<WorkInfoLite> { it.outputData.getLong(FetchAndNotifyWorker.KEY_COMPLETED_AT, 0L) }
            // Tie-break on success so a brand-new SUCCEEDED beats a pre-upgrade FAILED.
            .thenBy { if (it.state == WorkInfo.State.SUCCEEDED) 1 else 0 }
    ) ?: return WorkStatus.Idle
    return when (latest.state) {
        WorkInfo.State.FAILED -> WorkStatus.Failed(
            reason = latest.outputData.getString(FetchAndNotifyWorker.KEY_REASON),
            detail = latest.outputData.getString(FetchAndNotifyWorker.KEY_REASON_DETAIL),
        )
        else -> WorkStatus.Idle
    }
}

/**
 * Cross-chain precedence for [TodayViewModel]. Any in-flight chain (Running or
 * Retrying) keeps the spinner up; otherwise surface a failure if either chain
 * ended in one. Comparing run-attempt counts across two unrelated unique-work
 * chains was the previous source of "old failure on the wrong chain wins".
 */
internal fun mergeWorkStatus(a: WorkStatus, b: WorkStatus): WorkStatus = when {
    a is WorkStatus.Running || b is WorkStatus.Running -> WorkStatus.Running
    a is WorkStatus.Retrying || b is WorkStatus.Retrying -> WorkStatus.Retrying
    a is WorkStatus.Failed -> a
    b is WorkStatus.Failed -> b
    else -> WorkStatus.Idle
}

/**
 * Per-tick snapshot of the worker queues the Today state pulls — the
 * banner-driving [WorkStatus] plus the broader "is anything happening on
 * the alarm or replay queues right now?" flag. Bundled together so the
 * outer state combine stays at the 5-input cap.
 */
internal data class WorkSignals(
    val status: WorkStatus,
    val anyWorkActive: Boolean,
)

/** Presence + invalid-state mirror of the Gemini BYOK secret. */
internal data class GeminiKeySignals(
    val configured: Boolean,
    val needsReentry: Boolean,
)

/**
 * Per-tick bundle of the odds-and-ends signals folded into
 * [TodayViewModel.state] so the outer combine stays at its five-flow
 * typed-overload cap: the session-scoped model-spread toggle, the "Gemini key
 * configured" mirror, and the latest MQTT / Cast publish-error messages (null
 * when the last attempt succeeded or none has run yet).
 */
internal data class MiscSignals(
    val showModelSpread: Boolean,
    val geminiKey: GeminiKeySignals,
    /** Latest MQTT publish failure to show (null when none / dismissed). */
    val mqttError: String?,
    /** [recordedAtMs] of the latest MQTT failure, so dismiss can mark it seen. */
    val mqttErrorAt: Long,
    /** Sibling of [mqttError] for the cast destination. */
    val castError: String?,
    /** Sibling of [mqttErrorAt] for the cast destination. */
    val castErrorAt: Long,
    /** True iff the shared free Gemini TTS daily limit is hit and not dismissed. */
    val geminiTtsLimitExceeded: Boolean,
    /** [exceededAtMs] of the latest limit hit, so dismiss can mark it seen. */
    val geminiTtsLimitExceededAt: Long,
)

/**
 * True iff any of [infos] is in an active WorkManager state
 * (ENQUEUED / RUNNING / BLOCKED). Doesn't consult progress data — the
 * point is to catch every phase, including the post-KEY_FETCH_COMPLETE
 * delivery window where [selectStatus] returns Idle so the spinner
 * banner can hide. The Today screen's Play button uses this to avoid
 * starting a Replay while a Refresh is still mid-delivery (the two now
 * run on separate unique-work queues, so WorkManager won't serialize
 * them for us), and vice versa.
 */
internal fun anyActive(infos: List<WorkInfoLite>): Boolean = infos.any { info ->
    info.state == WorkInfo.State.ENQUEUED ||
        info.state == WorkInfo.State.RUNNING ||
        info.state == WorkInfo.State.BLOCKED
}

class TodayViewModel(
    private val insightCache: InsightCache,
    workManager: WorkManager,
    private val settingsRepository: SettingsRepository,
    /**
     * Re-renders each cached [app.clothescast.core.domain.model.ForecastSnapshot]
     * against the current preferences. Defaulted so pure-VM tests can omit it;
     * the Activity passes the app-singleton instance for parity with the worker.
     */
    private val deriveInsight: DeriveInsight = DeriveInsight(),
    /**
     * Pushes the home-screen widget after a clothes-rule nudge so the icon
     * out on the launcher refreshes in the same frame as the Today screen.
     * Defaulted to a no-op so pure-VM tests don't need an Android Context;
     * the Activity wires `OutfitWidget().updateAll(applicationContext)`.
     */
    private val refreshOutfitWidget: suspend () -> Unit = {},
    /**
     * Source of "today" for the holiday resolver. Held as a [Clock] (not
     * `() -> LocalDate`) so tests can pin a specific instant while production
     * uses the system zone. Defaulted to system clock so callers that don't
     * care about deterministic dates can omit it.
     */
    private val clock: Clock = Clock.systemDefaultZone(),
    /**
     * Resolves today's date + the user's enabled-holidays set into a
     * [HolidayTheme]. Defaulted to a fresh resolver reading the full v1
     * catalogue; tests can inject a smaller list.
     */
    private val holidayResolver: HolidayResolver = HolidayResolver(),
    /**
     * Combines [holidayResolver] with calendar-sourced holiday/birthday
     * events to produce the day's theme. The two opt-in toggles
     * (`themeFromCalendarHolidays`, `themeFromCalendarBirthdays`) are
     * checked inside, and the curated catalog wins when it matches.
     */
    private val themeForToday: ThemeForToday = ThemeForToday(holidayResolver),
    /**
     * Reads device calendar events for the day so the calendar-sourced
     * theming can fire. Nullable so production wiring can omit it on
     * builds without `READ_CALENDAR`; ViewModel tests pass a fake. When
     * null, [ThemeForToday] simply sees an empty event list.
     */
    private val calendarEventReader: CalendarEventReader? = null,
    /**
     * Reactive "is a Gemini API key configured" signal, mirrored from
     * [app.clothescast.data.SecureKeyStore.geminiKeyConfiguredFlow]. Feeds
     * [TodayState.geminiTtsPromoCardVisible] so the "Try high quality voices"
     * promo only nudges users who haven't set up Gemini yet, and disappears the
     * moment a key lands. Defaulted to `flowOf(false)` so pure-VM tests can omit
     * it (no key ⇒ the promo is eligible, matching a fresh install).
     */
    private val geminiKeyConfigured: Flow<Boolean> = flowOf(false),
    private val geminiKeyNeedsReentry: Flow<Boolean> = flowOf(false),
) : ViewModel() {
    /**
     * Session-scoped "show model spread" flag. Held in the ViewModel rather
     * than DataStore because the toggle is a per-screen-visit gesture, not a
     * persistent preference — flipped from the Today UI by tapping the
     * confidence chip / callout / any temp-or-precip card. See [TodayState.showModelSpread].
     */
    private val showModelSpread = MutableStateFlow(false)

    // Combine status across both alarm queues so the spinner / failure
    // banner reflects an in-flight tonight refresh too — the Refresh
    // button routes to TONIGHT when it's tapped between 19:00 and 07:00.
    // The replay queue rides alongside but doesn't feed [workStatus]: a
    // replay isn't a fetch, so it has no business surfacing the
    // "fetching" spinner. It only contributes to [anyWorkActive] —
    // the broader gate the Play button reads to avoid double delivery
    // against a still-running Refresh or alarm. Collapsed upstream
    // into a single flow so [state]'s `combine` stays under the 5-flow
    // overload cap now that the pager reads both period slots separately.
    private val workStatusFlow = combine(
        workManager.getWorkInfosForUniqueWorkFlow(FetchAndNotifyWorker.UNIQUE_WORK_NAME),
        workManager.getWorkInfosForUniqueWorkFlow(FetchAndNotifyWorker.UNIQUE_WORK_NAME_TONIGHT),
        workManager.getWorkInfosForUniqueWorkFlow(FetchAndNotifyWorker.UNIQUE_WORK_NAME_PLAY),
    ) { todayInfos, tonightInfos, replayInfos ->
        val todayLite = todayInfos.toLite()
        val tonightLite = tonightInfos.toLite()
        val replayLite = replayInfos.toLite()
        WorkSignals(
            status = mergeWorkStatus(selectStatus(todayLite), selectStatus(tonightLite)),
            anyWorkActive = anyActive(todayLite) || anyActive(tonightLite) || anyActive(replayLite),
        )
    }

    /**
     * Emits the current [LocalDate] and re-emits at every local midnight. Fused
     * into the [state] combine so a screen left open across midnight (no
     * settings change, no fetch, no work-status update) still flips its
     * holiday theme deterministically on the day rollover — without it, the
     * combine wouldn't re-execute until *some* unrelated input emitted, and
     * yesterday's banner could linger into the morning. The minimum delay
     * guards against zero / negative durations from a clock skew that's
     * already past midnight by the time we compute it.
     */
    private val dateTicker: Flow<LocalDate> = flow {
        while (true) {
            val zone = clock.zone ?: ZoneId.systemDefault()
            val today = LocalDate.now(clock)
            emit(today)
            val nextMidnightMs = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val nowMs = clock.instant().toEpochMilli()
            delay((nextMidnightMs - nowMs).coerceAtLeast(MIN_DATE_TICK_MS))
        }
    }

    // Fuse the user's preferences with the date tick upstream so the outer
    // [state] combine stays at five inputs (the typed-overload cap) and the
    // resolver still gets a freshly-read date every time it runs. Calendar
    // events fold into this same flow via flatMapLatest so we don't need a
    // sixth combine input; the read only fires when the user has opted into
    // calendar-sourced theming (or the reader is null in tests).
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val preferencesDateEvents: Flow<Triple<UserPreferences, LocalDate, List<CalendarEvent>>> =
        combine(settingsRepository.preferences, dateTicker, ::Pair).flatMapLatest { (prefs, date) ->
            flow {
                val needEvents = calendarEventReader != null &&
                    (prefs.calendarHolidayThemingActive || prefs.calendarBirthdayThemingActive)
                val events: List<CalendarEvent> = if (needEvents) {
                    runCatching {
                        calendarEventReader!!.eventsForDay(date, prefs.schedule.zoneId)
                    }.getOrDefault(emptyList())
                } else {
                    emptyList()
                }
                emit(Triple(prefs, date, events))
            }
        }

    // Wrap the persisted quota status so the limit card auto-expires at the
    // reset boundary even while the Today screen stays open and nothing else
    // emits. The dismiss/now-vs-reset gate in [miscSignals] only re-evaluates
    // on an upstream emission, and no other flow is keyed to the (UTC-based)
    // reset — the [dateTicker] fires at *local* midnight, which can be hours
    // off. So re-emit the same status once the reset passes, flipping the
    // gate; [flatMapLatest] cancels the pending wait when a newer status (a
    // fresh hit, a clear, or a dismissal) arrives.
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val geminiTtsLimitStatusTicking: Flow<SettingsRepository.GeminiTtsLimitStatus?> =
        settingsRepository.geminiTtsLimitStatus.flatMapLatest { status ->
            flow {
                emit(status)
                val untilResetMs = status?.resetAtMs?.minus(System.currentTimeMillis())
                if (untilResetMs != null && untilResetMs > 0) {
                    delay(untilResetMs)
                    emit(status)
                }
            }
        }

    private val geminiKeySignals: Flow<GeminiKeySignals> =
        combine(geminiKeyConfigured, geminiKeyNeedsReentry, ::GeminiKeySignals)

    // Fuse the session-scoped spread toggle, the external "Gemini key
    // configured" signal, and the latest MQTT / Cast publish-error messages
    // into one input so the outer [state] combine stays at five flows (the
    // typed-overload cap). The publish errors ride here so a failed cast or
    // smart-home publish surfaces as a top-of-page banner alongside the
    // fetch-failure card, without adding a sixth combine input.
    private val miscSignals: Flow<MiscSignals> =
        combine(
            showModelSpread,
            geminiKeySignals,
            settingsRepository.mqttPublishStatus,
            settingsRepository.castStatus,
            geminiTtsLimitStatusTicking,
        ) { spread, geminiKey, mqtt, cast, ttsLimit ->
            // Hide an error the user has already dismissed from the banner
            // (recordedAt <= dismissedAt); a strictly-newer failure resurfaces.
            val mqttError = mqtt?.takeIf {
                it.errorMessage != null && it.recordedAtMs > it.dismissedAtMs
            }?.errorMessage
            val castError = cast?.takeIf {
                it.errorMessage != null && it.recordedAtMs > it.dismissedAtMs
            }?.errorMessage
            // Same dismiss semantics for the shared-key daily TTS allowance: the
            // card hides once the user dismisses the exceedance they saw, and a
            // strictly-newer one (a later run that re-hit the limit) resurfaces.
            // Also auto-expire it once the wall clock passes the recorded reset
            // time — opening the app after the daily quota has reset (but before
            // the next synth runs to clear it) shouldn't still claim today's free
            // voices are spent. resetAtMs <= 0 means "no reset info"; don't expire
            // on that. [geminiTtsLimitStatusTicking] re-emits at the reset
            // boundary so this re-evaluates even if the screen stays open and no
            // other flow emits.
            val now = System.currentTimeMillis()
            val ttsLimitExceeded = ttsLimit?.let {
                it.exceededAtMs > it.dismissedAtMs && (it.resetAtMs <= 0L || now < it.resetAtMs)
            } ?: false
            MiscSignals(
                showModelSpread = spread,
                geminiKey = geminiKey,
                mqttError = mqttError,
                mqttErrorAt = mqtt?.recordedAtMs ?: 0L,
                castError = castError,
                castErrorAt = cast?.recordedAtMs ?: 0L,
                geminiTtsLimitExceeded = ttsLimitExceeded,
                geminiTtsLimitExceededAt = ttsLimit?.exceededAtMs ?: 0L,
            )
        }

    val state: StateFlow<TodayState> = combine(
        insightCache.thisPeriod,
        insightCache.nextPeriod,
        workStatusFlow,
        preferencesDateEvents,
        miscSignals,
    ) { thisPeriodSnapshot, nextPeriodSnapshot, workSignals, prefsDateEvents, misc ->
        val spread = misc.showModelSpread
        val geminiKeyConfigured = misc.geminiKey.configured
        val geminiKeyNeedsReentry = misc.geminiKey.needsReentry
        val (prefs, today, events) = prefsDateEvents
        // Derive each cached snapshot against the *current* prefs so a settings
        // change re-renders the prose / outfit / bullets in the same frame as
        // the dropdown closes — no waiting for the next worker run, no
        // preservation / re-gating logic on the cache side.
        val thisPeriodInsight = thisPeriodSnapshot?.let { deriveInsight(it, prefs).insight }
        val nextPeriodInsight = nextPeriodSnapshot?.let { deriveInsight(it, prefs).insight }
        // Resolve "is today a holiday the user wants themed?" — `today`
        // comes from the [dateTicker] flow above so the rollover at local
        // midnight re-fires combine even when no other input changes. The
        // country picker narrows AUTO holidays to the user's locale +
        // weather-location country + Global (the default); per-holiday
        // ON / OFF overrides bypass the filter in either direction.
        val localeCountry = prefs.region.toJavaLocale()?.country
            ?: Locale.getDefault().country
        val effectiveCountries = prefs.holidayCountrySelection.resolveEnabledCountries(
            localeCountry = localeCountry,
            weatherLocationCountry = prefs.location?.countryCode,
            allCountries = HolidayCatalog.allCountries,
        )
        val theme = themeForToday.resolve(
            date = today,
            overrides = prefs.holidayOverrides,
            enabledCountries = effectiveCountries,
            events = events,
            themeFromCalendarHolidays = prefs.calendarHolidayThemingActive,
            themeFromCalendarBirthdays = prefs.calendarBirthdayThemingActive,
        )
        // Merge holiday overrides on top of the user's custom colours. The
        // user's choices populate the base map; the holiday's per-tier
        // entries replace any user pick for that tier *for today only*
        // (this map is computed at render time and never persisted, so
        // tomorrow's render restores the user's pick).
        //
        // Strokes only come from the holiday theme — the user has no
        // stroke-customisation UI today. Empty map ⇒ the garment renderer
        // auto-derives the stroke as a darker shade of the fill (the
        // existing two-tone behaviour); a non-empty map paints the
        // holiday's chosen accent colour as a contrasting outline.
        val topColors = prefs.outfitTopColors + (theme?.topOverrides ?: emptyMap())
        val bottomColors = prefs.outfitBottomColors + (theme?.bottomOverrides ?: emptyMap())
        val topStrokes = theme?.topStrokeOverrides ?: emptyMap()
        val bottomStrokes = theme?.bottomStrokeOverrides ?: emptyMap()
        TodayState(
            thisPeriodInsight = thisPeriodInsight,
            nextPeriodInsight = nextPeriodInsight,
            workStatus = workSignals.status,
            anyWorkActive = workSignals.anyWorkActive,
            temperatureUnit = prefs.temperatureUnit,
            deltaThresholdC = prefs.deltaThresholdC,
            rangeFormat = prefs.rangeFormat,
            clothesFormat = prefs.clothesFormat,
            bottomsFormat = prefs.bottomsFormat,
            periodPreamble = prefs.periodPreamble,
            wearPreamble = prefs.wearPreamble,
            distanceUnit = prefs.distanceUnit,
            region = prefs.region,
            timeFormat = prefs.timeFormat,
            morningTime = prefs.schedule.time,
            tonightTime = prefs.tonightSchedule.time,
            useDeviceLocation = prefs.useDeviceLocation,
            hasFallbackLocation = prefs.location != null,
            notifyScheduleEnabled =
                (prefs.dailyEnabled && prefs.deliveryMode.postsNotification) ||
                    (prefs.tonightEnabled && prefs.tonightDeliveryMode.postsNotification),
            clothesRules = prefs.clothesRules,
            homeSectionOrder = prefs.homeSectionOrder,
            showModelSpread = spread,
            outfitTopColors = topColors,
            outfitBottomColors = bottomColors,
            outfitHandsColors = prefs.outfitHandsColors,
            outfitCarriedColors = prefs.outfitCarriedColors,
            outfitOuterColors = prefs.outfitOuterColors,
            outfitTopStrokes = topStrokes,
            outfitBottomStrokes = bottomStrokes,
            activeHoliday = theme,
            celebrationCardVisible = !prefs.celebrationCardDismissed &&
                !prefs.calendarHolidayThemingActive &&
                !prefs.calendarBirthdayThemingActive,
            clothesPromoCardVisible = !prefs.clothesPromoCardDismissed &&
                prefs.clothesRules == ClothesRule.DEFAULTS,
            schedulePromoCardVisible = !prefs.scheduleCardDismissed,
            playPromoCardVisible = !prefs.playCardDismissed &&
                (prefs.dailyEnabled || prefs.tonightEnabled),
            // Stand the "try high-quality voices" promo down while the limit
            // card is up — pitching voices the user just hit a cap on reads as
            // a bug. The limit card already routes to the same Speech settings.
            geminiTtsPromoCardVisible = !prefs.geminiPromoCardDismissed &&
                !geminiKeyConfigured && !geminiKeyNeedsReentry && !misc.geminiTtsLimitExceeded,
            // Gate on no BYOK key: a configured key bypasses the shared free
            // proxy entirely, so the moment the user adds one (e.g. via this
            // card's CTA) the limit no longer applies and the card hides
            // immediately — without waiting for the next synth to clear the
            // persisted quota status.
            geminiTtsLimitCardVisible = misc.geminiTtsLimitExceeded &&
                !geminiKeyConfigured &&
                !geminiKeyNeedsReentry,
            geminiTtsLimitExceededAt = misc.geminiTtsLimitExceededAt,
            geminiKeyNeedsReentry = geminiKeyNeedsReentry,
            telemetryNoticeVisible = !prefs.telemetryNoticeAcked,
            usesCalendarThemes = prefs.calendarHolidayThemingActive || prefs.calendarBirthdayThemingActive,
            // Surface a publish error for any failed delivery attempt — a
            // scheduled run or an on-demand Play — regardless of the current
            // destination config. We deliberately don't gate on whether the
            // destination is still "live" (master toggle / host / route): a
            // Play-button delivery is a real attempt that can fail even when no
            // schedule would retry it, and a later config change that stops
            // future attempts shouldn't silently bury a failure the user hasn't
            // seen. Instead the banner carries a dismiss action; [miscSignals]
            // filters out an error the user dismissed, and a strictly-newer
            // failure resurfaces. The *At fields ride along so dismiss knows
            // which failure was on screen.
            mqttPublishError = misc.mqttError,
            mqttPublishErrorAt = misc.mqttErrorAt,
            castPublishError = misc.castError,
            castPublishErrorAt = misc.castErrorAt,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TodayState())

    /**
     * Persists the user's dismissal of the Today-screen "Celebration
     * themes" promo card. The card hides immediately on the next state
     * emission. Mirrors [SettingsRepository.setTelemetryNoticeAcked]'s
     * pattern.
     */
    fun dismissCelebrationCard() {
        viewModelScope.launch {
            settingsRepository.setCelebrationCardDismissed(true)
        }
    }

    /**
     * Persists the user's dismissal of the Today-screen "Customize your
     * clothes" promo card. Called on both the X-tap and the
     * "Clothes settings" CTA so once the user has been pointed at the
     * page, the card stays hidden.
     */
    fun dismissClothesPromoCard() {
        viewModelScope.launch {
            settingsRepository.setClothesPromoCardDismissed(true)
        }
    }

    /**
     * Records that the user acted on the Today-screen "Automatic ClothesCasts"
     * promo card — called on both the X-tap and the "Schedule settings" CTA so
     * once the user has dismissed it or been pointed at the page, the card stays
     * hidden. Mirrors [dismissClothesPromoCard].
     */
    fun dismissSchedulePromoCard() {
        viewModelScope.launch {
            settingsRepository.setScheduleCardDismissed(true)
        }
    }

    /**
     * Retires the Today-screen "Preview your daily ClothesCast now" promo card.
     * Called on the X-tap *and* when the user taps the top-bar Play button the
     * card points at — once they've used Play the card has done its job. The
     * card hides on the next state emission. Mirrors [dismissSchedulePromoCard].
     */
    fun dismissPlayPromoCard() {
        viewModelScope.launch {
            settingsRepository.setPlayCardDismissed(true)
        }
    }

    /**
     * Persists the user's dismissal of the Today-screen "Try high quality
     * voices" promo card. Called on both the X-tap and the "Set up voices" CTA
     * so once the user has been pointed at Voice settings the card stays hidden
     * even if they back out without adding a key. Mirrors
     * [dismissClothesPromoCard].
     */
    fun dismissGeminiTtsPromoCard() {
        viewModelScope.launch {
            settingsRepository.setGeminiPromoCardDismissed(true)
        }
    }

    /**
     * Records that the user dismissed the "free voice limit reached" card.
     * Marks the on-screen exceedance ([TodayState.geminiTtsLimitExceededAt]) as
     * seen so it hides; a strictly-newer limit hit resurfaces. No-op when
     * nothing is shown. The CTA doesn't dismiss — Speech settings auto-clears
     * the card once the user adds a key and the next synth succeeds.
     */
    fun dismissGeminiTtsLimitCard() {
        val at = state.value.geminiTtsLimitExceededAt
        if (at <= 0L) return
        viewModelScope.launch {
            settingsRepository.setGeminiTtsLimitDismissedAt(at)
        }
    }

    /**
     * Bumps the calendar-permission recheck tick so the prefs flow re-emits
     * and the [preferencesDateEvents] combine re-reads calendar events. The
     * Today screen calls this on `ON_RESUME` when it detects the user has
     * changed `READ_CALENDAR` outside the app — either *granted* it (so a
     * fresh read can populate the holiday/birthday banner) or *revoked*
     * it (so the cached events list drops back to empty and the synthetic
     * banner disappears immediately, instead of lingering until midnight).
     */
    fun notifyCalendarPermissionChanged() {
        viewModelScope.launch {
            settingsRepository.markCalendarPermissionRechecked()
        }
    }

    /**
     * Records that the user dismissed the MQTT publish-error banner. Marks the
     * on-screen failure ([TodayState.mqttPublishErrorAt]) as seen so it hides;
     * a strictly-newer failure resurfaces. No-op when nothing is shown.
     */
    fun dismissMqttPublishError() {
        val at = state.value.mqttPublishErrorAt
        if (at <= 0L) return
        viewModelScope.launch {
            settingsRepository.setMqttErrorDismissedAt(at)
        }
    }

    /** Mirrors [dismissMqttPublishError] for the cast banner. */
    fun dismissCastPublishError() {
        val at = state.value.castPublishErrorAt
        if (at <= 0L) return
        viewModelScope.launch {
            settingsRepository.setCastErrorDismissedAt(at)
        }
    }

    /**
     * Flip the session-scoped model-spread overlay on the temp / feels-like /
     * precip cards. The Today screen wires this to a tap on the confidence
     * chip and the low-confidence callout — a labelled affordance with
     * explicit "show / hide" copy. Charts themselves use [revealModelSpread]
     * (a one-way set) so dragging the time indicator doesn't accidentally
     * hide the spread the user wanted shown.
     */
    fun toggleModelSpread() {
        showModelSpread.value = !showModelSpread.value
    }

    /**
     * One-way reveal — sets the model-spread overlay to visible without ever
     * hiding it. Wired to the chart-tap / scrub gesture so first contact on
     * any chart reveals the spread (and subsequent scrubs don't toggle it
     * off mid-drag). The user's escape route is the confidence chip's
     * explicit [toggleModelSpread].
     */
    fun revealModelSpread() {
        showModelSpread.value = true
    }

    /**
     * One-way hide — paired with [revealModelSpread]. Wired to the chart's
     * restore action: when the user exits scrub mode and we revealed the
     * per-model spread as part of entering it, undo that reveal so the
     * spread doesn't linger past the scrub session. If the user had the
     * spread on already (via the confidence chip) the restore path leaves
     * it alone — the controller tracks that on its side.
     */
    fun hideModelSpread() {
        showModelSpread.value = false
    }

    class Factory(
        private val insightCache: InsightCache,
        private val workManager: WorkManager,
        private val settingsRepository: SettingsRepository,
        private val refreshOutfitWidget: suspend () -> Unit,
        private val deriveInsight: DeriveInsight = DeriveInsight(),
        private val calendarEventReader: CalendarEventReader? = null,
        private val geminiKeyConfigured: Flow<Boolean> = flowOf(false),
        private val geminiKeyNeedsReentry: Flow<Boolean> = flowOf(false),
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(TodayViewModel::class.java)) {
                "Unknown ViewModel: ${modelClass.name}"
            }
            return TodayViewModel(
                insightCache = insightCache,
                workManager = workManager,
                settingsRepository = settingsRepository,
                refreshOutfitWidget = refreshOutfitWidget,
                deriveInsight = deriveInsight,
                calendarEventReader = calendarEventReader,
                geminiKeyConfigured = geminiKeyConfigured,
                geminiKeyNeedsReentry = geminiKeyNeedsReentry,
            ) as T
        }
    }

    private companion object {
        // Floor on the inter-tick delay so a clock that's nudged backward
        // (manual time change, NTP correction) doesn't cause the date ticker
        // to busy-loop while it waits for "midnight" to come back into the
        // future. One second is plenty — the ticker is a once-per-day event
        // in steady state.
        const val MIN_DATE_TICK_MS = 1_000L
    }
}
