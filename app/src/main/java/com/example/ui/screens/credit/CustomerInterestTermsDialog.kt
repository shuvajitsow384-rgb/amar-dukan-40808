package com.example.ui.screens.credit

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.local.entities.Customer
import com.example.data.local.entities.LedgerEntry
import com.example.ui.theme.*
import com.example.utils.CustomerInterestBreakdown
import com.example.utils.KhataInterestCalculator
import com.example.utils.StoreInfoManager
import java.util.Locale

enum class CustomerInterestPolicyMode {
    STORE_DEFAULT,
    CUSTOM_TERMS,
    EXEMPT
}

@Composable
fun CustomerInterestTermsDialog(
    customer: Customer,
    ledgerEntries: List<LedgerEntry>,
    isBn: Boolean,
    onDismiss: () -> Unit,
    onSave: (Customer) -> Unit
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val scrollState = rememberScrollState()

    // Determine initial mode
    val initialMode = remember(customer.id) {
        when {
            customer.interestExempt -> CustomerInterestPolicyMode.EXEMPT
            customer.customGracePeriodDays != null || customer.customInterestRate != null -> CustomerInterestPolicyMode.CUSTOM_TERMS
            else -> CustomerInterestPolicyMode.STORE_DEFAULT
        }
    }

    var selectedMode by remember(customer.id) { mutableStateOf(initialMode) }

    // Text inputs for custom policy
    var graceDaysText by remember(customer.id) {
        mutableStateOf(
            customer.customGracePeriodDays?.toString()
                ?: StoreInfoManager.interestGracePeriodDays.toString()
        )
    }

    var monthlyRateText by remember(customer.id) {
        mutableStateOf(
            customer.customInterestRate?.let { if (it % 1.0 == 0.0) "%.0f".format(Locale.US, it) else "%.2f".format(Locale.US, it) }
                ?: StoreInfoManager.monthlyInterestRate.let { if (it % 1.0 == 0.0) "%.0f".format(Locale.US, it) else "%.2f".format(Locale.US, it) }
        )
    }

    // Input Validation
    val parsedGraceDays = graceDaysText.trim().toIntOrNull()
    val isGraceValid = selectedMode != CustomerInterestPolicyMode.CUSTOM_TERMS || (parsedGraceDays != null && parsedGraceDays in 0..365)
    val graceErrorMsg = when {
        selectedMode != CustomerInterestPolicyMode.CUSTOM_TERMS -> null
        graceDaysText.isBlank() -> if (isBn) "গ্রেস পিরিয়ড লিখুন (যেমন ৩০ দিন)" else "Enter grace days (e.g. 30)"
        parsedGraceDays == null -> if (isBn) "সঠিক সংখ্যা লিখুন" else "Enter a valid integer"
        parsedGraceDays < 0 || parsedGraceDays > 365 -> if (isBn) "০ থেকে ৩৬৫ দিনের মধ্যে হতে হবে" else "Must be between 0 and 365 days"
        else -> null
    }

    val normalizedRateStr = monthlyRateText.trim().replace(',', '.')
    val parsedMonthlyRate = normalizedRateStr.toDoubleOrNull()
    val isRateValid = selectedMode != CustomerInterestPolicyMode.CUSTOM_TERMS || (parsedMonthlyRate != null && parsedMonthlyRate in 0.0..50.0)
    val rateErrorMsg = when {
        selectedMode != CustomerInterestPolicyMode.CUSTOM_TERMS -> null
        monthlyRateText.isBlank() -> if (isBn) "সুদের হার লিখুন (যেমন ২.০%)" else "Enter rate (e.g. 2.0%)"
        parsedMonthlyRate == null -> if (isBn) "সঠিক সুদের হার লিখুন" else "Enter a valid percentage"
        parsedMonthlyRate < 0.0 || parsedMonthlyRate > 50.0 -> if (isBn) "০.০% থেকে ৫০.০% এর মধ্যে হতে হবে" else "Must be between 0.0% and 50.0%"
        else -> null
    }

    val isInputValid = selectedMode != CustomerInterestPolicyMode.CUSTOM_TERMS || (isGraceValid && isRateValid)

    // Effective Terms for Calculation & Preview
    val effectiveGraceDays = when (selectedMode) {
        CustomerInterestPolicyMode.EXEMPT -> 0
        CustomerInterestPolicyMode.STORE_DEFAULT -> StoreInfoManager.interestGracePeriodDays
        CustomerInterestPolicyMode.CUSTOM_TERMS -> parsedGraceDays ?: StoreInfoManager.interestGracePeriodDays
    }

    val effectiveMonthlyRate = when (selectedMode) {
        CustomerInterestPolicyMode.EXEMPT -> 0.0
        CustomerInterestPolicyMode.STORE_DEFAULT -> StoreInfoManager.monthlyInterestRate
        CustomerInterestPolicyMode.CUSTOM_TERMS -> parsedMonthlyRate ?: StoreInfoManager.monthlyInterestRate
    }

    // Live Simulated Customer State for Real-Time Math
    val simulatedCustomer = remember(customer, selectedMode, effectiveGraceDays, effectiveMonthlyRate) {
        customer.copy(
            interestExempt = selectedMode == CustomerInterestPolicyMode.EXEMPT,
            customGracePeriodDays = if (selectedMode == CustomerInterestPolicyMode.CUSTOM_TERMS) effectiveGraceDays else null,
            customInterestRate = if (selectedMode == CustomerInterestPolicyMode.CUSTOM_TERMS) effectiveMonthlyRate else null
        )
    }

    // Force simulation settings with enabled = true so the preview demonstrates exact interest math
    val simulatedSettings = remember(effectiveGraceDays, effectiveMonthlyRate) {
        StoreInfoManager.getKhataInterestSettings().copy(
            enabled = true,
            gracePeriodDays = effectiveGraceDays,
            monthlyRatePercent = effectiveMonthlyRate
        )
    }

    val simulatedBreakdown: CustomerInterestBreakdown = remember(simulatedCustomer, ledgerEntries, simulatedSettings) {
        KhataInterestCalculator.calculateCustomerInterest(
            customer = simulatedCustomer,
            allLedgerEntries = ledgerEntries,
            settings = simulatedSettings
        )
    }

    val projectedMonthlyInterest = remember(customer.balance, effectiveMonthlyRate, selectedMode) {
        if (selectedMode == CustomerInterestPolicyMode.EXEMPT || customer.balance <= 0.0) {
            0.0
        } else {
            customer.balance * (effectiveMonthlyRate / 100.0)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .padding(vertical = 16.dp)
                .testTag("dialog_customer_late_interest_terms"),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
                    .verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Header row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = StoreGold.copy(alpha = 0.15f),
                            modifier = Modifier.size(40.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.Percent,
                                    contentDescription = null,
                                    tint = StoreGold,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }

                        Column {
                            Text(
                                text = if (isBn) "গ্রাহকের বাকী সুদ ও পলিসি শর্ত" else "Customer Late Interest Terms",
                                fontWeight = FontWeight.Bold,
                                fontSize = 17.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = if (isBn) "স্বতন্ত্র গ্রেস পিরিয়ড ও সুদের হার নির্ধারণ" else "Configure specific grace days & monthly rate",
                                fontSize = 11.sp,
                                color = TextMuted
                            )
                        }
                    }

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(32.dp).testTag("btn_close_interest_dialog")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = TextMuted
                        )
                    }
                }

                // Customer Info Card
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = StorePrimary.copy(alpha = 0.12f),
                                modifier = Modifier.size(36.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    val initials = customer.name.take(2).uppercase()
                                    Text(
                                        text = initials.ifBlank { "CU" },
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp,
                                        color = StorePrimary
                                    )
                                }
                            }

                            Column {
                                Text(
                                    text = customer.name,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                if (customer.phone.isNotBlank()) {
                                    Text(
                                        text = customer.phone,
                                        fontSize = 11.sp,
                                        color = TextMuted
                                    )
                                }
                            }
                        }

                        // Current due balance
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = if (isBn) "মোট বাকী" else "Due Balance",
                                fontSize = 10.sp,
                                color = TextMuted
                            )
                            Text(
                                text = "₹%.2f".format(Locale.US, customer.balance),
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                color = if (customer.balance > 0.0) StoreRedAlert else StoreGreenProfit
                            )
                        }
                    }
                }

                // Store-wide Global Setting Context Banner
                if (!StoreInfoManager.interestEnabled) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        color = StoreGold.copy(alpha = 0.10f),
                        border = BorderStroke(1.dp, StoreGold.copy(alpha = 0.35f))
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = null,
                                tint = StoreGold,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = if (isBn)
                                    "দোকানের মূল বাকী সুদ সেটিং বন্ধ আছে। কাস্টম শর্ত দিলে এই নির্দিষ্ট গ্রাহকের জন্য তা সক্রিয় থাকবে।"
                                else
                                    "Store-wide late interest is turned OFF in Settings. Custom terms set here will actively apply to this customer.",
                                fontSize = 11.sp,
                                lineHeight = 15.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                } else {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        color = StoreGreenProfit.copy(alpha = 0.08f),
                        border = BorderStroke(1.dp, StoreGreenProfit.copy(alpha = 0.25f))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = StoreGreenProfit,
                                modifier = Modifier.size(15.dp)
                            )
                            Text(
                                text = if (isBn)
                                    "স্টোর ডিফল্ট: ${StoreInfoManager.interestGracePeriodDays} দিন গ্রেস, ${StoreInfoManager.monthlyInterestRate}% মাসিক সুদ"
                                else
                                    "Store Default: ${StoreInfoManager.interestGracePeriodDays} days grace, ${StoreInfoManager.monthlyInterestRate}% monthly interest",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                color = StoreGreenProfit
                            )
                        }
                    }
                }

                // Policy Selector Options (3 distinct choices)
                Text(
                    text = if (isBn) "সুদ পলিসি নির্বাচন করুন" else "Select Late Interest Policy",
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Option 1: Store Default
                    PolicyOptionCard(
                        title = if (isBn) "দোকানের সাধারণ পলিসি (Store Default)" else "Store Default Policy",
                        description = if (isBn)
                            "স্টোর সেটিংস অনুকরণ করবে (${StoreInfoManager.interestGracePeriodDays} দিন গ্রেস, ${StoreInfoManager.monthlyInterestRate}% / মাস)।"
                        else
                            "Inherits shop settings (${StoreInfoManager.interestGracePeriodDays} days grace, ${StoreInfoManager.monthlyInterestRate}% / mo).",
                        selected = selectedMode == CustomerInterestPolicyMode.STORE_DEFAULT,
                        onClick = { selectedMode = CustomerInterestPolicyMode.STORE_DEFAULT },
                        icon = Icons.Default.Storefront,
                        testTag = "interest_policy_mode_default"
                    )

                    // Option 2: Custom Terms
                    PolicyOptionCard(
                        title = if (isBn) "কাস্টম শর্তাবলী (Custom Policy)" else "Custom Terms & Policy",
                        description = if (isBn)
                            "এই গ্রাহকের জন্য আলাদা গ্রেস পিরিয়ড ও সুদের হার নির্ধারণ করুন।"
                        else
                            "Set specific grace days and monthly interest rate for this customer.",
                        selected = selectedMode == CustomerInterestPolicyMode.CUSTOM_TERMS,
                        onClick = { selectedMode = CustomerInterestPolicyMode.CUSTOM_TERMS },
                        icon = Icons.Default.Tune,
                        testTag = "interest_policy_mode_custom"
                    )

                    // Option 3: Exempt
                    PolicyOptionCard(
                        title = if (isBn) "সম্পূর্ণ সুদ-মুক্ত (Exempt from Late Interest)" else "Exempt from Late Interest (0%)",
                        description = if (isBn)
                            "কোনো অবস্থাতেই এই গ্রাহকের বাকীতে কখনো সুদ ধার্য হবে না (VIP/বিশ্বস্ত গ্রাহক)।"
                        else
                            "No late interest or penalty will ever accrue for this customer.",
                        selected = selectedMode == CustomerInterestPolicyMode.EXEMPT,
                        onClick = { selectedMode = CustomerInterestPolicyMode.EXEMPT },
                        icon = Icons.Default.VerifiedUser,
                        selectedColor = StoreGreenProfit,
                        testTag = "interest_policy_mode_exempt"
                    )
                }

                // Custom Inputs Section (Visible only when CUSTOM_TERMS is selected)
                AnimatedVisibility(
                    visible = selectedMode == CustomerInterestPolicyMode.CUSTOM_TERMS,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                        border = BorderStroke(1.dp, StoreGold.copy(alpha = 0.4f))
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = if (isBn) "নির্দিষ্ট শর্ত পূরণ করুন" else "Configure Custom Parameters",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    color = StoreGold
                                )

                                TextButton(
                                    onClick = {
                                        graceDaysText = StoreInfoManager.interestGracePeriodDays.toString()
                                        monthlyRateText = StoreInfoManager.monthlyInterestRate.let {
                                            if (it % 1.0 == 0.0) "%.0f".format(Locale.US, it) else "%.2f".format(Locale.US, it)
                                        }
                                    },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Restore,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp),
                                        tint = StoreGold
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = if (isBn) "স্টোর ডিফল্ট কপি" else "Copy Store Defaults",
                                        fontSize = 11.sp,
                                        color = StoreGold,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }

                            // 1. Grace Days Input
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                OutlinedTextField(
                                    value = graceDaysText,
                                    onValueChange = { input ->
                                        graceDaysText = input.filter { it.isDigit() }
                                    },
                                    label = { Text(if (isBn) "গ্রেস পিরিয়ড (দিন)" else "Custom Grace Period") },
                                    placeholder = { Text("${StoreInfoManager.interestGracePeriodDays}") },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Default.Schedule,
                                            contentDescription = null,
                                            tint = if (isGraceValid) StoreGold else StoreRedAlert,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    },
                                    suffix = {
                                        Text(if (isBn) "দিন" else "Days", fontSize = 12.sp, color = TextMuted)
                                    },
                                    isError = !isGraceValid,
                                    supportingText = {
                                        if (graceErrorMsg != null) {
                                            Text(text = graceErrorMsg, color = MaterialTheme.colorScheme.error)
                                        } else {
                                            Text(
                                                text = if (isBn) "চালান তৈরির কত দিন পর থেকে সুদ হিসাব হবে" else "Days from transaction before interest starts accruing",
                                                fontSize = 10.sp
                                            )
                                        }
                                    },
                                    keyboardOptions = KeyboardOptions(
                                        keyboardType = KeyboardType.Number,
                                        imeAction = ImeAction.Next
                                    ),
                                    singleLine = true,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("input_custom_grace_days"),
                                    shape = RoundedCornerShape(10.dp)
                                )

                                // Quick presets for grace period
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    val gracePresets = listOf(
                                        0 to (if (isBn) "০ দিন (তাৎক্ষণিক)" else "0 Days (Immediate)"),
                                        15 to (if (isBn) "১৫ দিন" else "15 Days"),
                                        30 to (if (isBn) "৩০ দিন" else "30 Days"),
                                        45 to (if (isBn) "৪৫ দিন" else "45 Days"),
                                        60 to (if (isBn) "৬০ দিন" else "60 Days"),
                                        90 to (if (isBn) "৯০ দিন" else "90 Days")
                                    )
                                    for ((days, label) in gracePresets) {
                                        val isSelected = parsedGraceDays == days
                                        FilterChip(
                                            selected = isSelected,
                                            onClick = {
                                                graceDaysText = days.toString()
                                            },
                                            label = { Text(label, fontSize = 11.sp) },
                                            colors = FilterChipDefaults.filterChipColors(
                                                selectedContainerColor = StoreGold.copy(alpha = 0.18f),
                                                selectedLabelColor = StoreGold
                                            )
                                        )
                                    }
                                }
                            }

                            // 2. Monthly Rate Input
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                OutlinedTextField(
                                    value = monthlyRateText,
                                    onValueChange = { input ->
                                        // Allow one dot or comma for decimal
                                        var hasDot = false
                                        val filtered = input.filter { ch ->
                                            if (ch.isDigit()) true
                                            else if ((ch == '.' || ch == ',') && !hasDot) {
                                                hasDot = true
                                                true
                                            } else false
                                        }
                                        monthlyRateText = filtered
                                    },
                                    label = { Text(if (isBn) "মাসিক সুদের হার (%)" else "Custom Monthly Interest Rate") },
                                    placeholder = { Text("${StoreInfoManager.monthlyInterestRate}") },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Default.Percent,
                                            contentDescription = null,
                                            tint = if (isRateValid) StoreGold else StoreRedAlert,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    },
                                    suffix = {
                                        Text(if (isBn) "% / মাস" else "% / month", fontSize = 12.sp, color = TextMuted)
                                    },
                                    isError = !isRateValid,
                                    supportingText = {
                                        if (rateErrorMsg != null) {
                                            Text(text = rateErrorMsg, color = MaterialTheme.colorScheme.error)
                                        } else {
                                            Text(
                                                text = if (isBn) "প্রতি মাসে বাকির উপর প্রযোজ্য শতাংশ হার" else "Percentage applied monthly on overdue balance",
                                                fontSize = 10.sp
                                            )
                                        }
                                    },
                                    keyboardOptions = KeyboardOptions(
                                        keyboardType = KeyboardType.Decimal,
                                        imeAction = ImeAction.Done
                                    ),
                                    keyboardActions = KeyboardActions(
                                        onDone = { focusManager.clearFocus() }
                                    ),
                                    singleLine = true,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("input_custom_monthly_rate"),
                                    shape = RoundedCornerShape(10.dp)
                                )

                                // Quick presets for monthly rate
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    val ratePresets = listOf(
                                        1.0 to "1.0%",
                                        1.5 to "1.5%",
                                        2.0 to "2.0%",
                                        2.5 to "2.5%",
                                        3.0 to "3.0%",
                                        5.0 to "5.0%"
                                    )
                                    for ((rate, label) in ratePresets) {
                                        val isSelected = parsedMonthlyRate != null && kotlin.math.abs(parsedMonthlyRate - rate) < 0.01
                                        FilterChip(
                                            selected = isSelected,
                                            onClick = {
                                                monthlyRateText = if (rate % 1.0 == 0.0) "%.0f".format(Locale.US, rate) else "%.1f".format(Locale.US, rate)
                                            },
                                            label = { Text(label, fontSize = 11.sp) },
                                            colors = FilterChipDefaults.filterChipColors(
                                                selectedContainerColor = StoreGold.copy(alpha = 0.18f),
                                                selectedLabelColor = StoreGold
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Live Impact Calculation Card (Proper Financial Logic Preview)
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Calculate,
                                contentDescription = null,
                                tint = StoreGold,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = if (isBn) "লাইভ প্রভাব ও ক্যালকুলেশন প্রিভিউ" else "Live Impact & Calculation Preview",
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }

                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                            thickness = 0.8.dp
                        )

                        // 1. Effective Policy
                        PreviewMetricRow(
                            label = if (isBn) "কার্যকর নীতি:" else "Effective Policy:",
                            value = when (selectedMode) {
                                CustomerInterestPolicyMode.EXEMPT -> if (isBn) "সুদ-মুক্ত (০%)" else "Exempt (0%)"
                                CustomerInterestPolicyMode.STORE_DEFAULT -> "${StoreInfoManager.monthlyInterestRate}% / mo (${StoreInfoManager.interestGracePeriodDays}d grace)"
                                CustomerInterestPolicyMode.CUSTOM_TERMS -> "${"%.2f".format(Locale.US, effectiveMonthlyRate)}% / mo (${effectiveGraceDays}d grace)"
                            },
                            highlight = selectedMode != CustomerInterestPolicyMode.EXEMPT
                        )

                        // 2. Projected Monthly Interest on Current Due
                        if (customer.balance > 0.0) {
                            PreviewMetricRow(
                                label = if (isBn) "মাসিক সম্ভাব্য সুদ (₹%.0f বাকীতে):".format(customer.balance) else "Est. Monthly Interest (on ₹%.0f):".format(customer.balance),
                                value = if (selectedMode == CustomerInterestPolicyMode.EXEMPT) {
                                    if (isBn) "₹০.০০ (মুক্ত)" else "₹0.00 (Exempt)"
                                } else {
                                    "₹%.2f / month".format(Locale.US, projectedMonthlyInterest)
                                },
                                highlight = selectedMode != CustomerInterestPolicyMode.EXEMPT && projectedMonthlyInterest > 0.0
                            )
                        }

                        // 3. Current Overdue Interest based on actual Ledger Entries
                        PreviewMetricRow(
                            label = if (isBn) "বর্তমান অপরিশোধিত সুদ:" else "Accrued Overdue Interest:",
                            value = if (selectedMode == CustomerInterestPolicyMode.EXEMPT) {
                                "₹0.00"
                            } else {
                                "₹%.2f (%d bills overdue)".format(
                                    Locale.US,
                                    simulatedBreakdown.totalAccruedInterest,
                                    simulatedBreakdown.overdueEntriesCount
                                )
                            },
                            highlight = simulatedBreakdown.totalAccruedInterest > 0.0
                        )

                        // 4. Total Outstanding with Accrued Interest
                        if (simulatedBreakdown.totalAccruedInterest > 0.0 && selectedMode != CustomerInterestPolicyMode.EXEMPT) {
                            PreviewMetricRow(
                                label = if (isBn) "মোট প্রদেয় (আসল + সুদ):" else "Total Due with Interest:",
                                value = "₹%.2f".format(Locale.US, customer.balance + simulatedBreakdown.totalAccruedInterest),
                                highlight = true,
                                bold = true
                            )
                        }

                        // 5. Bill / Invoice Disclaimer Preview
                        val disclaimerPreview = remember(simulatedCustomer, effectiveGraceDays, effectiveMonthlyRate, selectedMode, isBn) {
                            if (selectedMode == CustomerInterestPolicyMode.EXEMPT) {
                                if (isBn) "এই গ্রাহক সুদমুক্ত (Interest Exempt)।" else "This customer account is interest-exempt."
                            } else {
                                KhataInterestCalculator.formatDisclaimer(
                                    settings = simulatedSettings,
                                    customer = simulatedCustomer,
                                    isBengali = isBn,
                                    dueAmount = customer.balance
                                )
                            }
                        }

                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surface,
                            border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(8.dp)) {
                                Text(
                                    text = if (isBn) "রসিদ ও রিমাইন্ডার ডিসক্লেইমার প্রিভিউ:" else "Receipt & Reminder Disclaimer Preview:",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = TextMuted
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = disclaimerPreview,
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    lineHeight = 15.sp
                                )
                            }
                        }
                    }
                }

                // Action Buttons (Cancel / Save)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("btn_cancel_interest_terms"),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            text = if (isBn) "বাতিল" else "Cancel",
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    Button(
                        onClick = {
                            if (!isInputValid) {
                                Toast.makeText(
                                    context,
                                    if (isBn) "অনুগ্রহ করে সঠিক তথ্য প্রদান করুন" else "Please correct invalid inputs",
                                    Toast.LENGTH_SHORT
                                ).show()
                                return@Button
                            }

                            val updatedCustomer = when (selectedMode) {
                                CustomerInterestPolicyMode.EXEMPT -> customer.copy(
                                    interestExempt = true,
                                    customGracePeriodDays = null,
                                    customInterestRate = null
                                )
                                CustomerInterestPolicyMode.STORE_DEFAULT -> customer.copy(
                                    interestExempt = false,
                                    customGracePeriodDays = null,
                                    customInterestRate = null
                                )
                                CustomerInterestPolicyMode.CUSTOM_TERMS -> customer.copy(
                                    interestExempt = false,
                                    customGracePeriodDays = parsedGraceDays,
                                    customInterestRate = parsedMonthlyRate
                                )
                            }

                            onSave(updatedCustomer)
                        },
                        enabled = isInputValid,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("btn_save_interest_terms"),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = StoreGold,
                            contentColor = Color.White
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Save,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isBn) "সংরক্ষণ করুন" else "Save Terms",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PolicyOptionCard(
    title: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selectedColor: Color = StoreGold,
    testTag: String = ""
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .testTag(testTag),
        shape = RoundedCornerShape(12.dp),
        color = if (selected) selectedColor.copy(alpha = 0.09f) else MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            width = if (selected) 1.5.dp else 1.dp,
            color = if (selected) selectedColor else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            RadioButton(
                selected = selected,
                onClick = onClick,
                colors = RadioButtonDefaults.colors(
                    selectedColor = selectedColor,
                    unselectedColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            )

            Surface(
                shape = CircleShape,
                color = if (selected) selectedColor.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.size(34.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = if (selected) selectedColor else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = description,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    color = TextMuted
                )
            }
        }
    }
}

@Composable
private fun PreviewMetricRow(
    label: String,
    value: String,
    highlight: Boolean = false,
    bold: Boolean = false
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontSize = 11.sp,
            color = TextMuted
        )
        Text(
            text = value,
            fontSize = 12.sp,
            fontWeight = if (bold || highlight) FontWeight.Bold else FontWeight.Normal,
            color = if (highlight) StoreGold else MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.End
        )
    }
}
