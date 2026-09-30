package com.example.data.auth

import android.content.Context
import kotlinx.coroutines.flow.StateFlow

/**
 * AuthManager forwards all authentication calls to SignInRepository,
 * maintaining backward compatibility across the codebase while providing centralized logic.
 */
class AuthManager(
    val signInRepository: SignInRepository = SignInRepository()
) {
    val currentUserState: StateFlow<AuthUser?> = signInRepository.currentUser

    fun getCurrentAuthUser(): AuthUser? = signInRepository.getCurrentAuthUser()

    fun isUserSignedIn(): Boolean = signInRepository.isUserSignedIn()

    fun checkGoogleSignInAvailability(context: Context): GoogleSignInAvailability =
        signInRepository.checkGoogleSignInAvailability(context)

    suspend fun signInWithGoogle(context: Context, serverClientId: String? = null): Result<AuthUser> =
        signInRepository.signInWithGoogle(context, serverClientId)

    suspend fun signInWithEmail(email: String, password: String): Result<AuthUser> =
        signInRepository.signInWithEmail(email, password)

    suspend fun signUpWithEmail(email: String, password: String, displayName: String? = null): Result<AuthUser> =
        signInRepository.signUpWithEmail(email, password, displayName)

    suspend fun sendPasswordReset(email: String): Result<Unit> =
        signInRepository.sendPasswordReset(email)

    suspend fun reauthenticateWithGoogle(context: Context, serverClientId: String? = null): Result<Unit> =
        signInRepository.reauthenticateWithGoogle(context, serverClientId)

    suspend fun linkBackupPassword(password: String, context: Context? = null): Result<AuthUser> =
        signInRepository.linkBackupPassword(password, context)

    fun isPasswordLinked(): Boolean =
        signInRepository.isPasswordLinked()

    suspend fun signOut(context: Context? = null) =
        signInRepository.signOut(context)
}
