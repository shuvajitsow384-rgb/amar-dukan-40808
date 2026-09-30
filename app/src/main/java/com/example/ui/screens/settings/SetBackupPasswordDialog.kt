package com.example.ui.screens.settings

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.example.ui.theme.*
import com.example.utils.LanguageManager
import com.example.utils.StoreInfoManager
import com.example.viewmodel.StoreViewModel

/**
 * Dialog for setting or updating a backup Email/Password credential attached to the existing Firebase user account.
 * This links the password to the existing Google account UID via linkWithCredential, avoiding separate accounts.
 */
@Composable
fun SetBackupPasswordDialog(
    viewModel: StoreViewModel,
    onDismiss: () -> Unit,
    isFirstTimePrompt: Boolean = false
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val isBn = LanguageManager.isBengali

    val currentUser by viewModel.currentUser.collectAsState()
    val hasExistingPassword = currentUser?.hasPasswordProvider == true
    val accountEmail = currentUser?.email ?: ""

    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var showConfirmPassword by remember { mutableStateOf(false) }
    var localError by remember { mutableStateOf<String?>(null) }

    val isPasswordValid = password.length >= 6
    val isPasswordMatch = password == confirmPassword && confirmPassword.isNotEmpty()

    AlertDialog(
        onDismissRequest = {
            if (!viewModel.isAuthLoading) {
                onDismiss()
            }
        },
        properties = DialogProperties(dismissOnClickOutside = !viewModel.isAuthLoading),
        icon = {
            Surface(
                color = if (hasExistingPassword) StorePrimary.copy(alpha = 0.12f) else StoreGold.copy(alpha = 0.15f),
                shape = CircleShape,
                modifier = Modifier.size(52.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (hasExistingPassword) Icons.Default.LockReset else Icons.Default.Security,
                        contentDescription = null,
                        tint = if (hasExistingPassword) StorePrimary else Color(0xFFD97706),
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
        },
        title = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = if (hasExistingPassword) {
                        if (isBn) "ব্যাকআপ পাসওয়ার্ড পরিবর্তন করুন" else "Change Backup Password"
                    } else {
                        if (isBn) "ব্যাকআপ পাসওয়ার্ড সেট করুন" else "Set a Backup Password"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = TextDark,
                    textAlign = TextAlign.Center
                )
                if (accountEmail.isNotBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            text = accountEmail,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = StorePrimary,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Recommendation note
                Surface(
                    color = if (isFirstTimePrompt) StoreGold.copy(alpha = 0.1f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, if (isFirstTimePrompt) StoreGold.copy(alpha = 0.4f) else Color.Transparent)
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            tint = if (isFirstTimePrompt) Color(0xFFD97706) else StorePrimary,
                            modifier = Modifier
                                .size(16.dp)
                                .padding(top = 2.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (isBn) {
                                "প্রস্তাবিত: আপনি অন্য ডিভাইসে কাজ করতে চাইলে বা Google অনুপলব্ধ থাকলে সরাসরি এই ইমেইল ও পাসওয়ার্ড দিয়ে দোকানে লগইন করতে পারবেন। সব দোকানের ডেটা একই অ্যাকাউন্টে থাকবে।"
                            } else {
                                "Recommended: Set a password so you can log in later with email + password if your Google account isn't available on another device. All shop data stays unified."
                            },
                            fontSize = 12.sp,
                            color = TextMuted,
                            lineHeight = 16.sp
                        )
                    }
                }

                // Password Input
                OutlinedTextField(
                    value = password,
                    onValueChange = {
                        password = it
                        localError = null
                    },
                    label = { Text(if (hasExistingPassword) "New Password" else "Backup Password") },
                    placeholder = { Text("At least 6 characters") },
                    leadingIcon = {
                        Icon(Icons.Default.Lock, contentDescription = null, tint = StorePrimary)
                    },
                    trailingIcon = {
                        IconButton(onClick = { showPassword = !showPassword }) {
                            Icon(
                                imageVector = if (showPassword) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                contentDescription = if (showPassword) "Hide password" else "Show password"
                            )
                        }
                    },
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Next
                    ),
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("backup_password_input"),
                    shape = RoundedCornerShape(8.dp),
                    isError = password.isNotEmpty() && password.length < 6,
                    supportingText = {
                        if (password.isNotEmpty() && password.length < 6) {
                            Text(
                                text = if (isBn) "কমপক্ষে ৬টি অক্ষর প্রয়োজন (${password.length}/6)" else "Minimum 6 characters required (${password.length}/6)",
                                color = MaterialTheme.colorScheme.error,
                                fontSize = 11.sp
                            )
                        } else if (password.length >= 6) {
                            Text(
                                text = if (isBn) "✓ পাসওয়ার্ডের দৈর্ঘ্য সঠিক" else "✓ Password length valid",
                                color = StoreGreenProfit,
                                fontSize = 11.sp
                            )
                        }
                    }
                )

                // Confirm Password Input
                OutlinedTextField(
                    value = confirmPassword,
                    onValueChange = {
                        confirmPassword = it
                        localError = null
                    },
                    label = { Text(if (isBn) "পাসওয়ার্ড নিশ্চিত করুন" else "Confirm Password") },
                    placeholder = { Text("Re-enter password") },
                    leadingIcon = {
                        Icon(Icons.Default.LockReset, contentDescription = null, tint = StorePrimary)
                    },
                    trailingIcon = {
                        IconButton(onClick = { showConfirmPassword = !showConfirmPassword }) {
                            Icon(
                                imageVector = if (showConfirmPassword) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                contentDescription = if (showConfirmPassword) "Hide password" else "Show password"
                            )
                        }
                    },
                    visualTransformation = if (showConfirmPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            focusManager.clearFocus()
                            keyboardController?.hide()
                        }
                    ),
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("backup_confirm_password_input"),
                    shape = RoundedCornerShape(8.dp),
                    isError = confirmPassword.isNotEmpty() && confirmPassword != password,
                    supportingText = {
                        if (confirmPassword.isNotEmpty()) {
                            if (confirmPassword != password) {
                                Text(
                                    text = if (isBn) "পাসওয়ার্ড দুটি মিলছে না" else "Passwords do not match",
                                    color = MaterialTheme.colorScheme.error,
                                    fontSize = 11.sp
                                )
                            } else {
                                Text(
                                    text = if (isBn) "✓ পাসওয়ার্ড মিলেছে" else "✓ Passwords match",
                                    color = StoreGreenProfit,
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }
                )

                // Error Message Box
                if (localError != null) {
                    val isRecentAuthRequired = localError?.let { err ->
                        err.contains("recent authentication", ignoreCase = true) ||
                        err.contains("sign in with Google again", ignoreCase = true) ||
                        err.contains("requires-recent-login", ignoreCase = true)
                    } == true

                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.85f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ErrorOutline,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = localError ?: "",
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    fontSize = 12.sp,
                                    lineHeight = 16.sp,
                                    modifier = Modifier.weight(1f)
                                )
                            }

                            if (isRecentAuthRequired) {
                                Button(
                                    onClick = {
                                        localError = null
                                        viewModel.reauthenticateAndSetPassword(password, context) { success, msg ->
                                            if (success) {
                                                Toast.makeText(
                                                    context,
                                                    msg ?: (if (isBn) "ব্যাকআপ পাসওয়ার্ড সফলভাবে সংরক্ষিত হয়েছে!" else "Backup password saved!"),
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                                StoreInfoManager.setBackupPasswordBannerDismissed(true, context)
                                                onDismiss()
                                            } else {
                                                localError = msg
                                            }
                                        }
                                    },
                                    enabled = !viewModel.isAuthLoading && isPasswordValid && isPasswordMatch,
                                    colors = ButtonDefaults.buttonColors(containerColor = StorePrimary),
                                    shape = RoundedCornerShape(6.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(36.dp)
                                        .testTag("verify_google_update_password_button"),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    if (viewModel.isAuthLoading) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(14.dp),
                                            color = Color.White,
                                            strokeWidth = 2.dp
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = if (isBn) "যাচাই করা হচ্ছে..." else "Verifying...",
                                            fontSize = 12.sp
                                        )
                                    } else {
                                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = if (isBn) "Google দিয়ে যাচাই ও আপডেট করুন" else "Verify with Google & Update",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    focusManager.clearFocus()
                    keyboardController?.hide()

                    if (password.length < 6) {
                        localError = if (isBn) "পাসওয়ার্ডে কমপক্ষে ৬টি অক্ষর থাকতে হবে।" else "Password must be at least 6 characters long."
                        return@Button
                    }
                    if (password != confirmPassword) {
                        localError = if (isBn) "নিশ্চিতকরণ পাসওয়ার্ড মূল পাসওয়ার্ডের সাথে মিলছে না।" else "Passwords do not match."
                        return@Button
                    }

                    localError = null
                    viewModel.linkBackupPassword(password, context) { success, msg ->
                        if (success) {
                            Toast.makeText(
                                context,
                                msg ?: (if (isBn) "ব্যাকআপ পাসওয়ার্ড সফলভাবে সংরক্ষিত হয়েছে!" else "Backup password saved!"),
                                Toast.LENGTH_SHORT
                            ).show()
                            StoreInfoManager.setBackupPasswordBannerDismissed(true, context)
                            onDismiss()
                        } else {
                            localError = msg
                        }
                    }
                },
                enabled = !viewModel.isAuthLoading && isPasswordValid && isPasswordMatch,
                colors = ButtonDefaults.buttonColors(containerColor = StorePrimary),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.testTag("save_backup_password_button")
            ) {
                if (viewModel.isAuthLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = Color.White,
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(if (isBn) "সংরক্ষণ করা হচ্ছে..." else "Saving...")
                } else {
                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (hasExistingPassword) {
                            if (isBn) "পাসওয়ার্ড আপডেট করুন" else "Update Password"
                        } else {
                            if (isBn) "পাসওয়ার্ড সেট করুন" else "Set Password"
                        },
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    if (isFirstTimePrompt) {
                        // User chose "Skip for now"
                        Toast.makeText(
                            context,
                            if (isBn) "পাসওয়ার্ড সেট এড়িয়ে যাওয়া হয়েছে। আপনি যেকোনো সময় সেটিংস থেকে সেট করতে পারেন।" else "Password setup skipped. You can set it anytime from Settings.",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    onDismiss()
                },
                enabled = !viewModel.isAuthLoading
            ) {
                Text(
                    text = if (isFirstTimePrompt) {
                        if (isBn) "আপাতত বাদ দিন" else "Skip for now"
                    } else {
                        if (isBn) "বাতিল" else "Cancel"
                    },
                    color = TextMuted,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    )
}

/**
 * A clean, compact, dismissible banner reminding Google-authenticated users to set up a backup password.
 */
@Composable
fun BackupPasswordReminderBanner(
    onSetPasswordClicked: () -> Unit,
    onDismissClicked: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isBn = LanguageManager.isBengali

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = Color(0xFFFFFBEB), // Warm amber tint
        border = BorderStroke(1.dp, Color(0xFFFDE68A)),
        shadowElevation = 1.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                color = Color(0xFFFEF3C7),
                shape = CircleShape,
                modifier = Modifier.size(32.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.Security,
                        contentDescription = null,
                        tint = Color(0xFFD97706),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (isBn) "ব্যাকআপ পাসওয়ার্ড সেট করুন" else "Set a Backup Password",
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    color = Color(0xFF92400E)
                )
                Text(
                    text = if (isBn) "Google অ্যাকাউন্ট অনুপলব্ধ থাকলে ইমেইল দিয়ে লগইন করতে পাসওয়ার্ড যোগ করুন।" else "Enable email + password login so you can sign in even without Google.",
                    fontSize = 11.sp,
                    color = Color(0xFFB45309),
                    lineHeight = 14.sp
                )
            }

            Spacer(modifier = Modifier.width(6.dp))

            Button(
                onClick = onSetPasswordClicked,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD97706)),
                shape = RoundedCornerShape(6.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                modifier = Modifier.height(28.dp)
            ) {
                Text(
                    text = if (isBn) "সেট করুন" else "Set",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }

            Spacer(modifier = Modifier.width(4.dp))

            IconButton(
                onClick = onDismissClicked,
                modifier = Modifier.size(24.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Dismiss reminder",
                    tint = Color(0xFF92400E),
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }
}
