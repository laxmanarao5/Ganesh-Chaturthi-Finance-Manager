package com.vinayaka.finance.data.auth

import android.accounts.Account
import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.vinayaka.finance.BuildConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.tasks.await
import java.lang.ref.WeakReference

data class GoogleUser(val email: String, val name: String)

class AuthException(message: String) : Exception(message)

/**
 * Google Sign-In (Credential Manager) for identity, plus the Authorization API for a short-lived
 * access token that lets the app read and write the committee's spreadsheet.
 */
class GoogleAuth(private val app: Context) {
    private var activityRef = WeakReference<Activity>(null)
    private var launcher: ActivityResultLauncher<IntentSenderRequest>? = null
    private var pending: CompletableDeferred<Intent?>? = null

    val isConfigured: Boolean get() = BuildConfig.GOOGLE_WEB_CLIENT_ID.isNotBlank()

    fun attach(activity: Activity, launcher: ActivityResultLauncher<IntentSenderRequest>) {
        activityRef = WeakReference(activity)
        this.launcher = launcher
    }

    fun detach(activity: Activity) {
        if (activityRef.get() === activity) { activityRef.clear(); launcher = null }
    }

    /** Called by the activity when the consent screen returns. */
    fun onConsentResult(data: Intent?, ok: Boolean) {
        pending?.complete(if (ok) data else null)
        pending = null
    }

    suspend fun signIn(): GoogleUser {
        if (!isConfigured) throw AuthException("Google sign-in is not configured in this build yet.")
        val activity = activityRef.get() ?: throw AuthException("The app window is not ready. Try again.")
        val option = GetSignInWithGoogleOption.Builder(BuildConfig.GOOGLE_WEB_CLIENT_ID).build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        val credential = try {
            CredentialManager.create(activity).getCredential(activity, request).credential
        } catch (e: GetCredentialCancellationException) {
            throw AuthException("Sign-in was cancelled.")
        } catch (e: GetCredentialException) {
            throw AuthException(e.message ?: "Google sign-in failed.")
        }
        if (credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            val g = GoogleIdTokenCredential.createFrom(credential.data)
            val email = (g.email ?: g.uniqueId).lowercase()
            return GoogleUser(email, g.displayName ?: email)
        }
        throw AuthException("Unexpected sign-in response.")
    }

    /**
     * Access token for the Sheets API. Shows Google's consent screen the first time.
     * @param drive also ask for Google Drive access, which only an admin needs, to change who the Sheet is shared with.
     *   Google shows its consent screen again the first time this is used.
     */
    suspend fun accessToken(email: String? = null, drive: Boolean = false): String {
        val client = Identity.getAuthorizationClient(app)
        val scopes = if (drive) listOf(Scope(SHEETS_SCOPE), Scope(DRIVE_SCOPE)) else listOf(Scope(SHEETS_SCOPE))
        val denied = if (drive) "Permission to change the Sheet's sharing was not granted." else "Permission to use Google Sheets was not granted."
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(scopes)
            .apply { if (!email.isNullOrBlank()) setAccount(Account(email, "com.google")) }
            .build()
        val result = client.authorize(request).await()
        if (result.hasResolution()) {
            val intentSender = result.pendingIntent?.intentSender ?: throw AuthException("Google could not ask for permission.")
            val launcher = launcher ?: throw AuthException("The app window is not ready. Try again.")
            val wait = CompletableDeferred<Intent?>()
            pending = wait
            launcher.launch(IntentSenderRequest.Builder(intentSender).build())
            val data = wait.await() ?: throw AuthException(denied)
            return client.getAuthorizationResultFromIntent(data).accessToken
                ?: throw AuthException("Google did not return an access token.")
        }
        return result.accessToken ?: throw AuthException("Google did not return an access token.")
    }

    suspend fun signOut() {
        runCatching { CredentialManager.create(app).clearCredentialState(ClearCredentialStateRequest()) }
    }

    companion object {
        const val SHEETS_SCOPE = "https://www.googleapis.com/auth/spreadsheets"
        /** Full Drive access: the only scope that lets the app change sharing on a file it did not create. */
        const val DRIVE_SCOPE = "https://www.googleapis.com/auth/drive"
    }
}
