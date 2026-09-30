package com.example

import android.Manifest
import android.content.ComponentCallbacks2
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Display
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.NavController
import androidx.navigation.NavHostController
import com.example.ui.theme.AnimationTokens
import coil.Coil
import com.example.data.firestore.PERMANENT_ADMIN_EMAILS
import com.example.ui.components.StaffOfflineBlockOverlay
import com.example.ui.components.UnrecognizedAccountGate
import com.example.ui.screens.auth.AuthGateScreen
import com.example.ui.screens.credit.CreditScreen
import com.example.ui.screens.employees.EmployeesScreen
import com.example.ui.screens.expenses.ExpenseScreen
import com.example.ui.screens.inventory.InventoryScreen
import com.example.ui.screens.pos.PosScreen
import com.example.utils.LanguageManager
import com.example.ui.screens.reports.ReportsScreen
import com.example.ui.screens.settings.SetBackupPasswordDialog
import com.example.ui.screens.settings.SettingsScreen
import com.example.ui.screens.users.AllAppUsersScreen
import com.example.ui.theme.MyApplicationTheme
import com.example.utils.NetworkMonitor
import com.example.utils.NotificationHelper
import com.example.utils.StaffManager
import com.example.utils.StoreInfoManager
import com.example.utils.ThemeManager
import com.example.viewmodel.StoreViewModel

enum class NavDestination(
    val route: String,
    val icon: ImageVector,
    val labelEn: String,
    val labelBn: String
) {
    POS("pos", Icons.Default.ShoppingCart, "POS Bill", "বিল"),
    INVENTORY("inventory", Icons.Default.Inventory2, "Stock", "স্টক"),
    CREDIT("credit", Icons.Default.MenuBook, "Khata", "খাতা"),
    EXPENSES("expenses", Icons.Default.AccountBalanceWallet, "Expense", "খরচ"),
    REPORTS("reports", Icons.Default.BarChart, "P&L", "লাভ-ক্ষতি"),
    SETTINGS("settings", Icons.Default.Settings, "Settings", "সেটিংস");

    val label: String
        get() = if (LanguageManager.isBengali) labelBn else labelEn
}

class MainActivity : ComponentActivity() {

    private val targetRouteState = mutableStateOf<String?>(null)
    private val storeViewModel: StoreViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        // 1. Install AndroidX SplashScreen API tied to actual data-ready condition
        val splashScreen = try {
            installSplashScreen()
        } catch (e: Throwable) {
            Log.e("MainActivity", "Splash screen init note: ${e.message}")
            null
        }
        splashScreen?.setKeepOnScreenCondition {
            !storeViewModel.isDataReady.value
        }

        super.onCreate(savedInstanceState)

        // Read destination from launching intent (e.g. from Widget, Notification or Shortcut)
        val initialDest = intent?.getStringExtra("nav_destination") ?: intent?.getStringExtra("OPEN_DESTINATION")
        if (!initialDest.isNullOrBlank()) {
            targetRouteState.value = initialDest
        }

        // 2. Safely initialize managers without blocking or crashing startup
        try {
            LanguageManager.init(applicationContext)
            ThemeManager.init(applicationContext)
            StoreInfoManager.init(applicationContext)
            StaffManager.init(applicationContext)
            NetworkMonitor.init(applicationContext)
            com.example.utils.CameraPreferenceManager.init(applicationContext)
        } catch (e: Throwable) {
            Log.e("MainActivity", "Manager init note: ${e.message}")
        }

        // 3. Request high refresh rate mode safely
        try {
            applyHighRefreshRateMode()
        } catch (e: Throwable) {
            Log.w("MainActivity", "High refresh rate init note: ${e.message}")
        }

        // 4. Defer non-critical startup tasks (Notification Channel & Permissions)
        window.decorView.post {
            try {
                NotificationHelper.createNotificationChannel(applicationContext)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
                    }
                }
            } catch (e: Throwable) {
                Log.w("MainActivity", "Deferred init note: ${e.message}")
            }
        }

        enableEdgeToEdge()
        setContent {
            // Observing ThemeManager state for dark / light mode recomposition
            val themeMode = ThemeManager.themeMode
            val targetRoute = targetRouteState.value
            MyApplicationTheme {
                MainAppScreen(
                    initialTargetRoute = targetRoute,
                    onClearTargetRoute = { targetRouteState.value = null }
                )
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val newDest = intent.getStringExtra("nav_destination") ?: intent.getStringExtra("OPEN_DESTINATION")
        if (!newDest.isNullOrBlank()) {
            targetRouteState.value = newDest
        }
    }

    override fun onResume() {
        super.onResume()
        // Re-affirm high refresh rate when resuming from background
        applyHighRefreshRateMode()
        try {
            com.example.widget.TodaySalesWidgetProvider.triggerWidgetUpdate(this)
        } catch (e: Throwable) {
            // Safe fallback
        }
    }

    /**
     * Explicitly requests the display's maximum supported refresh rate (e.g. 144Hz, 120Hz, 90Hz)
     * preventing standard Android 60Hz throttling on high-refresh-rate displays.
     */
    private fun applyHighRefreshRateMode() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val currentDisplay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    display
                } else {
                    @Suppress("DEPRECATION")
                    windowManager.defaultDisplay
                }

                currentDisplay?.let { disp ->
                    val modes = disp.supportedModes
                    val maxRefreshMode = modes.maxByOrNull { it.refreshRate }
                    if (maxRefreshMode != null && maxRefreshMode.refreshRate > 60f) {
                        val layoutParams = window.attributes
                        layoutParams.preferredDisplayModeId = maxRefreshMode.modeId
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            @Suppress("DEPRECATION")
                            layoutParams.preferredRefreshRate = maxRefreshMode.refreshRate
                        }
                        window.attributes = layoutParams
                        Log.i("MainActivity", "✓ High refresh rate enabled: ${maxRefreshMode.refreshRate}Hz (Mode ID: ${maxRefreshMode.modeId})")
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("MainActivity", "High refresh rate configuration note: ${e.message}")
        }
    }

    /**
     * Proactive memory management: When the app is minimized (UI hidden) or the system is under
     * memory pressure, immediately release in-memory bitmap caches to minimize memory footprint.
     * This drastically reduces the likelihood of Android Low Memory Killer (LMK) terminating the process.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        try {
            if (level == ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN ||
                level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND ||
                level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL
            ) {
                // Clear Coil's in-memory bitmap cache
                Coil.imageLoader(this).memoryCache?.clear()
                Log.d("MainActivity", "Cleared image memory cache on trim level: $level")
            }
        } catch (e: Exception) {
            Log.w("MainActivity", "Trim memory note: ${e.message}")
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        try {
            Coil.imageLoader(this).memoryCache?.clear()
            Log.d("MainActivity", "Cleared image memory cache on low memory")
        } catch (e: Exception) {
            Log.w("MainActivity", "Low memory note: ${e.message}")
        }
    }
}

@Composable
fun MainAppScreen(
    initialTargetRoute: String? = null,
    onClearTargetRoute: () -> Unit = {}
) {
    val viewModel: StoreViewModel = viewModel()
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route ?: NavDestination.POS.route

    // Handle deep-link navigation from Widget or Shortcuts
    LaunchedEffect(initialTargetRoute) {
        if (!initialTargetRoute.isNullOrBlank()) {
            try {
                navController.navigate(initialTargetRoute) {
                    popUpTo(navController.graph.startDestinationId) { saveState = true }
                    launchSingleTop = true
                    restoreState = true
                }
            } catch (e: Exception) {
                Log.w("MainActivity", "Failed to navigate to deep link destination: $initialTargetRoute")
            }
            onClearTargetRoute()
        }
    }

    val authUser by viewModel.currentUser.collectAsState()
    val currentFirestoreRole by viewModel.currentFirestoreUserRole.collectAsState()
    val isUnrecognized = currentFirestoreRole?.isUnrecognized == true

    // 1. Mandatory Authentication Gate:
    // When the app is freshly installed or no user is signed in, strictly block access
    // to all store records, inventory, khata, and settings.
    if (authUser == null) {
        AuthGateScreen(viewModel = viewModel)
        return
    }

    // 2. Unrecognized Account Gate:
    // If an unrecognized account is signed in, enforce strict zero-access barrier immediately
    if (isUnrecognized && currentFirestoreRole != null) {
        UnrecognizedAccountGate(
            userRole = currentFirestoreRole!!,
            viewModel = viewModel
        )
        return
    }

    // 3. Security verification screen while loading permissions for non-admin accounts
    val isPermAdmin = authUser?.email?.trim()?.lowercase()?.let { it in PERMANENT_ADMIN_EMAILS } == true
    if (!isPermAdmin && currentFirestoreRole == null) {
        val context = androidx.compose.ui.platform.LocalContext.current
        var showExitOption by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            kotlinx.coroutines.delay(5000L)
            showExitOption = true
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
            contentAlignment = androidx.compose.ui.Alignment.Center
        ) {
            Column(
                horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(36.dp),
                    color = MaterialTheme.colorScheme.primary,
                    strokeWidth = 3.dp
                )
                Text(
                    text = if (LanguageManager.isBengali) "অনুমতি যাচাই করা হচ্ছে..." else "Verifying permissions...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (showExitOption) {
                    TextButton(
                        onClick = { viewModel.signOut(context) }
                    ) {
                        Text(
                            text = if (LanguageManager.isBengali) "লগ আউট / একাউন্ট পরিবর্তন করুন" else "Sign Out / Switch Account",
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
        return
    }

    // Real-time Staff Internet requirement check
    // 1. Owner / Admin session: 100% Offline capable, never blocked.
    // 2. Employee / Staff session: Requires active internet on THIS device. Blocked if offline.
    val isStaffBlocked = StaffManager.isStaffBlockedDueToOffline()

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            bottomBar = {
                AppBottomNavigationBar(
                    navController = navController,
                    currentRoute = currentRoute,
                    viewModel = viewModel
                )
            }
        ) { innerPadding ->
            NavHost(
                navController = navController,
                startDestination = NavDestination.POS.route,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .consumeWindowInsets(innerPadding),
                enterTransition = { AnimationTokens.ScreenEnterTransition },
                exitTransition = { AnimationTokens.ScreenExitTransition },
                popEnterTransition = { AnimationTokens.ScreenPopEnterTransition },
                popExitTransition = { AnimationTokens.ScreenPopExitTransition }
            ) {
                composable(NavDestination.POS.route) {
                    PosScreen(
                        viewModel = viewModel,
                        onNavigateToIncomingOrders = { navController.navigate("incoming_orders") }
                    )
                }
                composable("incoming_orders") {
                    com.example.ui.screens.orders.IncomingOrdersScreen(
                        viewModel = viewModel,
                        onNavigateBack = { navController.popBackStack() }
                    )
                }
                composable(NavDestination.INVENTORY.route) {
                    InventoryScreen(viewModel = viewModel)
                }
                composable(NavDestination.CREDIT.route) {
                    CreditScreen(viewModel = viewModel)
                }
                composable(NavDestination.EXPENSES.route) {
                    ExpenseScreen(viewModel = viewModel)
                }
                composable("employees") {
                    EmployeesScreen(
                        viewModel = viewModel,
                        onNavigateBack = { navController.popBackStack() }
                    )
                }
                composable("all_users") {
                    AllAppUsersScreen(
                        viewModel = viewModel,
                        onNavigateBack = { navController.popBackStack() }
                    )
                }
                composable(NavDestination.REPORTS.route) {
                    ReportsScreen(viewModel = viewModel)
                }
                composable(NavDestination.SETTINGS.route) {
                    SettingsScreen(viewModel = viewModel)
                }
            }
        }

        // Live Real-Time Staff Internet Requirement Guard
        if (isStaffBlocked) {
            StaffOfflineBlockOverlay(
                onRetry = {
                    NetworkMonitor.checkNow()
                }
            )
        }

        // Global Set Backup Password Dialog (triggers on new Google Sign-In or when requested)
        if (viewModel.showSetBackupPasswordDialog) {
            SetBackupPasswordDialog(
                viewModel = viewModel,
                onDismiss = { viewModel.closeSetBackupPasswordDialog() },
                isFirstTimePrompt = viewModel.isFirstTimeBackupPasswordPrompt
            )
        }
    }
}

/**
 * Isolated BottomNavigationBar composable.
 * Subscribes to badge counters (online orders, customer links) internally,
 * completely isolating outer Scaffold and NavHost from badge-related recompositions.
 */
@Composable
private fun AppBottomNavigationBar(
    navController: NavHostController,
    currentRoute: String?,
    viewModel: StoreViewModel
) {
    val pendingOnlineOrdersCount by viewModel.pendingOrdersCount.collectAsState()
    val pendingCustomerLinksCount by viewModel.pendingCustomerLinksCount.collectAsState()

    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 8.dp
    ) {
        NavDestination.values().forEach { destination ->
            val isSelected = currentRoute == destination.route
            NavigationBarItem(
                selected = isSelected,
                onClick = {
                    if (currentRoute != destination.route) {
                        navController.navigate(destination.route) {
                            popUpTo(navController.graph.startDestinationId) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                ),
                icon = {
                    if (destination == NavDestination.POS && pendingOnlineOrdersCount > 0) {
                        BadgedBox(
                            badge = {
                                Badge(
                                    containerColor = androidx.compose.ui.graphics.Color(0xFFEA580C),
                                    contentColor = androidx.compose.ui.graphics.Color.White
                                ) {
                                    Text("$pendingOnlineOrdersCount", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        ) {
                            Icon(
                                destination.icon,
                                contentDescription = destination.label
                            )
                        }
                    } else if (destination == NavDestination.CREDIT && pendingCustomerLinksCount > 0) {
                        BadgedBox(
                            badge = {
                                Badge(
                                    containerColor = com.example.ui.theme.StoreGold,
                                    contentColor = androidx.compose.ui.graphics.Color.White
                                ) {
                                    Text("$pendingCustomerLinksCount", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        ) {
                            Icon(
                                destination.icon,
                                contentDescription = destination.label
                            )
                        }
                    } else {
                        Icon(
                            destination.icon,
                            contentDescription = destination.label
                        )
                    }
                },
                label = {
                    Text(
                        text = destination.label,
                        fontSize = 10.sp,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                    )
                }
            )
        }
    }
}

