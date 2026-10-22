package app.clothescast.alerts

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.clothescast.core.data.weather.GoogleWeatherAccess
import app.clothescast.core.data.weather.GoogleWeatherAlertsClient
import app.clothescast.core.domain.model.AlertLevel
import app.clothescast.core.domain.model.AlertNotifyLevel
import app.clothescast.core.domain.model.DeliveryMode
import app.clothescast.core.domain.model.HourlyForecast
import app.clothescast.core.domain.model.Location
import app.clothescast.core.domain.model.Schedule
import app.clothescast.core.domain.model.TemperatureUnit
import app.clothescast.core.domain.model.UserPreferences
import app.clothescast.core.domain.model.WeatherCondition
import app.clothescast.core.domain.model.WindSpeedUnit
import app.clothescast.data.AlertsCheckOutcome
import app.clothescast.data.WeatherAlertsStore
import app.clothescast.notification.CHANNEL_WEATHER_ALERTS
import app.clothescast.notification.WeatherAlertNotifier
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale

/**
 * The alerts pipeline as the app wires it: [WeatherAlertsRefresher] checks and
 * writes the store, and [WeatherAlertShade] settles the notifications from the
 * store and the settings after every write, every settings change and every
 * app resume. Real store and notifier, a mock Google.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class WeatherAlertsRefresherTest {
    @get:Rule val temp = TemporaryFolder()

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val notifications =
        application.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private val placeA = Location(latitude = 1.48, longitude = -1.9, displayName = "City A")
    private val placeB = Location(latitude = 10.0, longitude = 10.0, displayName = "City B")
    private val t0 = Instant.parse("2026-10-22T10:00:00Z")

    // The live settings both the refresher and the shade read.
    private var prefs = UserPreferences(
        schedule = Schedule(time = LocalTime.of(7, 0), days = Schedule.EVERY_DAY, zoneId = ZoneId.of("UTC")),
        deliveryMode = DeliveryMode.NOTIFICATION_AND_TTS,
        temperatureUnit = TemperatureUnit.CELSIUS,
        windSpeedUnit = WindSpeedUnit.KMH,
        clothesRules = emptyList(),
        weatherAlerts = true,
    )

    // What Google answers next: a body, or an error status.
    private var body: String = page()
    private var status: HttpStatusCode = HttpStatusCode.OK
    private var lookups = 0
    private var access: GoogleWeatherAccess? = GoogleWeatherAccess.OwnKey("test-key")
    private var now: Instant = t0
    private var onRequest: suspend () -> Unit = {}
    // The start-time wake the shade last asked for.
    private var wake: Instant? = null

    private lateinit var store: WeatherAlertsStore
    private lateinit var shade: WeatherAlertShade
    private lateinit var refresher: WeatherAlertsRefresher

    @Before
    fun setUp() {
        shadowOf(application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        store = WeatherAlertsStore(
            PreferenceDataStoreFactory.create(produceFile = { temp.newFile("alerts.preferences_pb") }),
        )
        val engine = MockEngine {
            lookups++
            onRequest()
            if (status == HttpStatusCode.OK) {
                respond(
                    content = ByteReadChannel(body),
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            } else {
                respondError(status)
            }
        }
        val http = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val clock = object : Clock() {
            override fun getZone(): ZoneId = ZoneOffset.UTC
            override fun withZone(zone: ZoneId?): Clock = this
            override fun instant(): Instant = now
        }
        shade = WeatherAlertShade(
            store = store,
            notifier = WeatherAlertNotifier(application),
            preferences = { prefs },
            scheduleWake = { wake = it },
            clock = clock,
            locale = { Locale.UK },
        )
        refresher = WeatherAlertsRefresher(
            store = store,
            client = GoogleWeatherAlertsClient(http),
            access = { access },
            clock = clock,
            locale = { Locale.UK },
            onChanged = { shade.reconcile() },
            stillEnabled = { prefs.weatherAlerts },
        )
    }

    /** One alert over a box around City A, at [severity], ending a day after [t0]. */
    private fun page(
        severity: String = "SEVERE",
        id: String = "alert-1",
        title: String = "Amber warning of wind",
        starts: String = "2026-10-22T09:00:00Z",
        expires: String = "2026-10-23T10:00:00Z",
    ): String {
        val box = """{"type":"Polygon","coordinates":[[[-3.0,0.5],[0.0,0.5],[0.0,2.5],[-3.0,2.5],[-3.0,0.5]]]}"""
        return """
            {"weatherAlerts": [{
              "alertId": "$id",
              "alertTitle": {"text": "$title"},
              "eventType": "WIND",
              "areaName": "Region A",
              "startTime": "$starts",
              "expirationTime": "$expires",
              "dataSource": {"name": "Met Office", "authorityUri": "https://example.com/warnings"},
              "polygon": ${JsonPrimitive(box)},
              "severity": "$severity"
            }]}
        """.trimIndent()
    }

    private suspend fun refresh(
        location: Location = placeA,
        trigger: WeatherAlertsRefresher.Trigger = WeatherAlertsRefresher.Trigger.ON_SCREEN,
        forecast: List<HourlyForecast> = emptyList(),
    ) = refresher.refreshIfDue(prefs, location, forecast, trigger)

    private fun shadeNow() = shadowOf(notifications).allNotifications

    @Test
    fun `a severe alert here is stored and notified once, titled by color and hazard`() = runTest {
        refresh() shouldBe true

        val state = store.current()
        state.outcome shouldBe AlertsCheckOutcome.OK
        state.alerts.single().level shouldBe AlertLevel.ORANGE
        state.notified shouldBe mapOf("alert-1" to AlertLevel.ORANGE)
        val posted = shadeNow().single()
        posted.channelId shouldBe CHANNEL_WEATHER_ALERTS
        posted.extras.getString(NotificationCompat.EXTRA_TITLE) shouldBe "Amber wind warning"

        // Checked again an hour on: the same alert isn't posted twice.
        now = t0.plus(Duration.ofHours(1))
        refresh() shouldBe true
        shadeNow() shouldHaveSize 1
    }

    @Test
    fun `a warning that hasn't started notifies when it starts, not when it's issued`() = runTest {
        body = page(starts = "2026-10-23T06:00:00Z")
        refresh()

        store.current().alerts shouldHaveSize 1
        shadeNow().shouldBeEmpty()
        wake shouldBe Instant.parse("2026-10-23T06:00:00Z")

        // The wake at its start settles the shade.
        now = Instant.parse("2026-10-23T06:00:00Z")
        shade.reconcile()
        shadeNow() shouldHaveSize 1
        wake shouldBe null
    }

    @Test
    fun `a yellow alert shows but doesn't notify at the default level`() = runTest {
        body = page(severity = "MODERATE", title = "Yellow warning of wind")
        refresh()

        store.current().alerts.single().level shouldBe AlertLevel.YELLOW
        shadeNow().shouldBeEmpty()
    }

    @Test
    fun `a level that rises notifies again`() = runTest {
        prefs = prefs.copy(weatherAlertsNotifyLevel = AlertNotifyLevel.ALL)
        body = page(severity = "MODERATE")
        refresh()
        shadeNow() shouldHaveSize 1

        body = page(severity = "EXTREME", title = "Red warning of wind")
        now = t0.plus(Duration.ofHours(1))
        refresh()
        store.current().notified shouldBe mapOf("alert-1" to AlertLevel.RED)
        shadeNow().single().extras.getString(NotificationCompat.EXTRA_TITLE) shouldBe "Red wind warning"
    }

    @Test
    fun `a notification still showing follows a downgrade and a new end time, silently`() = runTest {
        body = page(severity = "EXTREME", title = "Red warning of wind")
        refresh()
        shadeNow().single().extras.getString(NotificationCompat.EXTRA_TITLE) shouldBe "Red wind warning"

        body = page(severity = "SEVERE", expires = "2026-10-24T10:00:00Z")
        now = t0.plus(Duration.ofHours(1))
        refresh()

        val updated = shadeNow().single()
        updated.extras.getString(NotificationCompat.EXTRA_TITLE) shouldBe "Amber wind warning"
        updated.timeoutAfter shouldBe Duration.between(now, Instant.parse("2026-10-24T10:00:00Z")).toMillis()
        (updated.flags and android.app.Notification.FLAG_ONLY_ALERT_ONCE != 0) shouldBe true
        store.current().notified shouldBe mapOf("alert-1" to AlertLevel.ORANGE)
    }

    @Test
    fun `a dismissed alert stays dismissed at the same level`() = runTest {
        refresh()
        notifications.cancel("alert-1", WeatherAlertNotifier.NOTIFICATION_ID_WEATHER_ALERT)
        shadeNow().shouldBeEmpty()

        now = t0.plus(Duration.ofHours(1))
        refresh()
        shade.reconcile()
        shadeNow().shouldBeEmpty()
    }

    @Test
    fun `a new notify level applies to the alerts already known, without a lookup`() = runTest {
        body = page(severity = "MODERATE", title = "Yellow warning of wind")
        refresh()
        shadeNow().shouldBeEmpty()

        prefs = prefs.copy(weatherAlertsNotifyLevel = AlertNotifyLevel.ALL)
        shade.reconcile()
        shadeNow() shouldHaveSize 1
        store.current().notified shouldBe mapOf("alert-1" to AlertLevel.YELLOW)

        prefs = prefs.copy(weatherAlertsNotifyLevel = AlertNotifyLevel.NONE)
        shade.reconcile()
        shadeNow().shouldBeEmpty()
        store.current().notified shouldBe emptyMap()
        lookups shouldBe 1
    }

    @Test
    fun `a permission granted later posts what it held back, without a lookup`() = runTest {
        shadowOf(application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        refresh()
        shadeNow().shouldBeEmpty()
        store.current().notified shouldBe emptyMap()

        // The app resumes from the prompt.
        shadowOf(application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        shade.reconcile()
        lookups shouldBe 1
        shadeNow() shouldHaveSize 1
        store.current().notified shouldBe mapOf("alert-1" to AlertLevel.ORANGE)
    }

    @Test
    fun `on screen, a check under an hour old isn't repeated`() = runTest {
        refresh()
        now = t0.plus(Duration.ofMinutes(59))
        refresh() shouldBe false
        lookups shouldBe 1
        now = t0.plus(Duration.ofMinutes(60))
        refresh() shouldBe true
        lookups shouldBe 2
    }

    @Test
    fun `in the background, a quiet forecast waits 3 hours and an active alert 1`() = runTest {
        val background = WeatherAlertsRefresher.Trigger.BACKGROUND
        body = """{"weatherAlerts": []}"""
        refresh(trigger = background)
        now = t0.plus(Duration.ofHours(2))
        refresh(trigger = background) shouldBe false
        now = t0.plus(Duration.ofHours(3))
        refresh(trigger = background) shouldBe true

        // An alert in force makes the next check due an hour on.
        body = page()
        now = t0.plus(Duration.ofHours(4))
        refresh(trigger = WeatherAlertsRefresher.Trigger.ON_SCREEN)
        now = t0.plus(Duration.ofHours(5))
        refresh(trigger = background) shouldBe true
    }

    @Test
    fun `a severe-looking forecast checks hourly in the background`() = runTest {
        val background = WeatherAlertsRefresher.Trigger.BACKGROUND
        val gale = listOf(
            HourlyForecast(
                time = LocalTime.NOON,
                temperatureC = 10.0,
                feelsLikeC = 6.0,
                precipitationProbabilityPct = 20.0,
                condition = WeatherCondition.CLOUDY,
                windSpeedKmh = 70.0,
            ),
        )
        body = """{"weatherAlerts": []}"""
        refresh(trigger = background, forecast = gale)
        now = t0.plus(Duration.ofHours(1))
        refresh(trigger = background, forecast = gale) shouldBe true
    }

    @Test
    fun `a new place drops the old place's alerts and notifications at once`() = runTest {
        refresh()
        shadeNow() shouldHaveSize 1

        body = """{"weatherAlerts": []}"""
        refresh(location = placeB) shouldBe true

        val state = store.current()
        state.point shouldBe WeatherAlertsRefresher.alertPoint(placeB)
        state.alerts.shouldBeEmpty()
        shadeNow().shouldBeEmpty()
    }

    @Test
    fun `a failed check keeps what was known and says so`() = runTest {
        refresh()
        status = HttpStatusCode.InternalServerError
        now = t0.plus(Duration.ofHours(1))
        refresh()

        val state = store.current()
        state.outcome shouldBe AlertsCheckOutcome.FAILED
        state.alerts shouldHaveSize 1
        shadeNow() shouldHaveSize 1
    }

    @Test
    fun `a place without coverage is recorded as unsupported, never as all clear`() = runTest {
        status = HttpStatusCode.NotFound
        refresh()
        store.current().outcome shouldBe AlertsCheckOutcome.UNSUPPORTED
    }

    @Test
    fun `no route to Google costs no lookup, and the next chance retries`() = runTest {
        access = null
        refresh()
        store.current().outcome shouldBe AlertsCheckOutcome.NO_ACCESS
        lookups shouldBe 0

        // Signed in a minute later: due at once, not an hour on.
        access = GoogleWeatherAccess.OwnKey("test-key")
        now = t0.plus(Duration.ofMinutes(1))
        refresh() shouldBe true
        store.current().outcome shouldBe AlertsCheckOutcome.OK
    }

    @Test
    fun `turning alerts off forgets them and clears the shade`() = runTest {
        refresh()
        prefs = prefs.copy(weatherAlerts = false)
        refresh() shouldBe false
        store.current().alerts.shouldBeEmpty()
        shadeNow().shouldBeEmpty()
    }

    @Test
    fun `the switch going off clears the shade from the settings alone`() = runTest {
        refresh()
        prefs = prefs.copy(weatherAlerts = false)
        // The settings watcher's reconcile, before anything clears the store.
        shade.reconcile()
        shadeNow().shouldBeEmpty()
        wake shouldBe null
    }

    @Test
    fun `a check that returns after alerts went off publishes nothing`() = runTest {
        onRequest = { prefs = prefs.copy(weatherAlerts = false) }
        refresh()

        store.current().alerts.shouldBeEmpty()
        shadeNow().shouldBeEmpty()
    }

    @Test
    fun `the store round-trips alerts without their areas`() = runTest {
        refresh()
        val stored = store.current().alerts.single()
        stored.alert.issuer shouldBe "Met Office"
        stored.alert.endTime shouldBe Instant.parse("2026-10-23T10:00:00Z")
        stored.alert.area shouldBe null
        stored.notifiable shouldBe true
    }

    @Test
    fun `notifications recorded during a lookup survive its result`() = runTest {
        body = page(starts = "2026-10-22T11:00:00Z")
        refresh()
        shadeNow().shouldBeEmpty()

        // The alert starts while the next check waits on Google, and its
        // start-time wake posts it.
        now = Instant.parse("2026-10-22T11:00:00Z")
        onRequest = { shade.reconcile() }
        refresh(trigger = WeatherAlertsRefresher.Trigger.BACKGROUND)

        store.current().notified shouldBe mapOf("alert-1" to AlertLevel.ORANGE)
        // Settled after the result as an update, not posted (and sounded) again.
        val posted = shadeNow().single()
        (posted.flags and android.app.Notification.FLAG_ONLY_ALERT_ONCE != 0) shouldBe true
    }

    @Test
    fun `a dismissed card stays dismissed across checks, until the alert changes`() = runTest {
        refresh()
        store.dismiss(store.current().alerts.single())
        store.current().let { it.isDismissed(it.alerts.single()) } shouldBe true

        // The same alert again: still dismissed.
        now = t0.plus(Duration.ofHours(1))
        refresh()
        store.current().let { it.isDismissed(it.alerts.single()) } shouldBe true

        // The issuer extends it: the card is back.
        body = page(expires = "2026-10-24T10:00:00Z")
        now = t0.plus(Duration.ofHours(2))
        refresh()
        store.current().let { it.isDismissed(it.alerts.single()) } shouldBe false
    }

    @Test
    fun `a dismissal is forgotten with its alert, and at a new place`() = runTest {
        refresh()
        store.dismiss(store.current().alerts.single())

        body = """{"weatherAlerts": []}"""
        now = t0.plus(Duration.ofHours(1))
        refresh()
        store.current().dismissed shouldBe emptyMap()

        body = page()
        now = t0.plus(Duration.ofHours(2))
        refresh()
        store.dismiss(store.current().alerts.single())
        refresh(location = placeB)
        store.current().dismissed shouldBe emptyMap()
    }
}
