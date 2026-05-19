package app.clothescast.cast

import android.content.Context
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider

/**
 * Tells the Cast SDK which receiver app to look for. We target Google's
 * Default Media Receiver — it accepts arbitrary HTTP media URLs with
 * metadata (title, subtitle, image) and renders the image as a poster
 * while audio plays, which is exactly the shape we want for "outfit
 * picture + spoken forecast." No registration / fee / custom receiver
 * to host.
 *
 * The Cast SDK loads this class via reflection from the manifest
 * `OPTIONS_PROVIDER_CLASS_NAME` meta-data, so it must stay public with
 * a no-arg constructor.
 */
class CastOptionsProvider : OptionsProvider {
    override fun getCastOptions(context: Context): CastOptions =
        CastOptions.Builder()
            .setReceiverApplicationId(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)
            .build()

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null
}
