package com.example.ui.screens.offers

import android.app.DatePickerDialog
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import coil.compose.AsyncImage
import com.example.data.local.entities.Offer
import com.example.data.local.entities.OfferStatus
import com.example.data.local.entities.OfferType
import com.example.data.local.entities.Product
import com.example.ui.theme.*
import com.example.utils.LanguageManager
import com.example.utils.StaffManager
import com.example.viewmodel.StoreViewModel
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID

enum class OffersHubTab {
    OFFERS,
    DISCOUNTED_PRODUCTS
}

data class DiscountedProductInfo(
    val product: Product,
    val bestOffer: Offer,
    val regularPrice: Double,
    val discountAmount: Double,
    val discountPercent: Double,
    val discountedPrice: Double,
    val badgeText: String,
    val offerType: OfferType,
    val isLossLeader: Boolean,
    val lossAmount: Double,
    val profitMargin: Double,
    val profitMarginPercent: Double,
    val isSpecific: Boolean
)

data class StarterOfferTemplate(
    val titleEn: String,
    val titleBn: String,
    val descEn: String,
    val descBn: String,
    val tagEn: String,
    val tagBn: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val color: Color,
    val buildOffer: () -> Offer
)

fun calculateDiscountedProducts(
    allProducts: List<Product>,
    activeOffers: List<Offer>,
    todayStr: String,
    isBn: Boolean
): List<DiscountedProductInfo> {
    if (activeOffers.isEmpty() || allProducts.isEmpty()) return emptyList()

    val result = mutableListOf<DiscountedProductInfo>()

    for (product in allProducts) {
        val matchingOffers = activeOffers.filter { it.appliesToProduct(product.id, todayStr) }
        if (matchingOffers.isEmpty()) continue

        val candidates = matchingOffers.mapNotNull { offer ->
            val regularPrice = product.sellingPrice
            when (offer.getOfferTypeEnum()) {
                OfferType.PERCENT_DISCOUNT -> {
                    if (offer.discountValue <= 0.0) null
                    else {
                        val discAmt = (regularPrice * (offer.discountValue / 100.0)).coerceIn(0.0, regularPrice)
                        val finalPrice = (regularPrice - discAmt).coerceAtLeast(0.0)
                        val pVal = offer.discountValue
                        val badge = if (pVal % 1.0 == 0.0) "${pVal.toInt()}% OFF" else "%.1f%% OFF".format(pVal)
                        val isLoss = product.costPrice > 0.0 && finalPrice < product.costPrice
                        val lossAmt = if (isLoss) (product.costPrice - finalPrice) else 0.0
                        val margin = finalPrice - product.costPrice
                        val marginPct = if (finalPrice > 0) (margin / finalPrice) * 100.0 else 0.0
                        DiscountedProductInfo(
                            product = product,
                            bestOffer = offer,
                            regularPrice = regularPrice,
                            discountAmount = discAmt,
                            discountPercent = pVal,
                            discountedPrice = finalPrice,
                            badgeText = badge,
                            offerType = OfferType.PERCENT_DISCOUNT,
                            isLossLeader = isLoss,
                            lossAmount = lossAmt,
                            profitMargin = margin,
                            profitMarginPercent = marginPct,
                            isSpecific = !offer.isStoreWide()
                        )
                    }
                }
                OfferType.FLAT_DISCOUNT -> {
                    if (offer.discountValue <= 0.0) null
                    else {
                        val discAmt = offer.discountValue.coerceIn(0.0, regularPrice)
                        val finalPrice = (regularPrice - discAmt).coerceAtLeast(0.0)
                        val pVal = if (regularPrice > 0) (discAmt / regularPrice) * 100.0 else 0.0
                        val badge = if (offer.discountValue % 1.0 == 0.0) "₹${offer.discountValue.toInt()} OFF" else "₹%.2f OFF".format(offer.discountValue)
                        val isLoss = product.costPrice > 0.0 && finalPrice < product.costPrice
                        val lossAmt = if (isLoss) (product.costPrice - finalPrice) else 0.0
                        val margin = finalPrice - product.costPrice
                        val marginPct = if (finalPrice > 0) (margin / finalPrice) * 100.0 else 0.0
                        DiscountedProductInfo(
                            product = product,
                            bestOffer = offer,
                            regularPrice = regularPrice,
                            discountAmount = discAmt,
                            discountPercent = pVal,
                            discountedPrice = finalPrice,
                            badgeText = badge,
                            offerType = OfferType.FLAT_DISCOUNT,
                            isLossLeader = isLoss,
                            lossAmount = lossAmt,
                            profitMargin = margin,
                            profitMarginPercent = marginPct,
                            isSpecific = !offer.isStoreWide()
                        )
                    }
                }
                OfferType.BUY_X_GET_Y -> {
                    val buyQ = offer.buyQty.coerceAtLeast(1.0)
                    val getQ = offer.getQty.coerceAtLeast(1.0)
                    val setTotal = buyQ + getQ
                    val discountRatio = (getQ / setTotal) * (if (offer.getDiscountPercent > 0) offer.getDiscountPercent / 100.0 else 1.0)
                    val discAmt = (regularPrice * discountRatio).coerceIn(0.0, regularPrice)
                    val finalPrice = (regularPrice - discAmt).coerceAtLeast(0.0)
                    val pVal = discountRatio * 100.0
                    val bStr = if (buyQ % 1.0 == 0.0) "${buyQ.toInt()}" else "%.1f".format(buyQ)
                    val gStr = if (getQ % 1.0 == 0.0) "${getQ.toInt()}" else "%.1f".format(getQ)
                    val badge = if (isBn) "BOGO $bStr কিনুন $gStr ফ্রি" else "BOGO Buy $bStr Get $gStr Free"
                    val isLoss = product.costPrice > 0.0 && finalPrice < product.costPrice
                    val lossAmt = if (isLoss) (product.costPrice - finalPrice) else 0.0
                    val margin = finalPrice - product.costPrice
                    val marginPct = if (finalPrice > 0) (margin / finalPrice) * 100.0 else 0.0
                    DiscountedProductInfo(
                        product = product,
                        bestOffer = offer,
                        regularPrice = regularPrice,
                        discountAmount = discAmt,
                        discountPercent = pVal,
                        discountedPrice = finalPrice,
                        badgeText = badge,
                        offerType = OfferType.BUY_X_GET_Y,
                        isLossLeader = isLoss,
                        lossAmount = lossAmt,
                        profitMargin = margin,
                        profitMarginPercent = marginPct,
                        isSpecific = !offer.isStoreWide()
                    )
                }
                OfferType.FREE_GIFT -> {
                    if (offer.freeProductId == product.id) {
                        val badge = if (isBn) "ফ্রি উপহার সামগ্রী" else "FREE Gift Item"
                        DiscountedProductInfo(
                            product = product,
                            bestOffer = offer,
                            regularPrice = regularPrice,
                            discountAmount = regularPrice,
                            discountPercent = 100.0,
                            discountedPrice = 0.0,
                            badgeText = badge,
                            offerType = OfferType.FREE_GIFT,
                            isLossLeader = product.costPrice > 0,
                            lossAmount = product.costPrice,
                            profitMargin = -product.costPrice,
                            profitMarginPercent = -100.0,
                            isSpecific = true
                        )
                    } else null
                }
                OfferType.COMBO_BUNDLE -> null
            }
        }

        if (candidates.isNotEmpty()) {
            val best = candidates.maxWithOrNull(
                compareBy<DiscountedProductInfo> { it.discountAmount }
                    .thenBy { it.isSpecific }
            )
            if (best != null) {
                result.add(best)
            }
        }
    }

    return result
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OffersManagementDialog(
    viewModel: StoreViewModel,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val isBn = LanguageManager.isBengali
    val allOffers by viewModel.allOffers.collectAsState()
    val allProducts by viewModel.allProducts.collectAsState()

    var currentTab by remember { mutableStateOf(OffersHubTab.OFFERS) }

    var showAddEditDialog by remember { mutableStateOf(false) }
    var editingOffer by remember { mutableStateOf<Offer?>(null) }
    var offerToDelete by remember { mutableStateOf<Offer?>(null) }
    var productToRemoveDiscount by remember { mutableStateOf<DiscountedProductInfo?>(null) }
    var showPinDialog by remember { mutableStateOf(false) }
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    var selectedFilter by remember { mutableStateOf("ALL") } // ALL, ACTIVE, UPCOMING, EXPIRED, INACTIVE

    // Discounted Products Search & Filters
    var discountedSearchQuery by remember { mutableStateOf("") }
    var discountedTypeFilter by remember { mutableStateOf("ALL") } // ALL, PERCENT, FLAT, BOGO, LOSS_WARNING
    var discountedCategoryFilter by remember { mutableStateOf("ALL") }
    var discountedSortOption by remember { mutableStateOf("HIGHEST_DISCOUNT") } // HIGHEST_DISCOUNT, HIGHEST_SAVING, PRICE_LOW, PRICE_HIGH, NAME

    val todayStr = remember { Offer.getTodayDateString() }

    val filteredOffers = remember(allOffers, selectedFilter, todayStr) {
        when (selectedFilter) {
            "ACTIVE" -> allOffers.filter { it.getEffectiveStatus(todayStr) == OfferStatus.ACTIVE }
            "UPCOMING" -> allOffers.filter { it.getEffectiveStatus(todayStr) == OfferStatus.UPCOMING }
            "EXPIRED" -> allOffers.filter { it.getEffectiveStatus(todayStr) == OfferStatus.EXPIRED }
            "INACTIVE" -> allOffers.filter { it.getEffectiveStatus(todayStr) == OfferStatus.INACTIVE }
            else -> allOffers
        }
    }

    val activeOffers = remember(allOffers, todayStr) {
        allOffers.filter { it.getEffectiveStatus(todayStr) == OfferStatus.ACTIVE }
    }

    val discountedProductItems = remember(allProducts, activeOffers, todayStr, isBn) {
        calculateDiscountedProducts(allProducts, activeOffers, todayStr, isBn)
    }

    val discountedCategories = remember(discountedProductItems) {
        listOf("ALL") + discountedProductItems.map { it.product.category.trim() }.filter { it.isNotBlank() }.distinct()
    }

    val filteredDiscountedProducts = remember(
        discountedProductItems,
        discountedSearchQuery,
        discountedTypeFilter,
        discountedCategoryFilter,
        discountedSortOption
    ) {
        var list = discountedProductItems

        if (discountedCategoryFilter != "ALL") {
            list = list.filter { it.product.category.equals(discountedCategoryFilter, ignoreCase = true) }
        }

        if (discountedTypeFilter != "ALL") {
            list = when (discountedTypeFilter) {
                "PERCENT" -> list.filter { it.offerType == OfferType.PERCENT_DISCOUNT }
                "FLAT" -> list.filter { it.offerType == OfferType.FLAT_DISCOUNT }
                "BOGO" -> list.filter { it.offerType == OfferType.BUY_X_GET_Y }
                "LOSS_WARNING" -> list.filter { it.isLossLeader }
                else -> list
            }
        }

        if (discountedSearchQuery.isNotBlank()) {
            val q = discountedSearchQuery.trim()
            list = list.filter {
                it.product.nameEn.contains(q, ignoreCase = true) ||
                it.product.nameBn.contains(q, ignoreCase = true) ||
                (it.product.barcode?.contains(q, ignoreCase = true) == true) ||
                it.product.category.contains(q, ignoreCase = true) ||
                it.bestOffer.name.contains(q, ignoreCase = true)
            }
        }

        when (discountedSortOption) {
            "HIGHEST_DISCOUNT" -> list.sortedByDescending { it.discountPercent }
            "HIGHEST_SAVING" -> list.sortedByDescending { it.discountAmount }
            "PRICE_LOW" -> list.sortedBy { it.discountedPrice }
            "PRICE_HIGH" -> list.sortedByDescending { it.discountedPrice }
            "NAME" -> list.sortedBy { it.product.getDisplayName(isBn).lowercase() }
            else -> list
        }
    }

    val percentCount = remember(discountedProductItems) { discountedProductItems.count { it.offerType == OfferType.PERCENT_DISCOUNT } }
    val flatCount = remember(discountedProductItems) { discountedProductItems.count { it.offerType == OfferType.FLAT_DISCOUNT } }
    val bogoCount = remember(discountedProductItems) { discountedProductItems.count { it.offerType == OfferType.BUY_X_GET_Y } }
    val lossCount = remember(discountedProductItems) { discountedProductItems.count { it.isLossLeader } }

    val templatePrimaryColor = StorePrimary
    val templateProfitColor = StoreGreenProfit

    val starterTemplates = remember(allProducts, isBn, templatePrimaryColor, templateProfitColor) {
        listOf(
            StarterOfferTemplate(
                titleEn = "10% Storewide Weekend Sale",
                titleBn = "১০% সাপ্তাহিক দোকানব্যাপী সেল",
                descEn = "10% automatic discount on all store items",
                descBn = "দোকানের সমস্ত পণ্যে ১০% স্বয়ংক্রিয় নগদ ছাড়",
                tagEn = "10% OFF Storewide",
                tagBn = "১০% ছাড়",
                icon = Icons.Default.Percent,
                color = templatePrimaryColor,
                buildOffer = {
                    Offer(
                        name = if (isBn) "১০% সাপ্তাহিক অফার" else "10% Weekend Storewide Discount",
                        type = OfferType.PERCENT_DISCOUNT.name,
                        discountValue = 10.0,
                        applicableProductIds = ""
                    )
                }
            ),
            StarterOfferTemplate(
                titleEn = "Flat ₹20 Off Super Saver",
                titleBn = "নির্দিষ্ট ₹২০ ফ্ল্যাট ছাড়",
                descEn = "Flat ₹20 direct discount per item",
                descBn = "প্রতিটি আইটেমে সরাসরি ₹২০ নগদ ছাড়",
                tagEn = "Flat ₹20 OFF",
                tagBn = "₹২০ ফ্ল্যাট ছাড়",
                icon = Icons.Default.CurrencyRupee,
                color = templateProfitColor,
                buildOffer = {
                    Offer(
                        name = if (isBn) "₹২০ সুপার সেভার ছাড়" else "Flat ₹20 Super Saver Deal",
                        type = OfferType.FLAT_DISCOUNT.name,
                        discountValue = 20.0,
                        applicableProductIds = ""
                    )
                }
            ),
            StarterOfferTemplate(
                titleEn = "Buy 1 Get 1 Free (BOGO)",
                titleBn = "১টি কিনলে ১টি ফ্রি (BOGO)",
                descEn = "Reward 1 free item when customer buys 1",
                descBn = "একটি ক্রয়ে আরেকটি সম্পূর্ণ বিনামূল্যে পান",
                tagEn = "Buy 1 Get 1 FREE",
                tagBn = "১ কিনলে ১ ফ্রি",
                icon = Icons.Default.CardGiftcard,
                color = Color(0xFFEA580C),
                buildOffer = {
                    Offer(
                        name = if (isBn) "১টি কিনলে ১টি ফ্রি অফার" else "Buy 1 Get 1 FREE (BOGO Deal)",
                        type = OfferType.BUY_X_GET_Y.name,
                        buyQty = 1.0,
                        getQty = 1.0,
                        getDiscountPercent = 100.0,
                        applicableProductIds = ""
                    )
                }
            ),
            StarterOfferTemplate(
                titleEn = "Free Gift on ₹500+ Purchases",
                titleBn = "₹৫০০+ কেনাকাটায় ফ্রি উপহার সামগ্রী",
                descEn = "Reward free gift product when bill is ₹500+",
                descBn = "বিল ৫০০ টাকা ছাড়ালেই বিনামূল্যে উপহার",
                tagEn = "Gift on ₹500+",
                tagBn = "উপহার অফার",
                icon = Icons.Default.CardGiftcard,
                color = Color(0xFF0D9488),
                buildOffer = {
                    Offer(
                        name = if (isBn) "₹৫০০ কেনাকাটায় ফ্রি উপহার" else "Free Gift on ₹500+ Bill",
                        type = OfferType.FREE_GIFT.name,
                        minSpendAmount = 500.0,
                        freeProductQty = 1.0,
                        freeProductId = allProducts.firstOrNull()?.id,
                        applicableProductIds = ""
                    )
                }
            )
        )
    }

    val canManage = viewModel.canManageOffers()

    fun executeWithPermissionCheck(action: () -> Unit) {
        if (canManage) {
            action()
        } else {
            pendingAction = action
            showPinDialog = true
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.96f)
                .fillMaxHeight(0.94f)
                .clip(RoundedCornerShape(16.dp)),
            color = MaterialTheme.colorScheme.surface
        ) {
            Scaffold(
                topBar = {
                    Column {
                        TopAppBar(
                            title = {
                                Column {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Default.LocalOffer,
                                            contentDescription = null,
                                            tint = StorePrimary,
                                            modifier = Modifier.size(22.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = if (isBn) "অফার ও প্রোমোশন হাব" else "Unified Offers Hub",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 18.sp
                                        )
                                    }
                                    Text(
                                        text = if (isBn) "স্বয়ংক্রিয় প্রোডাক্ট ডিসকাউন্ট ও অফার ব্যবস্থাপনা" else "Automatic Product Discounts & Promotional Pricing",
                                        fontSize = 11.sp,
                                        color = TextMuted
                                    )
                                }
                            },
                            navigationIcon = {
                                IconButton(onClick = onDismiss) {
                                    Icon(Icons.Default.Close, contentDescription = "Close")
                                }
                            },
                            actions = {
                                Button(
                                    onClick = {
                                        executeWithPermissionCheck {
                                            editingOffer = null
                                            showAddEditDialog = true
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = StorePrimary),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.padding(end = 8.dp)
                                ) {
                                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = if (isBn) "নতুন অফার" else "New Offer",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            },
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                            )
                        )

                        // Segmented Tab Row: Tab 1: Offers & Campaigns, Tab 2: Discounted Products
                        TabRow(
                            selectedTabIndex = if (currentTab == OffersHubTab.OFFERS) 0 else 1,
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                            contentColor = StorePrimary,
                            indicator = { tabPositions ->
                                TabRowDefaults.SecondaryIndicator(
                                    modifier = Modifier.tabIndicatorOffset(tabPositions[if (currentTab == OffersHubTab.OFFERS) 0 else 1]),
                                    color = StorePrimary,
                                    height = 3.dp
                                )
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Tab(
                                selected = currentTab == OffersHubTab.OFFERS,
                                onClick = { currentTab = OffersHubTab.OFFERS },
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            Icons.Default.Campaign,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp),
                                            tint = if (currentTab == OffersHubTab.OFFERS) StorePrimary else TextMuted
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = if (isBn) "অফার ও নিয়মাবলী (${allOffers.size})" else "Offers & Rules (${allOffers.size})",
                                            fontWeight = if (currentTab == OffersHubTab.OFFERS) FontWeight.Bold else FontWeight.Normal,
                                            fontSize = 12.5.sp,
                                            color = if (currentTab == OffersHubTab.OFFERS) StorePrimary else TextMuted
                                        )
                                    }
                                }
                            )
                            Tab(
                                selected = currentTab == OffersHubTab.DISCOUNTED_PRODUCTS,
                                onClick = { currentTab = OffersHubTab.DISCOUNTED_PRODUCTS },
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            Icons.Default.Percent,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp),
                                            tint = if (currentTab == OffersHubTab.DISCOUNTED_PRODUCTS) StorePrimary else TextMuted
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = if (isBn) "ছাড়যুক্ত পণ্য তালিকা (${discountedProductItems.size})" else "Discounted Products (${discountedProductItems.size})",
                                            fontWeight = if (currentTab == OffersHubTab.DISCOUNTED_PRODUCTS) FontWeight.Bold else FontWeight.Normal,
                                            fontSize = 12.5.sp,
                                            color = if (currentTab == OffersHubTab.DISCOUNTED_PRODUCTS) StorePrimary else TextMuted
                                        )
                                    }
                                }
                            )
                        }
                    }
                }
            ) { paddingValues ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    if (currentTab == OffersHubTab.OFFERS) {
                        // TAB 1: OFFERS & CAMPAIGNS
                        val activeCount = remember(allOffers, todayStr) { allOffers.count { it.getEffectiveStatus(todayStr) == OfferStatus.ACTIVE } }
                        val upcomingCount = remember(allOffers, todayStr) { allOffers.count { it.getEffectiveStatus(todayStr) == OfferStatus.UPCOMING } }
                        val expiredCount = remember(allOffers, todayStr) { allOffers.count { it.getEffectiveStatus(todayStr) == OfferStatus.EXPIRED } }
                        val inactiveCount = remember(allOffers, todayStr) { allOffers.count { it.getEffectiveStatus(todayStr) == OfferStatus.INACTIVE } }

                        LazyRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            item {
                                FilterChip(
                                    selected = selectedFilter == "ALL",
                                    onClick = { selectedFilter = "ALL" },
                                    label = { Text(if (isBn) "সব (${allOffers.size})" else "All (${allOffers.size})", fontSize = 11.5.sp) }
                                )
                            }
                            item {
                                FilterChip(
                                    selected = selectedFilter == "ACTIVE",
                                    onClick = { selectedFilter = "ACTIVE" },
                                    label = {
                                        Text(
                                            if (isBn) "● সক্রিয় ($activeCount)" else "● Active ($activeCount)",
                                            fontSize = 11.5.sp,
                                            color = if (selectedFilter == "ACTIVE") Color.Unspecified else StoreGreenProfit,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                )
                            }
                            item {
                                FilterChip(
                                    selected = selectedFilter == "UPCOMING",
                                    onClick = { selectedFilter = "UPCOMING" },
                                    label = {
                                        Text(
                                            if (isBn) "⏱ আসন্ন ($upcomingCount)" else "⏱ Upcoming ($upcomingCount)",
                                            fontSize = 11.5.sp,
                                            color = if (selectedFilter == "UPCOMING") Color.Unspecified else Color(0xFF2563EB),
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                )
                            }
                            item {
                                FilterChip(
                                    selected = selectedFilter == "EXPIRED",
                                    onClick = { selectedFilter = "EXPIRED" },
                                    label = {
                                        Text(
                                            if (isBn) "✖ মেয়াদ শেষ ($expiredCount)" else "✖ Expired ($expiredCount)",
                                            fontSize = 11.5.sp,
                                            color = if (selectedFilter == "EXPIRED") Color.Unspecified else StoreRedAlert,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                )
                            }
                            item {
                                FilterChip(
                                    selected = selectedFilter == "INACTIVE",
                                    onClick = { selectedFilter = "INACTIVE" },
                                    label = {
                                        Text(
                                            if (isBn) "⏸ বন্ধ ($inactiveCount)" else "⏸ Inactive ($inactiveCount)",
                                            fontSize = 11.5.sp,
                                            color = if (selectedFilter == "INACTIVE") Color.Unspecified else TextMuted
                                        )
                                    }
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        if (filteredOffers.isEmpty()) {
                            if (allOffers.isEmpty()) {
                                // Rich Starter Empty State with Quick Templates
                                LazyColumn(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    item {
                                        Surface(
                                            shape = RoundedCornerShape(12.dp),
                                            color = StorePrimary.copy(alpha = 0.08f),
                                            border = BorderStroke(1.dp, StorePrimary.copy(alpha = 0.2f)),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(14.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Surface(
                                                    shape = CircleShape,
                                                    color = StorePrimary.copy(alpha = 0.15f),
                                                    modifier = Modifier.size(44.dp)
                                                ) {
                                                    Box(contentAlignment = Alignment.Center) {
                                                        Icon(
                                                            imageVector = Icons.Default.LocalOffer,
                                                            contentDescription = null,
                                                            tint = StorePrimary,
                                                            modifier = Modifier.size(24.dp)
                                                        )
                                                    }
                                                }
                                                Spacer(modifier = Modifier.width(12.dp))
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(
                                                        text = if (isBn) "কোনো অফার সক্রিয় নেই" else "No Offers Configured Yet",
                                                        fontWeight = FontWeight.Bold,
                                                        fontSize = 15.sp,
                                                        color = TextDark
                                                    )
                                                    Text(
                                                        text = if (isBn) "নিচের যেকোনো রেডিমেড টেমপ্লেটে ট্যাপ করে ১-ক্লিকে অফার শুরু করুন, অথবা কাস্টম অফার তৈরি করুন:" else "Tap any ready-to-use promotion template below to start in 1 click, or create a custom offer:",
                                                        fontSize = 12.sp,
                                                        color = TextMuted
                                                    )
                                                }
                                            }
                                        }
                                    }

                                    item {
                                        Text(
                                            text = if (isBn) "দ্রুত অফার তৈরির টেমপ্লেটসমূহ:" else "Quick-Start Promotion Templates:",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 13.sp,
                                            color = TextDark,
                                            modifier = Modifier.padding(top = 4.dp)
                                        )
                                    }

                                    items(starterTemplates, key = { it.titleEn }, contentType = { "OFFER_TEMPLATE_CARD" }) { tmpl ->
                                        Surface(
                                            shape = RoundedCornerShape(10.dp),
                                            color = CardBackground,
                                            border = BorderStroke(1.dp, tmpl.color.copy(alpha = 0.35f)),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable {
                                                    executeWithPermissionCheck {
                                                        editingOffer = tmpl.buildOffer()
                                                        showAddEditDialog = true
                                                    }
                                                }
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(12.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Surface(
                                                    shape = CircleShape,
                                                    color = tmpl.color.copy(alpha = 0.12f),
                                                    modifier = Modifier.size(40.dp)
                                                ) {
                                                    Box(contentAlignment = Alignment.Center) {
                                                        Icon(tmpl.icon, contentDescription = null, tint = tmpl.color, modifier = Modifier.size(20.dp))
                                                    }
                                                }
                                                Spacer(modifier = Modifier.width(12.dp))
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                                        Text(
                                                            text = if (isBn) tmpl.titleBn else tmpl.titleEn,
                                                            fontWeight = FontWeight.Bold,
                                                            fontSize = 13.5.sp,
                                                            color = TextDark
                                                        )
                                                        Spacer(modifier = Modifier.width(6.dp))
                                                        Surface(
                                                            shape = RoundedCornerShape(4.dp),
                                                            color = tmpl.color.copy(alpha = 0.12f)
                                                        ) {
                                                            Text(
                                                                text = if (isBn) tmpl.tagBn else tmpl.tagEn,
                                                                fontSize = 9.5.sp,
                                                                color = tmpl.color,
                                                                fontWeight = FontWeight.Bold,
                                                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                                            )
                                                        }
                                                    }
                                                    Spacer(modifier = Modifier.height(2.dp))
                                                    Text(
                                                        text = if (isBn) tmpl.descBn else tmpl.descEn,
                                                        fontSize = 11.5.sp,
                                                        color = TextMuted
                                                    )
                                                }
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Button(
                                                    onClick = {
                                                        executeWithPermissionCheck {
                                                            editingOffer = tmpl.buildOffer()
                                                            showAddEditDialog = true
                                                        }
                                                    },
                                                    colors = ButtonDefaults.buttonColors(containerColor = tmpl.color),
                                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                                    shape = RoundedCornerShape(6.dp)
                                                ) {
                                                    Text(if (isBn) "প্রয়োগ" else "Apply", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                                }
                                            }
                                        }
                                    }

                                    item {
                                        Spacer(modifier = Modifier.height(8.dp))
                                        OutlinedButton(
                                            onClick = {
                                                executeWithPermissionCheck {
                                                    editingOffer = null
                                                    showAddEditDialog = true
                                                }
                                            },
                                            shape = RoundedCornerShape(8.dp),
                                            modifier = Modifier.fillMaxWidth(),
                                            contentPadding = PaddingValues(vertical = 10.dp)
                                        ) {
                                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(if (isBn) "কাস্টম অফার তৈরি করুন" else "Create Custom Offer", fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            } else {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .weight(1f),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(
                                            text = if (isBn) "এই ফিল্টারে কোনো অফার নেই" else "No offers found in this filter",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 14.sp,
                                            color = TextDark
                                        )
                                        Spacer(modifier = Modifier.height(8.dp))
                                        TextButton(onClick = { selectedFilter = "ALL" }) {
                                            Text(if (isBn) "সকল অফার দেখুন" else "View All Offers")
                                        }
                                    }
                                }
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                items(filteredOffers, key = { it.id }) { offer ->
                                    OfferCard(
                                        offer = offer,
                                        allProducts = allProducts,
                                        isBn = isBn,
                                        onToggleActive = { active ->
                                            executeWithPermissionCheck {
                                                viewModel.toggleOfferActive(offer.id, active, authorized = true) { success, msg ->
                                                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                        },
                                        onEdit = {
                                            executeWithPermissionCheck {
                                                editingOffer = offer
                                                showAddEditDialog = true
                                            }
                                        },
                                        onDelete = {
                                            executeWithPermissionCheck {
                                                offerToDelete = offer
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    } else {
                        // TAB 2: DISCOUNTED PRODUCTS LIST
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                        ) {
                            // Summary Metrics Banner
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = SurfaceWarm,
                                border = BorderStroke(1.dp, TextMuted.copy(alpha = 0.2f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    horizontalArrangement = Arrangement.SpaceAround,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // Total items
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(
                                            text = "${discountedProductItems.size}",
                                            fontSize = 16.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = StorePrimary
                                        )
                                        Text(
                                            text = if (isBn) "ছাড়যুক্ত পণ্য" else "Discounted Items",
                                            fontSize = 10.5.sp,
                                            color = TextMuted
                                        )
                                    }

                                    Box(modifier = Modifier.width(1.dp).height(24.dp).background(TextMuted.copy(alpha = 0.2f)))

                                    // Average Discount
                                    val avgPct = if (discountedProductItems.isNotEmpty()) {
                                        discountedProductItems.map { it.discountPercent }.average()
                                    } else 0.0
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(
                                            text = "%.1f%%".format(avgPct),
                                            fontSize = 16.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = StoreGreenProfit
                                        )
                                        Text(
                                            text = if (isBn) "গড় ছাড়" else "Avg Discount",
                                            fontSize = 10.5.sp,
                                            color = TextMuted
                                        )
                                    }

                                    Box(modifier = Modifier.width(1.dp).height(24.dp).background(TextMuted.copy(alpha = 0.2f)))

                                    // Margin / Below Cost Status
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        if (lossCount > 0) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Icon(Icons.Default.Warning, contentDescription = null, tint = StoreRedAlert, modifier = Modifier.size(15.dp))
                                                Spacer(modifier = Modifier.width(3.dp))
                                                Text(
                                                    text = "$lossCount",
                                                    fontSize = 16.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = StoreRedAlert
                                                )
                                            }
                                            Text(
                                                text = if (isBn) "ক্রয়মূল্যের নিচে!" else "Below Cost!",
                                                fontSize = 10.5.sp,
                                                color = StoreRedAlert,
                                                fontWeight = FontWeight.Bold
                                            )
                                        } else {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = StoreGreenProfit, modifier = Modifier.size(15.dp))
                                                Spacer(modifier = Modifier.width(3.dp))
                                                Text(
                                                    text = if (isBn) "নিরাপদ" else "Safe",
                                                    fontSize = 14.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = StoreGreenProfit
                                                )
                                            }
                                            Text(
                                                text = if (isBn) "মুনাফা সুরক্ষিত" else "Profitable",
                                                fontSize = 10.5.sp,
                                                color = TextMuted
                                            )
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            // Search bar
                            OutlinedTextField(
                                value = discountedSearchQuery,
                                onValueChange = { discountedSearchQuery = it },
                                placeholder = { Text(if (isBn) "পণ্য, বারকোড বা অফার খুঁজুন..." else "Search products by name, barcode, or offer...", fontSize = 12.sp) },
                                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
                                trailingIcon = {
                                    if (discountedSearchQuery.isNotBlank()) {
                                        IconButton(onClick = { discountedSearchQuery = "" }) {
                                            Icon(Icons.Default.Clear, contentDescription = "Clear", modifier = Modifier.size(16.dp))
                                        }
                                    }
                                },
                                singleLine = true,
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(50.dp)
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            // Filter Chips Row
                            LazyRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                item {
                                    FilterChip(
                                        selected = discountedTypeFilter == "ALL",
                                        onClick = { discountedTypeFilter = "ALL" },
                                        label = { Text(if (isBn) "সব (${discountedProductItems.size})" else "All (${discountedProductItems.size})", fontSize = 11.sp) }
                                    )
                                }
                                item {
                                    FilterChip(
                                        selected = discountedTypeFilter == "PERCENT",
                                        onClick = { discountedTypeFilter = "PERCENT" },
                                        label = { Text(if (isBn) "% শতাংশ ($percentCount)" else "% Percent ($percentCount)", fontSize = 11.sp) }
                                    )
                                }
                                item {
                                    FilterChip(
                                        selected = discountedTypeFilter == "FLAT",
                                        onClick = { discountedTypeFilter = "FLAT" },
                                        label = { Text(if (isBn) "₹ ফ্ল্যাট ($flatCount)" else "₹ Flat ($flatCount)", fontSize = 11.sp) }
                                    )
                                }
                                if (bogoCount > 0) {
                                    item {
                                        FilterChip(
                                            selected = discountedTypeFilter == "BOGO",
                                            onClick = { discountedTypeFilter = "BOGO" },
                                            label = { Text(if (isBn) "BOGO ($bogoCount)" else "BOGO ($bogoCount)", fontSize = 11.sp) }
                                        )
                                    }
                                }
                                if (lossCount > 0) {
                                    item {
                                        FilterChip(
                                            selected = discountedTypeFilter == "LOSS_WARNING",
                                            onClick = { discountedTypeFilter = "LOSS_WARNING" },
                                            label = {
                                                Text(
                                                    if (isBn) "⚠️ ক্রয়মূল্যের নিচে ($lossCount)" else "⚠️ Below Cost ($lossCount)",
                                                    fontSize = 11.sp,
                                                    color = StoreRedAlert,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                        )
                                    }
                                }
                            }

                            // Category chips (if categories exist)
                            if (discountedCategories.size > 2) {
                                Spacer(modifier = Modifier.height(6.dp))
                                LazyRow(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    items(discountedCategories, key = { it }) { cat ->
                                        FilterChip(
                                            selected = discountedCategoryFilter == cat,
                                            onClick = { discountedCategoryFilter = cat },
                                            label = { Text(if (cat == "ALL") (if (isBn) "সব ক্যাটাগরি" else "All Categories") else cat, fontSize = 10.5.sp) }
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            // List of Discounted Products
                            if (filteredDiscountedProducts.isEmpty()) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .weight(1f),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.LocalOffer,
                                            contentDescription = null,
                                            tint = TextMuted,
                                            modifier = Modifier.size(48.dp)
                                        )
                                        Spacer(modifier = Modifier.height(10.dp))
                                        Text(
                                            text = if (discountedProductItems.isEmpty()) {
                                                if (isBn) "বর্তমানে কোনো পণ্যে সক্রিয় ছাড় নেই" else "No Products Currently Have Active Discounts"
                                            } else {
                                                if (isBn) "কোনো পণ্য পাওয়া যায়নি" else "No products match this filter"
                                            },
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 15.sp,
                                            color = TextDark
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = if (discountedProductItems.isEmpty()) {
                                                if (isBn) "অফার চালু করে পণ্যের মূল্য ও শতকরা ছাড় উপভোগ করুন" else "Create or activate an offer to apply discounts to your products"
                                            } else {
                                                if (isBn) "অনুসন্ধান বা ফিল্টার পরিবর্তন করুন" else "Try clearing your search or filters"
                                            },
                                            fontSize = 11.5.sp,
                                            color = TextMuted
                                        )
                                        if (discountedProductItems.isEmpty()) {
                                            Spacer(modifier = Modifier.height(14.dp))
                                            Button(
                                                onClick = {
                                                    currentTab = OffersHubTab.OFFERS
                                                    executeWithPermissionCheck {
                                                        editingOffer = null
                                                        showAddEditDialog = true
                                                    }
                                                },
                                                colors = ButtonDefaults.buttonColors(containerColor = StorePrimary),
                                                shape = RoundedCornerShape(8.dp)
                                            ) {
                                                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text(if (isBn) "ডিসকাউন্ট অফার তৈরি করুন" else "Create Discount Offer")
                                            }
                                        }
                                    }
                                }
                            } else {
                                LazyColumn(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    items(filteredDiscountedProducts, key = { it.product.id }) { item ->
                                        DiscountedProductCard(
                                            item = item,
                                            isBn = isBn,
                                            onEditOffer = {
                                                executeWithPermissionCheck {
                                                    editingOffer = item.bestOffer
                                                    showAddEditDialog = true
                                                }
                                            },
                                            onRemoveDiscount = {
                                                executeWithPermissionCheck {
                                                    productToRemoveDiscount = item
                                                }
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Confirmation dialog to exclude/remove discount from product
    if (productToRemoveDiscount != null) {
        val info = productToRemoveDiscount!!
        val offer = info.bestOffer
        val prod = info.product
        AlertDialog(
            onDismissRequest = { productToRemoveDiscount = null },
            title = {
                Text(if (isBn) "পণ্য থেকে ছাড় সরাবেন?" else "Remove Discount from Product?")
            },
            text = {
                if (offer.isStoreWide()) {
                    Text(
                        if (isBn) "'${offer.name}' অফারটি বর্তমানে সমস্ত পণ্যে প্রযোজ্য। আপনি কি '${prod.getDisplayName(isBn)}' পণ্যটিকে এই অফার থেকে বাদ দিতে চান?"
                        else "Offer '${offer.name}' currently applies to all products. Do you want to exclude '${prod.getDisplayName(isBn)}' from this discount?"
                    )
                } else {
                    Text(
                        if (isBn) "আপনি কি নিশ্চিত যে '${prod.getDisplayName(isBn)}' পণ্যটিকে '${offer.name}' অফার থেকে মুছে ফেলবেন?"
                        else "Are you sure you want to remove '${prod.getDisplayName(isBn)}' from offer '${offer.name}'?"
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val newProductIds = if (offer.isStoreWide()) {
                            allProducts.filter { it.id != prod.id }.map { it.id }.joinToString(",")
                        } else {
                            offer.getProductIdsList().filter { it != prod.id }.joinToString(",")
                        }
                        val updatedOffer = offer.copy(
                            applicableProductIds = newProductIds,
                            updatedAt = System.currentTimeMillis()
                        )
                        viewModel.saveOffer(updatedOffer, authorized = true) { success, msg ->
                            Toast.makeText(context, if (success) (if (isBn) "ছাড় সরানো হয়েছে!" else "Discount removed!") else msg, Toast.LENGTH_SHORT).show()
                            productToRemoveDiscount = null
                        }
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = StoreRedAlert)
                ) {
                    Text(if (isBn) "ছাড় সরান" else "Remove Discount", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { productToRemoveDiscount = null }) {
                    Text(if (isBn) "বাতিল" else "Cancel")
                }
            }
        )
    }

    // Add / Edit Offer Dialog
    if (showAddEditDialog) {
        AddEditOfferDialog(
            offer = editingOffer,
            allProducts = allProducts,
            isBn = isBn,
            onDismiss = { showAddEditDialog = false },
            onSave = { newOffer ->
                viewModel.saveOffer(newOffer, authorized = true) { success, msg ->
                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                    if (success) showAddEditDialog = false
                }
            }
        )
    }

    // Delete Confirmation Dialog
    if (offerToDelete != null) {
        val target = offerToDelete!!
        AlertDialog(
            onDismissRequest = { offerToDelete = null },
            title = { Text(if (isBn) "অফার মুছে ফেলতে চান?" else "Delete Offer?") },
            text = {
                Text(
                    if (isBn) "আপনি কি নিশ্চিত যে '${target.name}' অফারটি মুছে ফেলতে চান?"
                    else "Are you sure you want to delete offer '${target.name}'?"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteOffer(target, authorized = true) { success, msg ->
                            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                            offerToDelete = null
                        }
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = StoreRedAlert)
                ) {
                    Text(if (isBn) "মুছে ফেলুন" else "Delete", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { offerToDelete = null }) {
                    Text(if (isBn) "বাতিল" else "Cancel")
                }
            }
        )
    }

    // Master PIN Dialog for Permission Gating
    if (showPinDialog) {
        var pinInput by remember { mutableStateOf("") }
        var pinError by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = {
                showPinDialog = false
                pendingAction = null
            },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Lock, contentDescription = null, tint = StorePrimary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(if (isBn) "ম্যানেজার পিন কোড" else "Admin / Manager Authorization")
                }
            },
            text = {
                Column {
                    Text(
                        text = if (isBn) "অফার পরিবর্তনের জন্য মালিক বা ম্যানেজার পিন দিন:" else "Enter Owner/Manager Master PIN to manage offers:",
                        fontSize = 13.sp,
                        color = TextMuted
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = pinInput,
                        onValueChange = {
                            if (it.length <= 6) {
                                pinInput = it
                                pinError = false
                            }
                        },
                        label = { Text("PIN") },
                        isError = pinError,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (pinError) {
                        Text(
                            text = if (isBn) "ভুল পিন কোড" else "Incorrect PIN",
                            color = StoreRedAlert,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (StaffManager.verifyOwnerPin(pinInput)) {
                            showPinDialog = false
                            val act = pendingAction
                            pendingAction = null
                            act?.invoke()
                        } else {
                            pinError = true
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = StorePrimary)
                ) {
                    Text(if (isBn) "অনুমোদন করুন" else "Authorize")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showPinDialog = false
                        pendingAction = null
                    }
                ) {
                    Text(if (isBn) "বাতিল" else "Cancel")
                }
            }
        )
    }
}

@Composable
fun DiscountedProductCard(
    item: DiscountedProductInfo,
    isBn: Boolean,
    onEditOffer: () -> Unit,
    onRemoveDiscount: () -> Unit
) {
    val prod = item.product
    val offer = item.bestOffer

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = CardBackground,
        border = BorderStroke(
            1.dp,
            if (item.isLossLeader) StoreRedAlert.copy(alpha = 0.45f)
            else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
        ),
        shadowElevation = 1.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Product Thumbnail or Icon
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.size(52.dp)
                ) {
                    if (!prod.imageUri.isNullOrBlank()) {
                        AsyncImage(
                            model = prod.imageUri,
                            contentDescription = prod.getDisplayName(isBn),
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Default.Inventory2,
                                contentDescription = null,
                                tint = StorePrimary,
                                modifier = Modifier.size(26.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Top
                    ) {
                        Text(
                            text = prod.getDisplayName(isBn),
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        // Badge Tag
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = when (item.offerType) {
                                OfferType.PERCENT_DISCOUNT -> StorePrimary.copy(alpha = 0.12f)
                                OfferType.FLAT_DISCOUNT -> StoreGreenProfit.copy(alpha = 0.12f)
                                OfferType.BUY_X_GET_Y -> Color(0xFFEA580C).copy(alpha = 0.12f)
                                else -> StorePrimary.copy(alpha = 0.12f)
                            }
                        ) {
                            Text(
                                text = item.badgeText,
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = when (item.offerType) {
                                    OfferType.PERCENT_DISCOUNT -> StorePrimary
                                    OfferType.FLAT_DISCOUNT -> StoreGreenProfit
                                    OfferType.BUY_X_GET_Y -> Color(0xFFEA580C)
                                    else -> StorePrimary
                                },
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(2.dp))

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (prod.category.isNotBlank()) {
                            Text(
                                text = prod.category,
                                fontSize = 11.sp,
                                color = TextMuted
                            )
                            Text("•", fontSize = 11.sp, color = TextMuted)
                        }
                        Text(
                            text = "${if (isBn) "স্টক:" else "Stock:"} ${prod.currentStock.toInt()} ${prod.unitType}",
                            fontSize = 11.sp,
                            color = if (prod.currentStock <= 5) StoreRedAlert else TextMuted
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    // Pricing Row: Original Price (strikethrough) -> Discounted Price
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "₹%.2f".format(item.regularPrice),
                            fontSize = 12.sp,
                            color = TextMuted,
                            style = androidx.compose.ui.text.TextStyle(textDecoration = TextDecoration.LineThrough)
                        )
                        Icon(
                            Icons.Default.ArrowForward,
                            contentDescription = null,
                            tint = TextMuted,
                            modifier = Modifier.size(12.dp)
                        )
                        Text(
                            text = "₹%.2f".format(item.discountedPrice),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = StoreGreenProfit
                        )
                        Text(
                            text = "(${if (isBn) "সাশ্রয়" else "Save"} ₹%.2f)".format(item.discountAmount),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = StoreGreenProfit
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Details & Margin Status Row
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = if (item.isLossLeader) StoreRedAlert.copy(alpha = 0.08f)
                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Margin or Loss Warning
                    if (item.isLossLeader) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Warning, contentDescription = null, tint = StoreRedAlert, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = if (isBn) "ক্রয়মূল্যের নিচে বিক্রি! ক্ষতি: ₹%.2f/পিস".format(item.lossAmount)
                                       else "Selling below cost! Loss: ₹%.2f/unit".format(item.lossAmount),
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = StoreRedAlert
                            )
                        }
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "${if (isBn) "ক্রয়মূল্য:" else "Cost:"} ₹%.2f".format(prod.costPrice),
                                fontSize = 10.5.sp,
                                color = TextMuted
                            )
                            if (prod.costPrice > 0) {
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("•", fontSize = 10.sp, color = TextMuted)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "${if (isBn) "মুনাফা:" else "Margin:"} +₹%.2f (+%.0f%%)".format(item.profitMargin, item.profitMarginPercent),
                                    fontSize = 10.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = StoreGreenProfit
                                )
                            }
                        }
                    }

                    // Rule source badge
                    Text(
                        text = if (offer.isStoreWide()) (if (isBn) "দোকানব্যাপী অফার" else "Storewide Offer") else (if (isBn) "নির্দিষ্ট অফার" else "Specific Offer"),
                        fontSize = 10.sp,
                        color = TextMuted
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.LocalOffer,
                        contentDescription = null,
                        tint = StorePrimary,
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = offer.name,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = StorePrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 180.dp)
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(
                        onClick = onEditOffer,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.height(28.dp)
                    ) {
                        Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(13.dp), tint = StorePrimary)
                        Spacer(modifier = Modifier.width(3.dp))
                        Text(if (isBn) "অফার এডিট" else "Edit Rule", fontSize = 11.sp, color = StorePrimary)
                    }

                    TextButton(
                        onClick = onRemoveDiscount,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.height(28.dp)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(13.dp), tint = StoreRedAlert)
                        Spacer(modifier = Modifier.width(3.dp))
                        Text(if (isBn) "ছাড় সরান" else "Remove", fontSize = 11.sp, color = StoreRedAlert)
                    }
                }
            }
        }
    }
}

@Composable
fun OfferCard(
    offer: Offer,
    allProducts: List<Product>,
    isBn: Boolean,
    onToggleActive: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val offerType = offer.getOfferTypeEnum()
    val productIds = offer.getProductIdsList()
    val applicableProducts = remember(productIds, allProducts) {
        if (productIds.isEmpty()) emptyList()
        else allProducts.filter { productIds.contains(it.id) }
    }

    val todayStr = remember { Offer.getTodayDateString() }
    val effectiveStatus = offer.getEffectiveStatus(todayStr)

    val typeColor = when (offerType) {
        OfferType.PERCENT_DISCOUNT -> StorePrimary
        OfferType.FLAT_DISCOUNT -> StoreGreenProfit
        OfferType.BUY_X_GET_Y -> Color(0xFFEA580C)
        OfferType.FREE_GIFT -> Color(0xFF0D9488)
        OfferType.COMBO_BUNDLE -> Color(0xFF7C3AED)
    }

    val statusBadgeColor = when (effectiveStatus) {
        OfferStatus.ACTIVE -> StoreGreenProfit
        OfferStatus.UPCOMING -> Color(0xFF2563EB)
        OfferStatus.EXPIRED -> StoreRedAlert
        OfferStatus.INACTIVE -> TextMuted
    }

    val statusBadgeText = when (effectiveStatus) {
        OfferStatus.ACTIVE -> if (isBn) "● সক্রিয় (চালু)" else "● Active (Applying)"
        OfferStatus.UPCOMING -> if (isBn) "⏱ আসন্ন" else "⏱ Upcoming"
        OfferStatus.EXPIRED -> if (isBn) "✖ মেয়াদ শেষ" else "✖ Expired"
        OfferStatus.INACTIVE -> if (isBn) "⏸ বন্ধ" else "⏸ Inactive (Off)"
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = CardBackground),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        border = BorderStroke(
            1.dp,
            if (effectiveStatus == OfferStatus.ACTIVE) typeColor.copy(alpha = 0.6f)
            else if (effectiveStatus == OfferStatus.UPCOMING) Color(0xFF2563EB).copy(alpha = 0.4f)
            else TextMuted.copy(alpha = 0.25f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            // Header Row: Type Badge + Name + Active Switch
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    val badgeLabel = when (offerType) {
                        OfferType.PERCENT_DISCOUNT -> "% PERCENT"
                        OfferType.FLAT_DISCOUNT -> "₹ FLAT"
                        OfferType.BUY_X_GET_Y -> "BOGO"
                        OfferType.FREE_GIFT -> "FREE GIFT"
                        OfferType.COMBO_BUNDLE -> "COMBO"
                    }
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = typeColor.copy(alpha = 0.15f)
                    ) {
                        Text(
                            text = badgeLabel,
                            color = typeColor,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.ExtraBold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = offer.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (effectiveStatus == OfferStatus.ACTIVE) TextDark else TextMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = offer.isActive,
                        onCheckedChange = onToggleActive,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = StoreGreenProfit
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Status Banner Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = statusBadgeColor.copy(alpha = 0.12f),
                    border = BorderStroke(1.dp, statusBadgeColor.copy(alpha = 0.4f))
                ) {
                    Text(
                        text = statusBadgeText,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = statusBadgeColor,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }

                // Date validity summary
                val start = offer.startDate?.trim()?.takeIf { it.isNotEmpty() }
                val end = offer.endDate?.trim()?.takeIf { it.isNotEmpty() }
                val dateSummary = when {
                    start != null && end != null -> "$start → $end"
                    start != null -> "From $start"
                    end != null -> "Until $end"
                    else -> if (isBn) "সর্বদা সক্রিয় (সীমাহীন)" else "Always Active"
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.CalendarToday,
                        contentDescription = null,
                        tint = TextMuted,
                        modifier = Modifier.size(12.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = dateSummary,
                        fontSize = 10.5.sp,
                        color = TextMuted,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            // Explanatory note if not applying
            if (effectiveStatus != OfferStatus.ACTIVE) {
                Spacer(modifier = Modifier.height(4.dp))
                val reasonNote = when (effectiveStatus) {
                    OfferStatus.UPCOMING -> if (isBn) "ℹ️ অফারটি এখনও শুরু হয়নি (শুরুর তারিখ: ${offer.startDate})" else "ℹ️ Offer has not started yet (Starts on: ${offer.startDate})"
                    OfferStatus.EXPIRED -> if (isBn) "⚠️ অফারের মেয়াদ শেষ হয়ে গেছে (শেষের তারিখ: ${offer.endDate})" else "⚠️ Offer period has expired (Ended on: ${offer.endDate})"
                    OfferStatus.INACTIVE -> if (isBn) "ℹ️ ম্যানুয়ালি বন্ধ রাখা হয়েছে (সুইচ চালু করুন)" else "ℹ️ Manually disabled (turn on toggle to enable)"
                    else -> ""
                }
                Text(
                    text = reasonNote,
                    fontSize = 10.5.sp,
                    color = statusBadgeColor,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Discount Value Banner
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = if (effectiveStatus == OfferStatus.ACTIVE) typeColor.copy(alpha = 0.08f) else SurfaceWarm,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val typeIcon = when (offerType) {
                        OfferType.PERCENT_DISCOUNT -> Icons.Default.Percent
                        OfferType.FLAT_DISCOUNT -> Icons.Default.CurrencyRupee
                        OfferType.BUY_X_GET_Y -> Icons.Default.CardGiftcard
                        OfferType.FREE_GIFT -> Icons.Default.CardGiftcard
                        OfferType.COMBO_BUNDLE -> Icons.Default.Layers
                    }
                    Icon(
                        imageVector = typeIcon,
                        contentDescription = null,
                        tint = if (effectiveStatus == OfferStatus.ACTIVE) typeColor else TextMuted,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    val discountText = when (offerType) {
                        OfferType.PERCENT_DISCOUNT -> "%.1f%% Discount on Item Subtotal".format(offer.discountValue)
                        OfferType.FLAT_DISCOUNT -> "₹%.2f Flat Discount per Primary Unit".format(offer.discountValue)
                        OfferType.BUY_X_GET_Y -> if (isBn) "%.0fটি কিনলে %.0fটি ফ্রি (BOGO)".format(offer.buyQty, offer.getQty) else "Buy %.0f Get %.0f FREE (BOGO)".format(offer.buyQty, offer.getQty)
                        OfferType.FREE_GIFT -> {
                            val giftProd = allProducts.firstOrNull { it.id == offer.freeProductId }
                            val giftName = giftProd?.getDisplayName(isBn) ?: (if (isBn) "উপহার সামগ্রী" else "Gift Item")
                            val spendStr = if (offer.minSpendAmount % 1.0 == 0.0) "₹${offer.minSpendAmount.toInt()}" else "₹%.2f".format(offer.minSpendAmount)
                            val freeQ = if (offer.freeProductQty % 1.0 == 0.0) "${offer.freeProductQty.toInt()}" else "%.1f".format(offer.freeProductQty)
                            val unitStr = offer.freeProductUnit?.trim()?.takeIf { it.isNotBlank() } ?: giftProd?.unitType ?: ""
                            val unitSuffix = if (unitStr.isNotBlank()) " $unitStr" else " টি"
                            if (isBn) "$spendStr কেনাকাটায় $freeQ$unitSuffix $giftName বিনামূল্যে!" else "Spend $spendStr+ to get $freeQ$unitSuffix x $giftName for FREE!"
                        }
                        OfferType.COMBO_BUNDLE -> "Combo Price: ₹%.2f".format(offer.comboPrice)
                    }
                    Text(
                        text = discountText,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 13.sp,
                        color = if (effectiveStatus == OfferStatus.ACTIVE) typeColor else TextMuted
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Applicable Products
            Text(
                text = if (productIds.isEmpty()) {
                    if (isBn) "প্রযোজ্য: সমস্ত পণ্যে" else "Applies to: All Store Products"
                } else {
                    val names = applicableProducts.joinToString(", ") { it.getDisplayName(isBn) }
                    if (isBn) "প্রযোজ্য (${productIds.size} পণ্য): $names" else "Applies to (${productIds.size} items): $names"
                },
                fontSize = 11.sp,
                color = TextDark.copy(alpha = 0.8f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(10.dp))
            HorizontalDivider(color = TextMuted.copy(alpha = 0.15f))
            Spacer(modifier = Modifier.height(6.dp))

            // Action Buttons (Edit, Delete)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = onEdit,
                    shape = RoundedCornerShape(6.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    modifier = Modifier.height(30.dp)
                ) {
                    Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(if (isBn) "সম্পাদনা" else "Edit", fontSize = 11.sp)
                }

                Spacer(modifier = Modifier.width(8.dp))

                OutlinedButton(
                    onClick = onDelete,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = StoreRedAlert),
                    border = BorderStroke(1.dp, StoreRedAlert.copy(alpha = 0.5f)),
                    shape = RoundedCornerShape(6.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    modifier = Modifier.height(30.dp)
                ) {
                    Icon(Icons.Default.DeleteOutline, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(if (isBn) "মুছুন" else "Delete", fontSize = 11.sp)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddEditOfferDialog(
    offer: Offer?,
    allProducts: List<Product>,
    isBn: Boolean,
    onDismiss: () -> Unit,
    onSave: (Offer) -> Unit
) {
    val context = LocalContext.current
    var name by remember { mutableStateOf(offer?.name ?: "") }
    var selectedType by remember { mutableStateOf(offer?.type ?: OfferType.PERCENT_DISCOUNT.name) }
    var discountValueStr by remember {
        mutableStateOf(
            if (offer != null && offer.discountValue > 0) {
                if (offer.discountValue % 1.0 == 0.0) offer.discountValue.toInt().toString()
                else offer.discountValue.toString()
            } else ""
        )
    }
    var buyQtyStr by remember {
        mutableStateOf(
            if (offer != null && offer.buyQty > 0) {
                if (offer.buyQty % 1.0 == 0.0) offer.buyQty.toInt().toString() else offer.buyQty.toString()
            } else "2"
        )
    }
    var getQtyStr by remember {
        mutableStateOf(
            if (offer != null && offer.getQty > 0) {
                if (offer.getQty % 1.0 == 0.0) offer.getQty.toInt().toString() else offer.getQty.toString()
            } else "1"
        )
    }
    var minSpendAmountStr by remember {
        mutableStateOf(
            if (offer != null && offer.minSpendAmount > 0) {
                if (offer.minSpendAmount % 1.0 == 0.0) offer.minSpendAmount.toInt().toString() else offer.minSpendAmount.toString()
            } else "500"
        )
    }
    var selectedFreeProductId by remember { mutableStateOf(offer?.freeProductId) }
    var freeProductUnit by remember { mutableStateOf(offer?.freeProductUnit) }
    var freeProductQtyStr by remember {
        mutableStateOf(
            if (offer != null && offer.freeProductQty > 0) {
                if (offer.freeProductQty % 1.0 == 0.0) offer.freeProductQty.toInt().toString() else offer.freeProductQty.toString()
            } else "1"
        )
    }
    var showFreeProductPickerModal by remember { mutableStateOf(false) }
    var startDate by remember { mutableStateOf(offer?.startDate ?: "") }
    var endDate by remember { mutableStateOf(offer?.endDate ?: "") }
    var isActive by remember { mutableStateOf(offer?.isActive ?: true) }

    // Applicable Products selection
    var applyToAll by remember { mutableStateOf(offer == null || offer.applicableProductIds.isBlank()) }
    val selectedProductIds = remember {
        mutableStateListOf<String>().apply {
            if (offer != null && offer.applicableProductIds.isNotBlank()) {
                addAll(offer.getProductIdsList())
            }
        }
    }

    var selectedCategory by remember { mutableStateOf("ALL") }
    var productSearchQuery by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val categories = remember(allProducts) {
        listOf("ALL") + allProducts.map { it.category.trim() }.filter { it.isNotBlank() }.distinct()
    }

    fun showDatePicker(initialDateStr: String, onDateSelected: (String) -> Unit) {
        val cal = Calendar.getInstance()
        if (initialDateStr.isNotBlank()) {
            try {
                val parts = initialDateStr.split("-")
                if (parts.size == 3) {
                    cal.set(Calendar.YEAR, parts[0].toInt())
                    cal.set(Calendar.MONTH, parts[1].toInt() - 1)
                    cal.set(Calendar.DAY_OF_MONTH, parts[2].toInt())
                }
            } catch (e: Exception) {
                // Ignore parse exception and use current calendar
            }
        }

        DatePickerDialog(
            context,
            { _, year, month, dayOfMonth ->
                val formatted = "%04d-%02d-%02d".format(year, month + 1, dayOfMonth)
                onDateSelected(formatted)
            },
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH),
            cal.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    val filteredProducts = remember(allProducts, productSearchQuery, selectedCategory) {
        allProducts.filter { prod ->
            val matchCat = selectedCategory == "ALL" || prod.category.equals(selectedCategory, ignoreCase = true)
            val matchQuery = productSearchQuery.isBlank() ||
                    prod.nameEn.contains(productSearchQuery, ignoreCase = true) ||
                    prod.nameBn.contains(productSearchQuery, ignoreCase = true)
            matchCat && matchQuery
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .fillMaxHeight(0.92f)
                .clip(RoundedCornerShape(16.dp)),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp)
            ) {
                // Dialog Title
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = if (offer == null) (if (isBn) "নতুন অফার তৈরি করুন" else "Create New Offer")
                               else (if (isBn) "অফার সম্পাদনা" else "Edit Offer"),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // 1. Offer Name
                    item {
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it; errorMessage = null },
                            label = { Text(if (isBn) "অফারের নাম (রেফারেন্সের জন্য)" else "Offer Name (e.g. Weekend Sugar ₹10 Off)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    // 2. Offer Type Picker (PERCENT_DISCOUNT, FLAT_DISCOUNT, BUY_X_GET_Y)
                    item {
                        Text(
                            text = if (isBn) "অফারের ধরন নির্বাচন করুন:" else "Select Offer Type:",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            listOf(
                                Triple(OfferType.PERCENT_DISCOUNT.name, if (isBn) "% শতাংশ" else "% Percent", Icons.Default.Percent),
                                Triple(OfferType.FLAT_DISCOUNT.name, if (isBn) "₹ নির্দিষ্ট" else "₹ Flat", Icons.Default.CurrencyRupee),
                                Triple(OfferType.BUY_X_GET_Y.name, if (isBn) "BOGO" else "BOGO", Icons.Default.CardGiftcard),
                                Triple(OfferType.FREE_GIFT.name, if (isBn) "উপহার" else "Free Gift", Icons.Default.CardGiftcard)
                            ).forEach { (typeKey, label, icon) ->
                                val isSelected = selectedType == typeKey
                                Surface(
                                    onClick = { selectedType = typeKey; errorMessage = null },
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (isSelected) StorePrimary.copy(alpha = 0.15f) else SurfaceWarm,
                                    border = BorderStroke(1.5.dp, if (isSelected) StorePrimary else TextMuted.copy(alpha = 0.25f)),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.Center
                                    ) {
                                        Icon(
                                            imageVector = icon,
                                            contentDescription = null,
                                            tint = if (isSelected) StorePrimary else TextMuted,
                                            modifier = Modifier.size(13.dp)
                                        )
                                        Spacer(modifier = Modifier.width(3.dp))
                                        Text(
                                            text = label,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                            color = if (isSelected) StorePrimary else TextDark,
                                            fontSize = 10.5.sp,
                                            maxLines = 1
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // 3. Discount Value Field or BOGO / Free Gift Inputs
                    item {
                        when (selectedType) {
                            OfferType.PERCENT_DISCOUNT.name -> {
                                Column {
                                    OutlinedTextField(
                                        value = discountValueStr,
                                        onValueChange = {
                                            discountValueStr = it
                                            errorMessage = null
                                        },
                                        label = {
                                            Text(if (isBn) "ছাড়ের শতাংশ (যেমন: 10 = 10%)" else "Discount Percentage (e.g. 10 = 10%)")
                                        },
                                        leadingIcon = {
                                            Icon(Icons.Default.Percent, contentDescription = null, tint = StorePrimary)
                                        },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        listOf("5", "10", "15", "20", "25", "50").forEach { p ->
                                            SuggestionChip(
                                                onClick = { discountValueStr = p; errorMessage = null },
                                                label = { Text("$p%", fontSize = 11.sp) }
                                            )
                                        }
                                    }
                                }
                            }
                            OfferType.FLAT_DISCOUNT.name -> {
                                Column {
                                    OutlinedTextField(
                                        value = discountValueStr,
                                        onValueChange = {
                                            discountValueStr = it
                                            errorMessage = null
                                        },
                                        label = {
                                            Text(if (isBn) "নির্দিষ্ট ছাড়ের পরিমাণ (যেমন: 10 = ₹10)" else "Flat Discount Amount in ₹ (e.g. 10 = ₹10)")
                                        },
                                        leadingIcon = {
                                            Icon(Icons.Default.CurrencyRupee, contentDescription = null, tint = StorePrimary)
                                        },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        listOf("5", "10", "20", "50", "100").forEach { amt ->
                                            SuggestionChip(
                                                onClick = { discountValueStr = amt; errorMessage = null },
                                                label = { Text("₹$amt", fontSize = 11.sp) }
                                            )
                                        }
                                    }
                                }
                            }
                            OfferType.BUY_X_GET_Y.name -> {
                                Column {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        OutlinedTextField(
                                            value = buyQtyStr,
                                            onValueChange = { buyQtyStr = it; errorMessage = null },
                                            label = { Text(if (isBn) "কত কিনবেন (Buy Qty)" else "Buy Qty (X)") },
                                            singleLine = true,
                                            modifier = Modifier.weight(1f)
                                        )
                                        OutlinedTextField(
                                            value = getQtyStr,
                                            onValueChange = { getQtyStr = it; errorMessage = null },
                                            label = { Text(if (isBn) "কত ফ্রি পাবেন (Get Qty)" else "Get Free Qty (Y)") },
                                            singleLine = true,
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        listOf(
                                            "Buy 1 Get 1" to ("1" to "1"),
                                            "Buy 2 Get 1" to ("2" to "1"),
                                            "Buy 3 Get 1" to ("3" to "1")
                                        ).forEach { (label, pair) ->
                                            SuggestionChip(
                                                onClick = {
                                                    buyQtyStr = pair.first
                                                    getQtyStr = pair.second
                                                    errorMessage = null
                                                },
                                                label = { Text(label, fontSize = 11.sp) }
                                            )
                                        }
                                    }
                                }
                            }
                            OfferType.FREE_GIFT.name -> {
                                Column {
                                    OutlinedTextField(
                                        value = minSpendAmountStr,
                                        onValueChange = {
                                            minSpendAmountStr = it
                                            errorMessage = null
                                        },
                                        label = {
                                            Text(if (isBn) "ন্যূনতম কেনাকাটার পরিমাণ (যেমন: 500 = ₹500)" else "Minimum Spend / Purchase in ₹ (e.g. 500 = ₹500)")
                                        },
                                        leadingIcon = {
                                            Icon(Icons.Default.CurrencyRupee, contentDescription = null, tint = StoreGreenProfit)
                                        },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        listOf("200", "500", "1000", "2000", "5000").forEach { amt ->
                                            SuggestionChip(
                                                onClick = { minSpendAmountStr = amt; errorMessage = null },
                                                label = { Text("₹$amt", fontSize = 11.sp) }
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(12.dp))

                                    Text(
                                        text = if (isBn) "ফ্রি উপহার হিসেবে যে পণ্যটি দেওয়া হবে:" else "Select Product to Give as FREE GIFT:",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.5.sp,
                                        color = TextDark
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))

                                    val selectedGiftProd = remember(selectedFreeProductId, allProducts) {
                                        allProducts.firstOrNull { it.id == selectedFreeProductId }
                                    }

                                    if (selectedGiftProd != null) {
                                        Surface(
                                            shape = RoundedCornerShape(10.dp),
                                            color = StoreGreenProfit.copy(alpha = 0.08f),
                                            border = BorderStroke(1.5.dp, StoreGreenProfit.copy(alpha = 0.5f)),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Column(modifier = Modifier.padding(12.dp)) {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                    modifier = Modifier.fillMaxWidth()
                                                ) {
                                                    Row(
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        modifier = Modifier.weight(1f)
                                                    ) {
                                                        Surface(
                                                            shape = CircleShape,
                                                            color = StoreGreenProfit.copy(alpha = 0.18f),
                                                            modifier = Modifier.size(36.dp)
                                                        ) {
                                                            Box(contentAlignment = Alignment.Center) {
                                                                Icon(
                                                                    imageVector = Icons.Default.CardGiftcard,
                                                                    contentDescription = null,
                                                                    tint = StoreGreenProfit,
                                                                    modifier = Modifier.size(20.dp)
                                                                )
                                                            }
                                                        }
                                                        Spacer(modifier = Modifier.width(10.dp))
                                                        Column {
                                                            Text(
                                                                text = selectedGiftProd.getDisplayName(isBn),
                                                                fontWeight = FontWeight.Bold,
                                                                fontSize = 14.sp,
                                                                color = TextDark
                                                            )
                                                            Text(
                                                                text = "MRP: ₹%.2f | Stock: %.1f %s".format(
                                                                    selectedGiftProd.sellingPrice,
                                                                    selectedGiftProd.currentStock,
                                                                    selectedGiftProd.unitType
                                                                ),
                                                                fontSize = 11.sp,
                                                                color = TextMuted
                                                            )
                                                        }
                                                    }

                                                    TextButton(
                                                        onClick = { showFreeProductPickerModal = true }
                                                    ) {
                                                        Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(14.dp), tint = StorePrimary)
                                                        Spacer(modifier = Modifier.width(4.dp))
                                                        Text(if (isBn) "পরিবর্তন" else "Change", fontSize = 12.sp, color = StorePrimary)
                                                    }
                                                }

                                                Spacer(modifier = Modifier.height(8.dp))

                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                    modifier = Modifier.fillMaxWidth()
                                                ) {
                                                    OutlinedTextField(
                                                        value = freeProductQtyStr,
                                                        onValueChange = { freeProductQtyStr = it; errorMessage = null },
                                                        label = { Text(if (isBn) "উপহার সংখ্যা" else "Gift Qty") },
                                                        singleLine = true,
                                                        modifier = Modifier.weight(1.1f)
                                                    )

                                                    // Unit Dropdown selector
                                                    val availableUnits = remember(selectedGiftProd) {
                                                        val units = mutableListOf<String>()
                                                        if (selectedGiftProd.unitType.isNotBlank()) {
                                                             units.add(selectedGiftProd.unitType)
                                                        }
                                                        val sec = selectedGiftProd.getEffectiveSecondaryUnit()
                                                        if (sec.isNotBlank() && !units.any { it.equals(sec, ignoreCase = true) }) {
                                                            units.add(sec)
                                                        }
                                                        if (selectedGiftProd.hasBoxPricing()) {
                                                            if (!units.any { it.equals("box", ignoreCase = true) }) {
                                                                units.add("box")
                                                            }
                                                            if (!units.any { it.equals("piece", ignoreCase = true) }) {
                                                                units.add("piece")
                                                            }
                                                        }
                                                        val standardFallbacks = listOf("piece", "packet", "box", "kg", "gram", "liter", "ml")
                                                        standardFallbacks.forEach { u ->
                                                            if (!units.any { it.equals(u, ignoreCase = true) }) {
                                                                units.add(u)
                                                            }
                                                        }
                                                        units
                                                    }

                                                    val currentEffectiveUnit = freeProductUnit?.trim()?.takeIf { it.isNotBlank() } ?: selectedGiftProd.unitType

                                                    LaunchedEffect(selectedGiftProd.id) {
                                                        if (freeProductUnit.isNullOrBlank()) {
                                                            freeProductUnit = selectedGiftProd.unitType
                                                        }
                                                    }

                                                    var unitDropdownExpanded by remember { mutableStateOf(false) }

                                                    ExposedDropdownMenuBox(
                                                        expanded = unitDropdownExpanded,
                                                        onExpandedChange = { unitDropdownExpanded = it },
                                                        modifier = Modifier.weight(0.9f)
                                                    ) {
                                                        OutlinedTextField(
                                                            value = currentEffectiveUnit,
                                                            onValueChange = {},
                                                            readOnly = true,
                                                            label = { Text(if (isBn) "একক (Unit)" else "Unit") },
                                                            trailingIcon = {
                                                                ExposedDropdownMenuDefaults.TrailingIcon(expanded = unitDropdownExpanded)
                                                            },
                                                            colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                                                            singleLine = true,
                                                            modifier = Modifier
                                                                .fillMaxWidth()
                                                                .menuAnchor()
                                                        )

                                                        ExposedDropdownMenu(
                                                            expanded = unitDropdownExpanded,
                                                            onDismissRequest = { unitDropdownExpanded = false }
                                                        ) {
                                                            availableUnits.forEach { unitOption ->
                                                                val isSelected = unitOption.equals(currentEffectiveUnit, ignoreCase = true)
                                                                DropdownMenuItem(
                                                                    text = {
                                                                        Row(
                                                                            verticalAlignment = Alignment.CenterVertically,
                                                                            horizontalArrangement = Arrangement.SpaceBetween,
                                                                            modifier = Modifier.fillMaxWidth()
                                                                        ) {
                                                                            Text(
                                                                                text = unitOption,
                                                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                                                                color = if (isSelected) StorePrimary else MaterialTheme.colorScheme.onSurface
                                                                            )
                                                                            if (isSelected) {
                                                                                Spacer(modifier = Modifier.width(8.dp))
                                                                                Icon(
                                                                                    imageVector = Icons.Default.Check,
                                                                                    contentDescription = null,
                                                                                    tint = StorePrimary,
                                                                                    modifier = Modifier.size(16.dp)
                                                                                )
                                                                            }
                                                                        }
                                                                    },
                                                                    onClick = {
                                                                        freeProductUnit = unitOption
                                                                        unitDropdownExpanded = false
                                                                    }
                                                                )
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    } else {
                                        OutlinedButton(
                                            onClick = { showFreeProductPickerModal = true },
                                            modifier = Modifier.fillMaxWidth(),
                                            shape = RoundedCornerShape(10.dp),
                                            border = BorderStroke(1.5.dp, StorePrimary),
                                            colors = ButtonDefaults.outlinedButtonColors(contentColor = StorePrimary)
                                        ) {
                                            Icon(Icons.Default.CardGiftcard, contentDescription = null, modifier = Modifier.size(18.dp))
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                text = if (isBn) "উপহারের জন্য পণ্য নির্বাচন করুন" else "Select Free Gift Product from Store",
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 13.sp
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // 3b. Smart Offer Logic & Billing Simulation Card
                    item {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = Color(0xFF0284C7).copy(alpha = 0.08f),
                            border = BorderStroke(1.dp, Color(0xFF0284C7).copy(alpha = 0.3f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Info,
                                        contentDescription = null,
                                        tint = Color(0xFF0284C7),
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Text(
                                        text = if (isBn) "অফার লজিক ও বিলে যেভাবে কাজ করবে:" else "Offer Logic & How It Works in Billing:",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.5.sp,
                                        color = Color(0xFF0284C7)
                                    )
                                }

                                Spacer(modifier = Modifier.height(6.dp))

                                when (selectedType) {
                                    OfferType.BUY_X_GET_Y.name -> {
                                        val bVal = buyQtyStr.toDoubleOrNull() ?: 1.0
                                        val gVal = getQtyStr.toDoubleOrNull() ?: 1.0
                                        val bStr = if (bVal % 1.0 == 0.0) "${bVal.toInt()}" else "%.1f".format(bVal)
                                        val gStr = if (gVal % 1.0 == 0.0) "${gVal.toInt()}" else "%.1f".format(gVal)
                                        val setTotal = bVal + gVal
                                        val setStr = if (setTotal % 1.0 == 0.0) "${setTotal.toInt()}" else "%.1f".format(setTotal)

                                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                            Text(
                                                text = if (isBn) {
                                                    "• নিয়ম: $bStr টি কিনলে $gStr টি ১০০% ফ্রি (মোট সেট = $setStr টি আইটেম)।"
                                                } else {
                                                    "• Rule: Buy $bStr, Get $gStr 100% FREE (Pack Size = $setStr items)."
                                                },
                                                fontSize = 11.5.sp,
                                                color = TextDark,
                                                lineHeight = 16.sp
                                            )
                                            Text(
                                                text = if (isBn) {
                                                    "• কার্ট/বিলে: কাস্টমার কার্টে $setStr টি যোগ করলে, $gStr টির সম্পূর্ণ দাম স্বয়ংক্রিয়ভাবে ছাড় (-100%) হবে। কাস্টমার শুধু $bStr টির দাম দেবেন!"
                                                } else {
                                                    "• In Billing: When $setStr items are in cart, the cost of $gStr item(s) is automatically discounted (-100%). Customer only pays for $bStr!"
                                                },
                                                fontSize = 11.5.sp,
                                                color = TextDark,
                                                lineHeight = 16.sp
                                            )
                                            Text(
                                                text = if (isBn) {
                                                    "• যদি ক্যাশিয়ার $bStr টি যোগ করেন, POS স্ক্রিন '+১টি ফ্রি আইটেম যোগ করুন' বোতাম দেখাবে।"
                                                } else {
                                                    "• If cashier adds only $bStr item, POS screen will prompt '+ Claim $gStr FREE item' button."
                                                },
                                                fontSize = 11.sp,
                                                color = TextMuted,
                                                lineHeight = 15.sp
                                            )
                                        }
                                    }
                                    OfferType.PERCENT_DISCOUNT.name -> {
                                        val pVal = discountValueStr.toDoubleOrNull() ?: 10.0
                                        val pStr = if (pVal % 1.0 == 0.0) "${pVal.toInt()}%" else "%.1f%%".format(pVal)
                                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                            Text(
                                                text = if (isBn) {
                                                    "• নিয়ম: নির্বাচিত পণ্যের মূল দাম থেকে $pStr সরাসরি ছাড় হবে।"
                                                } else {
                                                    "• Rule: Direct $pStr discount off the unit selling price."
                                                },
                                                fontSize = 11.5.sp,
                                                color = TextDark,
                                                lineHeight = 16.sp
                                            )
                                            Text(
                                                text = if (isBn) {
                                                    "• উদাহরণ: ₹১০০ টাকার পণ্যে $pStr ছাড় দিলে কাস্টমার ₹${"%.1f".format(100.0 * (1.0 - pVal/100.0))} দেবেন।"
                                                } else {
                                                    "• Example: A ₹100 product with $pStr off will cost ₹${"%.1f".format(100.0 * (1.0 - pVal/100.0))} per piece."
                                                },
                                                fontSize = 11.5.sp,
                                                color = TextDark,
                                                lineHeight = 16.sp
                                            )
                                        }
                                    }
                                    OfferType.FLAT_DISCOUNT.name -> {
                                        val fVal = discountValueStr.toDoubleOrNull() ?: 10.0
                                        val fStr = if (fVal % 1.0 == 0.0) "₹${fVal.toInt()}" else "₹%.2f".format(fVal)
                                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                            Text(
                                                text = if (isBn) {
                                                    "• নিয়ম: প্রতি এককে $fStr নির্দিষ্ট নগদ ছাড় হবে।"
                                                } else {
                                                    "• Rule: Flat $fStr discount deducted per unit item."
                                                },
                                                fontSize = 11.5.sp,
                                                color = TextDark,
                                                lineHeight = 16.sp
                                            )
                                            Text(
                                                text = if (isBn) {
                                                    "• উদাহরণ: ₹৫০ টাকার পণ্যে $fStr ফ্ল্যাট ছাড় দিলে কাস্টমার ₹${"%.1f".format((50.0 - fVal).coerceAtLeast(0.0))} দেবেন।"
                                                } else {
                                                    "• Example: A ₹50 product with $fStr flat off will cost ₹${"%.1f".format((50.0 - fVal).coerceAtLeast(0.0))} per piece."
                                                },
                                                fontSize = 11.5.sp,
                                                color = TextDark,
                                                lineHeight = 16.sp
                                            )
                                        }
                                    }
                                    OfferType.FREE_GIFT.name -> {
                                        val sVal = minSpendAmountStr.toDoubleOrNull() ?: 500.0
                                        val sStr = if (sVal % 1.0 == 0.0) "₹${sVal.toInt()}" else "₹%.2f".format(sVal)
                                        Text(
                                            text = if (isBn) {
                                                "• নিয়ম: বিলে মোট কেনাকাটা $sStr বা তার বেশি হলে POS স্বয়ংক্রিয়ভাবে বিনামূল্যে উপহার সামগ্রীটি যোগ করবে।"
                                            } else {
                                                "• Rule: When customer's bill reaches $sStr or more, POS automatically rewards the free gift item."
                                            },
                                            fontSize = 11.5.sp,
                                            color = TextDark,
                                            lineHeight = 16.sp
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // 4. Date Scheduling (Start Date & End Date)
                    item {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = SurfaceWarm,
                            border = BorderStroke(1.dp, TextMuted.copy(alpha = 0.2f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Default.DateRange,
                                            contentDescription = null,
                                            tint = StorePrimary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = if (isBn) "অফারের সময়সীমা (ঐচ্ছিক তারিখ)" else "Schedule Date Range (Optional)",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 13.sp
                                        )
                                    }

                                    if (startDate.isNotBlank() || endDate.isNotBlank()) {
                                        TextButton(
                                            onClick = {
                                                startDate = ""
                                                endDate = ""
                                            },
                                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                                        ) {
                                            Text(
                                                text = if (isBn) "তারিখ মুছুন (সর্বদা চালু)" else "Clear Dates (Always Active)",
                                                fontSize = 11.sp,
                                                color = StoreRedAlert
                                            )
                                        }
                                    }
                                }

                                Text(
                                    text = if (isBn) "উভয় ঘর ফাঁকা রাখলে অফারটি সর্বদা চালু থাকবে" else "Leave dates empty to make this offer always active",
                                    fontSize = 11.sp,
                                    color = TextMuted,
                                    modifier = Modifier.padding(bottom = 8.dp)
                                )

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    // Start Date Button
                                    OutlinedButton(
                                        onClick = {
                                            showDatePicker(startDate) { picked ->
                                                startDate = picked
                                                errorMessage = null
                                            }
                                        },
                                        modifier = Modifier.weight(1f),
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
                                    ) {
                                        Icon(Icons.Default.CalendarToday, contentDescription = null, modifier = Modifier.size(14.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Column(horizontalAlignment = Alignment.Start) {
                                            Text(
                                                text = if (isBn) "শুরুর তারিখ" else "Start Date",
                                                fontSize = 9.sp,
                                                color = TextMuted
                                            )
                                            Text(
                                                text = if (startDate.isNotBlank()) startDate else (if (isBn) "শুরু: যে কোনো দিন" else "Anytime"),
                                                fontSize = 11.5.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = if (startDate.isNotBlank()) StorePrimary else TextDark
                                            )
                                        }
                                    }

                                    // End Date Button
                                    OutlinedButton(
                                        onClick = {
                                            showDatePicker(endDate) { picked ->
                                                endDate = picked
                                                errorMessage = null
                                            }
                                        },
                                        modifier = Modifier.weight(1f),
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
                                    ) {
                                        Icon(Icons.Default.Event, contentDescription = null, modifier = Modifier.size(14.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Column(horizontalAlignment = Alignment.Start) {
                                            Text(
                                                text = if (isBn) "শেষের তারিখ" else "End Date",
                                                fontSize = 9.sp,
                                                color = TextMuted
                                            )
                                            Text(
                                                text = if (endDate.isNotBlank()) endDate else (if (isBn) "শেষ: কোনো সীমা নেই" else "No Expiry"),
                                                fontSize = 11.5.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = if (endDate.isNotBlank()) StorePrimary else TextDark
                                            )
                                        }
                                    }
                                }

                                // Quick presets
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 8.dp),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    val today = remember { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date()) }
                                    AssistChip(
                                        onClick = {
                                            startDate = today
                                            val c = Calendar.getInstance()
                                            c.add(Calendar.DAY_OF_YEAR, 7)
                                            endDate = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(c.time)
                                        },
                                        label = { Text(if (isBn) "+৭ দিন" else "+7 Days", fontSize = 10.sp) }
                                    )
                                    AssistChip(
                                        onClick = {
                                            startDate = today
                                            val c = Calendar.getInstance()
                                            c.add(Calendar.DAY_OF_YEAR, 30)
                                            endDate = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(c.time)
                                        },
                                        label = { Text(if (isBn) "+৩০ দিন" else "+30 Days", fontSize = 10.sp) }
                                    )
                                    AssistChip(
                                        onClick = {
                                            startDate = today
                                            endDate = today
                                        },
                                        label = { Text(if (isBn) "আজকের জন্য" else "Today Only", fontSize = 10.sp) }
                                    )
                                }
                            }
                        }
                    }

                    // 5. Applicable Products Selection
                    item {
                        Text(
                            text = if (selectedType == OfferType.FREE_GIFT.name) {
                                if (isBn) "কোন কোন পণ্য কিনলে এই ফ্রি অফারটি প্রযোজ্য হবে?" else "Qualifying Product(s) to Spend On:"
                            } else {
                                if (isBn) "প্রযোজ্য পণ্য নির্বাচন করুন:" else "Applicable Product(s):"
                            },
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))

                        // Toggle All vs Specific
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    applyToAll = !applyToAll
                                    if (applyToAll) selectedProductIds.clear()
                                }
                        ) {
                            Checkbox(
                                checked = applyToAll,
                                onCheckedChange = {
                                    applyToAll = it
                                    if (applyToAll) selectedProductIds.clear()
                                }
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = if (selectedType == OfferType.FREE_GIFT.name) {
                                    if (isBn) "দোকানের যেকোনো পণ্য কেনাকাটা করলেই প্রযোজ্য (সুপারিশকৃত)" else "Qualify on ANY products in store (Recommended)"
                                } else {
                                    if (isBn) "দোকানের সমস্ত পণ্যের জন্য প্রযোজ্য" else "Apply to ALL products in store"
                                },
                                fontSize = 13.sp,
                                fontWeight = if (applyToAll) FontWeight.Bold else FontWeight.Normal
                            )
                        }

                        if (!applyToAll) {
                            Spacer(modifier = Modifier.height(6.dp))

                            // Category filter chips
                            if (categories.size > 1) {
                                LazyRow(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    items(categories, key = { it }) { cat ->
                                        FilterChip(
                                            selected = selectedCategory == cat,
                                            onClick = { selectedCategory = cat },
                                            label = { Text(if (cat == "ALL") (if (isBn) "সব" else "All") else cat, fontSize = 11.sp) }
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                            }

                            OutlinedTextField(
                                value = productSearchQuery,
                                onValueChange = { productSearchQuery = it },
                                placeholder = { Text(if (isBn) "পণ্য খুঁজুন..." else "Search products to select...", fontSize = 12.sp) },
                                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
                                singleLine = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(50.dp)
                            )
                            Spacer(modifier = Modifier.height(6.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = if (isBn) "নির্বাচিত (${selectedProductIds.size} পণ্য):" else "Selected (${selectedProductIds.size} products):",
                                    fontSize = 11.sp,
                                    color = TextMuted
                                )
                                Row {
                                    TextButton(
                                        onClick = {
                                            val filteredIds = filteredProducts.map { it.id }
                                            filteredIds.forEach { id ->
                                                if (!selectedProductIds.contains(id)) selectedProductIds.add(id)
                                            }
                                        },
                                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                                    ) {
                                        Text(if (isBn) "সব নির্বাচন" else "Select Filtered", fontSize = 11.sp)
                                    }
                                    if (selectedProductIds.isNotEmpty()) {
                                        TextButton(
                                            onClick = { selectedProductIds.clear() },
                                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                                        ) {
                                            Text(if (isBn) "মুছুন" else "Clear", fontSize = 11.sp, color = StoreRedAlert)
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(4.dp))

                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(1.dp, TextMuted.copy(alpha = 0.2f)),
                                color = CardBackground,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 200.dp)
                            ) {
                                LazyColumn(modifier = Modifier.padding(4.dp)) {
                                    items(filteredProducts, key = { it.id }) { prod ->
                                        val isChecked = selectedProductIds.contains(prod.id)
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable {
                                                    if (isChecked) selectedProductIds.remove(prod.id)
                                                    else selectedProductIds.add(prod.id)
                                                }
                                                .padding(horizontal = 8.dp, vertical = 6.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Checkbox(
                                                checked = isChecked,
                                                onCheckedChange = { checked ->
                                                    if (checked) selectedProductIds.add(prod.id)
                                                    else selectedProductIds.remove(prod.id)
                                                },
                                                modifier = Modifier.size(24.dp)
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = prod.getDisplayName(isBn),
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                val liveDiscPrice: Double? = when (selectedType) {
                                                    OfferType.PERCENT_DISCOUNT.name -> {
                                                        val p = discountValueStr.toDoubleOrNull()
                                                        if (p != null && p > 0.0) (prod.sellingPrice * (1.0 - (p / 100.0).coerceIn(0.0, 1.0))).coerceAtLeast(0.0) else null
                                                    }
                                                    OfferType.FLAT_DISCOUNT.name -> {
                                                        val f = discountValueStr.toDoubleOrNull()
                                                        if (f != null && f > 0.0) (prod.sellingPrice - f).coerceAtLeast(0.0) else null
                                                    }
                                                    else -> null
                                                }

                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    if (liveDiscPrice != null) {
                                                        Text(
                                                            text = "₹%.2f".format(prod.sellingPrice),
                                                            fontSize = 10.sp,
                                                            color = TextMuted,
                                                            style = androidx.compose.ui.text.TextStyle(textDecoration = TextDecoration.LineThrough)
                                                        )
                                                        Spacer(modifier = Modifier.width(4.dp))
                                                        Text(
                                                            text = "→ ₹%.2f".format(liveDiscPrice),
                                                            fontSize = 11.sp,
                                                            fontWeight = FontWeight.Bold,
                                                            color = StoreGreenProfit
                                                        )
                                                        if (prod.costPrice > 0 && liveDiscPrice < prod.costPrice) {
                                                            Spacer(modifier = Modifier.width(4.dp))
                                                            Text(
                                                                text = if (isBn) "⚠️ ক্রয়মূল্যের নিচে!" else "⚠️ Below Cost!",
                                                                fontSize = 9.5.sp,
                                                                color = StoreRedAlert,
                                                                fontWeight = FontWeight.Bold
                                                            )
                                                        }
                                                    } else {
                                                        Text(
                                                            text = "₹%.2f/%s".format(prod.sellingPrice, prod.unitType),
                                                            fontSize = 10.sp,
                                                            color = TextMuted
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // 6. Active Status Toggle
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(SurfaceWarm, RoundedCornerShape(8.dp))
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text(
                                    text = if (isBn) "ম্যানুয়াল সুইচ (সক্রিয়/নিষ্ক্রিয়)" else "Manual Active Switch",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp
                                )
                                Text(
                                    text = if (isBn) "সুইচ ও তারিখ উভয়ই বৈধ থাকলে বিলে ডিসকাউন্ট যোগ হবে" else "Must be ON and within date range to apply",
                                    fontSize = 11.sp,
                                    color = TextMuted
                                )
                            }
                            Switch(
                                checked = isActive,
                                onCheckedChange = { isActive = it },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.White,
                                    checkedTrackColor = StoreGreenProfit
                                )
                            )
                        }
                    }

                    if (errorMessage != null) {
                        item {
                            Text(
                                text = errorMessage!!,
                                color = StoreRedAlert,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Bottom Buttons (Cancel, Save)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(if (isBn) "বাতিল" else "Cancel")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            val cleanName = name.trim()
                            if (cleanName.isBlank()) {
                                errorMessage = "Please enter an offer name."
                                return@Button
                            }

                            var discVal = 0.0
                            var bQ = 0.0
                            var gQ = 0.0
                            var minSpendVal = 0.0
                            var freeGiftProdId: String? = null
                            var freeGiftQtyVal = 1.0

                            when (selectedType) {
                                OfferType.PERCENT_DISCOUNT.name -> {
                                    val v = discountValueStr.toDoubleOrNull()
                                    if (v == null || v <= 0.0) {
                                        errorMessage = "Please enter a valid discount percentage (e.g. 10)."
                                        return@Button
                                    }
                                    if (v > 100.0) {
                                        errorMessage = "Percentage discount cannot exceed 100%."
                                        return@Button
                                    }
                                    discVal = v
                                }
                                OfferType.FLAT_DISCOUNT.name -> {
                                    val v = discountValueStr.toDoubleOrNull()
                                    if (v == null || v <= 0.0) {
                                        errorMessage = "Please enter a valid flat discount amount (e.g. 10)."
                                        return@Button
                                    }
                                    discVal = v
                                }
                                OfferType.BUY_X_GET_Y.name -> {
                                    val buyV = buyQtyStr.toDoubleOrNull()
                                    val getV = getQtyStr.toDoubleOrNull()
                                    if (buyV == null || buyV <= 0.0) {
                                        errorMessage = "Please enter a valid Buy Quantity (e.g. 2)."
                                        return@Button
                                    }
                                    if (getV == null || getV <= 0.0) {
                                        errorMessage = "Please enter a valid Get Free Quantity (e.g. 1)."
                                        return@Button
                                    }
                                    bQ = buyV
                                    gQ = getV
                                    discVal = 100.0
                                }
                                OfferType.FREE_GIFT.name -> {
                                    val spendV = minSpendAmountStr.toDoubleOrNull()
                                    if (spendV == null || spendV <= 0.0) {
                                        errorMessage = if (isBn) "সঠিক ন্যূনতম কেনাকাটার পরিমাণ দিন (যেমন: ৫০০)" else "Please enter a valid minimum spend amount (e.g. 500)."
                                        return@Button
                                    }
                                    if (selectedFreeProductId.isNullOrBlank()) {
                                        errorMessage = if (isBn) "অনুগ্রহ করে ফ্রি উপহার পণ্যটি নির্বাচন করুন" else "Please select the product to give for free."
                                        return@Button
                                    }
                                    val freeQV = freeProductQtyStr.toDoubleOrNull()
                                    if (freeQV == null || freeQV <= 0.0) {
                                        errorMessage = if (isBn) "সঠিক উপহারের সংখ্যা দিন (যেমন: ১)" else "Please enter a valid free gift quantity (e.g. 1)."
                                        return@Button
                                    }
                                    minSpendVal = spendV
                                    freeGiftProdId = selectedFreeProductId
                                    freeGiftQtyVal = freeQV
                                    discVal = 0.0
                                }
                            }

                            if (!applyToAll && selectedProductIds.isEmpty()) {
                                errorMessage = "Please select at least one product, or check 'Apply to ALL products'."
                                return@Button
                            }

                            val cleanStart = startDate.trim().takeIf { it.isNotEmpty() }
                            val cleanEnd = endDate.trim().takeIf { it.isNotEmpty() }

                            if (cleanStart != null && cleanEnd != null && cleanStart > cleanEnd) {
                                errorMessage = "Start date ($cleanStart) cannot be after end date ($cleanEnd)."
                                return@Button
                            }

                            val productIdsString = if (applyToAll) "" else selectedProductIds.joinToString(",")

                            val newOffer = Offer(
                                id = offer?.id ?: UUID.randomUUID().toString(),
                                name = cleanName,
                                type = selectedType,
                                applicableProductIds = productIdsString,
                                discountValue = discVal,
                                buyQty = bQ,
                                getQty = gQ,
                                getDiscountPercent = 100.0,
                                minSpendAmount = minSpendVal,
                                freeProductId = freeGiftProdId,
                                freeProductQty = freeGiftQtyVal,
                                freeProductUnit = if (selectedType == OfferType.FREE_GIFT.name) freeProductUnit?.trim()?.takeIf { it.isNotBlank() } else null,
                                startDate = cleanStart,
                                endDate = cleanEnd,
                                isActive = isActive,
                                createdAt = offer?.createdAt ?: System.currentTimeMillis(),
                                updatedAt = System.currentTimeMillis()
                            )
                            onSave(newOffer)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = StorePrimary),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (offer == null) (if (isBn) "অফার সংরক্ষণ করুন" else "Save Offer")
                                   else (if (isBn) "আপডেট করুন" else "Update Offer"),
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }

    if (showFreeProductPickerModal) {
        var giftSearch by remember { mutableStateOf("") }
        var giftCategory by remember { mutableStateOf("ALL") }
        val eligibleGiftProducts = remember(allProducts, giftSearch, giftCategory) {
            allProducts.filter { prod ->
                val matchCat = giftCategory == "ALL" || prod.category.equals(giftCategory, ignoreCase = true)
                val matchQuery = giftSearch.isBlank() ||
                        prod.nameEn.contains(giftSearch, ignoreCase = true) ||
                        prod.nameBn.contains(giftSearch, ignoreCase = true) ||
                        (prod.barcode?.contains(giftSearch, ignoreCase = true) == true)
                matchCat && matchQuery
            }
        }

        AlertDialog(
            onDismissRequest = { showFreeProductPickerModal = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.CardGiftcard, contentDescription = null, tint = StoreGreenProfit)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isBn) "ফ্রি উপহার পণ্য নির্বাচন করুন" else "Select Free Gift Product",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                }
            },
            text = {
                Column(modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                    OutlinedTextField(
                        value = giftSearch,
                        onValueChange = { giftSearch = it },
                        placeholder = { Text(if (isBn) "পণ্যের নাম বা বারকোড খুঁজুন..." else "Search product name or barcode...") },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    if (categories.size > 1) {
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            items(categories, key = { it }) { cat ->
                                FilterChip(
                                    selected = giftCategory == cat,
                                    onClick = { giftCategory = cat },
                                    label = { Text(if (cat == "ALL") (if (isBn) "সব" else "All") else cat, fontSize = 11.sp) }
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                    }
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(eligibleGiftProducts, key = { it.id }) { prod ->
                            Surface(
                                onClick = {
                                    selectedFreeProductId = prod.id
                                    freeProductUnit = prod.unitType
                                    errorMessage = null
                                    showFreeProductPickerModal = false
                                },
                                shape = RoundedCornerShape(8.dp),
                                color = if (selectedFreeProductId == prod.id) StoreGreenProfit.copy(alpha = 0.15f) else SurfaceWarm,
                                border = BorderStroke(1.dp, if (selectedFreeProductId == prod.id) StoreGreenProfit else TextMuted.copy(alpha = 0.2f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = prod.getDisplayName(isBn),
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 13.sp,
                                            color = TextDark
                                        )
                                        Text(
                                            text = "Selling: ₹%.2f / %s | Stock: %.1f".format(prod.sellingPrice, prod.unitType, prod.currentStock),
                                            fontSize = 11.sp,
                                            color = TextMuted
                                        )
                                    }
                                    if (selectedFreeProductId == prod.id) {
                                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = StoreGreenProfit, modifier = Modifier.size(20.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showFreeProductPickerModal = false }) {
                    Text(if (isBn) "বন্ধ করুন" else "Close")
                }
            }
        )
    }
}
