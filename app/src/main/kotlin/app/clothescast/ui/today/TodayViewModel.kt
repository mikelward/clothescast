package app.clothescast.ui.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.work.Data
import androidx.work.WorkInfo
import androidx.work.WorkManager
import app.clothescast.core.domain.model.ClothesRule
import app.clothescast.core.domain.model.DistanceUnit
import app.clothescast.core.domain.model.ForecastPeriod
import app.clothescast.core.domain.model.Insight
import app.clothescast.core.domain.model.Region
import app.clothescast.core.domain.model.TemperatureUnit
import app.clothescast.data.InsightCache
import app.clothescast.data.SettingsRepository
import app.clothescast.work.FetchAndNotifyWorker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalTime

data class TodayState(
    /**
     * The insight shown on page 1 of the pager — whichever period's slot was
     * most recently generated. Mirrors the previous `state.insight` contract
     * so existing screen behaviour is unchanged on first open.
     */
    val primaryInsight: Insight? = null,
    /**
     * The insight shown on page 2 of the pager — the paired period's cached
     * slot, or `null` if the worker hasn't generated it yet. When null, the
     * screen renders a `MissingPeriodPlaceholder` for `nextPeriod`.
     */
    val nextInsight: Insight? = null,
    /**
     * Which period page 2 would show even when `nextInsight` is null — so the
     * placeholder copy reads correctly the first time the user swipes to it.
     * Always the opposite of `primaryInsight.period` when both are present.
     */
    val nextPeriod: ForecastPeriod = ForecastPeriod.TONIGHT,
    val workStatus: WorkStatus = WorkStatus.Idle,
    val temperatureUnit: TemperatureUnit = TemperatureUnit.CELSIUS,
    val distanceUnit: DistanceUnit = DistanceUnit.KILOMETERS,
    val region: Region = Region.SYSTEM,
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
    /** Live clothes rules the rationale dialog reads to render the current threshold
     * value and the `−1°` / `+1°` controls. The cached [Insight.outfitRationale]
     * still carries the rule values that *were* in effect at insight-generation
     * time (which can differ from these if the user has nudged a knob since); the
     * dialog prefers these for display so the controls stay honest. */
    val clothesRules: List<ClothesRule> = emptyList(),
    /**
     * Whether to overlay each major weather model's hourly curve on the
     * forecast / feels-like / precipitation charts. Session-scoped (lives on
     * the ViewModel, not in DataStore) — toggled by tapping the confidence
     * chip, the low-confidence callout, or any of the three temperature /
     * precip cards. Resets to off when the Activity's ViewModelStore is
     * cleared (config change preserves it; process death does not). The
     * diagnostic cards below (wind / cloud / humidity / solar / sunshine /
     * UV) ignore this flag — they're per-model-only and render whenever
     * [Insight.perModelHourly] is present.
     */
    val showModelSpread: Boolean = false,
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
)

internal fun List<WorkInfo>.toLite(): List<WorkInfoLite> =
    map { WorkInfoLite(it.state, it.runAttemptCount, it.outputData) }

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
    val active = infos.firstOrNull {
        it.state == WorkInfo.State.ENQUEUED ||
            it.state == WorkInfo.State.RUNNING ||
            it.state == WorkInfo.State.BLOCKED
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
/**
 * Picks which of the two cached insights is the "primary" (page 1) and which
 * is "next" (page 2). Tie-break: the one with the later `generatedAt` wins
 * primary, mirroring [InsightCache.latest]'s semantics so the screen's page-1
 * default doesn't drift from the pre-pager behaviour.
 *
 * Returns `Triple(primary, next, nextPeriod)`. `nextPeriod` is always the
 * *opposite* of `primary`'s period when primary exists; when both inputs are
 * null we default to TONIGHT so the empty-state path still has a sensible
 * placeholder period to surface.
 */
internal fun pickPrimary(
    today: Insight?,
    tonight: Insight?,
): Triple<Insight?, Insight?, ForecastPeriod> {
    val todayAt = today?.generatedAt?.toEpochMilli() ?: Long.MIN_VALUE
    val tonightAt = tonight?.generatedAt?.toEpochMilli() ?: Long.MIN_VALUE
    return when {
        today == null && tonight == null -> Triple(null, null, ForecastPeriod.TONIGHT)
        today == null -> Triple(tonight, null, ForecastPeriod.TODAY)
        tonight == null -> Triple(today, null, ForecastPeriod.TONIGHT)
        tonightAt > todayAt -> Triple(tonight, today, ForecastPeriod.TODAY)
        else -> Triple(today, tonight, ForecastPeriod.TONIGHT)
    }
}

internal fun mergeWorkStatus(a: WorkStatus, b: WorkStatus): WorkStatus = when {
    a is WorkStatus.Running || b is WorkStatus.Running -> WorkStatus.Running
    a is WorkStatus.Retrying || b is WorkStatus.Retrying -> WorkStatus.Retrying
    a is WorkStatus.Failed -> a
    b is WorkStatus.Failed -> b
    else -> WorkStatus.Idle
}

class TodayViewModel(
    private val insightCache: InsightCache,
    workManager: WorkManager,
    private val settingsRepository: SettingsRepository,
    /**
     * Pushes the home-screen widget after a clothes-rule nudge so the icon
     * out on the launcher refreshes in the same frame as the Today screen.
     * Defaulted to a no-op so pure-VM tests don't need an Android Context;
     * the Activity wires `OutfitWidget().updateAll(applicationContext)`.
     */
    private val refreshOutfitWidget: suspend () -> Unit = {},
) : ViewModel() {
    /**
     * Session-scoped "show model spread" flag. Held in the ViewModel rather
     * than DataStore because the toggle is a per-screen-visit gesture, not a
     * persistent preference — flipped from the Today UI by tapping the
     * confidence chip / callout / any temp-or-precip card. See [TodayState.showModelSpread].
     */
    private val showModelSpread = MutableStateFlow(false)

    // Combine status across both unique-work names so the spinner / failure
    // banner reflects an in-flight tonight refresh too — the Refresh button
    // routes to TONIGHT when it's tapped between 19:00 and 07:00. Collapsed
    // upstream into a single flow so [state]'s `combine` stays under the
    // 5-flow overload cap now that the pager reads both period slots
    // separately.
    private val workStatusFlow = combine(
        workManager.getWorkInfosForUniqueWorkFlow(FetchAndNotifyWorker.UNIQUE_WORK_NAME),
        workManager.getWorkInfosForUniqueWorkFlow(FetchAndNotifyWorker.UNIQUE_WORK_NAME_TONIGHT),
    ) { todayInfos, tonightInfos ->
        mergeWorkStatus(selectStatus(todayInfos.toLite()), selectStatus(tonightInfos.toLite()))
    }

    val state: StateFlow<TodayState> = combine(
        insightCache.latestForPeriod(ForecastPeriod.TODAY),
        insightCache.latestForPeriod(ForecastPeriod.TONIGHT),
        workStatusFlow,
        settingsRepository.preferences,
        showModelSpread,
    ) { todayInsight, tonightInsight, workStatus, prefs, spread ->
        val (primary, next, nextPeriod) = pickPrimary(todayInsight, tonightInsight)
        TodayState(
            primaryInsight = primary,
            nextInsight = next,
            nextPeriod = nextPeriod,
            workStatus = workStatus,
            temperatureUnit = prefs.temperatureUnit,
            distanceUnit = prefs.distanceUnit,
            region = prefs.region,
            morningTime = prefs.schedule.time,
            tonightTime = prefs.tonightSchedule.time,
            useDeviceLocation = prefs.useDeviceLocation,
            hasFallbackLocation = prefs.location != null,
            clothesRules = prefs.clothesRules,
            showModelSpread = spread,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TodayState())

    /**
     * Flip the session-scoped model-spread overlay on the temp / feels-like /
     * precip cards. The Today screen wires this to a tap on the confidence
     * chip, the low-confidence callout, and each of those three cards — a
     * wide gesture surface so the affordance is easy to stumble on.
     */
    fun toggleModelSpread() {
        showModelSpread.value = !showModelSpread.value
    }

    /**
     * Nudges the temperature threshold of the [ClothesRule] keyed [ruleItem] by
     * [deltaC] degrees Celsius. Wired to the rationale dialog's `−1°` / `+1°`
     * controls. The read-modify-write is delegated to
     * [SettingsRepository.adjustClothesRuleThreshold] which performs it inside a
     * single `DataStore.edit { … }` transaction — rapid taps each read the latest
     * persisted value, so taps don't collapse into one even when several coroutines
     * are in flight. Final value is clamped to the documented sanity range and
     * persisted in the rule's existing unit.
     */
    fun adjustClothesRuleThreshold(ruleItem: String, deltaC: Double) {
        viewModelScope.launch {
            settingsRepository.adjustClothesRuleThreshold(ruleItem, deltaC)
            // Repick the cached outfit against the updated threshold so the
            // home-screen icon catches up in the same frame as the rationale
            // dialog's number — without this, a `+1°` tap that flips the icon
            // tier wouldn't visibly do anything until the next refresh.
            val prefs = settingsRepository.preferences.first()
            insightCache.recomputeOutfits(prefs.clothesRules, prefs.defaultBottom)
            refreshOutfitWidget()
        }
    }

    class Factory(
        private val insightCache: InsightCache,
        private val workManager: WorkManager,
        private val settingsRepository: SettingsRepository,
        private val refreshOutfitWidget: suspend () -> Unit,
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
            ) as T
        }
    }
}
