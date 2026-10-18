package app.clothescast.notification

import android.Manifest
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.clothescast.MainActivity
import app.clothescast.R
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class GeminiSignInNotifierTest {
    private val application: Application = ApplicationProvider.getApplicationContext()
    private val context: Context = application
    private val notificationManager: NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    @Before
    fun grantNotificationPermission() {
        shadowOf(application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    @Test
    fun `needsNotice only for a signed-out Gemini user without a key, once`() {
        val base = { selected: Boolean, ownKey: Boolean, configured: Boolean, signedIn: Boolean, shown: Boolean ->
            GeminiSignInNotifier.needsNotice(true, selected, ownKey, configured, signedIn, shown)
        }
        base(true, false, true, false, false) shouldBe true
        // A run where the phone stays silent keeps the notice for one that speaks.
        GeminiSignInNotifier.needsNotice(false, true, false, true, false, false) shouldBe false
        // Each condition alone turns it off.
        base(false, false, true, false, false) shouldBe false // device voice picked
        base(true, true, true, false, false) shouldBe false // own key: Gemini still works
        base(true, false, false, false, false) shouldBe false // build has no shared voice
        base(true, false, true, true, false) shouldBe false // signed in: Gemini still works
        base(true, false, true, false, true) shouldBe false // already told
    }

    @Test
    fun `notify posts the notice to the scheduled channel`() {
        GeminiSignInNotifier(context).notify() shouldBe true

        val n = shadowOf(notificationManager).allNotifications.single()
        n.channelId shouldBe "scheduled_insight_v2"
        n.extras.getString(NotificationCompat.EXTRA_TITLE) shouldBe
            context.getString(R.string.notify_gemini_sign_in_title)
        n.extras.getString(NotificationCompat.EXTRA_TEXT) shouldBe
            context.getString(R.string.notify_gemini_sign_in_text)
    }

    @Test
    fun `tapping the notice opens Voice settings`() {
        GeminiSignInNotifier(context).notify() shouldBe true

        val n = shadowOf(notificationManager).allNotifications.single()
        val intent = shadowOf(n.contentIntent).savedIntent
        intent.data.toString() shouldBe MainActivity.DEEP_LINK_VOICE
    }

    @Test
    fun `notify reports false when the app's notifications are off`() {
        shadowOf(notificationManager).setNotificationsEnabled(false)

        GeminiSignInNotifier(context).notify() shouldBe false

        shadowOf(notificationManager).allNotifications shouldHaveSize 0
    }

    @Test
    fun `notify reports false when the scheduled channel is blocked`() {
        // As if the user switched the channel off in system settings.
        notificationManager.createNotificationChannel(
            NotificationChannel(CHANNEL_SCHEDULED_INSIGHT, "Scheduled", NotificationManager.IMPORTANCE_NONE),
        )

        GeminiSignInNotifier(context).notify() shouldBe false
    }

    @Test
    fun `notify reports false when POST_NOTIFICATIONS is denied, so it is retried later`() {
        shadowOf(application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)

        GeminiSignInNotifier(context).notify() shouldBe false

        shadowOf(notificationManager).allNotifications shouldHaveSize 0
    }
}
