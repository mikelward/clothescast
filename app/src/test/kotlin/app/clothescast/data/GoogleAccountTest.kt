package app.clothescast.data

import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialResponse
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialUnknownException
import androidx.credentials.exceptions.NoCredentialException
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.gms.tasks.Tasks
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthRecentLoginRequiredException
import com.google.firebase.auth.FirebaseUser
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class GoogleAccountTest {

    @Test
    fun `backing out of the picker is a cancel, not a failure`() {
        GoogleAccount.signInFailure(GetCredentialCancellationException()) shouldBe GoogleAccountResult.Canceled
    }

    @Test
    fun `no Google account on the device is its own outcome`() {
        GoogleAccount.signInFailure(NoCredentialException()) shouldBe GoogleAccountResult.NoAccount
    }

    @Test
    fun `any other failure is a generic one`() {
        GoogleAccount.signInFailure(GetCredentialUnknownException()) shouldBe GoogleAccountResult.Failed
        GoogleAccount.signInFailure(IllegalStateException("boom")) shouldBe GoogleAccountResult.Failed
    }

    @Test
    fun `without a web client ID sign-in is unavailable`() {
        GoogleAccount(firebaseAvailable = { true }, webClientId = null).available shouldBe false
        GoogleAccount(firebaseAvailable = { true }, webClientId = " ").available shouldBe false
        GoogleAccount(firebaseAvailable = { false }, webClientId = "id.apps.googleusercontent.com").available shouldBe false
        GoogleAccount(firebaseAvailable = { true }, webClientId = "id.apps.googleusercontent.com").available shouldBe true
    }

    @Test
    fun `an unavailable account reports signed out without touching Firebase`() {
        val account = GoogleAccount(
            firebaseAvailable = { false },
            webClientId = null,
            auth = { error("Firebase must not be read when sign-in is unavailable") },
        )
        account.isSignedIn() shouldBe false
        account.start()
        account.account.value shouldBe null
    }

    private fun staleUser(): FirebaseUser = mockk(relaxed = true) {
        every { delete() } returnsMany listOf(
            Tasks.forException(FirebaseAuthRecentLoginRequiredException("ERROR_REQUIRES_RECENT_LOGIN", "stale")),
            Tasks.forResult(null),
        )
        every { reauthenticate(any()) } returns Tasks.forResult(null)
    }

    private fun accountWith(user: FirebaseUser, credentials: CredentialManager) = GoogleAccount(
        firebaseAvailable = { true },
        webClientId = "id.apps.googleusercontent.com",
        auth = { mockk { every { currentUser } returns user } },
        credentialManager = { credentials },
    )

    @Test
    fun `a delete needing a recent sign-in reauthenticates and deletes`() = runTest {
        val user = staleUser()
        val credentials = mockk<CredentialManager>(relaxed = true) {
            coEvery { getCredential(any(), any<androidx.credentials.GetCredentialRequest>()) } returns
                GetCredentialResponse(GoogleIdTokenCredential.Builder().setId("alice").setIdToken(FAKE_ID_TOKEN).build())
        }

        accountWith(user, credentials).deleteAccount(ApplicationProvider.getApplicationContext()) shouldBe
            GoogleAccountResult.Success

        verify(exactly = 1) { user.reauthenticate(any()) }
        verify(exactly = 2) { user.delete() }
    }

    @Test
    fun `backing out of the reauthentication keeps the account`() = runTest {
        val user = staleUser()
        val credentials = mockk<CredentialManager>(relaxed = true) {
            coEvery { getCredential(any(), any<androidx.credentials.GetCredentialRequest>()) } throws
                GetCredentialCancellationException()
        }

        accountWith(user, credentials).deleteAccount(ApplicationProvider.getApplicationContext()) shouldBe
            GoogleAccountResult.Canceled

        verify(exactly = 1) { user.delete() }
    }

    private companion object {
        // A well-formed but unsigned JWT for a placeholder account: the
        // credential builder checks the shape, Firebase is mocked.
        const val FAKE_ID_TOKEN = "eyJhbGciOiAiUlMyNTYiLCAidHlwIjogIkpXVCJ9.eyJzdWIiOiAiMSIsICJlbWFpbCI6ICJhbGljZUBleGFtcGxlLmNvbSJ9.c2ln"
    }
}
