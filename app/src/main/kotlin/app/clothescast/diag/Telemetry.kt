package app.clothescast.diag

import android.content.Context
import android.os.Build
import android.os.Bundle
import app.clothescast.BuildConfig
import app.clothescast.data.SettingsRepository
import com.google.firebase.FirebaseApp
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.crashlytics.FirebaseCrashlytics
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Bridges the user's Privacy → "Send crash + usage data" toggle to Firebase
 * Analytics + Crashlytics collection flags, and mirrors the user's language /
 * accent / TTS configuration into Firebase Analytics user properties so
 * aggregate reports can break usage events down by configuration.
 *
 * The contract for what may / may not appear in those payloads is in
 * PRIVACY.md — calendar event data, location, insight prose, notification
 * text, and API keys are out of scope. This class deliberately does NOT set
 * Crashlytics custom keys from any of those, and the user properties it does
 * set are short configuration strings (enum names, BCP-47 locale tags, voice
 * IDs) — no user content, no identifiers.
 *
 * No-ops if Firebase didn't initialise — i.e. when this build was assembled
 * without `app/google-services.json` (CI). The .gitignore-d JSON is the only
 * thing keeping Firebase from auto-starting via FirebaseInitProvider, so
 * "no JSON, no SDK calls" is the natural quiet path. The Settings toggle
 * still flips the persisted preference in that case so a later build that
 * does have the JSON inherits the user's choice.
 */
object Telemetry {
    /**
     * Captured in [start] so background components (the WorkManager worker,
     * the alarm receiver) can emit events without re-resolving the SDK from
     * a Context. Stays null on builds without google-services.json (CI) or
     * on the OnePlus 8 Pro install-bypass path described in [start], which
     * is how all the logEvent helpers below stay silent on those builds.
     *
     * Published only once the stored telemetry choice has been applied to
     * both SDKs, so a helper called before that no-ops rather than emitting
     * an event from someone who may never have agreed.
     */
    @Volatile
    private var analyticsRef: FirebaseAnalytics? = null

    /** As [analyticsRef], for the one non-fatal recorded outside this object. */
    @Volatile
    private var crashlyticsRef: FirebaseCrashlytics? = null

    /**
     * Completed once the stored telemetry choice has been applied to both SDKs.
     * Everything that can produce telemetry waits on it — see [start].
     *
     * Never completed on a build without google-services.json, or on the
     * install-bypass path in [start], so a waiting producer there simply never
     * runs. That is the intended silence in both cases.
     */
    private val telemetryApplied = CompletableDeferred<Unit>()

    /**
     * Subscribes to [settings]'s telemetry preference and pushes each change
     * into FirebaseAnalytics + FirebaseCrashlytics. Both SDKs persist their
     * collection flag across launches, so a `false` set here also suppresses
     * the very first crash on next process start before this collector
     * attaches.
     *
     * Also subscribes to [SettingsRepository.analyticsSnapshot] and mirrors
     * each value into a Firebase Analytics user property. Properties are set
     * unconditionally — when collection is disabled the SDK won't send
     * anything anyway, and the property store is local until an event flushes.
     */
    // The SDK_INT < MIN_SDK_VERSION guard below looks dead to lint (install-time
    // minSdk enforcement), but Test Lab elevated installs and some sideload
    // paths reach it on sub-minSdk devices — see the comment there.
    @android.annotation.SuppressLint("ObsoleteSdkInt")
    fun start(context: Context, settings: SettingsRepository, scope: CoroutineScope) {
        if (FirebaseApp.getApps(context).isEmpty()) return
        val analytics = FirebaseAnalytics.getInstance(context)
        val crashlytics = FirebaseCrashlytics.getInstance()
        // Firebase has shown the app crashing on a OnePlus 8 Pro reporting
        // Android 11 (API 30) against a minSdk=31 APK — the OS package
        // manager normally enforces minSdk at install time, but Test Lab
        // elevated installs and some sideload paths can bypass that. The
        // resulting Crashlytics / Analytics traffic is from a config the app
        // was never built for, so silence both SDKs synchronously here
        // (before the collectors below race a startup crash) and skip the
        // user-preference subscription entirely — the persisted disable
        // survives across launches.
        if (Build.VERSION.SDK_INT < BuildConfig.MIN_SDK_VERSION) {
            analytics.setAnalyticsCollectionEnabled(false)
            crashlytics.setCrashlyticsCollectionEnabled(false)
            return
        }
        // Released only once the stored choice is on both SDKs. Everything
        // below that can *generate* telemetry waits on it: the three snapshot
        // collectors, and `analyticsRef`, which every logEvent helper reads.
        //
        // Without it the producers race the migration. On an upgraded install
        // carrying Analytics' persisted `true`, the snapshot collectors emit
        // their initial events the moment they attach, before the collector
        // below has disabled anything — so a user who has never agreed sends
        // freshly generated events, which is a different thing from the held
        // report `PRIVACY.md` already discloses (Codex, PR #1161).
        //
        // A helper called out of band inside that window finds `analyticsRef`
        // null and no-ops, exactly as on a build without google-services.json.
        // Dropping those few is the right side of the trade: there is nowhere
        // to hold an event that may turn out to be unconsented — except the
        // startup non-fatal, which has one and waits instead. See
        // [recordComposeStartupCrash].
        scope.launch {
            // Before the collector, so its first pass already carries the
            // debt: an install from the default-on era has `true` in
            // Crashlytics' own storage, which outranks the manifest default,
            // and only a discard makes that first upgraded launch safe.
            try {
                settings.migrateToOptInTelemetry()
            } catch (e: IOException) {
                // Fail closed. Without this the throw ends the coroutine
                // before the collector below ever attaches, so on exactly the
                // install this migration exists for — an upgrade carrying
                // Crashlytics' persisted `true` — both SDKs would stay
                // enabled for someone who has never agreed, with nothing said
                // about it (Codex, PR #1161).
                //
                // Disabling here rather than only logging: the migration's
                // whole job is to stop collection, and a failed *record* of
                // that is not a reason to keep collecting. The debt goes
                // unrecorded, so the discard is retried next launch; the
                // stopping is not deferred.
                analytics.setAnalyticsCollectionEnabled(false)
                crashlytics.setCrashlyticsCollectionEnabled(false)
                DiagLog.w(
                    "Telemetry",
                    "opt-in migration could not be persisted; collection disabled",
                    e,
                )
            }
            // One snapshot carries both, so the choice and the debt can never
            // be read from different states. Collecting the debt as its own
            // flow was the first attempt and looped — the transition writes it
            // — while reading it separately let the two disagree; taken
            // together, the clearing write settles after one further pass and
            // the pair always describes the same moment (Codex, PR #1161).
            settings.telemetryChoice
                .distinctUntilChanged()
                .collect { choice ->
                    // The collector must outlive one failed write. Every
                    // durable step below is DataStore I/O, and letting an
                    // IOException escape ends the *only* subscription to the
                    // user's choice: the switch would then read on with both
                    // SDKs stopped, and every later toggle would be ignored
                    // for the rest of the process (Codex, PR #1161). The debt
                    // is left uncleared on purpose, so the next launch
                    // retries the discard rather than assuming it happened.
                    try {
                    applyTelemetryChoice(
                        enabled = choice.enabled,
                        discardOwed = choice.discardOwed,
                        setAnalyticsCollectionEnabled = analytics::setAnalyticsCollectionEnabled,
                        setCrashlyticsCollectionEnabled = crashlytics::setCrashlyticsCollectionEnabled,
                        deleteUnsentReports = { crashlytics.deleteUnsentReports() },
                        resetAnalyticsData = { analytics.resetAnalyticsData() },
                        // Bound to the debt *this* pass read, so a crossing
                        // made while the purge runs records a debt the clear
                        // cannot retire.
                        clearDiscardOwed = {
                            settings.clearTelemetryDiscardOwed(choice.discardToken)
                        },
                        // Enabled, and the debt still standing is the one
                        // this pass discharged rather than a newer one — the
                        // generation is what tells them apart, and it has to,
                        // because the clear now happens after the enable. See
                        // the re-read in `applyTelemetryChoice`.
                        safeToEnable = {
                            settings.telemetryChoice.first().let {
                                it.enabled && it.discardToken == choice.discardToken
                            }
                        },
                    )
                    } catch (e: IOException) {
                        DiagLog.w("Telemetry", "applying the telemetry choice failed", e)
                    }
                    // Both idempotent, so this runs on every pass and matters
                    // only on the first: the SDKs now carry the stored choice,
                    // so producing telemetry is safe. A failed write above
                    // still releases them — the catch left the flags where
                    // `applyTelemetryChoice` put them, which is off on the
                    // path that matters.
                    analyticsRef = analytics
                    crashlyticsRef = crashlytics
                    telemetryApplied.complete(Unit)
                }
        }
        scope.launch {
            telemetryApplied.await()
            settings.analyticsSnapshot
                .distinctUntilChanged()
                .collect { snapshot -> snapshot.applyTo(analytics) }
        }
        scope.launch {
            telemetryApplied.await()
            settings.settingsSnapshot
                .distinctUntilChanged()
                .collect { snapshot -> logSettingsSnapshot(snapshot) }
        }
        scope.launch {
            telemetryApplied.await()
            settings.clothesRulesSnapshot
                .distinctUntilChanged()
                .collect { snapshot -> logClothesRulesSnapshot(snapshot) }
        }
    }

    /**
     * Applies the user's telemetry choice to both SDKs, discarding what they
     * had already collected whenever an opt-out's deletion is outstanding.
     *
     * Turning collection off is not enough on its own: Crashlytics honors the
     * flag from the *next* launch and leaves already-captured reports on disk,
     * so a crash caught before the opt-out could still upload afterwards.
     *
     * The deletion is therefore a **debt**, recorded in the same DataStore
     * edit as the opt-out itself (see `SettingsRepository.setTelemetryEnabled`)
     * and cleared only once the delete has run. Writing it with the choice is
     * what carries the promise across a rapid off→on, where the collector can
     * conflate away the intermediate `false` and the disabled transition never
     * runs at all.
     *
     * The debt carries a generation, and [clearDiscardOwed] retires only the
     * one this pass read. A purge suspends, so the same rapid off→on can land
     * *during* it: an unconditional clear would then retire the debt recorded
     * in that gap as though this purge had covered it, and a report captured
     * there would go out when the flags came back on (Codex, PR #1161).
     *
     * Both directions stop collection before discarding, and an enable
     * discharges anything owed before turning collection back on.
     *
     * **What this cannot do.** `deleteUnsentReports()` reaches only reports
     * Crashlytics is holding for a consent decision: it is
     * `reportActionProvided.trySetResult(false)`, and with automatic
     * collection on the SDK resolves that same source itself at startup, so
     * the call then no-ops. A report already scheduled for automatic upload
     * cannot be retracted from here — only the manual reporting flow (start
     * disabled, then `checkForUnsentReports` / `sendUnsentReports` /
     * `deleteUnsentReports`) can promise that. `TODO.md` tracks it, and
     * `PRIVACY.md` is worded to what this actually delivers.
     *
     * `resetAnalyticsData` goes with each discharge so a later re-enable
     * starts a fresh app-instance ID rather than resuming the stream the user
     * turned off.
     *
     * Parameterized rather than reaching for the SDKs directly so the ordering
     * is assertable without Firebase — see `TelemetryDisableTest`.
     */
    internal suspend fun applyTelemetryChoice(
        enabled: Boolean,
        discardOwed: Boolean,
        setAnalyticsCollectionEnabled: (Boolean) -> Unit,
        setCrashlyticsCollectionEnabled: (Boolean) -> Unit,
        deleteUnsentReports: () -> Unit,
        resetAnalyticsData: () -> Unit,
        clearDiscardOwed: suspend () -> Unit,
        safeToEnable: suspend () -> Boolean = { true },
    ) {
        // Stop first, always. Both SDKs persist their collection flag, so this
        // is itself durable: a kill after it leaves the next launch's
        // FirebaseInitProvider starting *disabled*, which is what keeps a
        // still-undeleted report from uploading before this runs again
        // (Codex, PR #1161). Writing the debt first and stopping second had it
        // the wrong way round — the debt survived, and so did the enabled flag
        // the provider reads before Application.onCreate. Both calls are
        // idempotent, which is what makes them safe on every launch.
        fun stop() {
            setAnalyticsCollectionEnabled(false)
            setCrashlyticsCollectionEnabled(false)
        }
        // Only where a consented period actually left something behind. The
        // debt is exactly that signal, so it is also what keeps this off the
        // startup path of every opted-out install: reporting is opt-in, so
        // "disabled, nothing owed" is now the common case, and running the
        // purge there would mean an analytics reset and a DataStore write on
        // every launch for a user who has never turned reporting on.
        // Deliberately does **not** clear the debt. On the enable path the
        // clear has to come after the flags go on — see below — and on the
        // disable path there is nothing after it, so the caller retires the
        // debt at the right moment rather than this doing it eagerly.
        suspend fun purge() {
            deleteUnsentReports()
            resetAnalyticsData()
        }
        suspend fun discharge() {
            stop()
            purge()
        }
        if (!enabled) {
            stop()
            if (discardOwed) {
                purge()
                // Nothing follows on this path, and the flags are already
                // persisted off, so retiring it here is safe.
                clearDiscardOwed()
            }
            return
        }
        if (discardOwed) {
            discharge()
            // Re-read the **whole** choice after the discharge, not only
            // before it and not only the `enabled` half. `discharge()`
            // suspends — a DataStore edit and two SDK calls — and the user can
            // cross the consent line while it is in flight, in two ways that
            // both end here:
            //
            //  - Opting out. A transition that set out to enable finds that
            //    decision already superseded, and turning the flags on would
            //    leave them enabled for an opted-out user — durably, since
            //    both SDKs persist them (Codex, PR #1161).
            //  - Off and back on. The final choice is still `enabled`, so
            //    checking that alone passes, but a *newer* debt has been
            //    recorded. Enabling here would turn collection on with a
            //    report from the opted-out gap still held, free to upload
            //    before the collector reaches the emission carrying that debt
            //    (Codex, PR #1161).
            //
            // It compares the **generation**, not `discardOwed`: the debt this
            // pass discharged is deliberately still set here, because the clear
            // happens after the enable below. Only a token that has moved means
            // someone crossed the line since.
            //
            // Returning leaves collection stopped and the debt standing, which
            // is the safe pair: the pending emission discharges it and enables
            // then.
            if (!safeToEnable()) return
        }
        setAnalyticsCollectionEnabled(true)
        setCrashlyticsCollectionEnabled(true)
        // The debt is retired **after** the enable it guards, not inside
        // `purge()`. Deletion is fire-and-forget — `deleteUnsentReports()`
        // returns `void`, so there is nothing to await — and the two calls
        // above overwrite the persisted disable that would otherwise cover a
        // process death here. Clearing first meant a death between the enable
        // and the clear left the next launch collecting with nothing owed,
        // free to release the pre-consent report; held to the end, that death
        // leaves the debt standing and the next launch discards before
        // enabling. The cost is one extra purge, which is the cheap side
        // (Codex, PR #1161 and the same finding on simmo PR #270).
        //
        // And it fails **closed**. Moving the clear after the enable created a
        // path the old order did not have: a throw here used to happen before
        // the flags went on, so a failure left them off; now it would leave
        // them persisted *on* with the deletion still owed, and the collector
        // will not retry because `distinctUntilChanged` drops the unchanged
        // choice. The next launch's provider then starts collecting before the
        // collector can discharge (Codex, PR #1161). Putting the flags back is
        // what keeps the invariant: collection is persisted on only when
        // nothing is owed.
        if (discardOwed) {
            try {
                clearDiscardOwed()
            } catch (e: IOException) {
                stop()
                throw e
            }
        }
        // Nothing is replayed here, deliberately.
        //
        // `resetAnalyticsData()` wipes the identity's user properties, and the
        // snapshot collectors will not resend them in this process because
        // their values are unchanged and `distinctUntilChanged` drops them. So
        // events emitted between an opt-in and the next app launch go out
        // without configuration attached. That is the whole cost, and it is
        // bounded: the next launch's collectors emit their initial values to
        // the new identity, so it self-heals without anything durable.
        //
        // Buying back those few minutes was tried three ways and produced five
        // findings (Codex, PR #1161): unconditional duplicated both events on
        // every opted-in launch; keying it on the discard debt lost the replay
        // when a process death landed between clearing that debt and enabling;
        // and a dedicated durable flag then reintroduced duplication across the
        // interrupted-enable window *and* cleared itself on a replay that had
        // failed. Making it exactly-once means coordinating a durable flag with
        // three independent startup collectors across arbitrary process deaths
        // — a distributed-systems problem taken on for dashboard accuracy.
        // `TODO.md` records it; if it comes back it should be designed rather
        // than bolted to the consent path.
    }

    /**
     * Records the Compose-startup failure on an OEM ROM missing
     * `Configuration.fontWeightAdjustment` as a non-fatal, so its incidence is
     * trackable. The only Crashlytics producer outside this object; it lives
     * here so it goes through the same gate everything else does.
     *
     * **Waits** rather than dropping. It is raised from `MainActivity.onCreate`,
     * which can beat the stored choice being applied on a migrating launch — and
     * dropping it there would lose it for a consenting user in the common case,
     * since that race is the normal one, not the exception. Held until the
     * choice is on both SDKs, it is written for a consenting user and merely
     * captured for anyone else, where the discard a first opt-in owes then
     * clears it (Codex, PR #1161).
     *
     * Silent on a build without google-services.json, and on the install-bypass
     * path in [start] — neither completes the gate.
     */
    fun recordComposeStartupCrash(scope: CoroutineScope, error: Throwable) {
        scope.launch {
            telemetryApplied.await()
            val crashlytics = crashlyticsRef ?: return@launch
            crashlytics.setCustomKey("compose_startup_unsupported", true)
            crashlytics.recordException(error)
        }
    }

    /**
     * Emits an `api_call` event. Called inline with every Open-Meteo / Gemini
     * request — see [TelemetryApiCallLogger] for the offline-filter wrapper
     * that decides which events make it here. Params:
     *
     *  - `endpoint` (string, ≤40 chars): see [app.clothescast.core.data.diag.ApiEndpoints].
     *  - `outcome` (string): `success`, `http_error`, `timeout`, `network_error`, `other_error`.
     *  - `status_code` (long): HTTP status when [outcome] is `success` / `http_error`, else 0.
     *  - `latency_ms` (long): wall-clock from request start.
     *
     * No-op when Firebase didn't initialise (builds without google-services.json),
     * and when the user's Privacy toggle has set collection to disabled the SDK
     * itself silently drops the event — no per-call gate needed here.
     */
    fun logApiCall(endpoint: String, outcome: String, statusCode: Int, latencyMs: Long) {
        val analytics = analyticsRef ?: return
        val params = Bundle().apply {
            putString(PARAM_ENDPOINT, endpoint)
            putString(PARAM_OUTCOME, outcome)
            putLong(PARAM_STATUS_CODE, statusCode.toLong())
            putLong(PARAM_LATENCY_MS, latencyMs)
        }
        analytics.logEvent(EVENT_API_CALL, params)
    }

    /**
     * Emits a `notification_delivery` event when the daily / tonight alarm
     * pipeline posts a notification. Params:
     *
     *  - `period` (string): `today` or `tonight`.
     *  - `alarm_delay_ms` (long): scheduled-fire time → AlarmReceiver fire time.
     *    Catches Doze deferral on `setExactAndAllowWhileIdle`. Always ≥ 0.
     *  - `total_delay_ms` (long): scheduled-fire time → moment the notification
     *    posted. Adds in WorkManager constraint waits (NetworkType.CONNECTED)
     *    and our own pipeline latency. Always ≥ alarm_delay_ms.
     *
     * Only emitted when the post actually happens, so a powered-off / offline
     * device that misses the alarm entirely simply doesn't show up in the
     * stream — matching the user's request to filter those out.
     */
    fun logNotificationDelivery(period: String, alarmDelayMs: Long, totalDelayMs: Long) {
        val analytics = analyticsRef ?: return
        val params = Bundle().apply {
            putString(PARAM_PERIOD, period)
            putLong(PARAM_ALARM_DELAY_MS, alarmDelayMs)
            putLong(PARAM_TOTAL_DELAY_MS, totalDelayMs)
        }
        analytics.logEvent(EVENT_NOTIFICATION_DELIVERY, params)
    }

    /**
     * Emits a `daily_refresh` event when a scheduled / force-refresh run of
     * the FetchAndNotifyWorker reaches a terminal Result. Params:
     *
     *  - `slot` (string): `today` or `tonight`.
     *  - `outcome` (string): `success`, `forecast_error`, `no_location`,
     *    `cancelled`, or `other_error`. See [classifyDailyRefreshReason].
     *  - `latency_ms` (long): wall-clock from `doWork()` entry to the
     *    terminal Result.
     *
     * Result.retry is not terminal and does not emit — WorkManager retries
     * silently on backoff, so the offline / transient-network case shows up
     * only when it eventually succeeds (or gives up and fails for some other
     * reason). That keeps the stream reflecting "did the user actually get a
     * refresh today?" rather than "how many retry hops did it take."
     */
    fun logDailyRefresh(slot: String, outcome: String, latencyMs: Long) {
        val analytics = analyticsRef ?: return
        val params = Bundle().apply {
            putString(PARAM_SLOT, slot)
            putString(PARAM_OUTCOME, outcome)
            putLong(PARAM_LATENCY_MS, latencyMs)
        }
        analytics.logEvent(EVENT_DAILY_REFRESH, params)
    }

    /**
     * Emits a `scheduled_delivery_timeout` event when
     * [app.clothescast.alarm.ScheduledDeliveryService]'s pre-RUNNING safety
     * timeout fires — the alarm fired and the Service started the foreground
     * notification, but the worker never reached the `RUNNING` state within
     * the cap (typically because `NetworkType.CONNECTED` deferred it on a
     * flaky / offline device).
     *
     * Distinct from `daily_refresh{outcome=cancelled}` which fires when the
     * worker itself was canceled mid-flight: this event surfaces the
     * "worker queued but never started" case where the worker may still
     * eventually run later (so it isn't yet canceled or failed), but the
     * user's "Preparing your ClothesCast" notification was dismissed at the
     * timeout.
     *
     *  - `slot` (string): `today` or `tonight`.
     */
    fun logScheduledDeliveryTimeout(slot: String) {
        val analytics = analyticsRef ?: return
        val params = Bundle().apply {
            putString(PARAM_SLOT, slot)
        }
        analytics.logEvent(EVENT_SCHEDULED_DELIVERY_TIMEOUT, params)
    }

    /**
     * Emits the user's non-voice settings as one event per Settings page —
     * `settings_schedule`, `settings_clothes`, `settings_format`,
     * `settings_region`, `settings_display`, and `settings_calendar` — so each
     * event name mirrors the screen the user actually edited, and a new
     * setting lands in the event for its own page.
     *
     * Why per-page events instead of one combined event: a Firebase Analytics
     * event caps at 25 params, and the combined snapshot had reached exactly
     * that, leaving no room for the next setting (Firebase silently drops
     * params over the cap). Splitting by page gives each screen its own
     * 25-param budget to grow into and keeps every dimension natively
     * sliceable in reports. The split is content-neutral — the same
     * already-coarsened values cross the device boundary, just grouped under
     * different event names; no new field is sent (see PRIVACY.md).
     *
     * Fires once per process start (the DataStore-backed flow replays its
     * current value to each new subscriber) and again every time the resolved
     * snapshot's `equals` changes within the process — i.e. whenever the user
     * toggles a setting. The per-launch baseline is intentional: it lets
     * reports see "what's the default-vs-customised mix across the
     * population?" without requiring the user to interact.
     */
    private fun logSettingsSnapshot(snapshot: SettingsSnapshot) {
        val analytics = analyticsRef ?: return
        // Event order mirrors the Settings root menu order (see SettingsDest).
        analytics.logEvent(EVENT_SETTINGS_SCHEDULE, Bundle().apply {
            putLong("daily_enabled", snapshot.dailyEnabled.toLongFlag())
            putString("daily_time_bucket_hour", snapshot.dailyTimeBucketHour)
            putLong("daily_days_count", snapshot.dailyDaysCount.toLong())
            putString("delivery_mode_daily", snapshot.deliveryModeDaily)
            putLong("tonight_enabled", snapshot.tonightEnabled.toLongFlag())
            putString("tonight_time_bucket_hour", snapshot.tonightTimeBucketHour)
            putLong("tonight_days_count", snapshot.tonightDaysCount.toLong())
            putString("delivery_mode_tonight", snapshot.deliveryModeTonight)
            putLong("tonight_notify_only_on_events", snapshot.tonightNotifyOnlyOnEvents.toLongFlag())
            putLong("daily_mention_evening_events", snapshot.dailyMentionEveningEvents.toLongFlag())
        })
        analytics.logEvent(EVENT_SETTINGS_CLOTHES, Bundle().apply {
            putString("default_bottom", snapshot.defaultBottom)
            putString("default_top", snapshot.defaultTop)
        })
        analytics.logEvent(EVENT_SETTINGS_FORMAT, Bundle().apply {
            putString("clothes_mention_mode", snapshot.clothesMentionMode)
            putString("range_format", snapshot.rangeFormat)
            putString("clothes_format", snapshot.clothesFormat)
            putString("bottoms_format", snapshot.bottomsFormat)
            putString("accessories_format", snapshot.accessoriesFormat)
            putLong("delta_threshold_c", snapshot.deltaThresholdC.toLong())
            putString("delta_format", snapshot.deltaFormat)
        })
        analytics.logEvent(EVENT_SETTINGS_REGION, Bundle().apply {
            putString("temperature_unit_setting", snapshot.temperatureUnitSetting)
            putString("temperature_unit_effective", snapshot.temperatureUnitEffective)
            putString("wind_speed_unit_setting", snapshot.windSpeedUnitSetting)
            putString("wind_speed_unit_effective", snapshot.windSpeedUnitEffective)
        })
        analytics.logEvent(EVENT_SETTINGS_DISPLAY, Bundle().apply {
            putString("theme_mode", snapshot.themeMode)
            putString("color_palette", snapshot.colorPalette)
        })
        analytics.logEvent(EVENT_SETTINGS_CALENDAR, Bundle().apply {
            putLong("use_calendar_events", snapshot.useCalendarEvents.toLongFlag())
        })
    }

    /**
     * Emits a `clothes_rules_snapshot` event whose params describe the user's
     * clothes-rule customisation. Per-category deltas are integer Celsius
     * differences from the catalog default, clamped to ±5°C — see
     * [ClothesRulesSnapshot] for the bucket format.
     *
     * Same emission cadence as [logSettingsSnapshot]: once on process start
     * (DataStore replay) and again on each subsequent change to the user's
     * rule list within the process.
     */
    private fun logClothesRulesSnapshot(snapshot: ClothesRulesSnapshot) {
        val analytics = analyticsRef ?: return
        val params = Bundle().apply {
            putLong("customised_count", snapshot.customisedCount.toLong())
            putLong("extra_rules_count", snapshot.extraRulesCount.toLong())
            putString("categories_customised", snapshot.categoriesCustomised)
            putLong("all_defaults", snapshot.allDefaults.toLongFlag())
            putString("sweater_delta_c", snapshot.sweaterDeltaC)
            putString("jacket_delta_c", snapshot.jacketDeltaC)
            putString("coat_delta_c", snapshot.coatDeltaC)
            putString("gloves_delta_c", snapshot.glovesDeltaC)
            putString("shorts_delta_c", snapshot.shortsDeltaC)
            putString("umbrella_delta_pct", snapshot.umbrellaDeltaPct)
            putString("rain_jacket_delta_pct", snapshot.rainJacketDeltaPct)
        }
        analytics.logEvent(EVENT_CLOTHES_RULES_SNAPSHOT, params)
    }

    /** Booleans ship as `0` / `1` longs so they slice cleanly in Firebase reports. */
    private fun Boolean.toLongFlag(): Long = if (this) 1L else 0L

    // Firebase Analytics caps: event names ≤40 chars, param names ≤40 chars,
    // string values ≤100 chars. All identifiers below comfortably fit.
    private const val EVENT_API_CALL = "api_call"
    private const val EVENT_NOTIFICATION_DELIVERY = "notification_delivery"
    private const val EVENT_DAILY_REFRESH = "daily_refresh"
    private const val EVENT_SCHEDULED_DELIVERY_TIMEOUT = "scheduled_delivery_timeout"
    // The non-voice settings snapshot is split into one event per Settings
    // page (names mirror the screen titles) so each stays well under
    // Firebase's 25-param-per-event cap; see logSettingsSnapshot.
    private const val EVENT_SETTINGS_SCHEDULE = "settings_schedule"
    private const val EVENT_SETTINGS_CLOTHES = "settings_clothes"
    private const val EVENT_SETTINGS_FORMAT = "settings_format"
    private const val EVENT_SETTINGS_REGION = "settings_region"
    private const val EVENT_SETTINGS_DISPLAY = "settings_display"
    private const val EVENT_SETTINGS_CALENDAR = "settings_calendar"
    private const val EVENT_CLOTHES_RULES_SNAPSHOT = "clothes_rules_snapshot"
    private const val PARAM_ENDPOINT = "endpoint"
    private const val PARAM_OUTCOME = "outcome"
    private const val PARAM_STATUS_CODE = "status_code"
    private const val PARAM_LATENCY_MS = "latency_ms"
    private const val PARAM_PERIOD = "period"
    private const val PARAM_SLOT = "slot"
    private const val PARAM_ALARM_DELAY_MS = "alarm_delay_ms"
    private const val PARAM_TOTAL_DELAY_MS = "total_delay_ms"
}

/**
 * Pure mapping from a [androidx.work.ListenableWorker.Result.Failure] reason
 * code (the `KEY_REASON` string stamped by FetchAndNotifyWorker) onto the
 * `outcome` bucket of the `daily_refresh` event. Extracted so the
 * classification is unit-testable without dragging WorkManager into the test
 * classpath.
 */
internal fun classifyDailyRefreshReason(reason: String?): String = when (reason) {
    "no_location" -> "no_location"
    "unexpected_http" -> "forecast_error"
    else -> "other_error"
}

/**
 * Pushes each field of [this] onto [analytics] as a user property. Values are
 * truncated to Firebase's 36-char per-property limit — voice IDs in particular
 * can grow beyond that with future engines, and Firebase silently drops
 * oversized values rather than truncating them itself.
 */
internal fun SettingsAnalyticsSnapshot.applyTo(analytics: FirebaseAnalytics) {
    analytics.setUserProperty("region_default", regionDefault.cap())
    analytics.setUserProperty("region_override", regionOverride.cap())
    analytics.setUserProperty("region_effective", regionEffective.cap())
    analytics.setUserProperty("voice_locale_default", voiceLocaleDefault.cap())
    analytics.setUserProperty("voice_locale_override", voiceLocaleOverride.cap())
    analytics.setUserProperty("voice_locale_effective", voiceLocaleEffective.cap())
    analytics.setUserProperty("tts_engine_default", ttsEngineDefault.cap())
    analytics.setUserProperty("tts_engine_override", ttsEngineOverride.cap())
    analytics.setUserProperty("tts_engine_effective", ttsEngineEffective.cap())
    analytics.setUserProperty("tts_style_default", ttsStyleDefault.cap())
    analytics.setUserProperty("tts_style_override", ttsStyleOverride.cap())
    analytics.setUserProperty("tts_style_effective", ttsStyleEffective.cap())
    analytics.setUserProperty("gemini_voice_default", geminiVoiceDefault.cap())
    analytics.setUserProperty("gemini_voice_override", geminiVoiceOverride.cap())
    analytics.setUserProperty("gemini_voice_effective", geminiVoiceEffective.cap())
    analytics.setUserProperty("device_voice_default", deviceVoiceDefault.cap())
    analytics.setUserProperty("device_voice_override", deviceVoiceOverride.cap())
    analytics.setUserProperty("device_voice_effective", deviceVoiceEffective.cap())
}

private fun String.cap(): String = if (length <= 36) this else take(36)
