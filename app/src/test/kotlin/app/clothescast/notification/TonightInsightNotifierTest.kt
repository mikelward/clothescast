package app.clothescast.notification

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.clothescast.R
import app.clothescast.core.domain.model.BandClause
import app.clothescast.core.domain.model.ForecastPeriod
import app.clothescast.core.domain.model.Insight
import app.clothescast.core.domain.model.InsightSummary
import app.clothescast.core.domain.model.TemperatureBand
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDate

/**
 * Channel routing for the tonight notification: every post lands on the shared
 * scheduled-insight channel, regardless of whether the evening has events.
 * Suppression of the empty-evening notification ("only notify on events") is
 * a worker-level gate now ([DeliveryGates.emptyEveningSkip]), not channel
 * routing — so when the notifier is called at all, it posts to the same
 * channel the morning notifier uses.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class TonightInsightNotifierTest {
    private val application: Application = ApplicationProvider.getApplicationContext()
    private val context: Context = application
    private val notificationManager: NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    @Before
    fun grantNotificationPermission() {
        // ShadowApplication's permission state is per-Application-instance and the
        // Application is shared across tests in the class, so the "deny" test's
        // revocation leaks into anything that runs after it. Re-grant in setup so
        // every test starts from the user-granted state.
        shadowOf(application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    @Test
    fun `tonight insight with events posts to the shared scheduled channel`() {
        TonightInsightNotifier(context).notify(sampleInsight(hasEvents = true), "Cool tonight. Bring a jacket.")

        val n = shadowOf(notificationManager).allNotifications.single()
        n.channelId shouldBe "scheduled_insight_v2"
        n.extras.getString(NotificationCompat.EXTRA_TITLE) shouldBe
            context.getString(R.string.notification_tonight_insight_title)
        n.extras.getString(NotificationCompat.EXTRA_TEXT) shouldBe "Cool tonight. Bring a jacket."
    }

    @Test
    fun `tonight insight without events still posts to the shared scheduled channel`() {
        TonightInsightNotifier(context).notify(sampleInsight(hasEvents = false), "Cool tonight.")

        val n = shadowOf(notificationManager).allNotifications.single()
        n.channelId shouldBe "scheduled_insight_v2"
    }

    @Test
    fun `notify is a no-op when POST_NOTIFICATIONS is denied`() {
        // Robolectric auto-grants manifest-declared permissions; revoke at runtime to
        // exercise the POST_NOTIFICATIONS gate that wraps every notify() call on
        // API 33+.
        shadowOf(application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)

        TonightInsightNotifier(context).notify(sampleInsight(hasEvents = true), "ignored")

        shadowOf(notificationManager).allNotifications shouldHaveSize 0
    }

    private fun sampleInsight(hasEvents: Boolean) = Insight(
        summary = InsightSummary(
            period = ForecastPeriod.TONIGHT,
            band = BandClause(TemperatureBand.COOL, TemperatureBand.COOL),
        ),
        recommendedItems = emptyList(),
        generatedAt = Instant.parse("2026-05-14T19:00:00Z"),
        forDate = LocalDate.of(2026, 5, 14),
        hasEvents = hasEvents,
    )
}
