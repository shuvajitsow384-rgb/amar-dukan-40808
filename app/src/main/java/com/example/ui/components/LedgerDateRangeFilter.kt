package com.example.ui.components

import android.app.DatePickerDialog
import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.*
import com.example.utils.LanguageManager
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

enum class LedgerDatePreset(val labelEn: String, val labelBn: String) {
    ALL("All Time", "সব সময়"),
    TODAY("Today", "আজকে"),
    YESTERDAY("Yesterday", "গতকাল"),
    LAST_7_DAYS("Last 7 Days", "গত ৭ দিন"),
    THIS_MONTH("This Month", "এই মাস"),
    CUSTOM("Custom Range", "কাস্টম রেঞ্জ")
}

/**
 * Computes start and end millisecond timestamps for standard ledger presets.
 */
fun getPresetDateBounds(preset: LedgerDatePreset): Pair<Long?, Long?> {
    return when (preset) {
        LedgerDatePreset.ALL -> Pair(null, null)
        LedgerDatePreset.TODAY -> {
            val cal = Calendar.getInstance()
            cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            val start = cal.timeInMillis
            cal.set(Calendar.HOUR_OF_DAY, 23)
            cal.set(Calendar.MINUTE, 59)
            cal.set(Calendar.SECOND, 59)
            cal.set(Calendar.MILLISECOND, 999)
            val end = cal.timeInMillis
            Pair(start, end)
        }
        LedgerDatePreset.YESTERDAY -> {
            val cal = Calendar.getInstance()
            cal.add(Calendar.DAY_OF_YEAR, -1)
            cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            val start = cal.timeInMillis
            cal.set(Calendar.HOUR_OF_DAY, 23)
            cal.set(Calendar.MINUTE, 59)
            cal.set(Calendar.SECOND, 59)
            cal.set(Calendar.MILLISECOND, 999)
            val end = cal.timeInMillis
            Pair(start, end)
        }
        LedgerDatePreset.LAST_7_DAYS -> {
            val cal = Calendar.getInstance()
            cal.set(Calendar.HOUR_OF_DAY, 23)
            cal.set(Calendar.MINUTE, 59)
            cal.set(Calendar.SECOND, 59)
            cal.set(Calendar.MILLISECOND, 999)
            val end = cal.timeInMillis
            cal.add(Calendar.DAY_OF_YEAR, -6)
            cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            val start = cal.timeInMillis
            Pair(start, end)
        }
        LedgerDatePreset.THIS_MONTH -> {
            val cal = Calendar.getInstance()
            cal.set(Calendar.HOUR_OF_DAY, 23)
            cal.set(Calendar.MINUTE, 59)
            cal.set(Calendar.SECOND, 59)
            cal.set(Calendar.MILLISECOND, 999)
            val end = cal.timeInMillis
            cal.set(Calendar.DAY_OF_MONTH, 1)
            cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            val start = cal.timeInMillis
            Pair(start, end)
        }
        LedgerDatePreset.CUSTOM -> Pair(null, null)
    }
}

/**
 * Reusable Date-Picker UI Component for filtering Transaction History and Ledgers by custom date ranges.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LedgerDateRangeFilterComponent(
    selectedPreset: LedgerDatePreset,
    customStartDate: Long,
    customEndDate: Long,
    onPresetChange: (LedgerDatePreset) -> Unit,
    onCustomRangeChange: (startDate: Long, endDate: Long) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
    isCompact: Boolean = false,
    isBn: Boolean = LanguageManager.isBengali
) {
    val context = LocalContext.current
    val sdfDisplay = remember { SimpleDateFormat("dd MMM yyyy", Locale.getDefault()) }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(if (isCompact) 4.dp else 6.dp)
    ) {
        // Horizontally scrollable chips row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LedgerDatePreset.values().forEach { preset ->
                val isSelected = selectedPreset == preset
                FilterChip(
                    selected = isSelected,
                    onClick = {
                        if (preset == LedgerDatePreset.CUSTOM) {
                            onPresetChange(LedgerDatePreset.CUSTOM)
                        } else {
                            onPresetChange(preset)
                        }
                    },
                    label = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (preset == LedgerDatePreset.CUSTOM) {
                                Icon(
                                    imageVector = Icons.Default.CalendarToday,
                                    contentDescription = null,
                                    modifier = Modifier.size(if (isCompact) 11.dp else 13.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                            }
                            Text(
                                text = if (isBn) preset.labelBn else preset.labelEn,
                                fontSize = if (isCompact) 11.sp else 12.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = StorePrimary,
                        selectedLabelColor = Color.White,
                        selectedLeadingIconColor = Color.White,
                        containerColor = SurfaceWarm,
                        labelColor = TextDark
                    ),
                    border = FilterChipDefaults.filterChipBorder(
                        enabled = true,
                        selected = isSelected,
                        borderColor = if (isSelected) StorePrimary else NeutralBorderDivider,
                        selectedBorderColor = StorePrimary,
                        borderWidth = 1.dp
                    ),
                    modifier = Modifier.height(if (isCompact) 32.dp else 36.dp)
                )
            }

            if (selectedPreset != LedgerDatePreset.ALL) {
                IconButton(
                    onClick = onReset,
                    modifier = Modifier.size(if (isCompact) 28.dp else 32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.RestartAlt,
                        contentDescription = "Reset Date Filter",
                        tint = StoreRedPrimary,
                        modifier = Modifier.size(if (isCompact) 16.dp else 18.dp)
                    )
                }
            }
        }

        // Custom Date Range Selector Bar (Shows when CUSTOM is selected)
        if (selectedPreset == LedgerDatePreset.CUSTOM) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = SurfaceWarm,
                border = BorderStroke(1.dp, StorePrimary.copy(alpha = 0.35f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .padding(horizontal = 8.dp, vertical = if (isCompact) 4.dp else 6.dp)
                        .fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    // Start Date Button
                    OutlinedButton(
                        onClick = {
                            val cal = Calendar.getInstance().apply { timeInMillis = customStartDate }
                            DatePickerDialog(
                                context,
                                { _, y, m, d ->
                                    val c = Calendar.getInstance().apply {
                                        set(y, m, d, 0, 0, 0)
                                        set(Calendar.MILLISECOND, 0)
                                    }
                                    val newStart = c.timeInMillis
                                    val newEnd = if (customEndDate < newStart) {
                                        Calendar.getInstance().apply {
                                            set(y, m, d, 23, 59, 59)
                                            set(Calendar.MILLISECOND, 999)
                                        }.timeInMillis
                                    } else customEndDate
                                    onCustomRangeChange(newStart, newEnd)
                                },
                                cal.get(Calendar.YEAR),
                                cal.get(Calendar.MONTH),
                                cal.get(Calendar.DAY_OF_MONTH)
                            ).show()
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(if (isCompact) 34.dp else 38.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.CalendarMonth,
                            contentDescription = null,
                            modifier = Modifier.size(13.dp),
                            tint = StorePrimary
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = sdfDisplay.format(Date(customStartDate)),
                            fontSize = if (isCompact) 10.sp else 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextDark
                        )
                    }

                    Text(
                        text = " → ",
                        fontWeight = FontWeight.Bold,
                        fontSize = if (isCompact) 12.sp else 14.sp,
                        color = TextMuted,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )

                    // End Date Button
                    OutlinedButton(
                        onClick = {
                            val cal = Calendar.getInstance().apply { timeInMillis = customEndDate }
                            DatePickerDialog(
                                context,
                                { _, y, m, d ->
                                    val c = Calendar.getInstance().apply {
                                        set(y, m, d, 23, 59, 59)
                                        set(Calendar.MILLISECOND, 999)
                                    }
                                    val newEnd = c.timeInMillis
                                    val newStart = if (customStartDate > newEnd) {
                                        Calendar.getInstance().apply {
                                            set(y, m, d, 0, 0, 0)
                                            set(Calendar.MILLISECOND, 0)
                                        }.timeInMillis
                                    } else customStartDate
                                    onCustomRangeChange(newStart, newEnd)
                                },
                                cal.get(Calendar.YEAR),
                                cal.get(Calendar.MONTH),
                                cal.get(Calendar.DAY_OF_MONTH)
                            ).show()
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(if (isCompact) 34.dp else 38.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.CalendarMonth,
                            contentDescription = null,
                            modifier = Modifier.size(13.dp),
                            tint = StorePrimary
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = sdfDisplay.format(Date(customEndDate)),
                            fontSize = if (isCompact) 10.sp else 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextDark
                        )
                    }

                    IconButton(
                        onClick = onReset,
                        modifier = Modifier.size(if (isCompact) 28.dp else 32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Clear Range",
                            tint = TextMuted,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }
        } else if (selectedPreset != LedgerDatePreset.ALL) {
            // Active preset indicator pill
            val bounds = getPresetDateBounds(selectedPreset)
            if (bounds.first != null && bounds.second != null) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = StorePrimary.copy(alpha = 0.08f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.DateRange,
                                contentDescription = null,
                                modifier = Modifier.size(12.dp),
                                tint = StorePrimary
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "${sdfDisplay.format(Date(bounds.first!!))} - ${sdfDisplay.format(Date(bounds.second!!))}",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                color = StorePrimary
                            )
                        }

                        Text(
                            text = if (isBn) "ফিল্টার সক্রিয়" else "Filter Active",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = StorePrimary
                        )
                    }
                }
            }
        }
    }
}
