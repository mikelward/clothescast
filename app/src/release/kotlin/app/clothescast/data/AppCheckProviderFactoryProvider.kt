package app.clothescast.data

import android.content.Context
import com.google.firebase.appcheck.AppCheckProviderFactory
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory

/**
 * Release builds use Play Integrity for App Check attestation —
 * production-grade, no per-install token registration needed.
 * Debug-variant counterpart lives in `app/src/debug/...` and uses
 * the Firebase Debug provider.
 *
 * Context is accepted for signature parity with the debug variant
 * (which uses it to pre-populate the SDK's debug-secret store);
 * Play Integrity doesn't need it.
 */
@Suppress("UNUSED_PARAMETER")
fun provideAppCheckProviderFactory(context: Context): AppCheckProviderFactory =
    PlayIntegrityAppCheckProviderFactory.getInstance()
