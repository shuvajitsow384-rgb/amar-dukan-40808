package com.example.ui.screens.auth

import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.auth.GoogleSignInAvailability
import com.example.ui.theme.*
import com.example.utils.AppLanguage
import com.example.utils.LanguageManager
import com.example.utils.NetworkMonitor
import com.example.viewmodel.StoreViewModel

/**
 * Mandatory Authentication Gate Screen.
 * Displayed whenever no user is authenticated (e.g. fresh installation or logged out state).
 * Prevents unauthorized access to store data, inventory, billing, customers, and accounts.
 */
@Composable
fun AuthGateScreen(
    viewModel: StoreViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val isBn = LanguageManager.isBengali

    // Tabs: 0 = Sign In, 1 = Create Account / Sign Up
    var selectedTab by remember { mutableIntStateOf(0) }
    var isForgotPasswordMode by remember { mutableStateOf(false) }
    var resetEmailSent by remember { mutableStateOf(false) }

    // Form inputs
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var ownerName by remember { mutableStateOf("") }
    var storeName by remember { mutableStateOf("") }
    var resetEmail by remember { mutableStateOf("") }

    var showPassword by remember { mutableStateOf(false) }
    var showConfirmPassword by remember { mutableStateOf(false) }
    var localError by remember { mutableStateOf<String?>(null) }

    val googleStatus by viewModel.googleSignInStatus.collectAsState()
    val isAuthLoading = viewModel.isAuthLoading
    val globalAuthError = viewModel.authError

    LaunchedEffect(Unit) {
        viewModel.checkGoogleSignInAvailability(context)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Card Container (Max width for tablets & landscape)
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 480.dp),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Top Bar: Language Selector
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = StorePrimary.copy(alpha = 0.10f),
                            border = BorderStroke(1.dp, StorePrimary.copy(alpha = 0.25f))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Shield,
                                    contentDescription = null,
                                    tint = StorePrimary,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = if (isBn) "সুরক্ষিত প্রবেশ" else "Protected Store Gate",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = StorePrimary,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        // Language Toggle Button
                        OutlinedButton(
                            onClick = { LanguageManager.toggleLanguage(context) },
                            shape = RoundedCornerShape(16.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.height(30.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                        ) {
                            Icon(
                                imageVector = Icons.Default.Language,
                                contentDescription = "Language",
                                modifier = Modifier.size(13.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = if (isBn) "English" else "বাংলা",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // App Store Logo & Identity
                    Surface(
                        shape = CircleShape,
                        color = StorePrimary.copy(alpha = 0.12f),
                        border = BorderStroke(2.dp, StorePrimary.copy(alpha = 0.35f)),
                        modifier = Modifier.size(68.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.Storefront,
                                contentDescription = "Amar Dukan",
                                tint = StorePrimary,
                                modifier = Modifier.size(36.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = if (isBn) "আমার দোকান" else "Amar Dukan",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center
                    )

                    Text(
                        text = if (isBn) "স্মার্ট খুচরা বিক্রি ও ডিজিটাল খাতা ম্যানেজমেন্ট" else "Smart Store POS & Khata Management",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = if (isBn)
                            "দোকানের স্টক, বেচাকেনা ও হিসাব সুরক্ষিত রাখতে অনুগ্রহ করে সাইন ইন করুন বা নতুন অ্যাকাউন্ট খুলুন।"
                        else
                            "Sign in or create an account to securely access your store records, inventory, and accounts.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                        textAlign = TextAlign.Center,
                        fontSize = 11.5.sp
                    )

                    // Offline Warning Banner if device lacks internet connection
                    if (!NetworkMonitor.isOnline) {
                        Spacer(modifier = Modifier.height(14.dp))
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = StoreOrangeWarning.copy(alpha = 0.12f),
                            border = BorderStroke(1.dp, StoreOrangeWarning.copy(alpha = 0.4f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.WifiOff,
                                    contentDescription = null,
                                    tint = StoreOrangeWarning,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = if (isBn)
                                        "লগইন বা রেজিস্টার করার জন্য ইন্টারনেট সংযোগ প্রয়োজন। অনুগ্রহ করে মোবাইল ডাটা বা ওয়াইফাই চালু করুন।"
                                    else
                                        "Internet connection required to log in or register. Please turn on Wi-Fi or Mobile Data.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = StoreOrangeWarning,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(18.dp))

                    if (isForgotPasswordMode) {
                        // ==========================================
                        // FORGOT PASSWORD SUB-FLOW
                        // ==========================================
                        ForgotPasswordSection(
                            isBn = isBn,
                            resetEmail = resetEmail,
                            fallbackEmail = email,
                            onResetEmailChange = { resetEmail = it; localError = null },
                            resetEmailSent = resetEmailSent,
                            isLoading = isAuthLoading,
                            errorMessage = localError ?: globalAuthError,
                            onSendReset = { targetEmail ->
                                keyboardController?.hide()
                                focusManager.clearFocus()
                                val clean = targetEmail.trim()
                                if (clean.isBlank() || !android.util.Patterns.EMAIL_ADDRESS.matcher(clean).matches()) {
                                    localError = if (isBn) "অনুগ্রহ করে সঠিক ইমেইল দিন।" else "Please enter a valid email address."
                                    return@ForgotPasswordSection
                                }
                                viewModel.sendPasswordReset(clean) { success, msg ->
                                    if (success) {
                                        resetEmailSent = true
                                        localError = null
                                        Toast.makeText(context, if (isBn) "রিসেট ইমেইল পাঠানো হয়েছে!" else "Password reset link sent!", Toast.LENGTH_LONG).show()
                                    } else {
                                        localError = msg ?: (if (isBn) "ইমেইল পাঠানো যায়নি।" else "Failed to send reset email.")
                                    }
                                }
                            },
                            onBackToSignIn = {
                                isForgotPasswordMode = false
                                resetEmailSent = false
                                localError = null
                                viewModel.authError = null
                            }
                        )
                    } else {
                        // ==========================================
                        // MAIN SIGN IN / SIGN UP FLOW
                        // ==========================================

                        // Google One-Tap Sign In Button
                        Button(
                            onClick = {
                                keyboardController?.hide()
                                focusManager.clearFocus()
                                localError = null
                                viewModel.authError = null
                                viewModel.signInWithGoogle(context) { success, msg ->
                                    if (success) {
                                        localError = null
                                        Toast.makeText(context, msg ?: (if (isBn) "সফলভাবে সাইন ইন হয়েছে!" else "Signed in successfully!"), Toast.LENGTH_SHORT).show()
                                    } else {
                                        val display = msg ?: (if (isBn) "Google সাইন ইন সম্পন্ন হয়নি।" else "Google Sign-In failed.")
                                        if (display.contains("cancel", ignoreCase = true) || display.contains("dismiss", ignoreCase = true)) {
                                            Toast.makeText(context, if (isBn) "Google সাইন ইন বাতিল করা হয়েছে" else "Google Sign-In was cancelled", Toast.LENGTH_SHORT).show()
                                        } else {
                                            localError = display
                                            Toast.makeText(context, display, Toast.LENGTH_LONG).show()
                                        }
                                    }
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(46.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = StorePrimary,
                                contentColor = Color.White
                            ),
                            enabled = !isAuthLoading
                        ) {
                            if (isAuthLoading) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                            } else {
                                Icon(
                                    imageVector = Icons.Default.AccountCircle,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = if (isBn) "Google দিয়ে চালিয়ে যান" else "Continue with Google",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        // Immediate visible error notification for Google sign in failures
                        val activeGoogleError = (localError ?: globalAuthError)?.takeIf {
                            !it.contains("cancel", ignoreCase = true) && !it.contains("dismiss", ignoreCase = true)
                        }
                        if (!activeGoogleError.isNullOrBlank() && !isAuthLoading) {
                            Spacer(modifier = Modifier.height(10.dp))
                            Surface(
                                color = MaterialTheme.colorScheme.errorContainer,
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Warning,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = activeGoogleError,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onErrorContainer,
                                        modifier = Modifier.weight(1f)
                                    )
                                    IconButton(
                                        onClick = {
                                            localError = null
                                            viewModel.authError = null
                                        },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = "Dismiss",
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        }

                        // Fallback notice if Google Play Services is unavailable
                        val currentStatus = googleStatus
                        if (currentStatus is GoogleSignInAvailability.Unavailable && localError == null) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Surface(
                                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = "${currentStatus.reason} ${currentStatus.suggestedAction}",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.padding(8.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // Divider with OR
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            HorizontalDivider(modifier = Modifier.weight(1f))
                            Text(
                                text = if (isBn) " অথবা ইমেইল দিয়ে " else " OR WITH EMAIL ",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 8.dp)
                            )
                            HorizontalDivider(modifier = Modifier.weight(1f))
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        // Tab Row: Sign In vs. Create Account
                        TabRow(
                            selectedTabIndex = selectedTab,
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                            contentColor = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(40.dp),
                            indicator = {},
                            divider = {}
                        ) {
                            Tab(
                                selected = selectedTab == 0,
                                onClick = {
                                    selectedTab = 0
                                    localError = null
                                    viewModel.authError = null
                                },
                                text = {
                                    Text(
                                        text = if (isBn) "লগইন" else "Sign In",
                                        fontWeight = if (selectedTab == 0) FontWeight.Bold else FontWeight.Medium,
                                        fontSize = 13.sp
                                    )
                                }
                            )
                            Tab(
                                selected = selectedTab == 1,
                                onClick = {
                                    selectedTab = 1
                                    localError = null
                                    viewModel.authError = null
                                },
                                text = {
                                    Text(
                                        text = if (isBn) "নতুন অ্যাকাউন্ট" else "Create Account",
                                        fontWeight = if (selectedTab == 1) FontWeight.Bold else FontWeight.Medium,
                                        fontSize = 13.sp
                                    )
                                }
                            )
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        // Display Error Message if any
                        val displayErr = (localError ?: globalAuthError)?.takeIf {
                            !it.contains("cancelled", ignoreCase = true) &&
                            !it.contains("canceled", ignoreCase = true) &&
                            !it.contains("dismissed", ignoreCase = true)
                        }

                        if (displayErr != null) {
                            Surface(
                                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Info,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = displayErr,
                                        color = MaterialTheme.colorScheme.error,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontSize = 11.5.sp,
                                        modifier = Modifier.weight(1f)
                                    )
                                    IconButton(
                                        onClick = {
                                            localError = null
                                            viewModel.authError = null
                                        },
                                        modifier = Modifier.size(20.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Close,
                                            contentDescription = "Dismiss",
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(10.dp))
                        }

                        // Form Fields based on selected tab
                        if (selectedTab == 0) {
                            // ============================
                            // SIGN IN TAB
                            // ============================
                            OutlinedTextField(
                                value = email,
                                onValueChange = {
                                    email = it
                                    localError = null
                                    viewModel.authError = null
                                },
                                label = { Text(if (isBn) "ইমেইল এড্রেস" else "Email Address") },
                                placeholder = { Text("e.g. name@example.com") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Email,
                                    imeAction = ImeAction.Next
                                ),
                                modifier = Modifier.fillMaxWidth(),
                                leadingIcon = { Icon(Icons.Default.Email, contentDescription = null) }
                            )

                            Spacer(modifier = Modifier.height(10.dp))

                            OutlinedTextField(
                                value = password,
                                onValueChange = {
                                    password = it
                                    localError = null
                                    viewModel.authError = null
                                },
                                label = { Text(if (isBn) "পাসওয়ার্ড" else "Password") },
                                singleLine = true,
                                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Password,
                                    imeAction = ImeAction.Done
                                ),
                                keyboardActions = KeyboardActions(
                                    onDone = {
                                        keyboardController?.hide()
                                        focusManager.clearFocus()
                                    }
                                ),
                                trailingIcon = {
                                    IconButton(onClick = { showPassword = !showPassword }) {
                                        Icon(
                                            imageVector = if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                            contentDescription = if (showPassword) "Hide password" else "Show password"
                                        )
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) }
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                TextButton(
                                    onClick = {
                                        resetEmail = email
                                        isForgotPasswordMode = true
                                        localError = null
                                        viewModel.authError = null
                                    }
                                ) {
                                    Text(
                                        text = if (isBn) "পাসওয়ার্ড ভুলে গেছেন?" else "Forgot Password?",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = StorePrimary
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(4.dp))

                            Button(
                                onClick = {
                                    keyboardController?.hide()
                                    focusManager.clearFocus()
                                    val cleanEmail = email.trim()
                                    if (cleanEmail.isBlank() || !android.util.Patterns.EMAIL_ADDRESS.matcher(cleanEmail).matches()) {
                                        localError = if (isBn) "অনুগ্রহ করে সঠিক ইমেইল এড্রেস লিখুন।" else "Please enter a valid email address."
                                        return@Button
                                    }
                                    if (password.length < 6) {
                                        localError = if (isBn) "পাসওয়ার্ড কমপক্ষে ৬ অক্ষরের হতে হবে।" else "Password must be at least 6 characters."
                                        return@Button
                                    }
                                    viewModel.signInWithEmail(cleanEmail, password) { success, msg ->
                                        if (success) {
                                            Toast.makeText(context, msg ?: (if (isBn) "স্বাগতম!" else "Welcome!"), Toast.LENGTH_SHORT).show()
                                        } else {
                                            localError = msg
                                        }
                                    }
                                },
                                enabled = !isAuthLoading,
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = StorePrimary),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                            ) {
                                if (isAuthLoading) {
                                    CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                                } else {
                                    Icon(Icons.Default.Login, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = if (isBn) "দোকানে প্রবেশ করুন" else "Sign In to Store",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp
                                    )
                                }
                            }
                        } else {
                            // ============================
                            // CREATE ACCOUNT / SIGN UP TAB
                            // ============================
                            OutlinedTextField(
                                value = ownerName,
                                onValueChange = { ownerName = it; localError = null },
                                label = { Text(if (isBn) "আপনার পুরো নাম" else "Your Full Name") },
                                placeholder = { Text(if (isBn) "উদাঃ শুভজিৎ সোম" else "e.g. Shuvajit Sow") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Text,
                                    imeAction = ImeAction.Next
                                ),
                                modifier = Modifier.fillMaxWidth(),
                                leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) }
                            )

                            Spacer(modifier = Modifier.height(10.dp))

                            OutlinedTextField(
                                value = storeName,
                                onValueChange = { storeName = it; localError = null },
                                label = { Text(if (isBn) "দোকানের নাম" else "Store Name") },
                                placeholder = { Text(if (isBn) "উদাঃ মা কালী ভাণ্ডার" else "e.g. Maa Kali Variety Store") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Text,
                                    imeAction = ImeAction.Next
                                ),
                                modifier = Modifier.fillMaxWidth(),
                                leadingIcon = { Icon(Icons.Default.Storefront, contentDescription = null) }
                            )

                            Spacer(modifier = Modifier.height(10.dp))

                            OutlinedTextField(
                                value = email,
                                onValueChange = { email = it; localError = null; viewModel.authError = null },
                                label = { Text(if (isBn) "ইমেইল এড্রেস" else "Email Address") },
                                placeholder = { Text("e.g. name@example.com") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Email,
                                    imeAction = ImeAction.Next
                                ),
                                modifier = Modifier.fillMaxWidth(),
                                leadingIcon = { Icon(Icons.Default.Email, contentDescription = null) }
                            )

                            Spacer(modifier = Modifier.height(10.dp))

                            OutlinedTextField(
                                value = password,
                                onValueChange = { password = it; localError = null; viewModel.authError = null },
                                label = { Text(if (isBn) "পাসওয়ার্ড (কমপক্ষে ৬ অক্ষর)" else "Password (min 6 characters)") },
                                singleLine = true,
                                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Password,
                                    imeAction = ImeAction.Next
                                ),
                                trailingIcon = {
                                    IconButton(onClick = { showPassword = !showPassword }) {
                                        Icon(
                                            imageVector = if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                            contentDescription = if (showPassword) "Hide password" else "Show password"
                                        )
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) }
                            )

                            Spacer(modifier = Modifier.height(10.dp))

                            OutlinedTextField(
                                value = confirmPassword,
                                onValueChange = { confirmPassword = it; localError = null },
                                label = { Text(if (isBn) "পাসওয়ার্ড নিশ্চিত করুন" else "Confirm Password") },
                                singleLine = true,
                                visualTransformation = if (showConfirmPassword) VisualTransformation.None else PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Password,
                                    imeAction = ImeAction.Done
                                ),
                                keyboardActions = KeyboardActions(
                                    onDone = {
                                        keyboardController?.hide()
                                        focusManager.clearFocus()
                                    }
                                ),
                                trailingIcon = {
                                    IconButton(onClick = { showConfirmPassword = !showConfirmPassword }) {
                                        Icon(
                                            imageVector = if (showConfirmPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                            contentDescription = if (showConfirmPassword) "Hide password" else "Show password"
                                        )
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                leadingIcon = { Icon(Icons.Default.CheckCircle, contentDescription = null) }
                            )

                            Spacer(modifier = Modifier.height(16.dp))

                            Button(
                                onClick = {
                                    keyboardController?.hide()
                                    focusManager.clearFocus()
                                    val cleanEmail = email.trim()
                                    if (cleanEmail.isBlank() || !android.util.Patterns.EMAIL_ADDRESS.matcher(cleanEmail).matches()) {
                                        localError = if (isBn) "অনুগ্রহ করে সঠিক ইমেইল এড্রেস লিখুন।" else "Please enter a valid email address."
                                        return@Button
                                    }
                                    if (password.length < 6) {
                                        localError = if (isBn) "পাসওয়ার্ড কমপক্ষে ৬ অক্ষরের হতে হবে।" else "Password must be at least 6 characters."
                                        return@Button
                                    }
                                    if (password != confirmPassword) {
                                        localError = if (isBn) "উভয় পাসওয়ার্ড মিলছে না।" else "Passwords do not match."
                                        return@Button
                                    }
                                    viewModel.signUpWithEmail(
                                        email = cleanEmail,
                                        pass = password,
                                        displayName = ownerName.trim().ifBlank { null },
                                        storeName = storeName.trim().ifBlank { null }
                                    ) { success, msg ->
                                        if (success) {
                                            Toast.makeText(context, msg ?: (if (isBn) "অ্যাকাউন্ট সফলভাবে তৈরি হয়েছে!" else "Account created successfully!"), Toast.LENGTH_SHORT).show()
                                        } else {
                                            localError = msg
                                        }
                                    }
                                },
                                enabled = !isAuthLoading,
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = StoreGreenProfit),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                            ) {
                                if (isAuthLoading) {
                                    CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                                } else {
                                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = if (isBn) "অ্যাকাউন্ট তৈরি করুন ও শুরু করুন" else "Create Account & Get Started",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(18.dp))

                    // Footer security reassurance
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (isBn)
                                "সুরক্ষিত ক্লাউড ডাটাবেজ • শুধুমাত্র অনুমোদিত ব্যবহারকারীদের প্রবেশাধিকার"
                            else
                                "Protected Cloud Database • Only authorized store users can access data",
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ForgotPasswordSection(
    isBn: Boolean,
    resetEmail: String,
    fallbackEmail: String,
    onResetEmailChange: (String) -> Unit,
    resetEmailSent: Boolean,
    isLoading: Boolean,
    errorMessage: String?,
    onSendReset: (String) -> Unit,
    onBackToSignIn: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (resetEmailSent) {
            Surface(
                color = StoreGreenProfit.copy(alpha = 0.10f),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, StoreGreenProfit.copy(alpha = 0.35f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        shape = CircleShape,
                        color = StoreGreenProfit.copy(alpha = 0.2f),
                        modifier = Modifier.size(44.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = "Success",
                                tint = StoreGreenProfit,
                                modifier = Modifier.size(26.dp)
                            )
                        }
                    }
                    Text(
                        text = if (isBn) "রিসেট লিংক পাঠানো হয়েছে" else "Password Reset Dispatched",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = StoreGreenProfit
                    )
                    Text(
                        text = if (isBn)
                            "পাসওয়ার্ড পরিবর্তনের নির্দেশাবলী আপনার ইমেইলে পাঠানো হয়েছে। অনুগ্রহ করে ইনবক্স ও স্প্যাম ফোল্ডার চেক করুন।"
                        else
                            "Password reset instructions have been sent to your email address. Please check your inbox and Spam / Promotions folder.",
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            Button(
                onClick = onBackToSignIn,
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = StorePrimary),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (isBn) "লগইনে ফিরে যান" else "Return to Sign In", fontWeight = FontWeight.Bold)
            }
        } else {
            Text(
                text = if (isBn)
                    "আপনার রেজিস্টার্ড ইমেইল এড্রেস লিখুন। আমরা একটি পাসওয়ার্ড রিসেট লিংক পাঠিয়ে দেব।"
                else
                    "Enter your registered email address below. We'll send a password reset link to create a new password.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            OutlinedTextField(
                value = resetEmail.ifBlank { fallbackEmail },
                onValueChange = onResetEmailChange,
                label = { Text(if (isBn) "রেজিস্টার্ড ইমেইল এড্রেস" else "Registered Email Address") },
                placeholder = { Text("e.g. name@example.com") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Email,
                    imeAction = ImeAction.Done
                ),
                modifier = Modifier.fillMaxWidth(),
                leadingIcon = { Icon(Icons.Default.Email, contentDescription = null) }
            )

            if (errorMessage != null) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = errorMessage,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(8.dp)
                    )
                }
            }

            Button(
                onClick = { onSendReset(resetEmail.ifBlank { fallbackEmail }) },
                enabled = !isLoading,
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = StorePrimary),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(46.dp)
            ) {
                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.Send, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isBn) "রিসেট লিংক পাঠান" else "Send Reset Link",
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            TextButton(onClick = onBackToSignIn) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = if (isBn) "লগইনে ফিরে যান" else "Back to Sign In",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp
                )
            }
        }
    }
}
