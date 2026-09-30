package com.example.data.auth

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.util.Log
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialCustomException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthRecentLoginRequiredException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await

data class AuthUser(
    val uid: String,
    val email: String?,
    val displayName: String?,
    val photoUrl: String?,
    val hasPasswordProvider: Boolean = false,
    val hasGoogleProvider: Boolean = false,
    val isNewUser: Boolean = false
)

sealed class GoogleSignInAvailability {
    object Available : GoogleSignInAvailability()
    data class Unavailable(
        val reason: String,
        val isSha1Conflict: Boolean = false,
        val suggestedAction: String = "Please sign in with Email & Password below."
    ) : GoogleSignInAvailability()
}

class GoogleSignInCancelledException(message: String = "Google Sign-In was dismissed or cancelled.") : Exception(message)

class SignInRepository(
    private val auth: FirebaseAuth = FirebaseAuth.getInstance()
) {
    companion object {
        private const val TAG = "SignInRepository"
        const val FALLBACK_WEB_CLIENT_ID = "645499359222-t6kmdj2nmpu6u0tvp80gccp538bf8if0.apps.googleusercontent.com"
    }

    private val _currentUser = MutableStateFlow<AuthUser?>(getCurrentAuthUser())
    val currentUser: StateFlow<AuthUser?> = _currentUser.asStateFlow()

    private val _lastGoogleSignInStatus = MutableStateFlow<GoogleSignInAvailability?>(null)
    val lastGoogleSignInStatus: StateFlow<GoogleSignInAvailability?> = _lastGoogleSignInStatus.asStateFlow()

    init {
        try {
            Log.i(TAG, "Initializing SignInRepository and setting up AuthStateListener")
            auth.addAuthStateListener { firebaseAuth ->
                val user = mapFirebaseUser(firebaseAuth.currentUser)
                _currentUser.value = user
                Log.d(TAG, "AuthStateListener fired: currentUser = ${user?.email ?: "null"}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize AuthStateListener: ${e.message}", e)
        }
    }

    fun getCurrentAuthUser(): AuthUser? {
        return try {
            mapFirebaseUser(auth.currentUser)
        } catch (e: Exception) {
            Log.w(TAG, "Error getting current user: ${e.message}")
            null
        }
    }

    fun isUserSignedIn(): Boolean = auth.currentUser != null

    private fun mapFirebaseUser(user: FirebaseUser?, isNewUser: Boolean = false): AuthUser? {
        return user?.let {
            val providers = it.providerData.map { p -> p.providerId }
            val hasPassword = providers.contains(EmailAuthProvider.PROVIDER_ID)
            val hasGoogle = providers.contains(GoogleAuthProvider.PROVIDER_ID)
            AuthUser(
                uid = it.uid,
                email = it.email,
                displayName = it.displayName ?: it.email?.substringBefore("@"),
                photoUrl = it.photoUrl?.toString(),
                hasPasswordProvider = hasPassword,
                hasGoogleProvider = hasGoogle,
                isNewUser = isNewUser
            )
        }
    }

    private fun Context.findActivity(): Activity? {
        var currentContext: Context? = this
        while (currentContext is ContextWrapper) {
            if (currentContext is Activity) {
                return currentContext
            }
            currentContext = currentContext.baseContext
        }
        return null
    }

    /**
     * Inspects device Google Play Services availability and Web Client ID configuration safely
     * using PackageManager without initializing background GoogleApiManager broker loops.
     */
     fun checkGoogleSignInAvailability(context: Context): GoogleSignInAvailability {
        try {
            Log.d(TAG, "[Google Auth Check] Checking Google Play Services package on device...")
            val packageManager = context.packageManager
            val isGmsInstalled = try {
                val pInfo = packageManager.getPackageInfo("com.google.android.gms", 0)
                pInfo.applicationInfo?.enabled == true
            } catch (e: Exception) {
                false
            }

            if (!isGmsInstalled) {
                Log.w(TAG, "[Google Auth Check] com.google.android.gms package not found or disabled on device.")
                val status = GoogleSignInAvailability.Unavailable(
                    reason = "Google Play Services is not installed or enabled on this device.",
                    suggestedAction = "Sign in directly with your Email & Password below."
                )
                _lastGoogleSignInStatus.value = status
                return status
            }

            val webClientId = getWebClientId(context)
            if (webClientId.isBlank()) {
                Log.w(TAG, "[Google Auth Check] Web Client ID is missing in configuration.")
                val status = GoogleSignInAvailability.Unavailable(
                    reason = "Google Web Client ID is not configured.",
                    suggestedAction = "Please use Email & Password sign-in below."
                )
                _lastGoogleSignInStatus.value = status
                return status
            }

            Log.i(TAG, "[Google Auth Check] Google Play Services and Web Client ID are valid. Ready for Credential Manager.")
            val status = GoogleSignInAvailability.Available
            _lastGoogleSignInStatus.value = status
            return status
        } catch (e: Exception) {
            Log.e(TAG, "[Google Auth Check] Exception during availability check: ${e.message}", e)
            val status = GoogleSignInAvailability.Unavailable(
                reason = "Google Sign-In check failed: ${e.message}",
                suggestedAction = "Please use Email & Password sign-in below."
            )
            _lastGoogleSignInStatus.value = status
            return status
        }
    }

    private fun getWebClientId(context: Context, serverClientId: String? = null): String {
        if (!serverClientId.isNullOrBlank()) return serverClientId
        val defaultWebClientIdRes = context.resources.getIdentifier("default_web_client_id", "string", context.packageName)
        return if (defaultWebClientIdRes != 0) {
            try {
                val id = context.getString(defaultWebClientIdRes)
                // Filter out non-web client ID or appinvite client ID if encountered
                if (id.isNotBlank() && !id.contains("0qrb7o3aqo89ogkeiubrdflptpg6t384")) {
                    id
                } else {
                    Log.w(TAG, "Ignoring non-web client ID from resources ($id). Using FALLBACK_WEB_CLIENT_ID: $FALLBACK_WEB_CLIENT_ID")
                    FALLBACK_WEB_CLIENT_ID
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to read default_web_client_id resource: ${e.message}")
                FALLBACK_WEB_CLIENT_ID
            }
        } else {
            FALLBACK_WEB_CLIENT_ID
        }
    }

    /**
     * Executes Google Sign-In using Android Credential Manager with robust fallback and logging.
     */
    suspend fun signInWithGoogle(context: Context, serverClientId: String? = null): Result<AuthUser> {
        val webClientId = getWebClientId(context, serverClientId)
        Log.i(TAG, "==================================================")
        Log.i(TAG, "[Google Auth Flow] Initiating Google Sign-In via Credential Manager")
        Log.i(TAG, "[Google Auth Flow] Package Name: ${context.packageName}")
        Log.i(TAG, "[Google Auth Flow] Target Web Client ID: $webClientId")
        Log.i(TAG, "==================================================")

        return try {
            val activityContext = context.findActivity() ?: context
            val credentialManager = CredentialManager.create(activityContext)

            // Step 1: Request credential using GetSignInWithGoogleOption specifically for explicit button clicks.
            // If any non-cancellation exception occurs, retry with GetGoogleIdOption fallback.
            val result: GetCredentialResponse = try {
                val signInWithGoogleOption = GetSignInWithGoogleOption.Builder(webClientId)
                    .build()

                val request = GetCredentialRequest.Builder()
                    .addCredentialOption(signInWithGoogleOption)
                    .build()

                Log.d(TAG, "[Google Auth Flow] Step 1: Requesting credentials with GetSignInWithGoogleOption...")
                credentialManager.getCredential(
                    request = request,
                    context = activityContext
                )
            } catch (e: GetCredentialCancellationException) {
                // If it's a genuine user cancellation, rethrow so outer handler treats it cleanly
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "[Google Auth Flow] GetSignInWithGoogleOption failed (${e::class.java.simpleName}: ${e.message}). Retrying with GetGoogleIdOption fallback...")
                val googleIdOption = GetGoogleIdOption.Builder()
                    .setFilterByAuthorizedAccounts(false)
                    .setAutoSelectEnabled(false)
                    .setServerClientId(webClientId)
                    .build()

                val fallbackRequest = GetCredentialRequest.Builder()
                    .addCredentialOption(googleIdOption)
                    .build()

                credentialManager.getCredential(
                    request = fallbackRequest,
                    context = activityContext
                )
            }

            Log.d(TAG, "[Google Auth Flow] CredentialManager response received successfully.")
            val credential = result.credential
            Log.d(TAG, "[Google Auth Flow] Credential received class: ${credential::class.java.name}")

            var idToken: String? = null
            var userGoogleEmail: String? = null

            when {
                // 1. Direct instance of GoogleIdTokenCredential
                credential is GoogleIdTokenCredential -> {
                    Log.i(TAG, "[Google Auth Flow] Credential is direct GoogleIdTokenCredential for: ${credential.id}")
                    idToken = credential.idToken
                    userGoogleEmail = credential.id
                }

                // 2. CustomCredential with Google ID token type
                credential is CustomCredential && (
                    credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL ||
                    credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_SIWG_CREDENTIAL ||
                    credential.type.contains("google", ignoreCase = true)
                ) -> {
                    Log.i(TAG, "[Google Auth Flow] Credential is CustomCredential with type: ${credential.type}")
                    try {
                        val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(credential.data)
                        Log.i(TAG, "[Google Auth Flow] Successfully parsed GoogleIdTokenCredential for: ${googleIdTokenCredential.id}")
                        idToken = googleIdTokenCredential.idToken
                        userGoogleEmail = googleIdTokenCredential.id
                    } catch (e: Exception) {
                        Log.w(TAG, "[Google Auth Flow] GoogleIdTokenCredential.createFrom failed: ${e.message}. Inspecting bundle keys directly...")
                        val bundle = credential.data
                        idToken = bundle.getString("com.google.android.libraries.identity.googleid.BUNDLE_KEY_ID_TOKEN")
                            ?: bundle.getString("id_token")
                            ?: bundle.getString("idToken")
                        userGoogleEmail = bundle.getString("com.google.android.libraries.identity.googleid.BUNDLE_KEY_ID")
                            ?: bundle.getString("id")
                            ?: bundle.getString("email")
                        if (idToken != null) {
                            Log.i(TAG, "[Google Auth Flow] Extracted idToken from bundle fallback")
                        } else {
                            val keys = bundle.keySet()?.joinToString(", ") ?: "none"
                            Log.e(TAG, "[Google Auth Flow] Failed to extract token. Bundle keys present: $keys")
                        }
                    }
                }

                // 3. Any other CustomCredential type
                credential is CustomCredential -> {
                    Log.w(TAG, "[Google Auth Flow] Received CustomCredential with unexpected type: ${credential.type}")
                    val bundle = credential.data
                    idToken = bundle.getString("com.google.android.libraries.identity.googleid.BUNDLE_KEY_ID_TOKEN")
                        ?: bundle.getString("id_token")
                        ?: bundle.getString("idToken")
                    userGoogleEmail = bundle.getString("com.google.android.libraries.identity.googleid.BUNDLE_KEY_ID")
                        ?: bundle.getString("id")
                        ?: bundle.getString("email")
                    if (idToken != null) {
                        Log.i(TAG, "[Google Auth Flow] Extracted token from unexpected CustomCredential: ${credential.type}")
                    }
                }

                // 4. Non-custom or unsupported credential
                else -> {
                    Log.e(TAG, "[Google Auth Flow] Unsupported non-custom credential type: ${credential::class.java.name}")
                }
            }

            if (!idToken.isNullOrBlank()) {
                Log.d(TAG, "[Google Auth Flow] Valid Google ID token obtained for $userGoogleEmail. Authenticating with Firebase Auth...")
                val authCredential = GoogleAuthProvider.getCredential(idToken, null)
                val authResult = auth.signInWithCredential(authCredential).await()
                val isNew = authResult.additionalUserInfo?.isNewUser == true
                val user = mapFirebaseUser(authResult.user, isNewUser = isNew)
                    ?: throw Exception("Firebase user mapping returned null after Google sign-in.")

                Log.i(TAG, "[Google Auth Flow] SUCCESS: Signed in as ${user.email} (UID: ${user.uid}, isNewUser=$isNew, hasPassword=${user.hasPasswordProvider})")
                _lastGoogleSignInStatus.value = GoogleSignInAvailability.Available
                Result.success(user)
            } else {
                val credTypeName = (credential as? CustomCredential)?.type ?: credential::class.java.name
                val bundleKeys = (credential as? CustomCredential)?.data?.keySet()?.joinToString(", ") ?: "no_data_bundle"
                val errorMsg = "Could not retrieve Google ID token from selected account ($credTypeName, bundle keys: $bundleKeys). Please check Google Play Services or use Email & Password."
                Log.e(TAG, "[Google Auth Flow] ERROR: $errorMsg")
                Result.failure(Exception(errorMsg))
            }
        } catch (e: GetCredentialCancellationException) {
            Log.w(TAG, "[Google Auth Flow] GetCredentialCancellationException: ${e.message}", e)
            val msg = e.message ?: ""
            if (msg.contains("10", ignoreCase = true) || msg.contains("DEVELOPER_ERROR", ignoreCase = true) ||
                msg.contains("16", ignoreCase = true) || msg.contains("SIGN_IN_FAILED", ignoreCase = true)) {
                Result.failure(Exception("Google Sign-In configuration error ($msg). Please check Google Play Services or use Email login."))
            } else {
                Result.failure(GoogleSignInCancelledException("Google Sign-In was cancelled."))
            }
        } catch (e: NoCredentialException) {
            Log.w(TAG, "[Google Auth Flow] No credential returned by CredentialManager: ${e.message}")
            Result.failure(Exception("No Google account selected. Please try again or sign in with Email & Password below."))
        } catch (e: GetCredentialCustomException) {
            Log.e(TAG, "[Google Auth Flow] GetCredentialCustomException: Type='${e.type}', Message='${e.message}'", e)
            val isUserDismissed = e.type.equals("android.credentials.GetCredentialException.TYPE_USER_CANCELED", ignoreCase = true) ||
                    e.message?.contains("user canceled", ignoreCase = true) == true ||
                    e.message?.contains("canceled by user", ignoreCase = true) == true
            if (isUserDismissed) {
                Result.failure(GoogleSignInCancelledException("Google Sign-In was cancelled."))
            } else {
                Result.failure(Exception("Google Sign-In failed (${e.type}): ${e.message ?: "Unavailable on this device"}. Please sign in with Email below."))
            }
        } catch (e: GetCredentialException) {
            Log.e(TAG, "[Google Auth Flow] GetCredentialException: Message='${e.message}'", e)
            val isUserDismissed = e.message?.contains("user canceled", ignoreCase = true) == true ||
                    e.message?.contains("canceled by user", ignoreCase = true) == true ||
                    e.message?.equals("The user canceled the request.", ignoreCase = true) == true
            if (isUserDismissed) {
                Result.failure(GoogleSignInCancelledException("Google Sign-In was cancelled."))
            } else {
                val rawMsg = e.message ?: ""
                val errorMsg = when {
                    rawMsg.contains("10", ignoreCase = true) || rawMsg.contains("DEVELOPER_ERROR", ignoreCase = true) ->
                        "Google Sign-In configuration mismatch (Code 10: DEVELOPER_ERROR). Please verify keystore SHA-1 in Firebase Console or use Email login."
                    rawMsg.contains("16", ignoreCase = true) || rawMsg.contains("SIGN_IN_FAILED", ignoreCase = true) ->
                        "Google Sign-In token exchange failed (Code 16: SIGN_IN_FAILED). Please try again or use Email login."
                    rawMsg.isNotBlank() ->
                        "Google Sign-In failed: $rawMsg. Please use Email & Password below."
                    else ->
                        "Google Sign-In could not complete. Please use Email & Password below."
                }
                Result.failure(Exception(errorMsg))
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "[Google Auth Flow] SecurityException: ${e.message}", e)
            Result.failure(Exception("Google Play Services security error on this device: ${e.message}. Please use Email & Password sign-in below."))
        } catch (e: FirebaseAuthInvalidUserException) {
            Log.e(TAG, "[Google Auth Flow] FirebaseAuthInvalidUserException: ${e.message}", e)
            Result.failure(Exception("This Google account has been disabled or blocked. Please contact support or use another account."))
        } catch (e: FirebaseAuthInvalidCredentialsException) {
            Log.e(TAG, "[Google Auth Flow] FirebaseAuthInvalidCredentialsException: ${e.message}", e)
            Result.failure(Exception("Invalid authentication credential from Google. Please try again."))
        } catch (e: FirebaseAuthUserCollisionException) {
            Log.e(TAG, "[Google Auth Flow] FirebaseAuthUserCollisionException: ${e.message}", e)
            Result.failure(Exception("An account with this email already exists with a different sign-in method. Please sign in with Email & Password below."))
        } catch (e: FirebaseNetworkException) {
            Log.e(TAG, "[Google Auth Flow] FirebaseNetworkException: ${e.message}", e)
            Result.failure(Exception("Network error connecting to Firebase. Please check your internet connection and try again."))
        } catch (e: Exception) {
            if (e is GoogleSignInCancelledException) {
                Result.failure(e)
            } else {
                Log.e(TAG, "[Google Auth Flow] Unexpected error during Google Sign-In: ${e.message}", e)
                Result.failure(Exception(e.message ?: "Google Sign-In failed. Please sign in with Email & Password below."))
            }
        }
    }

    /**
     * Executes Email & Password sign in with explicit logging and detailed exception mapping.
     */
    suspend fun signInWithEmail(email: String, password: String): Result<AuthUser> {
        val cleanEmail = email.trim()
        Log.i(TAG, "==================================================")
        Log.i(TAG, "[Email Auth Flow] Starting Email & Password Sign-In for: $cleanEmail")
        Log.i(TAG, "==================================================")

        return try {
            if (cleanEmail.isBlank()) {
                Log.w(TAG, "[Email Auth Flow] Validation failure: Empty email address.")
                return Result.failure(Exception("Please enter your email address."))
            }
            if (password.isBlank()) {
                Log.w(TAG, "[Email Auth Flow] Validation failure: Empty password.")
                return Result.failure(Exception("Please enter your password."))
            }

            val authResult = auth.signInWithEmailAndPassword(cleanEmail, password).await()
            val user = mapFirebaseUser(authResult.user)
                ?: throw Exception("Could not retrieve user details after email sign-in.")

            Log.i(TAG, "[Email Auth Flow] SUCCESS: Successfully signed in as ${user.email} (UID: ${user.uid})")
            Result.success(user)
        } catch (e: Exception) {
            val formatted = formatAndLogEmailAuthError("signInWithEmail", cleanEmail, e)
            Result.failure(formatted)
        }
    }

    /**
     * Executes Email & Password sign up / registration with explicit logging.
     */
    suspend fun signUpWithEmail(email: String, password: String, displayName: String? = null): Result<AuthUser> {
        val cleanEmail = email.trim()
        Log.i(TAG, "==================================================")
        Log.i(TAG, "[Email Auth Flow] Starting Email & Password Account Registration for: $cleanEmail")
        Log.i(TAG, "==================================================")

        return try {
            if (cleanEmail.isBlank()) {
                return Result.failure(Exception("Please enter a valid email address."))
            }
            if (password.length < 6) {
                return Result.failure(Exception("Password must be at least 6 characters."))
            }

            val authResult = auth.createUserWithEmailAndPassword(cleanEmail, password).await()
            if (!displayName.isNullOrBlank()) {
                try {
                    val profileUpdates = com.google.firebase.auth.UserProfileChangeRequest.Builder()
                        .setDisplayName(displayName.trim())
                        .build()
                    authResult.user?.updateProfile(profileUpdates)?.await()
                } catch (pe: Exception) {
                    Log.w(TAG, "Profile update warning: ${pe.message}")
                }
            }
            val user = mapFirebaseUser(authResult.user)
                ?: throw Exception("Could not retrieve created user profile.")

            Log.i(TAG, "[Email Auth Flow] SUCCESS: Created new account for ${user.email} (UID: ${user.uid})")
            Result.success(user)
        } catch (e: Exception) {
            val formatted = formatAndLogEmailAuthError("signUpWithEmail", cleanEmail, e)
            Result.failure(formatted)
        }
    }

    /**
     * Sends password reset email with explicit error logging.
     */
    suspend fun sendPasswordReset(email: String): Result<Unit> {
        val cleanEmail = email.trim()
        Log.i(TAG, "[Email Auth Flow] Requesting Password Reset Email for: $cleanEmail")

        return try {
            if (cleanEmail.isBlank()) {
                return Result.failure(Exception("Please enter your email address to reset password."))
            }
            if (!android.util.Patterns.EMAIL_ADDRESS.matcher(cleanEmail).matches()) {
                return Result.failure(Exception("Please enter a valid email address (e.g. user@example.com)."))
            }
            auth.sendPasswordResetEmail(cleanEmail).await()
            Log.i(TAG, "[Email Auth Flow] SUCCESS: Password reset link dispatched to: $cleanEmail")
            Result.success(Unit)
        } catch (e: Exception) {
            val formatted = formatAndLogEmailAuthError("sendPasswordReset", cleanEmail, e)
            Result.failure(formatted)
        }
    }

    /**
     * Re-authenticates the current user using Google credentials via Credential Manager.
     */
    suspend fun reauthenticateWithGoogle(context: Context, serverClientId: String? = null): Result<Unit> {
        val user = auth.currentUser
            ?: return Result.failure(Exception("No user is currently signed in."))
        val webClientId = getWebClientId(context, serverClientId)
        return try {
            val activityContext = context.findActivity() ?: context
            val credentialManager = CredentialManager.create(activityContext)

            val signInWithGoogleOption = GetSignInWithGoogleOption.Builder(webClientId).build()
            val request = GetCredentialRequest.Builder()
                .addCredentialOption(signInWithGoogleOption)
                .build()

            val result = try {
                credentialManager.getCredential(request = request, context = activityContext)
            } catch (e: NoCredentialException) {
                val googleIdOption = GetGoogleIdOption.Builder()
                    .setFilterByAuthorizedAccounts(false)
                    .setAutoSelectEnabled(false)
                    .setServerClientId(webClientId)
                    .build()
                val fallbackRequest = GetCredentialRequest.Builder()
                    .addCredentialOption(googleIdOption)
                    .build()
                credentialManager.getCredential(request = fallbackRequest, context = activityContext)
            }

            val credential = result.credential
            val idToken: String? = when {
                credential is GoogleIdTokenCredential -> credential.idToken
                credential is CustomCredential -> {
                    try {
                        val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(credential.data)
                        googleIdTokenCredential.idToken
                    } catch (e: Exception) {
                        credential.data.getString("com.google.android.libraries.identity.googleid.BUNDLE_KEY_ID_TOKEN")
                            ?: credential.data.getString("id_token")
                            ?: credential.data.getString("idToken")
                    }
                }
                else -> null
            }

            if (!idToken.isNullOrBlank()) {
                val authCredential = GoogleAuthProvider.getCredential(idToken, null)
                user.reauthenticate(authCredential).await()
                Log.i(TAG, "[Google Reauth Flow] SUCCESS: User re-authenticated successfully.")
                Result.success(Unit)
            } else {
                Result.failure(Exception("Could not obtain Google ID token for re-authentication."))
            }
        } catch (e: Exception) {
            Log.e(TAG, "[Google Reauth Flow] Re-authentication failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Links an Email/Password credential to the currently signed-in Google account using linkWithCredential,
     * or updates the password if already linked. This guarantees ONE unified Firebase Auth UID.
     * If recent authentication is required, it can automatically attempt Google re-authentication.
     */
    suspend fun linkBackupPassword(password: String, context: Context? = null): Result<AuthUser> {
        val cleanPassword = password.trim()
        val user = auth.currentUser
            ?: return Result.failure(Exception("No user is currently signed in."))
        val email = user.email
            ?: return Result.failure(Exception("The signed-in account does not have a verified email address."))

        if (cleanPassword.length < 6) {
            return Result.failure(Exception("Password must be at least 6 characters long."))
        }

        return try {
            val providers = user.providerData.map { it.providerId }
            val hasPasswordAlready = providers.contains(EmailAuthProvider.PROVIDER_ID)

            if (hasPasswordAlready) {
                Log.i(TAG, "[Account Linking] Updating existing backup password for: $email (UID: ${user.uid})")
                user.updatePassword(cleanPassword).await()
            } else {
                Log.i(TAG, "[Account Linking] Linking Email/Password credential to Google account: $email (UID: ${user.uid})")
                val credential = EmailAuthProvider.getCredential(email, cleanPassword)
                user.linkWithCredential(credential).await()
            }

            // Reload user to update providerData in Firebase SDK
            user.reload().await()
            val updatedUser = mapFirebaseUser(auth.currentUser)
                ?: throw Exception("Failed to update user profile after password linking.")

            _currentUser.value = updatedUser
            Log.i(TAG, "[Account Linking] SUCCESS: Backup password successfully linked for $email (hasPassword=${updatedUser.hasPasswordProvider})")
            Result.success(updatedUser)
        } catch (e: Exception) {
            val isRecentAuthNeeded = e is FirebaseAuthRecentLoginRequiredException ||
                    e.message?.contains("requires-recent-login", ignoreCase = true) == true ||
                    e.message?.contains("recent authentication", ignoreCase = true) == true ||
                    e.message?.contains("CREDENTIAL_TOO_OLD", ignoreCase = true) == true

            if (isRecentAuthNeeded && context != null) {
                Log.i(TAG, "[Account Linking] Recent login required. Prompting for Google re-authentication...")
                val reauthResult = reauthenticateWithGoogle(context)
                if (reauthResult.isSuccess) {
                    Log.i(TAG, "[Account Linking] Re-authentication succeeded. Retrying password update...")
                    val freshUser = auth.currentUser
                    if (freshUser != null) {
                        return try {
                            val providers = freshUser.providerData.map { it.providerId }
                            val hasPasswordAlready = providers.contains(EmailAuthProvider.PROVIDER_ID)
                            if (hasPasswordAlready) {
                                freshUser.updatePassword(cleanPassword).await()
                            } else {
                                val credential = EmailAuthProvider.getCredential(email, cleanPassword)
                                freshUser.linkWithCredential(credential).await()
                            }
                            freshUser.reload().await()
                            val updatedUser = mapFirebaseUser(auth.currentUser)
                                ?: throw Exception("Failed to update user profile after password linking.")
                            _currentUser.value = updatedUser
                            Log.i(TAG, "[Account Linking] SUCCESS on retry after re-authentication!")
                            Result.success(updatedUser)
                        } catch (retryEx: Exception) {
                            val formattedRetry = formatAndLogEmailAuthError("linkBackupPassword", email, retryEx)
                            Result.failure(formattedRetry)
                        }
                    }
                }
            }
            val formatted = formatAndLogEmailAuthError("linkBackupPassword", email, e)
            Result.failure(formatted)
        }
    }

    /**
     * Checks if the currently active user has an email & password provider attached.
     */
    fun isPasswordLinked(): Boolean {
        return auth.currentUser?.providerData?.any { it.providerId == EmailAuthProvider.PROVIDER_ID } == true
    }

    /**
     * Performs comprehensive sign out and clears credential manager state.
     */
    suspend fun signOut(context: Context? = null) {
        try {
            Log.i(TAG, "[Auth Flow] Signing out active user: ${_currentUser.value?.email}")
            auth.signOut()
            if (context != null) {
                val credentialManager = CredentialManager.create(context)
                credentialManager.clearCredentialState(ClearCredentialStateRequest())
                Log.d(TAG, "[Auth Flow] Cleared CredentialManager state.")
            }
            _currentUser.value = null
            Log.i(TAG, "[Auth Flow] Sign out complete.")
        } catch (e: Exception) {
            Log.e(TAG, "[Auth Flow] Exception during sign out: ${e.message}", e)
        }
    }

    private fun formatAndLogEmailAuthError(operation: String, email: String, e: Throwable): Exception {
        val rawMsg = e.message ?: "Authentication failed"
        
        // Log expected user-auth failures as warnings rather than severe app errors
        if (e is FirebaseAuthInvalidCredentialsException || 
            e is FirebaseAuthInvalidUserException || 
            e is FirebaseAuthUserCollisionException || 
            e is FirebaseAuthWeakPasswordException) {
            Log.w(TAG, "[Email Auth Flow] Notice during $operation for $email: ${e::class.java.simpleName} - $rawMsg")
        } else {
            Log.e(TAG, "[Email Auth Flow] Unexpected error during $operation for $email: [${e::class.java.simpleName}] $rawMsg", e)
        }

        val userMessage = when (e) {
            is FirebaseAuthInvalidUserException -> {
                if (operation == "sendPasswordReset") {
                    "No account found registered with $email. Please verify your email or create a new account."
                } else {
                    "No account found for $email. Tap 'New user? Create Account' below to set up your password."
                }
            }
            is FirebaseAuthInvalidCredentialsException -> {
                if (operation == "sendPasswordReset") {
                    "Invalid email address format ($email). Please enter a valid email."
                } else {
                    "Incorrect password for $email. If this is your first time logging in with email, tap 'New user? Create Account' below, or use 'Forgot Password'."
                }
            }
            is FirebaseAuthUserCollisionException -> {
                if (operation == "linkBackupPassword") {
                    "An account with $email is already registered with a different login method. You can sign in directly using that method."
                } else {
                    "An account with $email already exists. Tap 'Already have an account? Sign In' to enter your password."
                }
            }
            is FirebaseAuthRecentLoginRequiredException -> {
                "For security reasons, this operation requires recent authentication. Please sign in with Google again before updating your password."
            }
            is FirebaseAuthWeakPasswordException -> {
                "Password is too weak. Please use at least 6 characters."
            }
            is FirebaseNetworkException -> {
                "Network error. Please check your internet connection and try again."
            }
            else -> when {
                rawMsg.contains("CREDENTIAL_ALREADY_IN_USE", ignoreCase = true) ||
                rawMsg.contains("already in use", ignoreCase = true) ||
                rawMsg.contains("already linked", ignoreCase = true) -> {
                    if (operation == "linkBackupPassword") {
                        "This email is already linked or in use by another account. You can log in directly with your email and password."
                    } else {
                        "This email is already registered. Tap 'Already have an account? Sign In' below."
                    }
                }
                rawMsg.contains("requires-recent-login", ignoreCase = true) -> {
                    "Please sign in with Google again before changing your backup password."
                }
                rawMsg.contains("CONFIGURATION_NOT_FOUND", ignoreCase = true) -> {
                    "Firebase Authentication is not enabled for project 'amar-dukan-40808'. Please enable 'Email/Password' under Firebase Console > Authentication > Sign-in method."
                }
                rawMsg.contains("EMAIL_EXISTS", ignoreCase = true) -> {
                    "This email is already registered. Tap 'Already have an account? Sign In' below."
                }
                rawMsg.contains("USER_NOT_FOUND", ignoreCase = true) -> {
                    if (operation == "sendPasswordReset") {
                        "No account found with $email. Please check spelling or create an account."
                    } else {
                        "No account found with this email. Tap 'Create Account' first."
                    }
                }
                rawMsg.contains("INVALID_LOGIN_CREDENTIALS", ignoreCase = true) ||
                rawMsg.contains("wrong-password", ignoreCase = true) -> {
                    "Incorrect password or email not registered. Tap 'Create Account' if you are registering for the first time."
                }
                rawMsg.contains("INVALID_EMAIL", ignoreCase = true) -> {
                    "Please enter a valid email address."
                }
                rawMsg.contains("TOO_MANY_ATTEMPTS_TRY_LATER", ignoreCase = true) -> {
                    "Too many failed attempts. Please wait a moment before trying again."
                }
                else -> rawMsg
            }
        }
        return Exception(userMessage)
    }
}
