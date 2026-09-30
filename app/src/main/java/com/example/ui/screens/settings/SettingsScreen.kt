package com.example.ui.screens.settings

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import com.example.data.auth.AuthUser
import com.example.data.firestore.FirestoreUserRole
import com.example.data.local.entities.Employee
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.window.DialogProperties
import com.example.data.auth.GoogleSignInAvailability
import com.example.data.drive.DriveBackupFile
import com.example.ui.components.DeleteAllDataDialog
import com.example.ui.theme.*
import com.example.utils.AppThemeMode
import com.example.utils.LanguageManager
import com.example.utils.SmsHelper
import com.example.utils.StoreInfoManager
import com.example.utils.ThemeManager
import com.example.viewmodel.StoreViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: StoreViewModel) {
    val context = LocalContext.current

    if (!com.example.utils.StaffManager.canAccessSettings()) {
        com.example.ui.components.StaffAccessGate(
            screenTitle = "Store Settings & Backups",
            screenDescription = "Store configuration, cloud credentials, and database backups are restricted to the Store Owner."
        )
        return
    }

    val pairedPrinters = viewModel.pairedPrinters
    val selectedPrinterAddress = viewModel.selectedPrinterAddress
    val currentUser by viewModel.currentUser.collectAsState()
    val currentFirestoreUserRole by viewModel.currentFirestoreUserRole.collectAsState()
    val allEmployees by viewModel.allEmployees.collectAsState()

    val lastSyncTimestamp by viewModel.lastSyncTimestamp.collectAsState()
    val isSyncingAllData by viewModel.isSyncingAllData.collectAsState()

    val driveBackupsList by viewModel.driveBackupsList.collectAsState()
    val isDriveLoading by viewModel.isDriveLoading.collectAsState()
    val driveStatusMessage by viewModel.driveStatusMessage.collectAsState()
    val driveAccountEmail by viewModel.driveAccountEmail.collectAsState()
    val lastDriveBackupTimestamp by viewModel.lastDriveBackupTimestamp.collectAsState()
    val driveAuthIntent by viewModel.driveAuthIntent.collectAsState()
    val isBackfillingPublicLedger by viewModel.isBackfillingPublicLedger.collectAsState()
    val publicLedgerBackfillResult by viewModel.publicLedgerBackfillResult.collectAsState()

    val driveAuthLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        viewModel.clearDriveAuthIntent()
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            Toast.makeText(context, "Google Drive authorization granted! Retrying backup...", Toast.LENGTH_SHORT).show()
            viewModel.backupToGoogleDrive { success, msg ->
                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            }
        } else {
            Toast.makeText(context, "Drive authorization was cancelled or not granted.", Toast.LENGTH_SHORT).show()
        }
    }

    val smsPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            Toast.makeText(context, "SMS permission granted! Credit SMS is now active.", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "SMS permission was not granted.", Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(driveAuthIntent) {
        driveAuthIntent?.let { intent ->
            try {
                driveAuthLauncher.launch(intent)
            } catch (e: Exception) {
                android.util.Log.e("SettingsScreen", "Failed to launch Drive auth intent: ${e.message}")
            }
        }
    }

    var showDriveBackupDialog by remember { mutableStateOf(false) }
    var driveBackupNote by remember { mutableStateOf("") }
    var showRestoreDriveDialog by remember { mutableStateOf(false) }
    var fileToRestoreFromDrive by remember { mutableStateOf<DriveBackupFile?>(null) }
    var fileToDeleteFromDrive by remember { mutableStateOf<DriveBackupFile?>(null) }
    var showEditDriveAccountDialog by remember { mutableStateOf(false) }
    var editDriveEmailText by remember { mutableStateOf(driveAccountEmail ?: "") }

    var showEditStoreDialog by remember { mutableStateOf(false) }
    var showEditPdfFormatDialog by remember { mutableStateOf(false) }
    var showAuthDialog by remember { mutableStateOf(false) }
    var showManageEmployeesDialog by remember { mutableStateOf(false) }
    var showFullEmployeeHubDialog by remember { mutableStateOf(false) }
    var selectedEmployeeTab by remember { mutableStateOf(com.example.ui.screens.employees.EmployeeTab.ATTENDANCE) }
    var showManageFirestoreRolesDialog by remember { mutableStateOf(false) }
    var showAllAppUsersDialog by remember { mutableStateOf(false) }
    var showStaffSwitchDialog by remember { mutableStateOf(false) }
    var showChangeOwnerPinDialog by remember { mutableStateOf(false) }
    var showOffersHubDialog by remember { mutableStateOf(false) }
    var showKhataInterestDialog by remember { mutableStateOf(false) }
    var showConfirmBackfillDialog by remember { mutableStateOf(false) }
    var showBackfillSummaryDialog by remember { mutableStateOf(false) }
    var showDeleteAllDataDialog by remember { mutableStateOf(false) }
    var selectedTab by remember { mutableStateOf(SettingsTab.STORE_STAFF) }
    var isWidgetProfitEnabled by remember {
        mutableStateOf(com.example.widget.WidgetDataManager.isShowProfitEnabled(context))
    }
    var selectedDensity by remember { mutableStateOf(StoreInfoManager.thermalPrinterDensity) }
    var selectedFontSize by remember { mutableStateOf(StoreInfoManager.thermalReceiptFontSize) }
    var selectedPaperSize by remember { mutableStateOf(StoreInfoManager.pdfPaperSize) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp)
    ) {
        // Backup Password Setup Banner for Google Users who haven't set a backup password yet
        if (currentUser != null && currentUser?.hasGoogleProvider == true && currentUser?.hasPasswordProvider == false && !StoreInfoManager.backupPasswordBannerDismissed) {
            BackupPasswordReminderBanner(
                onSetPasswordClicked = { viewModel.openSetBackupPasswordDialog(isFirstTimePrompt = false) },
                onDismissClicked = { StoreInfoManager.setBackupPasswordBannerDismissed(true, context) },
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }

        // Settings Navigation Tabs
        Surface(
            color = CardBackground,
            shape = RoundedCornerShape(12.dp),
            shadowElevation = 2.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            ScrollableTabRow(
                selectedTabIndex = selectedTab.ordinal,
                edgePadding = 8.dp,
                containerColor = Color.Transparent,
                contentColor = StoreRedPrimary,
                divider = {},
                indicator = { tabPositions ->
                    if (selectedTab.ordinal in tabPositions.indices) {
                        val currentTabPosition = tabPositions[selectedTab.ordinal]
                        val widthVal = currentTabPosition.width.value
                        val leftVal = currentTabPosition.left.value
                        if (!widthVal.isNaN() && !leftVal.isNaN() && widthVal > 0f) {
                            Box(
                                modifier = Modifier
                                    .tabIndicatorOffset(currentTabPosition)
                                    .height(3.dp)
                                    .background(StoreRedPrimary, RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                            )
                        }
                    }
                }
            ) {
                SettingsTab.values().forEach { tab ->
                    val isSelected = selectedTab == tab
                    Tab(
                        selected = isSelected,
                        onClick = { selectedTab = tab },
                        text = {
                            Text(
                                text = tab.getLabel(),
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                fontSize = 12.sp,
                                color = if (isSelected) StoreRedPrimary else TextMuted
                            )
                        },
                        icon = {
                            Icon(
                                imageVector = tab.icon,
                                contentDescription = tab.getLabel(),
                                modifier = Modifier.size(18.dp),
                                tint = if (isSelected) StoreRedPrimary else TextMuted
                            )
                        }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Selected Tab Content
        when (selectedTab) {
            SettingsTab.STORE_STAFF -> {
                StoreAndStaffSection(
                    context = context,
                    viewModel = viewModel,
                    currentUser = currentUser,
                    currentFirestoreUserRole = currentFirestoreUserRole,
                    allEmployees = allEmployees,
                    onEditStoreClicked = { showEditStoreDialog = true },
                    onManageEmployeesClicked = { showManageEmployeesDialog = true },
                    onOpenAttendanceHubClicked = {
                        selectedEmployeeTab = com.example.ui.screens.employees.EmployeeTab.ATTENDANCE
                        showFullEmployeeHubDialog = true
                    },
                    onOpenPayrollHubClicked = {
                        selectedEmployeeTab = com.example.ui.screens.employees.EmployeeTab.SALARY
                        showFullEmployeeHubDialog = true
                    },
                    onOpenActivityLogHubClicked = {
                        selectedEmployeeTab = com.example.ui.screens.employees.EmployeeTab.LOGS
                        showFullEmployeeHubDialog = true
                    },
                    onChangeOwnerPinClicked = { showChangeOwnerPinDialog = true },
                    onSwitchStaffProfileClicked = { showStaffSwitchDialog = true }
                )
            }

            SettingsTab.BILLING_HARDWARE -> {
                BillingAndHardwareSection(
                    context = context,
                    viewModel = viewModel,
                    pairedPrinters = pairedPrinters,
                    selectedPrinterAddress = selectedPrinterAddress,
                    selectedDensity = selectedDensity,
                    onDensityChange = { selectedDensity = it },
                    selectedFontSize = selectedFontSize,
                    onFontSizeChange = { selectedFontSize = it },
                    selectedPaperSize = selectedPaperSize,
                    onPaperSizeChange = {
                        selectedPaperSize = it
                        viewModel.updatePaperSize(it)
                        StoreInfoManager.updatePaperSize(it, context)
                    },
                    onEditPdfFormatClicked = { showEditPdfFormatDialog = true },
                    onConfigureKhataInterestClicked = { showKhataInterestDialog = true },
                    onRequestSmsPermission = {
                        if (!SmsHelper.hasSmsPermission(context)) {
                            smsPermissionLauncher.launch(android.Manifest.permission.SEND_SMS)
                        }
                    },
                    onOpenOffersHubClicked = { showOffersHubDialog = true }
                )
            }

            SettingsTab.CLOUD_BACKUP -> {
                CloudAndBackupSection(
                    context = context,
                    viewModel = viewModel,
                    currentUser = currentUser,
                    currentFirestoreUserRole = currentFirestoreUserRole,
                    lastSyncTimestamp = lastSyncTimestamp,
                    isSyncingAllData = isSyncingAllData,
                    isBackfillingPublicLedger = isBackfillingPublicLedger,
                    driveAccountEmail = driveAccountEmail,
                    lastDriveBackupTimestamp = lastDriveBackupTimestamp,
                    isDriveLoading = isDriveLoading,
                    driveStatusMessage = driveStatusMessage,
                    onOpenAuthDialog = { showAuthDialog = true },
                    onManageFirestoreRolesClicked = { showManageFirestoreRolesDialog = true },
                    onAllAppUsersClicked = { showAllAppUsersDialog = true },
                    onConfirmBackfillClicked = { showConfirmBackfillDialog = true },
                    onOpenDriveBackupDialog = {
                        driveBackupNote = ""
                        showDriveBackupDialog = true
                    },
                    onOpenRestoreDriveDialog = { showRestoreDriveDialog = true },
                    onOpenEditDriveAccountDialog = {
                        editDriveEmailText = driveAccountEmail ?: currentUser?.email ?: "shuvajitsow384@gmail.com"
                        showEditDriveAccountDialog = true
                    }
                )
            }

            SettingsTab.SYSTEM -> {
                SystemPreferencesSection(
                    viewModel = viewModel,
                    context = context,
                    isWidgetProfitEnabled = isWidgetProfitEnabled,
                    onWidgetProfitChange = { isWidgetProfitEnabled = it },
                    onDeleteAllDataClicked = { showDeleteAllDataDialog = true }
                )
            }
        }

        Spacer(modifier = Modifier.height(32.dp))
    }

    // ==========================================
    // GOOGLE DRIVE BACKUP & RESTORE DIALOGS
    // ==========================================

    if (showKhataInterestDialog) {
        KhataInterestSettingsDialog(
            viewModel = viewModel,
            onDismiss = { showKhataInterestDialog = false }
        )
    }

    if (showConfirmBackfillDialog) {
        AlertDialog(
            onDismissRequest = { showConfirmBackfillDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.ReceiptLong, contentDescription = null, tint = StoreGold)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (LanguageManager.isBengali) "পাবলিক খাতা হিস্ট্রি ব্যাকফিল" else "Backfill Public Khata History",
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = if (LanguageManager.isBengali) {
                            "এটি ফায়ারস্টোরের মূল ledger_entries কালেকশন থেকে সমস্ত গ্রাহকের অতীতের হিসাবের প্রতিলিপি customers/{customerId}/public_ledger সাব-কালেকশনে সুরক্ষিত ব্যাচে (batch writes) লিখবে।"
                        } else {
                            "This will copy all existing customer ledger entries from the top-level 'ledger_entries' collection into each customer's 'customers/{customerId}/public_ledger' subcollection using the exact same document IDs."
                        },
                        fontSize = 13.sp,
                        color = TextDark
                    )
                    Text(
                        text = if (LanguageManager.isBengali) {
                            "• মূল ledger_entries অপরিবর্তিত থাকবে।\n• নিরাপদ ব্যাচে (batched writes) সম্পন্ন হবে।\n• পুনরায় চালালেও কোনো ডুপ্লিকেট তৈরি হবে না।"
                        } else {
                            "• Original ledger entries will NOT be modified or deleted.\n• Uses batched writes to prevent partial failures.\n• Idempotent: Can be safely re-run without creating duplicates."
                        },
                        fontSize = 12.sp,
                        color = TextMuted
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showConfirmBackfillDialog = false
                        viewModel.runPublicKhataBackfill {
                            showBackfillSummaryDialog = true
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = StoreGold)
                ) {
                    Text(if (LanguageManager.isBengali) "শুরু করুন" else "Start Backfill", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmBackfillDialog = false }) {
                    Text(if (LanguageManager.isBengali) "বাতিল" else "Cancel")
                }
            }
        )
    }

    if (showBackfillSummaryDialog && publicLedgerBackfillResult != null) {
        val result = publicLedgerBackfillResult!!
        AlertDialog(
            onDismissRequest = {
                showBackfillSummaryDialog = false
                viewModel.clearPublicLedgerBackfillResult()
            },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (result.isSuccess) Icons.Default.CheckCircle else Icons.Default.Warning,
                        contentDescription = null,
                        tint = if (result.isSuccess) StoreGreenProfit else StoreRedPrimary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (result.isSuccess) {
                            if (LanguageManager.isBengali) "ব্যাকফিল সম্পন্ন হয়েছে" else "Backfill Completed"
                        } else {
                            if (LanguageManager.isBengali) "ব্যাকফিল সতর্কতা / ত্রুটি" else "Backfill Partial / Error"
                        },
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Surface(
                        color = if (result.isSuccess) StoreGreenProfit.copy(alpha = 0.08f) else StoreRedPrimary.copy(alpha = 0.08f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = if (LanguageManager.isBengali) "📊 মোট গ্রাহক এন্ট্রি পাওয়া গেছে: ${result.totalFound}" else "📊 Total Customer Entries Found: ${result.totalFound}",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = TextDark
                            )
                            Text(
                                text = if (LanguageManager.isBengali) "✅ সফলভাবে কপি হয়েছে: ${result.copiedCount}" else "✅ Successfully Copied: ${result.copiedCount}",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = StoreGreenProfit
                            )
                            if (result.failedCount > 0) {
                                Text(
                                    text = if (LanguageManager.isBengali) "❌ ব্যর্থ হয়েছে: ${result.failedCount}" else "❌ Failed Entries: ${result.failedCount}",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = StoreRedPrimary
                                )
                            }
                        }
                    }

                    if (!result.errorMessage.isNullOrBlank()) {
                        Text(
                            text = "Error Details: ${result.errorMessage}",
                            fontSize = 11.sp,
                            color = StoreRedPrimary
                        )
                    } else if (result.isSuccess) {
                        Text(
                            text = if (LanguageManager.isBengali) {
                                "গ্রাহকগণ এখন পাবলিক খাতা লিঙ্কে তাদের অতীতের পূর্ণ লেনদেন হিস্ট্রি ও সঠিক লাইভ ব্যালেন্স দেখতে পাবেন।"
                            } else {
                                "All historical customer ledger transactions are now live in public_ledger subcollections. Customers can now view their complete transaction history."
                            },
                            fontSize = 12.sp,
                            color = TextMuted
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showBackfillSummaryDialog = false
                        viewModel.clearPublicLedgerBackfillResult()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = if (result.isSuccess) StoreGreenProfit else StorePrimary)
                ) {
                    Text(if (LanguageManager.isBengali) "ঠিক আছে" else "Done", fontWeight = FontWeight.Bold)
                }
            }
        )
    }

    if (showDriveBackupDialog) {
        AlertDialog(
            onDismissRequest = { showDriveBackupDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.CloudUpload, contentDescription = null, tint = StoreRedPrimary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Backup to Google Drive", fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Column {
                    Text(
                        text = "Exports all database records (products, sales, customers, suppliers, employees, ledger entries, expenses) as a single timestamped JSON file to your Google Drive folder 'Amar Dukan Backups'.",
                        fontSize = 13.sp,
                        color = TextDark
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = driveBackupNote,
                        onValueChange = { driveBackupNote = it },
                        label = { Text("Backup Note / Label (Optional)") },
                        placeholder = { Text("e.g., Before monthly closing") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val note = driveBackupNote.trim().ifBlank { null }
                        showDriveBackupDialog = false
                        viewModel.backupToGoogleDrive(labelNote = note) { success, msg ->
                            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = StoreRedPrimary)
                ) {
                    Text("Start Backup")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDriveBackupDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showRestoreDriveDialog) {
        AlertDialog(
            onDismissRequest = { showRestoreDriveDialog = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .heightIn(max = 600.dp),
            title = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CloudDownload, contentDescription = null, tint = StorePrimary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Restore from Google Drive", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    }
                    IconButton(
                        onClick = { viewModel.fetchGoogleDriveBackups() },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh", modifier = Modifier.size(18.dp))
                    }
                }
            },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "Folder: Google Drive > Amar Dukan Backups",
                        fontSize = 12.sp,
                        color = TextMuted
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    if (isDriveLoading && driveBackupsList.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(160.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator(color = StorePrimary)
                                Spacer(modifier = Modifier.height(8.dp))
                                Text("Connecting to Google Drive...", fontSize = 12.sp, color = TextMuted)
                            }
                        }
                    } else if (driveBackupsList.isEmpty()) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 16.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(
                                    Icons.Default.FolderOpen,
                                    contentDescription = null,
                                    tint = TextMuted,
                                    modifier = Modifier.size(36.dp)
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "No backups found in Google Drive.",
                                    fontWeight = FontWeight.Medium,
                                    fontSize = 13.sp,
                                    color = TextDark
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Tap 'Backup Now' on the Settings screen to create your first backup.",
                                    fontSize = 11.sp,
                                    color = TextMuted,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                )
                            }
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f, fill = false),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(driveBackupsList, key = { it.id }) { file ->
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                                    border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = file.name,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = TextDark,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Text(
                                                text = "${file.formattedDate} • ${file.formattedSize}",
                                                fontSize = 10.sp,
                                                color = TextMuted
                                            )
                                        }

                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            Button(
                                                onClick = {
                                                    fileToRestoreFromDrive = file
                                                },
                                                colors = ButtonDefaults.buttonColors(containerColor = StoreGreenProfit),
                                                shape = RoundedCornerShape(6.dp),
                                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                                modifier = Modifier.height(30.dp)
                                            ) {
                                                Text("Restore", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                            }

                                            IconButton(
                                                onClick = {
                                                    fileToDeleteFromDrive = file
                                                },
                                                modifier = Modifier.size(30.dp)
                                            ) {
                                                Icon(
                                                    Icons.Default.DeleteOutline,
                                                    contentDescription = "Delete",
                                                    tint = StoreRedPrimary,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showRestoreDriveDialog = false }) {
                    Text("Close")
                }
            }
        )
    }

    if (fileToRestoreFromDrive != null) {
        val target = fileToRestoreFromDrive!!
        AlertDialog(
            onDismissRequest = { fileToRestoreFromDrive = null },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Warning, contentDescription = null, tint = StoreRedPrimary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Restore from Google Drive?", fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Column {
                    Text(
                        text = "Are you sure you want to restore data from:\n\n📄 ${target.name}\n🕒 ${target.formattedDate} (${target.formattedSize})",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = TextDark
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = StoreRedPrimary.copy(alpha = 0.08f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "⚠️ WARNING: This will overwrite current products, sales, customers, suppliers, employees, ledger entries, and expenses with the data in this backup file.",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = StoreRedPrimary,
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val file = target
                        fileToRestoreFromDrive = null
                        showRestoreDriveDialog = false
                        viewModel.restoreFromGoogleDriveBackup(file) { success, msg ->
                            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = StoreRedPrimary)
                ) {
                    Text("Yes, Overwrite & Restore")
                }
            },
            dismissButton = {
                TextButton(onClick = { fileToRestoreFromDrive = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (fileToDeleteFromDrive != null) {
        val target = fileToDeleteFromDrive!!
        AlertDialog(
            onDismissRequest = { fileToDeleteFromDrive = null },
            title = { Text("Delete Backup from Drive?", fontWeight = FontWeight.Bold) },
            text = { Text("Permanently delete '${target.name}' from your Google Drive folder?") },
            confirmButton = {
                Button(
                    onClick = {
                        val fileId = target.id
                        fileToDeleteFromDrive = null
                        viewModel.deleteGoogleDriveBackup(fileId) { success, msg ->
                            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = StoreRedPrimary)
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { fileToDeleteFromDrive = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showEditDriveAccountDialog) {
        var availableDeviceAccounts by remember { mutableStateOf(viewModel.googleDriveManager.getAvailableGoogleAccounts()) }

        AlertDialog(
            onDismissRequest = { showEditDriveAccountDialog = false },
            title = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.AccountCircle, contentDescription = null, tint = StorePrimary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Google Drive Account", fontWeight = FontWeight.Bold)
                    }
                    IconButton(
                        onClick = { availableDeviceAccounts = viewModel.googleDriveManager.getAvailableGoogleAccounts() },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh Accounts", modifier = Modifier.size(18.dp))
                    }
                }
            },
            text = {
                Column {
                    Text(
                        text = "Select or enter the Google Account email to use for Google Drive backups:",
                        fontSize = 13.sp,
                        color = TextDark
                    )

                    if (availableDeviceAccounts.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "Detected Accounts on Device:",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = StorePrimary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        availableDeviceAccounts.forEach { acc ->
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = if (editDriveEmailText.trim().equals(acc, ignoreCase = true)) StorePrimary.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                border = if (editDriveEmailText.trim().equals(acc, ignoreCase = true)) BorderStroke(1.dp, StorePrimary) else null,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp)
                                    .clickable { editDriveEmailText = acc }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        if (editDriveEmailText.trim().equals(acc, ignoreCase = true)) Icons.Default.CheckCircle else Icons.Default.AccountCircle,
                                        contentDescription = null,
                                        tint = if (editDriveEmailText.trim().equals(acc, ignoreCase = true)) StorePrimary else TextMuted,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = acc,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = TextDark
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = editDriveEmailText,
                        onValueChange = { editDriveEmailText = it },
                        label = { Text("Google Account Email") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email)
                    )

                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = {
                            try {
                                val intent = Intent(android.provider.Settings.ACTION_SYNC_SETTINGS).apply {
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                }
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                try {
                                    val addIntent = Intent(android.provider.Settings.ACTION_ADD_ACCOUNT).apply {
                                        putExtra(android.provider.Settings.EXTRA_ACCOUNT_TYPES, arrayOf("com.google"))
                                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                    }
                                    context.startActivity(addIntent)
                                } catch (e2: Exception) {
                                    Toast.makeText(context, "Open Android Settings > Accounts to add a Google account", Toast.LENGTH_LONG).show()
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(6.dp),
                        contentPadding = PaddingValues(vertical = 4.dp)
                    ) {
                        Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Device Account Settings", fontSize = 11.sp)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val newEmail = editDriveEmailText.trim()
                        if (newEmail.isNotBlank()) {
                            viewModel.setDriveAccountEmail(newEmail)
                            Toast.makeText(context, "Google Drive account set to: $newEmail", Toast.LENGTH_SHORT).show()
                            showEditDriveAccountDialog = false
                        } else {
                            Toast.makeText(context, "Please enter a valid email", Toast.LENGTH_SHORT).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = StorePrimary)
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showEditDriveAccountDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showEditStoreDialog) {
        EditStoreInformationDialog(onDismiss = { showEditStoreDialog = false })
    }

    if (showFullEmployeeHubDialog) {
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { showFullEmployeeHubDialog = false },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                com.example.ui.screens.employees.EmployeesScreen(
                    viewModel = viewModel,
                    initialTab = selectedEmployeeTab,
                    onNavigateBack = { showFullEmployeeHubDialog = false }
                )
            }
        }
    }

    if (showChangeOwnerPinDialog) {
        com.example.ui.components.ChangeOwnerPinDialog(
            onDismiss = { showChangeOwnerPinDialog = false }
        )
    }

    if (showManageEmployeesDialog) {
        com.example.ui.components.ManageEmployeesDialog(
            viewModel = viewModel,
            onDismiss = { showManageEmployeesDialog = false }
        )
    }

    if (showManageFirestoreRolesDialog) {
        com.example.ui.components.ManageFirestoreRolesDialog(
            viewModel = viewModel,
            onDismiss = { showManageFirestoreRolesDialog = false }
        )
    }

    if (showAllAppUsersDialog) {
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { showAllAppUsersDialog = false },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                com.example.ui.screens.users.AllAppUsersScreen(
                    viewModel = viewModel,
                    onNavigateBack = { showAllAppUsersDialog = false }
                )
            }
        }
    }

    if (showStaffSwitchDialog) {
        com.example.ui.components.StaffSwitchDialog(
            employees = allEmployees,
            onDismiss = { showStaffSwitchDialog = false },
            onManageEmployees = { showManageEmployeesDialog = true }
        )
    }

    if (showEditPdfFormatDialog) {
        com.example.ui.components.EditPdfFormatDialog(onDismiss = { showEditPdfFormatDialog = false })
    }

    if (showAuthDialog) {
        AuthDialog(
            viewModel = viewModel,
            onDismiss = { showAuthDialog = false }
        )
    }

    if (showOffersHubDialog) {
        com.example.ui.screens.offers.OffersManagementDialog(
            viewModel = viewModel,
            onDismiss = { showOffersHubDialog = false }
        )
    }

    if (showDeleteAllDataDialog) {
        DeleteAllDataDialog(
            viewModel = viewModel,
            isGoogleSignedIn = currentUser != null,
            onDismiss = { showDeleteAllDataDialog = false }
        )
    }
}

@Composable
fun AuthDialog(
    viewModel: StoreViewModel,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    var isSignUp by remember { mutableStateOf(false) }
    var isForgotPasswordMode by remember { mutableStateOf(false) }
    var resetEmailSent by remember { mutableStateOf(false) }
    var resetEmail by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var localError by remember { mutableStateOf<String?>(null) }
    val googleStatus by viewModel.googleSignInStatus.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.checkGoogleSignInAvailability(context)
    }

    fun dismissCleanly() {
        keyboardController?.hide()
        focusManager.clearFocus()
        onDismiss()
    }

    AlertDialog(
        onDismissRequest = { dismissCleanly() },
        properties = DialogProperties(decorFitsSystemWindows = true),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (isForgotPasswordMode) Icons.Default.LockReset else Icons.Default.AccountCircle,
                    contentDescription = null,
                    tint = StorePrimary,
                    modifier = Modifier.size(28.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = when {
                        isForgotPasswordMode -> "Reset Password"
                        isSignUp -> "Create Firebase Account"
                        else -> "Sign In to Amar Dukan"
                    },
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (isForgotPasswordMode) {
                    if (resetEmailSent) {
                        Surface(
                            color = StoreGreenProfit.copy(alpha = 0.08f),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, StoreGreenProfit.copy(alpha = 0.3f)),
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
                                            modifier = Modifier.size(28.dp)
                                        )
                                    }
                                }
                                Text(
                                    text = "Reset Request Dispatched",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = StoreGreenProfit
                                )
                                Text(
                                    text = "Instructions were dispatched for:",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextDark,
                                    textAlign = TextAlign.Center
                                )
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = MaterialTheme.colorScheme.surface,
                                    border = BorderStroke(1.dp, Color(0xFFCBD5E1)),
                                    modifier = Modifier.padding(horizontal = 4.dp)
                                ) {
                                    Text(
                                        text = resetEmail.ifBlank { email },
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = StorePrimary,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                    )
                                }

                                Surface(
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 4.dp)
                                ) {
                                    Column(
                                        modifier = Modifier.padding(10.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Text(
                                            text = "Haven't received the email yet?",
                                            fontWeight = FontWeight.Bold,
                                            style = MaterialTheme.typography.labelMedium,
                                            color = TextDark
                                        )
                                        Text(
                                            text = "• Check your Spam, Junk, or Updates / Promotions folder in Gmail for emails from noreply@amar-dukan-40808.firebaseapp.com.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = TextMuted
                                        )
                                        Text(
                                            text = "• If you haven't created an account yet on this project, Firebase will not deliver an email. Tap 'Create Account Now' below to register with this email.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = TextMuted
                                        )
                                        Text(
                                            text = "• If you previously used Google login, tap 'Continue with Google' on the main sign in page.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = TextMuted
                                        )
                                    }
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    OutlinedButton(
                                        onClick = {
                                            val targetEmail = (resetEmail.ifBlank { email }).trim()
                                            viewModel.sendPasswordReset(targetEmail) { success, msg ->
                                                Toast.makeText(
                                                    context,
                                                    if (success) "Reset link resent! Check spam folder." else (msg ?: "Error sending reset email"),
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                            }
                                        },
                                        modifier = Modifier.weight(1f),
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Text("Resend Link", fontSize = 12.sp)
                                    }

                                    Button(
                                        onClick = {
                                            email = resetEmail.ifBlank { email }
                                            isSignUp = true
                                            isForgotPasswordMode = false
                                            resetEmailSent = false
                                        },
                                        modifier = Modifier.weight(1f),
                                        shape = RoundedCornerShape(8.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = StorePrimary)
                                    ) {
                                        Text("Create Account", fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    } else {
                        Text(
                            text = "Enter your registered email address below. We'll dispatch a password reset link to create a new password.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextMuted
                        )

                        Surface(
                            color = Color(0xFFF1F5F9),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.Info,
                                    contentDescription = null,
                                    tint = StorePrimary,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Never registered before? You can tap 'Create Account' directly without resetting.",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontSize = 11.sp,
                                    color = TextDark
                                )
                            }
                        }

                        OutlinedTextField(
                            value = resetEmail,
                            onValueChange = { resetEmail = it; localError = null },
                            label = { Text("Registered Email Address") },
                            placeholder = { Text("e.g. name@example.com") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Email,
                                imeAction = ImeAction.Send
                            ),
                            keyboardActions = KeyboardActions(
                                onSend = {
                                    keyboardController?.hide()
                                    focusManager.clearFocus()
                                    val targetEmail = resetEmail.trim()
                                    if (targetEmail.isBlank() || !android.util.Patterns.EMAIL_ADDRESS.matcher(targetEmail).matches()) {
                                        localError = "Please enter a valid email address (e.g. name@example.com)."
                                        return@KeyboardActions
                                    }
                                    viewModel.sendPasswordReset(targetEmail) { success, msg ->
                                        if (success) {
                                            resetEmailSent = true
                                            localError = null
                                            Toast.makeText(context, "Password reset email sent!", Toast.LENGTH_LONG).show()
                                        } else {
                                            localError = msg ?: "Failed to send password reset email."
                                        }
                                    }
                                }
                            ),
                            modifier = Modifier.fillMaxWidth(),
                            leadingIcon = { Icon(Icons.Default.Email, contentDescription = null) },
                            trailingIcon = {
                                if (resetEmail.isNotBlank()) {
                                    IconButton(onClick = { resetEmail = "" }) {
                                        Icon(Icons.Default.Clear, contentDescription = "Clear")
                                    }
                                }
                            }
                        )

                        val displayErr = localError ?: viewModel.authError
                        if (displayErr != null) {
                            Surface(
                                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = displayErr,
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(8.dp)
                                )
                            }
                        }

                        Button(
                            onClick = {
                                keyboardController?.hide()
                                focusManager.clearFocus()
                                val targetEmail = resetEmail.trim()
                                if (targetEmail.isBlank() || !android.util.Patterns.EMAIL_ADDRESS.matcher(targetEmail).matches()) {
                                    localError = "Please enter a valid email address (e.g. name@example.com)."
                                    return@Button
                                }
                                viewModel.sendPasswordReset(targetEmail) { success, msg ->
                                    if (success) {
                                        resetEmailSent = true
                                        localError = null
                                        Toast.makeText(context, "Password reset email sent!", Toast.LENGTH_LONG).show()
                                    } else {
                                        localError = msg ?: "Failed to send password reset email."
                                    }
                                }
                            },
                            enabled = !viewModel.isAuthLoading,
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = StorePrimary),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (viewModel.isAuthLoading) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Default.Send, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Send Password Reset Link", fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    TextButton(
                        onClick = {
                            isForgotPasswordMode = false
                            resetEmailSent = false
                            localError = null
                        },
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Back to Sign In", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                } else {
                    Text(
                        text = "Sign in to enable Firestore real-time cloud sync across your phones.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted
                    )

                    // Fallback check status banner if Google Sign-In is unavailable
                    val currentStatus = googleStatus
                    if (currentStatus is GoogleSignInAvailability.Unavailable && localError == null) {
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                verticalAlignment = Alignment.Top
                            ) {
                                Icon(
                                    Icons.Default.Info,
                                    contentDescription = null,
                                    tint = StorePrimary,
                                    modifier = Modifier
                                        .size(18.dp)
                                        .padding(top = 2.dp)
                                    )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(
                                        text = if (currentStatus.isSha1Conflict) "Google Sign-In Notice (SHA-1 Conflict)" else "Google Sign-In Notice",
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                    Text(
                                        text = "${currentStatus.reason} ${currentStatus.suggestedAction}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.85f)
                                    )
                                }
                            }
                        }
                    }

                    // Google One-Tap Sign In
                    Button(
                        onClick = {
                            keyboardController?.hide()
                            focusManager.clearFocus()
                            localError = null
                            viewModel.authError = null
                            viewModel.signInWithGoogle(context) { success, msg ->
                                if (success) {
                                    Toast.makeText(context, msg ?: "Signed in successfully!", Toast.LENGTH_SHORT).show()
                                    dismissCleanly()
                                } else if (!msg.isNullOrBlank() && !msg.contains("cancel", ignoreCase = true) && !msg.contains("dismiss", ignoreCase = true)) {
                                    localError = msg
                                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                                } else {
                                    localError = null
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = StorePrimary),
                        enabled = !viewModel.isAuthLoading
                    ) {
                        if (viewModel.isAuthLoading) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.AccountCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Continue with Google", fontWeight = FontWeight.Bold)
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        HorizontalDivider(modifier = Modifier.weight(1f))
                        Text(
                            text = " OR WITH EMAIL ",
                            fontSize = 10.sp,
                            color = TextMuted,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp)
                        )
                        HorizontalDivider(modifier = Modifier.weight(1f))
                    }

                    OutlinedTextField(
                        value = email,
                        onValueChange = { 
                            email = it
                            localError = null
                            viewModel.authError = null
                        },
                        label = { Text("Email Address") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Email,
                            imeAction = ImeAction.Next
                        ),
                        modifier = Modifier.fillMaxWidth(),
                        leadingIcon = { Icon(Icons.Default.Email, contentDescription = null) }
                    )

                    OutlinedTextField(
                        value = password,
                        onValueChange = { 
                            password = it
                            localError = null
                            viewModel.authError = null
                        },
                        label = { Text("Password (min 6 characters)") },
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

                    val displayErr = (localError ?: viewModel.authError)?.takeIf { 
                        !it.contains("cancelled", ignoreCase = true) && 
                        !it.contains("canceled", ignoreCase = true) &&
                        !it.contains("dismissed", ignoreCase = true)
                    }
                    if (displayErr != null) {
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = displayErr,
                                        color = MaterialTheme.colorScheme.error,
                                        style = MaterialTheme.typography.bodySmall,
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
                                            Icons.Default.Close,
                                            contentDescription = "Dismiss Error",
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                                if (!isSignUp && (displayErr.contains("Create Account", ignoreCase = true) || displayErr.contains("Incorrect password", ignoreCase = true))) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        TextButton(
                                            onClick = { 
                                                isSignUp = true
                                                localError = null
                                                viewModel.authError = null
                                            },
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                            modifier = Modifier.height(28.dp)
                                        ) {
                                            Text("Switch to Create Account", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = StorePrimary)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        TextButton(onClick = { isSignUp = !isSignUp; localError = null }) {
                            Text(
                                text = if (isSignUp) "Already have an account? Sign In" else "New user? Create Account",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        if (!isSignUp) {
                            TextButton(
                                onClick = {
                                    resetEmail = email.trim()
                                    resetEmailSent = false
                                    localError = null
                                    isForgotPasswordMode = true
                                }
                            ) {
                                Icon(Icons.Default.LockReset, contentDescription = null, modifier = Modifier.size(15.dp), tint = StorePrimary)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "Forgot Password? Reset Here",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = StorePrimary
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (!isForgotPasswordMode) {
                Button(
                    onClick = {
                        keyboardController?.hide()
                        focusManager.clearFocus()
                        if (email.isBlank() || password.length < 6) {
                            localError = "Please enter a valid email and 6+ character password."
                            return@Button
                        }
                        if (isSignUp) {
                            viewModel.signUpWithEmail(email, password) { success, msg ->
                                if (success) {
                                    Toast.makeText(context, msg ?: "Account created!", Toast.LENGTH_SHORT).show()
                                    dismissCleanly()
                                } else {
                                    localError = msg
                                }
                            }
                        } else {
                            viewModel.signInWithEmail(email, password) { success, msg ->
                                if (success) {
                                    Toast.makeText(context, msg ?: "Signed in!", Toast.LENGTH_SHORT).show()
                                    dismissCleanly()
                                } else {
                                    localError = msg
                                }
                            }
                        }
                    },
                    enabled = !viewModel.isAuthLoading,
                    colors = ButtonDefaults.buttonColors(containerColor = StorePrimary)
                ) {
                    if (viewModel.isAuthLoading) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                    } else {
                        Text(if (isSignUp) "Create Account" else "Sign In")
                    }
                }
            } else if (resetEmailSent) {
                Button(
                    onClick = {
                        isForgotPasswordMode = false
                        resetEmailSent = false
                        localError = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = StorePrimary)
                ) {
                    Text("Return to Sign In")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = { dismissCleanly() }) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun EditStoreInformationDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var name by remember { mutableStateOf(StoreInfoManager.storeName) }
    var address by remember { mutableStateOf(StoreInfoManager.storeAddress) }
    var owner by remember { mutableStateOf(StoreInfoManager.ownerName) }
    var phone by remember { mutableStateOf(StoreInfoManager.phone) }
    var tagline by remember { mutableStateOf(StoreInfoManager.tagline) }
    var gstin by remember { mutableStateOf(StoreInfoManager.gstin) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Storefront, contentDescription = null, tint = StoreRedPrimary)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Edit Store Information", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Store Name *") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    leadingIcon = { Icon(Icons.Default.Store, contentDescription = null) }
                )

                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it },
                    label = { Text("Store Address / Location *") },
                    modifier = Modifier.fillMaxWidth(),
                    leadingIcon = { Icon(Icons.Default.LocationOn, contentDescription = null) }
                )

                OutlinedTextField(
                    value = owner,
                    onValueChange = { owner = it },
                    label = { Text("Owner / Manager Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) }
                )

                OutlinedTextField(
                    value = phone,
                    onValueChange = { phone = it },
                    label = { Text("Contact Phone / WhatsApp") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    leadingIcon = { Icon(Icons.Default.Phone, contentDescription = null) }
                )

                OutlinedTextField(
                    value = tagline,
                    onValueChange = { tagline = it },
                    label = { Text("Store Tagline / Slogan (Optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    leadingIcon = { Icon(Icons.Default.FormatQuote, contentDescription = null) }
                )

                OutlinedTextField(
                    value = gstin,
                    onValueChange = { gstin = it },
                    label = { Text("GSTIN / Trade License No. (Optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    leadingIcon = { Icon(Icons.Default.ReceiptLong, contentDescription = null) }
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (name.isBlank()) {
                        Toast.makeText(context, "Store name cannot be blank!", Toast.LENGTH_SHORT).show()
                        return@Button
                    }
                    StoreInfoManager.updateStoreInfo(
                        name = name,
                        address = address,
                        owner = owner,
                        phoneNum = phone,
                        taglineStr = tagline,
                        gstinStr = gstin,
                        context = context
                    )
                    Toast.makeText(context, "Store Information Updated!", Toast.LENGTH_SHORT).show()
                    onDismiss()
                },
                colors = ButtonDefaults.buttonColors(containerColor = StoreRedPrimary)
            ) {
                Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Save Changes")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
