package com.example.ui.screens.settings

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.components.HomeScreenWidgetStudioCard
import com.example.ui.theme.*
import com.example.utils.AppThemeMode
import com.example.utils.LanguageManager
import com.example.utils.ThemeManager
import com.example.viewmodel.StoreViewModel

@Composable
fun SystemPreferencesSection(
    viewModel: StoreViewModel,
    context: Context,
    isWidgetProfitEnabled: Boolean,
    onWidgetProfitChange: (Boolean) -> Unit,
    onDeleteAllDataClicked: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // App Theme Selector Card (Light / White Mode & Dark Mode)
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = CardBackground),
            elevation = CardDefaults.cardElevation(2.dp),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = CircleShape,
                            color = StoreSaffronAccent.copy(alpha = 0.15f),
                            modifier = Modifier.size(38.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = if (ThemeManager.isDarkMode()) Icons.Default.DarkMode else Icons.Default.LightMode,
                                    contentDescription = null,
                                    tint = StoreSaffronAccent,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = if (LanguageManager.isBengali) "অ্যাপ থিম মোড (Theme)" else "App Theme Mode",
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.titleSmall,
                                color = TextDark
                            )
                            Text(
                                text = when (ThemeManager.themeMode) {
                                    AppThemeMode.LIGHT -> if (LanguageManager.isBengali) "বর্তমান: লাইট (সাদা) মোড" else "Current: Light Mode"
                                    AppThemeMode.DARK -> if (LanguageManager.isBengali) "বর্তমান: ডার্ক মোড" else "Current: Dark Mode"
                                    AppThemeMode.SYSTEM -> if (LanguageManager.isBengali) "বর্তমান: সিস্টেম ডিফল্ট" else "Current: System Default"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = TextMuted
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = ThemeManager.themeMode == AppThemeMode.LIGHT,
                        onClick = { ThemeManager.setThemeMode(AppThemeMode.LIGHT, context) },
                        label = { Text("☀️ Light", fontSize = 12.sp) },
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = ThemeManager.themeMode == AppThemeMode.DARK,
                        onClick = { ThemeManager.setThemeMode(AppThemeMode.DARK, context) },
                        label = { Text("🌙 Dark", fontSize = 12.sp) },
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = ThemeManager.themeMode == AppThemeMode.SYSTEM,
                        onClick = { ThemeManager.setThemeMode(AppThemeMode.SYSTEM, context) },
                        label = { Text("⚙️ Auto", fontSize = 12.sp) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // Advanced Home Screen Widget Studio Card
        HomeScreenWidgetStudioCard(
            viewModel = viewModel
        )

        // Danger Zone: Delete All App & Cloud Data Card
        val dangerRedColor = Color(0xFFDC2626)
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (ThemeManager.isDarkMode()) Color(0xFF281313) else Color(0xFFFEF2F2)
            ),
            border = BorderStroke(1.dp, dangerRedColor.copy(alpha = 0.35f)),
            elevation = CardDefaults.cardElevation(2.dp),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = dangerRedColor.copy(alpha = 0.15f),
                        modifier = Modifier.size(36.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Default.DeleteForever,
                                contentDescription = null,
                                tint = dangerRedColor,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (LanguageManager.isBengali) "বিপদ অঞ্চল / সমস্ত ডেটা রিসেট" else "DANGER ZONE / DATA RESET",
                            color = dangerRedColor,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp
                        )
                        Text(
                            text = if (LanguageManager.isBengali) "অ্যাপ ও ক্লাউড ডেটা সম্পূর্ণ মুছুন" else "Delete All App & Cloud Data",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (ThemeManager.isDarkMode()) Color(0xFFFCA5A5) else Color(0xFF991B1B)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = if (LanguageManager.isBengali)
                        "আপনার ফোন এবং ক্লাউড ডাটাবেস থেকে পণ্য, বিক্রয়, বাকি খাতা ও ব্যাকআপ স্থায়ীভাবে মুছে নতুন করে শুরু করুন।"
                    else
                        "Permanently erase all inventory items, sales records, customer khata ledger, and cloud backups from your device and server.",
                    fontSize = 12.sp,
                    color = if (ThemeManager.isDarkMode()) Color(0xFFE2E8F0).copy(alpha = 0.8f) else Color(0xFF475569),
                    lineHeight = 16.sp
                )

                Spacer(modifier = Modifier.height(14.dp))

                Button(
                    onClick = onDeleteAllDataClicked,
                    colors = ButtonDefaults.buttonColors(containerColor = dangerRedColor),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 44.dp)
                ) {
                    Icon(
                        Icons.Default.DeleteForever,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = Color.White
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (LanguageManager.isBengali) "সমস্ত ডেটা মুছুন..." else "Delete All App & Cloud Data...",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }
        }
    }
}
