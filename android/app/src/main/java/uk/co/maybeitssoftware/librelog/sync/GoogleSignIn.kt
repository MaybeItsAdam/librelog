package uk.co.maybeitssoftware.librelog.sync

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import uk.co.maybeitssoftware.librelog.BuildConfig

/**
 * Sign in with Google through Credential Manager: an ID token for the Web
 * OAuth client [BuildConfig.GOOGLE_WEB_CLIENT_ID], which Supabase then
 * exchanges for a session (SPEC.md §12.3). Google is asked for the token with
 * the SHA-256 of a nonce and Supabase is given the nonce itself, so a token
 * can't be replayed. docs/google-sign-in.md has the console setup.
 */
object GoogleSignIn {
    /** Whether this build has a client id; without one the button is hidden. */
    val isConfigured: Boolean get() = BuildConfig.GOOGLE_WEB_CLIENT_ID.isNotBlank()

    class Token(val idToken: String, val rawNonce: String)

    /**
     * Shows Google's account picker. Null when the person backed out.
     * [context] must be an activity, which the picker is drawn over.
     */
    suspend fun request(context: Context): Token? {
        val rawNonce = newNonce()
        val option = GetSignInWithGoogleOption.Builder(BuildConfig.GOOGLE_WEB_CLIENT_ID)
            .setNonce(sha256Hex(rawNonce))
            .build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        val credential = try {
            CredentialManager.create(context).getCredential(context, request).credential
        } catch (_: GetCredentialCancellationException) {
            return null
        } catch (_: NoCredentialException) {
            throw GoogleSignInException("There's no Google account on this device to sign in with.")
        } catch (error: GetCredentialException) {
            throw GoogleSignInException(error.errorMessage?.toString() ?: "Couldn't sign in with Google.")
        }
        if (credential !is CustomCredential || credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            throw GoogleSignInException("Google answered with something other than an ID token.")
        }
        return Token(GoogleIdTokenCredential.createFrom(credential.data).idToken, rawNonce)
    }

    /** 32 random bytes as hex. */
    fun newNonce(random: SecureRandom = SecureRandom()): String =
        ByteArray(32).also(random::nextBytes).joinToString("") { "%02x".format(Locale.ROOT, it) }

    /** What Google is given in place of the nonce: its SHA-256, as lowercase hex. */
    fun sha256Hex(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(Locale.ROOT, it) }
}

/** Why Google's sheet gave no token, in words fit to show. */
class GoogleSignInException(message: String) : Exception(message)
