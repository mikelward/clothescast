package app.clothescast.data

import android.content.Context
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import app.clothescast.diag.DiagLog
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.AuthCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthRecentLoginRequiredException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await

/** A Google-signed-in Firebase user, as the UI shows it. */
data class SignedInGoogleAccount(val email: String?)

/** Outcome of a sign-in or delete attempt, for the UI to explain. */
sealed interface GoogleAccountResult {
    data object Success : GoogleAccountResult
    /** The user backed out of the account picker. Nothing to say. */
    data object Canceled : GoogleAccountResult
    /** No Google account on the device to pick. */
    data object NoAccount : GoogleAccountResult
    /** Anything else; the UI shows a generic failure. */
    data object Failed : GoogleAccountResult
}

/**
 * Optional Sign in with Google, the identity the developer-funded paths
 * (the shared Gemini voice, the Google forecast proxy) require — see SPEC.md
 * → "Accounts and the developer-funded paths".
 *
 * A plain sign-in: the Google credential replaces whatever Firebase user was
 * current (an anonymous one from older builds), never linked to it, so the
 * uid is the Google account's own on every device. [available] is false on
 * builds without Firebase or without the web client ID that
 * `google-services.json` generates, and every entry point is then a no-op.
 */
class GoogleAccount(
    private val firebaseAvailable: () -> Boolean,
    private val webClientId: String?,
    private val auth: () -> FirebaseAuth = { FirebaseAuth.getInstance() },
    private val credentialManager: (Context) -> CredentialManager = { CredentialManager.create(it) },
) {
    val available: Boolean
        get() = !webClientId.isNullOrBlank() && firebaseAvailable()

    private val _account = MutableStateFlow<SignedInGoogleAccount?>(null)

    /** The signed-in Google account, or null when signed out (or anonymous). */
    val account: StateFlow<SignedInGoogleAccount?> = _account.asStateFlow()

    /** Starts tracking Firebase's current user. Call once, from Application.onCreate. */
    fun start() {
        if (!available) return
        auth().addAuthStateListener { _account.value = googleAccountOf(it.currentUser) }
    }

    /** Read at the moment of use (the worker), not cached from [account]. */
    fun isSignedIn(): Boolean = available && googleAccountOf(auth().currentUser) != null

    /**
     * Shows the Sign in with Google sheet and signs Firebase in with the
     * result. [activityContext] must be an Activity: Credential Manager
     * anchors its UI to it.
     */
    suspend fun signIn(activityContext: Context): GoogleAccountResult {
        val clientId = webClientId
        if (!available || clientId == null) return GoogleAccountResult.Failed
        return try {
            val credential = googleCredential(activityContext, clientId) ?: return GoogleAccountResult.Failed
            auth().signInWithCredential(credential).await()
            GoogleAccountResult.Success
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            signInFailure(e)
        }
    }

    /** Signs out of Firebase and clears Credential Manager's remembered choice. */
    suspend fun signOut(context: Context) {
        if (!available) return
        auth().signOut()
        clearCredentialState(context)
    }

    /**
     * Deletes the Firebase account (Play's account-deletion requirement).
     * Its email, name, photo URL and custom claims go with it; the day's
     * quota count survives until its TTL (SPEC.md). Firebase refuses a delete
     * once the sign-in isn't recent, so that case shows the Sign in with
     * Google sheet, reauthenticates and deletes, rather than leaving the
     * user to find a sign-out and sign-in first. [activityContext] must be
     * an Activity, as for [signIn].
     */
    suspend fun deleteAccount(activityContext: Context): GoogleAccountResult {
        val user = auth().currentUser ?: return GoogleAccountResult.Success
        return try {
            try {
                user.delete().await()
            } catch (e: FirebaseAuthRecentLoginRequiredException) {
                DiagLog.i(TAG, "Account delete needs a recent sign-in; reauthenticating")
                val clientId = webClientId ?: return GoogleAccountResult.Failed
                val credential = googleCredential(activityContext, clientId) ?: return GoogleAccountResult.Failed
                user.reauthenticate(credential).await()
                user.delete().await()
            }
            clearCredentialState(activityContext)
            GoogleAccountResult.Success
        } catch (e: CancellationException) {
            throw e
        } catch (e: GetCredentialException) {
            signInFailure(e)
        } catch (e: Exception) {
            DiagLog.w(TAG, e, "Account delete failed")
            GoogleAccountResult.Failed
        }
    }

    /**
     * Shows the Sign in with Google sheet and returns its Firebase credential,
     * or null for an unexpected credential type. Throws what Credential
     * Manager throws (cancel, no account) for the caller to map.
     */
    private suspend fun googleCredential(activityContext: Context, clientId: String): AuthCredential? {
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(GetSignInWithGoogleOption.Builder(clientId).build())
            .build()
        val credential = credentialManager(activityContext)
            .getCredential(activityContext, request)
            .credential
        if (credential !is CustomCredential ||
            credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
        ) {
            DiagLog.w(TAG, "Sign-in returned an unexpected credential type")
            return null
        }
        val idToken = GoogleIdTokenCredential.createFrom(credential.data).idToken
        return GoogleAuthProvider.getCredential(idToken, null)
    }

    private suspend fun clearCredentialState(context: Context) {
        try {
            credentialManager(context).clearCredentialState(ClearCredentialStateRequest())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Firebase is already signed out; a stale remembered choice only
            // pre-selects the account next time.
            DiagLog.w(TAG, e, "Clearing credential state failed")
        }
    }

    companion object {
        private const val TAG = "GoogleAccount"

        internal fun googleAccountOf(user: FirebaseUser?): SignedInGoogleAccount? {
            if (user == null || user.isAnonymous) return null
            if (user.providerData.none { it.providerId == GoogleAuthProvider.PROVIDER_ID }) return null
            return SignedInGoogleAccount(email = user.email)
        }

        /** Maps a sign-in failure to what the UI says. Logs all but a cancel. */
        internal fun signInFailure(e: Exception): GoogleAccountResult = when (e) {
            is GetCredentialCancellationException -> GoogleAccountResult.Canceled
            is NoCredentialException -> {
                DiagLog.i(TAG, "Sign-in found no Google account")
                GoogleAccountResult.NoAccount
            }
            is GetCredentialException -> {
                DiagLog.w(TAG, e, "Sign-in failed: %s", e.type)
                GoogleAccountResult.Failed
            }
            else -> {
                DiagLog.w(TAG, e, "Sign-in failed")
                GoogleAccountResult.Failed
            }
        }
    }
}
