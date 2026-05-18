package app.clothescast

import android.app.Application
import android.content.Context
import app.clothescast.alarm.DailyAlarmScheduler
import app.clothescast.calendar.CalendarContractEventReader
import app.clothescast.cast.CastInsightController
import app.clothescast.core.data.diag.ApiCallLogger
import app.clothescast.core.data.location.OpenMeteoGeocodingClient
import app.clothescast.core.data.tts.GeminiTtsClient
import app.clothescast.core.data.weather.ConfidenceFetchLogger
import app.clothescast.core.data.weather.OpenMeteoClient
import app.clothescast.core.domain.model.ForecastModel
import app.clothescast.core.domain.model.ForecastPeriod
import app.clothescast.core.domain.model.defaultsFor
import app.clothescast.core.domain.repository.CachingWeatherRepository
import app.clothescast.core.domain.repository.CalendarEventReader
import app.clothescast.core.domain.repository.WeatherRepository
import app.clothescast.core.domain.usecase.GenerateDailyInsight
import app.clothescast.data.InsightCache
import app.clothescast.data.SecureKeyStore
import app.clothescast.data.SettingsRepository
import app.clothescast.diag.DiagLog
import app.clothescast.discovery.HomeAssistantDiscovery
import app.clothescast.discovery.NsdHomeAssistantDiscovery
import app.clothescast.diag.Telemetry
import app.clothescast.diag.TelemetryApiCallLogger
import app.clothescast.locale.AppLocale
import app.clothescast.location.LocationResolver
import app.clothescast.location.ReverseGeocoder
import app.clothescast.mqtt.MqttPublisher
import app.clothescast.notification.InsightNotifier
import app.clothescast.notification.NotificationChannelRegistrar
import app.clothescast.notification.TonightInsightNotifier
import app.clothescast.notification.WeatherAlertNotifier
import app.clothescast.tts.AndroidTtsSpeaker
import app.clothescast.tts.AndroidTtsVoiceEnumerator
import app.clothescast.tts.TtsSpeaker
import app.clothescast.update.AppUpdateChecker
import com.google.android.gms.cast.framework.CastContext
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/**
 * Lightweight DI: lazy singletons for things that depend on Android Context or that
 * the Worker / UI / receivers all share. Will move to Hilt once we have more than a
 * handful of consumers.
 *
 * The TTS *client* is exposed (Gemini) but not the speakers — speakers wrap a
 * per-call voice choice, so they're constructed at the call site from current
 * preferences. The client itself is heavy (shares the OkHttp engine) so it
 * stays a singleton.
 */
class ClothesCastApplication : Application() {
    val secureKeyStore: SecureKeyStore by lazy { SecureKeyStore.create(this) }
    val settingsRepository: SettingsRepository by lazy { SettingsRepository.create(this) }
    val insightCache: InsightCache by lazy { InsightCache.create(this) }
    val locationResolver: LocationResolver by lazy { LocationResolver(this) }
    val reverseGeocoder: ReverseGeocoder by lazy { ReverseGeocoder(this) }
    val insightNotifier: InsightNotifier by lazy { InsightNotifier(this) }
    val tonightInsightNotifier: TonightInsightNotifier by lazy { TonightInsightNotifier(this) }
    val weatherAlertNotifier: WeatherAlertNotifier by lazy { WeatherAlertNotifier(this) }
    val dailyAlarmScheduler: DailyAlarmScheduler by lazy { DailyAlarmScheduler(this) }
    /**
     * Build an on-device TTS speaker pinned to [voiceId], or to the auto-pick
     * when [voiceId] is null. Constructed per call (matching the cloud
     * speakers) because the chosen voice is part of the speaker's
     * identity — there's no shared engine state to reuse across calls.
     */
    fun deviceTtsSpeaker(voiceId: String? = null): TtsSpeaker = AndroidTtsSpeaker(this, voiceId)

    /**
     * Voice enumeration is stateless and Android-cheap (one engine init per
     * `listVoices` call), but the wrapper itself is harmless to share — used
     * by the Settings voice picker and the "currently using" line.
     */
    val androidTtsVoiceEnumerator: AndroidTtsVoiceEnumerator by lazy { AndroidTtsVoiceEnumerator(this) }
    val calendarEventReader: CalendarEventReader by lazy { CalendarContractEventReader(this) }
    val appUpdateChecker: AppUpdateChecker by lazy { AppUpdateChecker(this) }
    val geminiTtsClient: GeminiTtsClient by lazy {
        GeminiTtsClient(httpClient, secureKeyStore, apiCallLogger = apiCallLogger)
    }

    /**
     * Optional Smart Home / Home Assistant bridge. Off until the user enables
     * it in Settings → Smart Home; the worker calls
     * [MqttPublisher.publishIfEnabled] after each delivered insight, and the
     * publisher itself gates on the runtime config — so the lazy stays cheap
     * for users who never opt in.
     */
    val mqttPublisher: MqttPublisher by lazy {
        MqttPublisher(
            preferences = settingsRepository.preferences,
            passwordProvider = { secureKeyStore.getMqttPassword() },
        )
    }

    /**
     * mDNS / DNS-SD discovery for the Smart Home settings page's "Scan local
     * network" affordance. Stateless — each subscription starts its own pair
     * of NsdManager listeners and tears them down on unsubscribe — so a
     * single shared instance is fine.
     */
    val homeAssistantDiscovery: HomeAssistantDiscovery by lazy {
        NsdHomeAssistantDiscovery(this)
    }

    /**
     * Cast SDK entry point. Returns `null` when Google Play Services isn't
     * available (Cast-less emulators, AOSP / GMS-free builds) — the Today
     * screen hides the Cast button in that case rather than crashing on
     * [com.google.android.gms.cast.framework.CastContext.getSharedInstance].
     */
    val castContext: CastContext? by lazy {
        try {
            CastContext.getSharedInstance(this)
        } catch (t: Throwable) {
            DiagLog.w(TAG, "CastContext.getSharedInstance failed; Cast disabled", t)
            null
        }
    }

    /**
     * Orchestrates "cast today's insight" — synth + WAV-wrap + LAN-host the
     * outfit PNG + load into the active session. Null whenever [castContext]
     * is, so the UI can skip wiring listeners on Cast-less builds.
     */
    val castInsightController: CastInsightController? by lazy {
        castContext?.let {
            CastInsightController(
                context = this,
                castContext = it,
                ttsClient = geminiTtsClient,
                applicationScope = applicationScope,
            )
        }
    }

    private val httpClient: HttpClient by lazy {
        HttpClient(OkHttp) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        }
    }

    // Shared across all network clients so every Open-Meteo / Gemini call
    // emits one Firebase Analytics `api_call` event with status + latency.
    // The wrapper drops "device is offline" failures so the resulting stream
    // is a clean read on rate limits and transient server issues.
    private val apiCallLogger: ApiCallLogger by lazy { TelemetryApiCallLogger(this) }

    val weatherRepository: WeatherRepository by lazy {
        // Wrap the network client in a 1 h in-memory cache so a manual
        // refresh on the Today screen — or back-to-back worker fires — reuse
        // a fresh forecast instead of re-hitting Open-Meteo each time. The
        // cache keys on location rounded to ~1 km, so small GPS jitter still
        // hits and a real move (different suburb / city) misses.
        CachingWeatherRepository(
            delegate = OpenMeteoClient(
                httpClient = httpClient,
                confidenceLogger = ConfidenceFetchLogger { message, throwable ->
                    DiagLog.w("ConfidenceFetcher", message, throwable)
                },
                apiCallLogger = apiCallLogger,
                // Read the user's Forecasters selection on every fetch so a
                // settings change takes effect on the next manual refresh
                // without rebuilding the client. When the user is on Auto
                // (stored selection is null), resolve a location-aware
                // default trio via [ForecastModel.defaultsFor] — so a UK
                // user gets UKMO + ECMWF + ICON without ever opening the
                // picker, a North American gets GFS + GEM + ECMWF, etc.
                // DataStore caches the latest emission, so this is a memory
                // read after the first call.
                confidenceModelsProvider = { location ->
                    val prefs = settingsRepository.preferences.first()
                    val effective = prefs.forecastModels ?: ForecastModel.defaultsFor(location)
                    effective.map { it.openMeteoId }
                },
            ),
            // Make the cache invalidate when the *effective* Forecasters set
            // changes — otherwise a Settings edit (or a move to a different
            // region while on Auto) followed by a Today refresh within the
            // 1 h TTL would silently return the previous model set's bundle.
            // Resolving the same null-or-Set chain as confidenceModelsProvider
            // means an Auto user crossing regions invalidates the cache,
            // and an explicit-pick user's invalidation is unaffected by
            // location.
            freshnessKeyProvider = { location ->
                val prefs = settingsRepository.preferences.first()
                prefs.forecastModels ?: ForecastModel.defaultsFor(location)
            },
        )
    }
    val geocodingClient: OpenMeteoGeocodingClient by lazy {
        OpenMeteoGeocodingClient(httpClient, apiCallLogger = apiCallLogger)
    }

    val generateDailyInsight: GenerateDailyInsight by lazy {
        GenerateDailyInsight(
            weatherRepository = weatherRepository,
            calendarEventReader = calendarEventReader,
        )
    }

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun attachBaseContext(base: Context) {
        // Re-apply the persisted per-app locale before the framework caches a
        // Resources reference for the Application context (used by the worker
        // and any non-Activity component). On API 33+ this is a no-op — the
        // system honours LocaleManager.setApplicationLocales without our help.
        super.attachBaseContext(AppLocale.wrap(base))
    }

    override fun onCreate() {
        super.onCreate()
        DiagLog.install(this)
        // Bridge the user's Privacy toggle to Firebase. No-ops on builds
        // assembled without google-services.json (CI). Crashlytics's own
        // UncaughtExceptionHandler is auto-installed by FirebaseInitProvider
        // before Application.onCreate runs, so it sits between the OS handler
        // and DiagLog's wrapper above — no manual chaining needed here.
        Telemetry.start(this, settingsRepository, applicationScope)
        NotificationChannelRegistrar.register(this)
        applicationScope.launch {
            try {
                val prefs = settingsRepository.preferences.first()
                // Reconcile Locale.setDefault (process-scoped, lost on cold
                // start) and the API 33+ LocaleManager state with the
                // persisted Region. The pre-API-33 SharedPreferences cache is
                // already consulted by attachBaseContext above.
                AppLocale.apply(this@ClothesCastApplication, prefs.region)
                dailyAlarmScheduler.schedule(prefs.schedule, ForecastPeriod.TODAY)
                if (prefs.tonightEnabled) {
                    dailyAlarmScheduler.schedule(prefs.tonightSchedule, ForecastPeriod.TONIGHT)
                } else {
                    dailyAlarmScheduler.cancel(ForecastPeriod.TONIGHT)
                }
            } catch (t: Throwable) {
                DiagLog.e(TAG, "Initial alarm scheduling failed", t)
            }
        }
    }

    companion object {
        private const val TAG = "ClothesCastApplication"
    }
}
