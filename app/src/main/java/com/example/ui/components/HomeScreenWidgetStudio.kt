package com.example.ui.components

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.*
import com.example.utils.LanguageManager
import com.example.utils.StoreInfoManager
import com.example.viewmodel.StoreViewModel
import com.example.widget.TodaySalesWidgetProvider
import com.example.widget.WidgetDataManager
import kotlin.math.abs

@Composable
fun HomeScreenWidgetStudioCard(
    viewModel: StoreViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val isBn = LanguageManager.isBengali

    var isProfitEnabled by remember { mutableStateOf(WidgetDataManager.isShowProfitEnabled(context)) }
    var isPaymentSplitEnabled by remember { mutableStateOf(WidgetDataManager.isShowPaymentSplitEnabled(context)) }
    var isGoalProgressEnabled by remember { mutableStateOf(WidgetDataManager.isShowGoalProgressEnabled(context)) }
    var isQuickActionsEnabled by remember { mutableStateOf(WidgetDataManager.isShowQuickActionsEnabled(context)) }
    var isLowStockAlertEnabled by remember { mutableStateOf(WidgetDataManager.isShowLowStockAlertEnabled(context)) }
    var isPrivacyMaskEnabled by remember { mutableStateOf(WidgetDataManager.isPrivacyMaskEnabled(context)) }
    var selectedTheme by remember { mutableStateOf(WidgetDataManager.getWidgetTheme(context)) }
    var dailyGoalText by remember { mutableStateOf(WidgetDataManager.getDailySalesGoal(context).toInt().toString()) }

    // Read real numbers from WidgetDataManager
    val todaySales = WidgetDataManager.getTodaySales(context)
    val todayBills = WidgetDataManager.getTodayBills(context)
    val todayProfit = WidgetDataManager.getTodayProfit(context)
    val todayCash = WidgetDataManager.getTodayCashSales(context)
    val todayUpi = WidgetDataManager.getTodayUpiSales(context)
    val lowStockCount = WidgetDataManager.getLowStockCount(context)
    val yesterdaySales = WidgetDataManager.getYesterdaySalesSameTime(context)
    val storeName = StoreInfoManager.storeName.ifBlank { if (isBn) "আমার দোকান" else "Amar Dukan" }

    val currentGoal = dailyGoalText.toDoubleOrNull() ?: 5000.0
    val goalPercent = if (currentGoal > 0) ((todaySales / currentGoal) * 100).toInt().coerceIn(0, 100) else 0

    fun triggerWidgetSync() {
        TodaySalesWidgetProvider.triggerWidgetUpdate(context)
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CardBackground),
        elevation = CardDefaults.cardElevation(2.dp),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Header Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = CircleShape,
                        color = StoreGold.copy(alpha = 0.15f),
                        modifier = Modifier.size(40.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.Widgets,
                                contentDescription = null,
                                tint = StoreGold,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = if (isBn) "হোম স্ক্রিন উইজেট স্টুডিও" else "Home Screen Widget Studio",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = TextDark
                        )
                        Text(
                            text = if (isBn) "রিয়েল-টাইম লাইভ উইজেট কনফিগার ও কাস্টমাইজ করুন" else "Customize themes, targets & quick shortcuts",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextMuted
                        )
                    }
                }

                IconButton(
                    onClick = {
                        triggerWidgetSync()
                        Toast.makeText(context, if (isBn) "উইজেট রিফ্রেশ সম্পন্ন!" else "Widgets refreshed successfully!", Toast.LENGTH_SHORT).show()
                    }
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Refresh",
                        tint = StorePrimary
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // LIVE INTERACTIVE WIDGET PREVIEW CARD
            Text(
                text = if (isBn) "লাইভ প্রিভিউ (Home Screen Widget Live Preview):" else "LIVE HOME SCREEN PREVIEW:",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = TextMuted
            )
            Spacer(modifier = Modifier.height(6.dp))

            // Widget Box Preview
            val (cardGradient, strokeColor) = when (selectedTheme) {
                WidgetDataManager.THEME_OBSIDIAN -> Pair(
                    listOf(Color(0xFF27272A), Color(0xFF18181B), Color(0xFF09090B)),
                    Color(0xFF10B981)
                )
                WidgetDataManager.THEME_SAPPHIRE -> Pair(
                    listOf(Color(0xFF1E293B), Color(0xFF0F172A), Color(0xFF020617)),
                    Color(0xFF38BDF8)
                )
                else -> Pair(
                    listOf(Color(0xFFC4222E), Color(0xFF9E1620), Color(0xFF7A1018)),
                    Color(0xFFFFD54F)
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Brush.linearGradient(cardGradient))
                    .border(1.2.dp, strokeColor.copy(alpha = 0.4f), RoundedCornerShape(16.dp))
                    .padding(14.dp)
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    // 1. Header inside preview
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = Color.White.copy(alpha = 0.2f),
                            modifier = Modifier.size(22.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.Storefront,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = storeName,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            modifier = Modifier.weight(1f),
                            maxLines = 1
                        )

                        // Privacy Mask Toggle Button in preview
                        Surface(
                            shape = CircleShape,
                            color = Color.White.copy(alpha = 0.15f),
                            modifier = Modifier
                                .size(24.dp)
                                .clickable {
                                    val next = !isPrivacyMaskEnabled
                                    isPrivacyMaskEnabled = next
                                    WidgetDataManager.setPrivacyMaskEnabled(context, next)
                                    triggerWidgetSync()
                                }
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = if (isPrivacyMaskEnabled) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = "Toggle Privacy",
                                    tint = Color.White,
                                    modifier = Modifier.size(13.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(5.dp))

                    // 2. Sales Label & Last Updated Time Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (isBn) "আজকের মোট বিক্রি" else "TODAY'S SALES",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFFBBF24),
                            fontSize = 9.5.sp
                        )
                        Text(
                            text = if (isBn) "আপডেট: এইমাত্র" else "Updated: Just now",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFFD1C4C4),
                            fontSize = 9.sp
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (isPrivacyMaskEnabled) "₹ ••••••" else "₹%.2f".format(todaySales),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )

                        // Comparison badge
                        if (!isPrivacyMaskEnabled && (yesterdaySales > 0.0 || todaySales > 0.0)) {
                            val diff = todaySales - yesterdaySales
                            val pct = if (yesterdaySales > 0.0) (abs(diff) / yesterdaySales * 100.0) else 100.0
                            Spacer(modifier = Modifier.width(8.dp))
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = if (diff >= 0) Color(0xFF14532D) else Color(0xFF7F1D1D)
                            ) {
                                Text(
                                    text = if (diff >= 0) "▲ +₹%.0f (+%.0f%%)".format(diff, pct) else "▼ -₹%.0f (-%.0f%%)".format(abs(diff), pct),
                                    color = if (diff >= 0) Color(0xFF4ADE80) else Color(0xFFF87171),
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    // 3. Sub-Metric Pills:
                    // Row 1: Bills & Cash/UPI Payment Split
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        // Bills Pill
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = Color(0xFFFBBF24)
                        ) {
                            Text(
                                text = if (isBn) "$todayBills টি বিল" else "$todayBills Bills",
                                color = Color(0xFF3B070B),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                            )
                        }

                        // Cash/UPI Split Pill
                        if (isPaymentSplitEnabled && (todayCash > 0 || todayUpi > 0)) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = Color.Black.copy(alpha = 0.25f),
                                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFBBF24).copy(alpha = 0.3f))
                            ) {
                                Text(
                                    text = if (isPrivacyMaskEnabled) "💵 ••• | 📱 •••" else "💵 ₹%.0f | 📱 ₹%.0f".format(todayCash, todayUpi),
                                    color = Color(0xFFFFFBEB),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }

                    // Row 2: Business Health (Profit & Low Stock Alert)
                    val showRow2 = isProfitEnabled || (isLowStockAlertEnabled && lowStockCount > 0)
                    if (showRow2) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            // Profit Pill
                            if (isProfitEnabled) {
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = Color(0xFF14532D),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF22C55E).copy(alpha = 0.4f))
                                ) {
                                    val marginPct = if (todaySales > 0.0) ((todayProfit / todaySales) * 100.0).coerceAtLeast(0.0) else 0.0
                                    Text(
                                        text = if (isPrivacyMaskEnabled) (if (isBn) "লাভ: ₹••••" else "Profit: ₹••••") else (if (isBn) "লাভ: ₹%.0f (%.0f%%)".format(todayProfit, marginPct) else "Profit: ₹%.0f (%.0f%%)".format(todayProfit, marginPct)),
                                        color = Color(0xFFDCFCE7),
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                                    )
                                }
                            }

                            // Low Stock Pill
                            if (isLowStockAlertEnabled && lowStockCount > 0) {
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = Color(0xFF7F1D1D),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFEF4444).copy(alpha = 0.4f))
                                ) {
                                    Text(
                                        text = if (isBn) "⚠️ $lowStockCount টি স্টক কম" else "⚠️ $lowStockCount Low Stock",
                                        color = Color(0xFFFEE2E2),
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                    }

                    // 4. Daily Sales Goal Section
                    if (isGoalProgressEnabled && currentGoal > 0) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = if (isPrivacyMaskEnabled) (if (isBn) "🎯 লক্ষ্য অগ্রগতি: $goalPercent%" else "🎯 Goal Progress: $goalPercent%")
                                else if (isBn) "🎯 লক্ষ্য: ₹%.0f / ₹%.0f (%d%%)".format(todaySales, currentGoal, goalPercent)
                                else "🎯 Goal: ₹%.0f / ₹%.0f (%d%%)".format(todaySales, currentGoal, goalPercent),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFFDE68A),
                                fontSize = 9.sp
                            )
                        }
                        Spacer(modifier = Modifier.height(3.dp))
                        LinearProgressIndicator(
                            progress = { goalPercent / 100f },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(5.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            color = Color(0xFF10B981),
                            trackColor = Color.Black.copy(alpha = 0.3f)
                        )
                    }

                    // 5. Quick Action Buttons Bar
                    if (isQuickActionsEnabled) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            // POS Button
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = Color(0xFFFBBF24),
                                modifier = Modifier.weight(1f)
                            ) {
                                Row(
                                    modifier = Modifier.padding(vertical = 4.dp),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.ShoppingCart,
                                        contentDescription = null,
                                        tint = Color(0xFF3B070B),
                                        modifier = Modifier.size(12.dp)
                                    )
                                    Spacer(modifier = Modifier.width(3.dp))
                                    Text(
                                        text = if (isBn) "+ বিল" else "+ Sale",
                                        color = Color(0xFF3B070B),
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }

                            // Khata Button
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = Color.White.copy(alpha = 0.15f),
                                border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.2f)),
                                modifier = Modifier.weight(1f)
                            ) {
                                Row(
                                    modifier = Modifier.padding(vertical = 4.dp),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.MenuBook,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(12.dp)
                                    )
                                    Spacer(modifier = Modifier.width(3.dp))
                                    Text(
                                        text = if (isBn) "খাতা" else "Khata",
                                        color = Color.White,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }

                            // Stock Button
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = Color.White.copy(alpha = 0.15f),
                                border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.2f)),
                                modifier = Modifier.weight(1f)
                            ) {
                                Row(
                                    modifier = Modifier.padding(vertical = 4.dp),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Inventory2,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(12.dp)
                                    )
                                    Spacer(modifier = Modifier.width(3.dp))
                                    Text(
                                        text = if (isBn) "স্টক" else "Stock",
                                        color = Color.White,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }

                            // Reports Button
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = Color.White.copy(alpha = 0.15f),
                                border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.2f)),
                                modifier = Modifier.weight(1f)
                            ) {
                                Row(
                                    modifier = Modifier.padding(vertical = 4.dp),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.BarChart,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(12.dp)
                                    )
                                    Spacer(modifier = Modifier.width(3.dp))
                                    Text(
                                        text = if (isBn) "লাভ" else "P&L",
                                        color = Color.White,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // THEME SELECTOR
            Text(
                text = if (isBn) "উইজেট কালার থিম নির্বাচন করুন:" else "Select Widget Theme:",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = TextDark
            )
            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Theme 1: Crimson
                ThemeChoiceChip(
                    name = if (isBn) "রয়েল ক্রাফট (Crimson)" else "Royal Crimson",
                    colors = listOf(Color(0xFFC4222E), Color(0xFF7A1018)),
                    isSelected = selectedTheme == WidgetDataManager.THEME_CRIMSON,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        selectedTheme = WidgetDataManager.THEME_CRIMSON
                        WidgetDataManager.setWidgetTheme(context, WidgetDataManager.THEME_CRIMSON)
                        triggerWidgetSync()
                    }
                )

                // Theme 2: Obsidian
                ThemeChoiceChip(
                    name = if (isBn) "মিডনাইট ডার্ক (Obsidian)" else "Midnight Dark",
                    colors = listOf(Color(0xFF27272A), Color(0xFF09090B)),
                    isSelected = selectedTheme == WidgetDataManager.THEME_OBSIDIAN,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        selectedTheme = WidgetDataManager.THEME_OBSIDIAN
                        WidgetDataManager.setWidgetTheme(context, WidgetDataManager.THEME_OBSIDIAN)
                        triggerWidgetSync()
                    }
                )

                // Theme 3: Sapphire
                ThemeChoiceChip(
                    name = if (isBn) "স্যাফায়ার ব্লু (Sapphire)" else "Sapphire Blue",
                    colors = listOf(Color(0xFF1E293B), Color(0xFF0F172A)),
                    isSelected = selectedTheme == WidgetDataManager.THEME_SAPPHIRE,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        selectedTheme = WidgetDataManager.THEME_SAPPHIRE
                        WidgetDataManager.setWidgetTheme(context, WidgetDataManager.THEME_SAPPHIRE)
                        triggerWidgetSync()
                    }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // DAILY SALES TARGET GOAL CONFIG
            Text(
                text = if (isBn) "দৈনিক বিক্রয় লক্ষ্যমাত্রা (Daily Sales Target):" else "Daily Sales Target Goal:",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = TextDark
            )
            Spacer(modifier = Modifier.height(6.dp))

            OutlinedTextField(
                value = dailyGoalText,
                onValueChange = { input ->
                    dailyGoalText = input.filter { it.isDigit() }
                    val goal = dailyGoalText.toDoubleOrNull() ?: 0.0
                    if (goal > 0) {
                        WidgetDataManager.setDailySalesGoal(context, goal)
                        triggerWidgetSync()
                    }
                },
                leadingIcon = {
                    Text("₹", fontWeight = FontWeight.Bold, color = StoreRedPrimary, fontSize = 16.sp)
                },
                label = { Text(if (isBn) "দৈনিক লক্ষ্য (টাকা/₹)" else "Daily Goal (₹)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Quick Target Presets
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf(2000, 5000, 10000, 25000, 50000).forEach { preset ->
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (dailyGoalText == preset.toString()) StoreRedPrimary else MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier
                            .weight(1f)
                            .clickable {
                                dailyGoalText = preset.toString()
                                WidgetDataManager.setDailySalesGoal(context, preset.toDouble())
                                triggerWidgetSync()
                            }
                    ) {
                        Text(
                            text = "₹${preset / 1000}k",
                            color = if (dailyGoalText == preset.toString()) Color.White else TextDark,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(vertical = 6.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // FEATURE TOGGLES LIST
            Text(
                text = if (isBn) "উইজেট ফিচার ও গোপনীয়তা সেটিংস:" else "Widget Feature & Privacy Toggles:",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = TextDark
            )
            Spacer(modifier = Modifier.height(8.dp))

            // 1. Quick Actions Bar Toggle
            WidgetToggleRow(
                icon = Icons.Default.Bolt,
                title = if (isBn) "দ্রুত অ্যাকশন শর্টকাট (+বিল, খাতা, স্টক)" else "Quick Action Buttons (+Sale, Khata, Stock)",
                subtitle = if (isBn) "উইজেট থেকে সরাসরি এক ক্লিকে নতুন বিল বা খাতা খুলুন" else "One-tap shortcuts directly on home screen",
                checked = isQuickActionsEnabled,
                onCheckedChange = { enabled ->
                    isQuickActionsEnabled = enabled
                    WidgetDataManager.setShowQuickActionsEnabled(context, enabled)
                    triggerWidgetSync()
                }
            )

            Spacer(modifier = Modifier.height(8.dp))

            // 2. Goal Progress Toggle
            WidgetToggleRow(
                icon = Icons.Default.TrackChanges,
                title = if (isBn) "দৈনিক লক্ষ্য প্রগ্রেস বার প্রদর্শন" else "Show Daily Goal Progress Bar",
                subtitle = if (isBn) "আজকের বিক্রির লক্ষ্যমাত্রা অগ্রগতি শতাংশ ট্র্যাক করুন" else "Visual progress towards your daily sales goal",
                checked = isGoalProgressEnabled,
                onCheckedChange = { enabled ->
                    isGoalProgressEnabled = enabled
                    WidgetDataManager.setShowGoalProgressEnabled(context, enabled)
                    triggerWidgetSync()
                }
            )

            Spacer(modifier = Modifier.height(8.dp))

            // 3. Cash & UPI Split Toggle
            WidgetToggleRow(
                icon = Icons.Default.Payments,
                title = if (isBn) "নগদ ও ইউপিআই/অনলাইন বিক্রয় বিভাজন" else "Show Cash & UPI Revenue Split",
                subtitle = if (isBn) "হোম স্ক্রিনে নগদ ক্যাশ ও ডিজিটাল পেমেন্ট আলাদা দেখুন" else "Breakdown of Cash vs UPI collections",
                checked = isPaymentSplitEnabled,
                onCheckedChange = { enabled ->
                    isPaymentSplitEnabled = enabled
                    WidgetDataManager.setShowPaymentSplitEnabled(context, enabled)
                    triggerWidgetSync()
                }
            )

            Spacer(modifier = Modifier.height(8.dp))

            // 4. Low Stock Alert Toggle
            WidgetToggleRow(
                icon = Icons.Default.Warning,
                title = if (isBn) "কম স্টক সতর্কতা ব্যাজ (Low Stock Warning)" else "Low Stock Alert Badge",
                subtitle = if (isBn) "যেসব পণ্যের স্টক ফুরিয়ে আসছে তা সাথে সাথে নোটিশ করুন" else "Alerts you when items reach reorder threshold",
                checked = isLowStockAlertEnabled,
                onCheckedChange = { enabled ->
                    isLowStockAlertEnabled = enabled
                    WidgetDataManager.setShowLowStockAlertEnabled(context, enabled)
                    triggerWidgetSync()
                }
            )

            Spacer(modifier = Modifier.height(8.dp))

            // 5. Profit Display Toggle (Restricted)
            WidgetToggleRow(
                icon = Icons.Default.TrendingUp,
                title = if (isBn) "উইজেটে আজকের লাভ প্রদর্শন (Owner Only)" else "Show Net Profit on Widget (Owner Only)",
                subtitle = if (isBn) "গোপনীয়তার জন্য ডিফল্টভাবে বন্ধ। চালু করলে উইজেটে আজকের লাভ দেখাবে।" else "Restricted by default to prevent customers or staff from viewing profit margins.",
                checked = isProfitEnabled,
                onCheckedChange = { enabled ->
                    isProfitEnabled = enabled
                    WidgetDataManager.setShowProfitEnabled(context, enabled)
                    triggerWidgetSync()
                }
            )

            Spacer(modifier = Modifier.height(8.dp))

            // 6. Privacy Mask Mode Toggle
            WidgetToggleRow(
                icon = Icons.Default.VisibilityOff,
                title = if (isBn) "প্রাইভেসি মাস্ক মোড (Privacy Mode: ₹ •••••)" else "Privacy Mask Mode (Hide Sales Figures)",
                subtitle = if (isBn) "অন্যদের সামনে ফোনের হোম স্ক্রিনে বিক্রির টাকা গোপন রাখুন" else "Masks numbers with bullets on home screen until revealed",
                checked = isPrivacyMaskEnabled,
                onCheckedChange = { enabled ->
                    isPrivacyMaskEnabled = enabled
                    WidgetDataManager.setPrivacyMaskEnabled(context, enabled)
                    triggerWidgetSync()
                }
            )

            Spacer(modifier = Modifier.height(16.dp))

            // ONE-TAP ADD WIDGET TO HOME SCREEN BUTTON
            Button(
                onClick = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        val appWidgetManager = AppWidgetManager.getInstance(context)
                        val provider = ComponentName(context, TodaySalesWidgetProvider::class.java)
                        if (appWidgetManager.isRequestPinAppWidgetSupported) {
                            appWidgetManager.requestPinAppWidget(provider, null, null)
                            Toast.makeText(
                                context,
                                if (isBn) "হোম স্ক্রিনে উইজেট পিন করার অনুরোধ পাঠানো হয়েছে!" else "Pin widget prompt sent to launcher!",
                                Toast.LENGTH_SHORT
                            ).show()
                        } else {
                            Toast.makeText(
                                context,
                                if (isBn) "হোম স্ক্রিনে খালি জায়গায় চেপে ধরে 'Widgets' থেকে Amar Dukan যোগ করুন।" else "Long-press your home screen and choose 'Widgets' to add Amar Dukan.",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    } else {
                        Toast.makeText(
                            context,
                            if (isBn) "হোম স্ক্রিনে খালি জায়গায় চেপে ধরে 'Widgets' থেকে Amar Dukan যোগ করুন।" else "Long-press your home screen and choose 'Widgets' to add Amar Dukan.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = StoreRedPrimary),
                shape = RoundedCornerShape(10.dp)
            ) {
                Icon(Icons.Default.AddHome, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (isBn) "হোম স্ক্রিনে উইজেট যোগ করুন (Pin Widget)" else "Add Widget to Home Screen",
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun ThemeChoiceChip(
    name: String,
    colors: List<Color>,
    isSelected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (isSelected) StoreRedPrimary.copy(alpha = 0.1f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        border = androidx.compose.foundation.BorderStroke(
            if (isSelected) 2.dp else 1.dp,
            if (isSelected) StoreRedPrimary else Color.Transparent
        ),
        modifier = modifier.clickable { onClick() }
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(Brush.linearGradient(colors))
                    .border(1.5.dp, Color.White.copy(alpha = 0.6f), CircleShape)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = name,
                fontSize = 10.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                color = if (isSelected) StoreRedPrimary else TextDark,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                maxLines = 2
            )
        }
    }
}

@Composable
private fun WidgetToggleRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.Top) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (checked) StoreRedPrimary else TextMuted,
                    modifier = Modifier
                        .size(20.dp)
                        .padding(top = 2.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = title,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        color = TextDark
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = subtitle,
                        fontSize = 11.sp,
                        color = TextMuted,
                        lineHeight = 15.sp
                    )
                }
            }

            Spacer(modifier = Modifier.width(10.dp))

            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = StoreGreenProfit,
                    uncheckedThumbColor = Color.White,
                    uncheckedTrackColor = Color.LightGray
                )
            )
        }
    }
}
