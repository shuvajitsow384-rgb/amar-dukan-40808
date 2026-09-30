package com.example.ui.components

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.ui.theme.*
import com.example.utils.LanguageManager
import com.example.utils.ThemeManager
import com.example.viewmodel.StoreViewModel

@Composable
fun DeleteAllDataDialog(
    viewModel: StoreViewModel,
    isGoogleSignedIn: Boolean,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val isDeleting by viewModel.isDeletingAllData.collectAsState()
    val isBn = LanguageManager.isBengali
    val isDark = ThemeManager.isDarkMode()

    var deleteLocalData by remember { mutableStateOf(true) }
    var deleteCloudData by remember { mutableStateOf(true) }
    var deleteLocalBackups by remember { mutableStateOf(true) }
    var deleteDriveBackups by remember { mutableStateOf(isGoogleSignedIn) }

    var confirmationText by remember { mutableStateOf("") }
    val isConfirmed = confirmationText.trim().equals("DELETE", ignoreCase = true) || confirmationText.trim() == "ডিলিট" || confirmationText.trim() == "দিলীট"
    val hasSelectedOption = deleteLocalData || deleteCloudData || deleteLocalBackups || (deleteDriveBackups && isGoogleSignedIn)

    val dangerRed = Color(0xFFDC2626)
    val cardBg = if (isDark) Color(0xFF1E293B) else Color(0xFFFFFFFF)
    val warningBg = if (isDark) Color(0xFF3B1818) else Color(0xFFFEF2F2)
    val textColor = if (isDark) Color(0xFFF1F5F9) else Color(0xFF0F172A)
    val subTextColor = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B)

    Dialog(
        onDismissRequest = {
            if (!isDeleting) onDismiss()
        },
        properties = DialogProperties(
            dismissOnBackPress = !isDeleting,
            dismissOnClickOutside = !isDeleting,
            usePlatformDefaultWidth = false
        )
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .padding(vertical = 24.dp),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = cardBg),
            elevation = CardDefaults.cardElevation(8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp)
            ) {
                // Header with Danger Warning
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = dangerRed.copy(alpha = 0.15f),
                        modifier = Modifier.size(44.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.DeleteForever,
                                contentDescription = null,
                                tint = dangerRed,
                                modifier = Modifier.size(26.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = if (isBn) "অ্যাপ ও ক্লাউড ডেটা মুছুন" else "Delete App & Cloud Data",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = dangerRed
                        )
                        Text(
                            text = if (isBn) "স্থায়ীভাবে মুছে ফেলার বিকল্প" else "Permanent Data Wipe",
                            fontSize = 12.sp,
                            color = subTextColor
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Warning Banner
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = warningBg,
                    border = BorderStroke(1.dp, dangerRed.copy(alpha = 0.35f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = dangerRed,
                            modifier = Modifier
                                .size(20.dp)
                                .padding(top = 1.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = if (isBn)
                                "সতর্কতা: এই ক্রিয়াটি অপরিবর্তনীয়! নির্বাচিত সমস্ত ইনভেন্টরি, বিক্রয়, বাকি খাতা ও ক্লাউড ব্যাকআপ স্থায়ীভাবে মুছে যাবে।"
                            else
                                "Warning: This action is permanent and irreversible! All selected inventory, sales, customer ledger, and cloud synchronization data will be completely deleted.",
                            fontSize = 12.sp,
                            color = if (isDark) Color(0xFFFCA5A5) else Color(0xFF991B1B),
                            lineHeight = 16.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = if (isBn) "কী কী মুছতে চান নির্বাচন করুন:" else "Select data to delete:",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = textColor
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Option 1: Local App Data
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = if (deleteLocalData) dangerRed.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                    border = BorderStroke(1.dp, if (deleteLocalData) dangerRed.copy(alpha = 0.4f) else Color.Transparent),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !isDeleting) { deleteLocalData = !deleteLocalData }
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = deleteLocalData,
                            onCheckedChange = { deleteLocalData = it },
                            enabled = !isDeleting,
                            colors = CheckboxDefaults.colors(checkedColor = dangerRed)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (isBn) "স্থানীয় অ্যাপ ডেটা (Phone Database)" else "Local App Database",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = textColor
                            )
                            Text(
                                text = if (isBn) "পণ্য, বিক্রয় রেকর্ড, কাস্টমার বাকি খাতা, সাপ্লায়ার, খরচ ও কর্মী তালিকা" else "Products, sales, stock, customer khata balances, suppliers, expenses & staff",
                                fontSize = 11.sp,
                                color = subTextColor
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Option 2: Cloud Database (Firestore)
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = if (deleteCloudData) dangerRed.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                    border = BorderStroke(1.dp, if (deleteCloudData) dangerRed.copy(alpha = 0.4f) else Color.Transparent),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !isDeleting) { deleteCloudData = !deleteCloudData }
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = deleteCloudData,
                            onCheckedChange = { deleteCloudData = it },
                            enabled = !isDeleting,
                            colors = CheckboxDefaults.colors(checkedColor = dangerRed)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (isBn) "ক্লাউড ডেটাবেস (Firebase Cloud)" else "Firebase Cloud Database",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = textColor
                            )
                            Text(
                                text = if (isBn) "সকল ডিভাইসের লাইভ সিঙ্ক করা ক্লাউড ডাটাবেস রেকর্ড ও স্ন্যাপশট" else "All live synced records, backup snapshots and cloud reports across all devices",
                                fontSize = 11.sp,
                                color = subTextColor
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Option 3: Local Backup Files
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = if (deleteLocalBackups) dangerRed.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                    border = BorderStroke(1.dp, if (deleteLocalBackups) dangerRed.copy(alpha = 0.4f) else Color.Transparent),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !isDeleting) { deleteLocalBackups = !deleteLocalBackups }
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = deleteLocalBackups,
                            onCheckedChange = { deleteLocalBackups = it },
                            enabled = !isDeleting,
                            colors = CheckboxDefaults.colors(checkedColor = dangerRed)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (isBn) "স্থানীয় ব্যাকআপ ফাইল (Local JSON Files)" else "Local JSON Backup Files",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = textColor
                            )
                            Text(
                                text = if (isBn) "ফোনে সেভ থাকা অটো ও ম্যানুয়াল ব্যাকআপ ফাইলসমূহ" else "Auto-backup snapshots saved locally in phone storage",
                                fontSize = 11.sp,
                                color = subTextColor
                            )
                        }
                    }
                }

                if (isGoogleSignedIn) {
                    Spacer(modifier = Modifier.height(8.dp))

                    // Option 4: Google Drive Backups
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (deleteDriveBackups) dangerRed.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                        border = BorderStroke(1.dp, if (deleteDriveBackups) dangerRed.copy(alpha = 0.4f) else Color.Transparent),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !isDeleting) { deleteDriveBackups = !deleteDriveBackups }
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = deleteDriveBackups,
                                onCheckedChange = { deleteDriveBackups = it },
                                enabled = !isDeleting,
                                colors = CheckboxDefaults.colors(checkedColor = dangerRed)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (isBn) "গুগল ড্রাইভ ব্যাকআপ (Google Drive)" else "Google Drive Backups",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = textColor
                                )
                                Text(
                                    text = if (isBn) "গুগল ড্রাইভের 'Amar Dukan Backups' ফোল্ডারের ফাইলসমূহ" else "All backup files in 'Amar Dukan Backups' on Google Drive",
                                    fontSize = 11.sp,
                                    color = subTextColor
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Confirmation Input Requirement with Quick-Fill Action
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (isBn) "নিশ্চিত করতে নিচে DELETE লিখুন:" else "Type DELETE below to confirm:",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = textColor
                    )

                    // 1-Tap Quick Auto Fill Button
                    Surface(
                        onClick = {
                            confirmationText = "DELETE"
                            focusManager.clearFocus()
                        },
                        shape = RoundedCornerShape(6.dp),
                        color = if (isConfirmed) Color(0xFF16A34A).copy(alpha = 0.15f) else dangerRed.copy(alpha = 0.12f),
                        border = BorderStroke(1.dp, if (isConfirmed) Color(0xFF16A34A) else dangerRed.copy(alpha = 0.5f)),
                        modifier = Modifier.height(28.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (isConfirmed) Icons.Default.CheckCircle else Icons.Default.TouchApp,
                                contentDescription = null,
                                tint = if (isConfirmed) Color(0xFF16A34A) else dangerRed,
                                modifier = Modifier.size(13.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = if (isConfirmed) {
                                    if (isBn) "পূরণ করা হয়েছে ✓" else "Filled ✓"
                                } else {
                                    if (isBn) "অটো লিখুন (DELETE)" else "Auto Fill (DELETE)"
                                },
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isConfirmed) Color(0xFF16A34A) else dangerRed
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                OutlinedTextField(
                    value = confirmationText,
                    onValueChange = { confirmationText = it },
                    placeholder = { Text(if (isBn) "এখানে DELETE লিখুন..." else "Type DELETE here...", fontSize = 13.sp, color = subTextColor.copy(alpha = 0.6f)) },
                    trailingIcon = {
                        if (isConfirmed) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = "Confirmed",
                                tint = Color(0xFF16A34A),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    },
                    singleLine = true,
                    enabled = !isDeleting,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = if (isConfirmed) Color(0xFF16A34A) else dangerRed,
                        unfocusedBorderColor = if (isConfirmed) Color(0xFF16A34A) else subTextColor.copy(alpha = 0.4f)
                    ),
                    shape = RoundedCornerShape(10.dp)
                )

                // Helper Text explaining button state
                Spacer(modifier = Modifier.height(4.dp))
                if (!isConfirmed) {
                    Text(
                        text = if (isBn) "🔒 'সব মুছুন' বাটন চালু করতে উপরে DELETE লিখুন বা 'অটো লিখুন' বাটনে চাপুন।"
                               else "🔒 Type DELETE or tap 'Auto Fill' above to enable the Delete All button.",
                        fontSize = 11.sp,
                        color = dangerRed,
                        lineHeight = 14.sp
                    )
                } else {
                    Text(
                        text = if (isBn) "✓ নিশ্চিতকরণ সম্পন্ন হয়েছে। এখন 'সব মুছুন' চাপুন।"
                               else "✓ Confirmed! Tap 'Delete All' to permanently erase data.",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF16A34A),
                        lineHeight = 14.sp
                    )
                }

                if (isDeleting) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator(
                            color = dangerRed,
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.5.dp
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = if (isBn) "ডেটা মুছে ফেলা হচ্ছে..." else "Deleting selected data...",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = dangerRed
                        )
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Actions
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        enabled = !isDeleting,
                        modifier = Modifier
                            .weight(1f)
                            .height(46.dp),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text(
                            text = if (isBn) "বাতিল" else "Cancel",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    Button(
                        onClick = {
                            focusManager.clearFocus()
                            viewModel.deleteAllAppData(
                                deleteLocal = deleteLocalData,
                                deleteCloud = deleteCloudData,
                                deleteLocalBackups = deleteLocalBackups,
                                deleteDriveBackups = deleteDriveBackups && isGoogleSignedIn
                            ) { success, msg ->
                                if (success) {
                                    Toast.makeText(
                                        context,
                                        if (isBn) "সমস্ত নির্বাচিত ডেটা সফলভাবে মুছে ফেলা হয়েছে!" else "All selected data was successfully deleted!",
                                        Toast.LENGTH_LONG
                                    ).show()
                                    onDismiss()
                                } else {
                                    Toast.makeText(
                                        context,
                                        "${if (isBn) "মুছতে ত্রুটি হয়েছে: " else "Error deleting data: "}$msg",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            }
                        },
                        enabled = !isDeleting && isConfirmed && hasSelectedOption,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = dangerRed,
                            disabledContainerColor = dangerRed.copy(alpha = 0.35f)
                        ),
                        modifier = Modifier
                            .weight(1.3f)
                            .height(46.dp),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.DeleteForever,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isBn) "সব মুছুন" else "Delete All",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }
            }
        }
    }
}
