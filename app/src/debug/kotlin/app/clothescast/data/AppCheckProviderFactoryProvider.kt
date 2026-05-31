package app.clothescast.data

import com.google.firebase.appcheck.AppCheckProviderFactory
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory

/**
 * Debug builds use the Firebase Debug provider — register the debug
 * token (printed once per install in Logcat under the
 * `com.google.firebase.appcheck` tag) in the Firebase Console under
 * App Check → Apps → Manage debug tokens. Release-variant counterpart
 * lives in `app/src/release/...` and uses Play Integrity.
 */
fun provideAppCheckProviderFactory(): AppCheckProviderFactory =
    DebugAppCheckProviderFactory.getInstance()
