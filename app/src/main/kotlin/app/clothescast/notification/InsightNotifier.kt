package app.clothescast.notification

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.clothescast.MainActivity
import app.clothescast.R
import app.clothescast.core.domain.model.Insight
import app.clothescast.core.domain.model.OutfitSuggestion
import app.clothescast.ui.garment.outfitTopDefaults
import app.clothescast.ui.garment.renderOutfitBitmap
import app.clothescast.ui.garment.topDrawable

/**
 * Posts the daily insight as a system notification. Tapping the notification opens
 * MainActivity. POST_NOTIFICATIONS is checked before posting; on Android 13+ a missing
 * permission silently no-ops (the worker keeps the cached insight; the user will see it
 * in-app the next time they open the app).
 */
class InsightNotifier(private val context: Context) {

    fun notify(
        insight: Insight,
        prose: String,
        topColors: Map<OutfitSuggestion.Top, Long> = emptyMap(),
    ) {
        if (!NotificationPermission.isGranted(context)) return

        val tapIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_NAVIGATE_TO_TODAY, true)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            REQUEST_OPEN_APP,
            tapIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        // The small status-bar icon mirrors the recommended top (silhouette).
        // The large icon picks up the same top in full colour so the expanded
        // notification carries the same glanceable "what to wear today" cue the
        // Today screen's OutfitPreviewCard does — recoloured if the user has
        // customised the icon's fill.
        val top = insight.outfit?.top
        val notification = NotificationCompat.Builder(context, CHANNEL_DAILY_INSIGHT)
            .setSmallIcon(smallIconFor(top))
            .setLargeIcon(largeIconForTop(context, top, top?.let { topColors[it] }))
            .setContentTitle(context.getString(R.string.notification_daily_insight_title))
            .setContentText(prose)
            .setStyle(NotificationCompat.BigTextStyle().bigText(prose))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .build()

        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID_DAILY_INSIGHT, notification)
    }

    companion object {
        const val NOTIFICATION_ID_DAILY_INSIGHT = 1001
        private const val REQUEST_OPEN_APP = 100

        // The status-bar silhouette mirrors the recommended top so a glance at the
        // notification shade already says "sweater day" / "jacket day" before the
        // user reads anything. ic_notification_insight is itself a t-shirt silhouette,
        // so it doubles as both the TSHIRT icon and the null fallback (older cached
        // insights without an outfit). Each variant gets its own explicit branch so
        // the mapping is easy to scan and the t-shirt-vs-null sharing is obvious.
        internal fun smallIconFor(top: OutfitSuggestion.Top?): Int = when (top) {
            OutfitSuggestion.Top.SWEATER,
            OutfitSuggestion.Top.THIN_JACKET -> R.drawable.ic_notification_top_sweater
            OutfitSuggestion.Top.THICK_JACKET,
            OutfitSuggestion.Top.THICK_COAT,
            OutfitSuggestion.Top.PUFFER_JACKET -> R.drawable.ic_notification_top_thick_jacket
            OutfitSuggestion.Top.TSHIRT,
            OutfitSuggestion.Top.POLO -> R.drawable.ic_notification_insight
            null -> R.drawable.ic_notification_insight
        }

        /**
         * Renders the recommended top as a Bitmap for [NotificationCompat.Builder.setLargeIcon].
         * Reuses the full-colour `ic_outfit_tshirt` / `ic_outfit_sweater` /
         * `ic_outfit_thick_jacket` drawables from `OutfitPreviewCard` so the
         * notification visual matches the home-screen card — including any
         * user-picked colour override passed in via [customFillArgb]. Returns
         * null when [top] is missing (older cached payloads), letting the
         * system fall back to no large icon.
         */
        internal fun largeIconForTop(
            context: Context,
            top: OutfitSuggestion.Top?,
            customFillArgb: Long? = null,
        ): Bitmap? {
            if (top == null) return null
            val sizePx = context.resources.getDimensionPixelSize(android.R.dimen.notification_large_icon_width)
                .takeIf { it > 0 }
                ?: LARGE_ICON_FALLBACK_PX
            return renderOutfitBitmap(
                context = context,
                drawableRes = topDrawable(top),
                defaults = outfitTopDefaults.getValue(top),
                customFillArgb = customFillArgb,
                sizePx = sizePx,
            )
        }

        // Fallback large-icon size in raw pixels for the rare case where
        // `android.R.dimen.notification_large_icon_width` resolves to ≤0 (some
        // Robolectric configs and a handful of stripped-down OEM ROMs do this).
        // 192px ≈ 64dp on xxhdpi, which is the recommended large-icon target.
        private const val LARGE_ICON_FALLBACK_PX = 192
    }
}
