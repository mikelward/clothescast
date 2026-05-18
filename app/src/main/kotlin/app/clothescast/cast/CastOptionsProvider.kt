package app.clothescast.cast

import android.content.Context
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider

/**
 * Tells the Cast SDK which receiver app to look for. We target Google's
 * Default Media Receiver — it accepts any HTTP media URL with metadata
 * (title, subtitle, image) and renders the image as a poster while the
 * audio plays, which is exactly the shape of "outfit picture + spoken
 * insight" we want. No custom Web Receiver to register or host.
 *
 * The Cast SDK loads this class via reflection from the manifest
 * `OPTIONS_PROVIDER_CLASS_NAME` meta-data, so the class must stay public
 * with a no-arg constructor.
 */
class CastOptionsProvider : OptionsProvider {
    override fun getCastOptions(context: Context): CastOptions =
        CastOptions.Builder()
            .setReceiverApplicationId(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)
            .build()

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null
}
