package app.clothescast.notification

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.clothescast.R
import app.clothescast.core.domain.model.BandClause
import app.clothescast.core.domain.model.ForecastPeriod
import app.clothescast.core.domain.model.Insight
import app.clothescast.core.domain.model.InsightSummary
import app.clothescast.core.domain.model.OutfitSuggestion
import app.clothescast.core.domain.model.TemperatureBand
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDate

/**
 * Robolectric coverage for the morning daily-insight notification path:
 *  - the right channel, title, body, big-text style, and tap-to-open intent;
 *  - permission gating on API 33+;
 *  - the outfit-driven small-icon mapping.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class InsightNotifierTest {
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
    fun `notify posts to the daily-insight channel with title body and big-text style`() {
        InsightNotifier(context).notify(sampleInsight(), "Today will be mild. Wear a t-shirt.")

        val posted = shadowOf(notificationManager).allNotifications
        posted shouldHaveSize 1
        val n = posted.single()

        n.channelId shouldBe "scheduled_insight_v1"
        n.extras.getString(NotificationCompat.EXTRA_TITLE) shouldBe
            context.getString(R.string.notification_daily_insight_title)
        n.extras.getString(NotificationCompat.EXTRA_TEXT) shouldBe "Today will be mild. Wear a t-shirt."
        n.extras.getString(NotificationCompat.EXTRA_BIG_TEXT) shouldBe "Today will be mild. Wear a t-shirt."
        n.contentIntent.shouldNotBeNull()
    }

    @Test
    fun `notify is a no-op when POST_NOTIFICATIONS is denied`() {
        // Robolectric auto-grants manifest-declared permissions; revoke at runtime to
        // exercise the POST_NOTIFICATIONS gate that wraps every notify() call on
        // API 33+.
        shadowOf(application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        check(
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_DENIED,
        )

        InsightNotifier(context).notify(sampleInsight(), "ignored")

        shadowOf(notificationManager).allNotifications shouldHaveSize 0
    }

    @Test
    fun `smallIconFor maps tops to their dedicated drawables with a t-shirt fallback`() {
        val sweater = R.drawable.ic_notification_top_sweater
        val thick = R.drawable.ic_notification_top_thick_jacket
        val tshirt = R.drawable.ic_notification_insight

        InsightNotifier.smallIconFor(OutfitSuggestion.Top.SWEATER) shouldBe sweater
        InsightNotifier.smallIconFor(OutfitSuggestion.Top.THIN_JACKET) shouldBe sweater
        InsightNotifier.smallIconFor(OutfitSuggestion.Top.THICK_JACKET) shouldBe thick
        InsightNotifier.smallIconFor(OutfitSuggestion.Top.THICK_COAT) shouldBe thick
        InsightNotifier.smallIconFor(OutfitSuggestion.Top.PUFFER_JACKET) shouldBe thick
        InsightNotifier.smallIconFor(OutfitSuggestion.Top.TSHIRT) shouldBe tshirt
        InsightNotifier.smallIconFor(OutfitSuggestion.Top.POLO) shouldBe tshirt
        InsightNotifier.smallIconFor(null) shouldBe tshirt
    }

    @Test
    fun `largeIconForTop returns null when the outfit is absent`() {
        InsightNotifier.largeIconForTop(context, top = null) shouldBe null
    }

    @Test
    fun `largeIconForTop renders the recommended top to a bitmap`() {
        val bitmap = InsightNotifier.largeIconForTop(context, OutfitSuggestion.Top.SWEATER)
        bitmap.shouldNotBeNull()
        check(bitmap.width > 0 && bitmap.height > 0) { "expected a non-empty bitmap" }
    }

    @Test
    fun `largeIconForTop composites the gloves overlay when the hands slot is set`() {
        // Exercises the hands != null branch of renderTopWithHandsBitmap — it
        // reads outfitHandsDefaults.getValue(GLOVES), so a missing entry would
        // throw here. The overlaid pixels are pixel-verified by the NATIVE
        // cast-card snapshot; this just guards the notification wiring.
        val bitmap = InsightNotifier.largeIconForTop(
            context,
            OutfitSuggestion.Top.THICK_COAT,
            hands = OutfitSuggestion.Hands.GLOVES,
        )
        bitmap.shouldNotBeNull()
        check(bitmap.width > 0 && bitmap.height > 0) { "expected a non-empty bitmap" }
    }

    private fun sampleInsight(
        outfit: OutfitSuggestion? = OutfitSuggestion(
            top = OutfitSuggestion.Top.TSHIRT,
            bottom = OutfitSuggestion.Bottom.LONG_PANTS,
        ),
    ) = Insight(
        summary = InsightSummary(
            period = ForecastPeriod.TODAY,
            band = BandClause(TemperatureBand.MILD, TemperatureBand.MILD),
        ),
        recommendedItems = emptyList(),
        generatedAt = Instant.parse("2026-05-14T07:00:00Z"),
        forDate = LocalDate.of(2026, 5, 14),
        outfit = outfit,
    )
}
