package app.clothescast.data

import com.google.firebase.appcheck.AppCheckProviderFactory
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory

/**
 * Release builds use Play Integrity for App Check attestation —
 * production-grade, no per-install token registration needed.
 * Debug-variant counterpart lives in `app/src/debug/...` and uses
 * the Firebase Debug provider.
 */
fun provideAppCheckProviderFactory(): AppCheckProviderFactory =
    PlayIntegrityAppCheckProviderFactory.getInstance()
