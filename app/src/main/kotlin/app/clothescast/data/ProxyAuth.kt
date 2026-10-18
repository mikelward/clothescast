package app.clothescast.data

import app.clothescast.diag.DiagLog
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import io.ktor.client.request.HttpRequestBuilder
import kotlinx.coroutines.tasks.await

/**
 * Adds what the ClothesCast Cloud Functions proxies require of a caller: a
 * Firebase App Check token (a genuine app build) and the signed-in user's
 * Firebase ID token. Throws when there is no signed-in user or a token
 * can't be had; the forecast path then leaves Google out of the blend. An
 * account deleted or disabled server-side is signed out first, as the TTS
 * planner does, so the app stops treating the proxy as reachable.
 */
internal suspend fun authorizeForProxy(
    builder: HttpRequestBuilder,
    appCheck: FirebaseAppCheck = FirebaseAppCheck.getInstance(),
    auth: FirebaseAuth = FirebaseAuth.getInstance(),
) {
    builder.headers.append("X-Firebase-AppCheck", appCheck.getAppCheckToken(false).await().token)
    val user = auth.currentUser ?: error("Not signed in")
    val idToken = try {
        user.getIdToken(false).await().token ?: error("Signed-in user returned no ID token")
    } catch (e: FirebaseAuthInvalidUserException) {
        DiagLog.w("ProxyAuth", e, "Signed-in user is no longer valid; signing out")
        auth.signOut()
        throw e
    }
    builder.headers.append("Authorization", "Bearer $idToken")
}
