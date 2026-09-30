package com.example.utils

import com.example.data.local.dao.PurchaseWithItems
import com.example.data.local.dao.SaleWithItems
import com.example.data.local.entities.EmployeeSalaryPayment
import com.example.data.local.entities.Expense
import com.example.data.local.entities.Offer
import com.example.data.local.entities.Product
import com.example.data.local.entities.SaleReturnWithItems
import com.example.data.local.entities.StockOutEntry
import kotlin.math.max

/**
 * Data model for individual product profit and loss analysis.
 */
data class ProductProfitItem(
    val productId: String,
    val productNameEn: String,
    val productNameBn: String,
    val category: String,
    val unitType: String,
    val currentStock: Double,
    val costPrice: Double,
    val sellingPrice: Double,
    val unitsSold: Double,
    val salesRevenue: Double,
    val cogs: Double,
    val grossProfit: Double,
    val stockLossQty: Double = 0.0,
    val stockLossCost: Double = 0.0,
    val netProfit: Double,
    val profitMarginPercent: Double,
    val profitPerUnit: Double,
    val billsCount: Int = 0,
    val effectiveSellingPrice: Double = if (unitsSold > 0.0) salesRevenue / unitsSold else sellingPrice,
    val effectiveCostPrice: Double = if (unitsSold > 0.0) cogs / unitsSold else costPrice,
    val isArchivedOrDeleted: Boolean = false,
    val activeOfferTitle: String? = null,
    val activeOfferDiscountPercent: Double = 0.0,
    val lossReason: String? = null,
    val isLossMaking: Boolean = netProfit < 0.0 || grossProfit < 0.0,
    val isLowMargin: Boolean = profitMarginPercent in 0.0..10.0 && salesRevenue > 0.0,
    val isHighMargin: Boolean = profitMarginPercent >= 25.0
) {
    fun getDisplayName(isBn: Boolean): String {
        val base = if (isBn && productNameBn.isNotBlank()) productNameBn else productNameEn
        return if (isArchivedOrDeleted && !base.contains("(Archived)", ignoreCase = true)) "$base (Archived)" else base
    }
}

/**
 * Data model for an expense / money outflow category.
 */
data class OutflowCategoryItem(
    val categoryKey: String,
    val categoryNameEn: String,
    val categoryNameBn: String,
    val amount: Double,
    val percentage: Double,
    val transactionCount: Int,
    val iconName: String,
    val colorHex: Long
) {
    fun getDisplayName(isBn: Boolean): String {
        return if (isBn && categoryNameBn.isNotBlank()) categoryNameBn else categoryNameEn
    }
}

/**
 * Data model for single large expense or payment item.
 */
data class TopExpenseItem(
    val titleEn: String,
    val titleBn: String,
    val category: String,
    val amount: Double,
    val date: Long,
    val note: String? = null
)

/**
 * Velocity status classification.
 */
enum class VelocityClassification {
    SUPER_FAST,      // Top seller / very high daily turnover
    STEADY,          // Moderate steady sales
    SLOW_MOVER,      // Low turnover
    DEAD_STOCK,      // In-stock with zero sales in period
    OUT_OF_STOCK     // Zero or negative stock remaining
}

enum class StockRunoutRisk {
    OUT_OF_STOCK,    // Current stock <= 0
    CRITICAL,        // Stock will run out in < 3 days at current velocity
    WARNING,         // Stock will run out in 3 to 7 days
    SAFE,            // Stock lasts > 7 days or no sales
    NO_DATA          // No sales velocity
}

/**
 * Data model for individual product sales velocity and stock runway.
 */
data class ProductVelocityItem(
    val productId: String,
    val productNameEn: String,
    val productNameBn: String,
    val category: String,
    val unitType: String,
    val currentStock: Double,
    val unitsSold: Double,
    val periodDays: Double,
    val dailyVelocity: Double,      // Units sold per day
    val revenueVelocityDaily: Double,// ₹ Sales per day
    val totalRevenue: Double,
    val billsCount: Int,
    val daysToRunout: Double,       // Estimated days until out of stock
    val velocityRank: Int = 0,
    val classification: VelocityClassification,
    val runoutRisk: StockRunoutRisk
) {
    fun getDisplayName(isBn: Boolean): String {
        return if (isBn && productNameBn.isNotBlank()) productNameBn else productNameEn
    }
}

/**
 * Master Comprehensive Business Intelligence Report containing:
 * 1. Product Profit & Loss breakdown (Top generators & Loss makers)
 * 2. Money Spending & Outflow breakdown (Where money is spent most)
 * 3. Product Sales Velocity & Fast Movers (Runway & Stockout forecasting)
 */
data class ExpenseCategorySummary(
    val categoryKey: String,
    val categoryNameEn: String,
    val categoryNameBn: String,
    val totalAmount: Double,
    val percentage: Double,
    val count: Int,
    val colorHex: Long
) {
    fun getDisplayName(isBengali: Boolean): String = if (isBengali && categoryNameBn.isNotBlank()) categoryNameBn else categoryNameEn
}

data class ComprehensiveBusinessReport(
    val startTime: Long,
    val endTime: Long,
    val periodDays: Double,
    
    // Overall High-Level Summary
    val totalRevenue: Double,
    val totalCogs: Double,
    val grossProfit: Double,
    val totalOutflow: Double,
    val netProfit: Double,
    
    // 1. Product Profit & Loss
    val allProductProfits: List<ProductProfitItem>,
    val topProfitableProducts: List<ProductProfitItem>,
    val lossMakingProducts: List<ProductProfitItem>,
    val lowMarginProducts: List<ProductProfitItem>,
    val mostProfitableProduct: ProductProfitItem?,
    val biggestLossProduct: ProductProfitItem?,
    
    // 2. Money Spending & Outflow
    val supplierPurchasesSpend: Double,
    val operationalExpensesSpend: Double,
    val staffPayrollSpend: Double,
    val stockLossSpend: Double,
    val outflowCategories: List<OutflowCategoryItem>,
    val topSpendingCategory: OutflowCategoryItem?,
    val topExpenseItems: List<TopExpenseItem>,
    
    // 3. Sales Velocity & Fast Movers
    val allProductVelocities: List<ProductVelocityItem>,
    val fastestMovingProducts: List<ProductVelocityItem>,
    val slowMovingProducts: List<ProductVelocityItem>,
    val deadStockProducts: List<ProductVelocityItem>,
    val criticalRunoutProducts: List<ProductVelocityItem>,
    val topFastestProduct: ProductVelocityItem?
)

/**
 * Calculation service that converts raw transactional data into deep business intelligence.
 */
object BusinessAnalyticsService {

    fun generateComprehensiveReport(
        startTime: Long,
        endTime: Long,
        sales: List<SaleWithItems>,
        expenses: List<Expense>,
        purchases: List<PurchaseWithItems>,
        salaryPayments: List<EmployeeSalaryPayment>,
        stockOuts: List<StockOutEntry>,
        products: List<Product>,
        returns: List<SaleReturnWithItems> = emptyList(),
        offers: List<Offer> = emptyList()
    ): ComprehensiveBusinessReport {
        val now = System.currentTimeMillis()
        val effectiveEnd = if (endTime > now) now else endTime

        // Determine earliest transaction time for accurate velocity in "All Time"
        val earliestTxTime = listOfNotNull(
            sales.minOfOrNull { it.sale.datetime },
            expenses.minOfOrNull { it.date },
            purchases.minOfOrNull { it.purchase.datetime },
            salaryPayments.minOfOrNull { it.paymentDate },
            stockOuts.minOfOrNull { it.timestamp }
        ).minOrNull()

        val effectiveStart = if (startTime <= 0L || (earliestTxTime != null && startTime < earliestTxTime && (now - startTime) > 365L * 86400000L)) {
            earliestTxTime ?: (now - 86400000L)
        } else {
            startTime
        }
        val diffMs = max(86400000L, effectiveEnd - effectiveStart)
        val periodDays = max(1.0, diffMs / 86400000.0)

        val productsMap = products.associateBy { it.id }

        // Track historical metadata for products that might have been deleted or renamed
        val historicalNamesEn = mutableMapOf<String, String>()
        val historicalNamesBn = mutableMapOf<String, String>()
        val historicalUnits = mutableMapOf<String, String>()
        val historicalCostPrices = mutableMapOf<String, Double>()
        val historicalUnitPrices = mutableMapOf<String, Double>()

        // --- 1. PRODUCT PROFIT & LOSS INTELLIGENCE ---
        // Accumulators per productId
        val productSalesQty = mutableMapOf<String, Double>()
        val productSalesRev = mutableMapOf<String, Double>()
        val productSalesCogs = mutableMapOf<String, Double>()
        val productBillCounts = mutableMapOf<String, MutableSet<String>>()

        // Tally sales
        for (saleWithItems in sales) {
            val saleId = saleWithItems.sale.id
            val consolidated = saleWithItems.consolidatedItems
            for (item in consolidated) {
                val pId = item.productId
                val product = productsMap[pId]

                // Save historical metadata
                if (item.productNameEn.isNotBlank() && !item.productNameEn.startsWith("Product #")) {
                    historicalNamesEn[pId] = item.productNameEn
                }
                if (item.productNameBn.isNotBlank()) {
                    historicalNamesBn[pId] = item.productNameBn
                }
                if (item.unitType.isNotBlank()) {
                    historicalUnits[pId] = item.unitType
                }
                if (item.costPrice > 0.0) {
                    historicalCostPrices[pId] = item.costPrice
                }
                if (item.unitPrice > 0.0) {
                    historicalUnitPrices[pId] = item.unitPrice
                }

                // CRITICAL: Normalize quantity to the product's catalog base unit!
                val baseQty = if (product != null) {
                    product.convertQuantityToBaseUnit(item.quantity, item.unitType)
                } else {
                    item.quantity
                }

                productSalesQty[pId] = (productSalesQty[pId] ?: 0.0) + baseQty
                productSalesRev[pId] = (productSalesRev[pId] ?: 0.0) + item.subtotal
                productSalesCogs[pId] = (productSalesCogs[pId] ?: 0.0) + item.totalCost
                val set = productBillCounts.getOrPut(pId) { mutableSetOf() }
                set.add(saleId)
            }
        }

        // Adjust for returns and replacements
        for (ret in returns) {
            for (rItem in ret.items) {
                val pId = rItem.productId
                val product = productsMap[pId]
                val baseQty = if (product != null) {
                    product.convertQuantityToBaseUnit(rItem.quantity, rItem.unitType)
                } else {
                    rItem.quantity
                }
                val costPrice = product?.costPrice ?: (rItem.unitPrice * 0.7)
                val itemCost = costPrice * baseQty
                val itemRev = rItem.unitPrice * baseQty

                if (rItem.isReplacement) {
                    productSalesQty[pId] = (productSalesQty[pId] ?: 0.0) + baseQty
                    productSalesRev[pId] = (productSalesRev[pId] ?: 0.0) + itemRev
                    productSalesCogs[pId] = (productSalesCogs[pId] ?: 0.0) + itemCost
                } else {
                    productSalesQty[pId] = (productSalesQty[pId] ?: 0.0) - baseQty
                    productSalesRev[pId] = (productSalesRev[pId] ?: 0.0) - itemRev
                    productSalesCogs[pId] = (productSalesCogs[pId] ?: 0.0) - itemCost
                }
            }
        }

        // Tally Stock Loss per product
        val productStockLossQty = mutableMapOf<String, Double>()
        val productStockLossCost = mutableMapOf<String, Double>()
        for (so in stockOuts) {
            if (so.isBusinessLoss()) {
                val pId = so.productId
                productStockLossQty[pId] = (productStockLossQty[pId] ?: 0.0) + so.quantity
                productStockLossCost[pId] = (productStockLossCost[pId] ?: 0.0) + so.totalCostValue
            }
        }

        // Map active offers by productId
        val activeOffersMap = mutableMapOf<String, Offer>()
        val storewideActiveOffer = offers.firstOrNull { it.isCurrentlyApplying() && it.applicableProductIds.isBlank() }
        for (offer in offers) {
            if (offer.isCurrentlyApplying()) {
                for (prodId in offer.getProductIdsList()) {
                    if (!activeOffersMap.containsKey(prodId)) {
                        activeOffersMap[prodId] = offer
                    }
                }
            }
        }

        // Build list of ProductProfitItem for all products that had sales OR stock loss OR exist in catalog
        val allProductProfitList = mutableListOf<ProductProfitItem>()
        val allInvolvedProductIds = (productsMap.keys + productSalesRev.keys + productStockLossCost.keys).toSet()

        for (pId in allInvolvedProductIds) {
            val product = productsMap[pId]
            val isArchived = product == null

            val nameEn = product?.nameEn
                ?: historicalNamesEn[pId]
                ?: "Product #$pId"
            val nameBn = product?.nameBn
                ?: historicalNamesBn[pId]
                ?: ""
            val category = product?.category ?: "General"
            val unitType = product?.unitType
                ?: historicalUnits[pId]
                ?: "piece"
            val currentStock = product?.currentStock ?: 0.0
            val costPrice = product?.costPrice
                ?: historicalCostPrices[pId]
                ?: 0.0
            val sellingPrice = product?.sellingPrice
                ?: historicalUnitPrices[pId]
                ?: 0.0

            val unitsSold = max(0.0, productSalesQty[pId] ?: 0.0)
            val rev = max(0.0, productSalesRev[pId] ?: 0.0)
            val cogs = max(0.0, productSalesCogs[pId] ?: 0.0)
            val grossProfit = rev - cogs
            val stockLossCost = productStockLossCost[pId] ?: 0.0
            val stockLossQty = productStockLossQty[pId] ?: 0.0
            val netProfit = grossProfit - stockLossCost
            val margin = if (rev > 0.0) (netProfit / rev) * 100.0 else 0.0
            val profitPerUnit = if (unitsSold > 0.0) netProfit / unitsSold else 0.0
            val bills = productBillCounts[pId]?.size ?: 0

            val effectiveSell = if (unitsSold > 0.0) rev / unitsSold else sellingPrice
            val effectiveCost = if (unitsSold > 0.0) cogs / unitsSold else costPrice

            val activeOffer = activeOffersMap[pId] ?: storewideActiveOffer
            val activeOfferTitle = activeOffer?.name
            val activeOfferDiscountPercent = if (activeOffer != null) {
                if (activeOffer.type == "PERCENT_DISCOUNT") activeOffer.discountValue
                else if (sellingPrice > 0.0 && activeOffer.discountValue > 0.0) (activeOffer.discountValue / sellingPrice) * 100.0
                else 0.0
            } else 0.0

            val lossReason = when {
                netProfit < 0.0 && effectiveSell < effectiveCost -> "SOLD_BELOW_COST"
                netProfit < 0.0 && stockLossCost > grossProfit -> "WASTAGE_EXCEEDED_PROFIT"
                netProfit < 0.0 && effectiveSell < sellingPrice -> "DISCOUNT_ERODED_MARGIN"
                margin in 0.0..5.0 && rev > 0.0 -> "THIN_MARGIN"
                else -> null
            }

            allProductProfitList.add(
                ProductProfitItem(
                    productId = pId,
                    productNameEn = nameEn,
                    productNameBn = nameBn,
                    category = category,
                    unitType = unitType,
                    currentStock = currentStock,
                    costPrice = costPrice,
                    sellingPrice = sellingPrice,
                    unitsSold = unitsSold,
                    salesRevenue = rev,
                    cogs = cogs,
                    grossProfit = grossProfit,
                    stockLossQty = stockLossQty,
                    stockLossCost = stockLossCost,
                    netProfit = netProfit,
                    profitMarginPercent = margin,
                    profitPerUnit = profitPerUnit,
                    billsCount = bills,
                    effectiveSellingPrice = effectiveSell,
                    effectiveCostPrice = effectiveCost,
                    isArchivedOrDeleted = isArchived,
                    activeOfferTitle = activeOfferTitle,
                    activeOfferDiscountPercent = activeOfferDiscountPercent,
                    lossReason = lossReason
                )
            )
        }

        val topProfitableProducts = allProductProfitList
            .filter { it.netProfit > 0.0 && it.unitsSold > 0.0 }
            .sortedByDescending { it.netProfit }

        val lossMakingProducts = allProductProfitList
            .filter { it.isLossMaking && (it.unitsSold > 0.0 || it.stockLossCost > 0.0) }
            .sortedBy { it.netProfit }

        val lowMarginProducts = allProductProfitList
            .filter { it.isLowMargin && it.unitsSold > 0.0 && it.netProfit >= 0.0 }
            .sortedBy { it.profitMarginPercent }

        val mostProfitable = topProfitableProducts.firstOrNull()
        val biggestLoss = lossMakingProducts.firstOrNull()

        // --- 2. WHERE MONEY IS SPENT (OUTFLOW INTELLIGENCE) ---
        var supplierPurchasesSpend = purchases.sumOf { it.purchase.amountPaid }
        // Fallback: If purchases weren't logged separately in purchases table, COGS represents inventory purchase outflow
        if (supplierPurchasesSpend <= 0.0 && productSalesCogs.values.sum() > 0.0) {
            supplierPurchasesSpend = productSalesCogs.values.sum()
        }

        // Shop Expenses (exclude stock loss / wastage categories to prevent double count)
        val filteredExpenses = expenses.filterNot {
            it.category.contains("Stock Loss", ignoreCase = true) || it.category.contains("Wastage", ignoreCase = true)
        }
        val operationalExpensesSpend = filteredExpenses.sumOf { it.amount }

        // Staff Payroll disbursed in this window
        val staffPayrollSpend = salaryPayments.filter { it.paymentDate in startTime..endTime }.sumOf { it.netSalaryPaid }

        // Stock Loss (Damaged / Expired / Wastage)
        val stockLossSpend = stockOuts.filter { it.isBusinessLoss() }.sumOf { it.totalCostValue }

        val totalOutflow = supplierPurchasesSpend + operationalExpensesSpend + staffPayrollSpend + stockLossSpend

        // Group operational expenses by category
        val expensesByCategory = filteredExpenses.groupBy { it.category.trim() }
        val outflowCategoryList = mutableListOf<OutflowCategoryItem>()

        // 1. Supplier / Stock Inflow
        if (supplierPurchasesSpend > 0.0) {
            val pct = if (totalOutflow > 0) (supplierPurchasesSpend / totalOutflow) * 100.0 else 0.0
            outflowCategoryList.add(
                OutflowCategoryItem(
                    categoryKey = "SUPPLIER_PURCHASES",
                    categoryNameEn = "Stock & Inventory Purchases",
                    categoryNameBn = "পণ্য ক্রয় ও মহাজন পেমেন্ট",
                    amount = supplierPurchasesSpend,
                    percentage = pct,
                    transactionCount = purchases.size.coerceAtLeast(1),
                    iconName = "Inventory",
                    colorHex = 0xFF1E88E5 // Blue
                )
            )
        }

        // 2. Staff Payroll / Wages
        val wagesExpenses = filteredExpenses.filter { it.category.contains("Wage", ignoreCase = true) || it.category.contains("Salary", ignoreCase = true) }.sumOf { it.amount }
        val combinedStaffOutflow = staffPayrollSpend + wagesExpenses
        if (combinedStaffOutflow > 0.0) {
            val pct = if (totalOutflow > 0) (combinedStaffOutflow / totalOutflow) * 100.0 else 0.0
            outflowCategoryList.add(
                OutflowCategoryItem(
                    categoryKey = "STAFF_PAYROLL",
                    categoryNameEn = "Employee Salary & Wages",
                    categoryNameBn = "কর্মচারী বেতন ও মজুরি",
                    amount = combinedStaffOutflow,
                    percentage = pct,
                    transactionCount = salaryPayments.size + filteredExpenses.count { it.category.contains("Wage", ignoreCase = true) },
                    iconName = "Badge",
                    colorHex = 0xFF8E24AA // Purple
                )
            )
        }

        // 3. Other Operational Expense Categories
        val categoryColorMap = mapOf(
            "Rent" to 0xFFE53935L,          // Red
            "Electricity" to 0xFFFB8C00L,   // Orange
            "Transport" to 0xFF00897BL,     // Teal
            "Packaging" to 0xFF6D4C41L,     // Brown
            "Tea" to 0xFFFDD835L,           // Amber
            "Maintenance" to 0xFF546E7AL,   // Blue-Grey
            "Marketing" to 0xFF43A047L      // Green
        )

        for ((catName, expList) in expensesByCategory) {
            if (catName.contains("Wage", ignoreCase = true) || catName.contains("Salary", ignoreCase = true)) continue
            val sum = expList.sumOf { it.amount }
            if (sum > 0.0) {
                val pct = if (totalOutflow > 0) (sum / totalOutflow) * 100.0 else 0.0
                val color = categoryColorMap.entries.firstOrNull { catName.contains(it.key, ignoreCase = true) }?.value ?: 0xFF78909CL
                val bnName = when {
                    catName.contains("Rent", ignoreCase = true) -> "দোকান ভাড়া"
                    catName.contains("Electric", ignoreCase = true) -> "বিদ্যুৎ ও ইউটিলিটি বিল"
                    catName.contains("Transport", ignoreCase = true) -> "যাতায়াত ও পরিবহন খরচ"
                    catName.contains("Packaging", ignoreCase = true) -> "প্যাকেজিং ও ব্যাগ"
                    catName.contains("Tea", ignoreCase = true) || catName.contains("Snack", ignoreCase = true) -> "চা, নাস্তা ও আপ্যায়ন"
                    catName.contains("Maintenance", ignoreCase = true) -> "মেরামত ও রক্ষণাবেক্ষণ"
                    catName.contains("Marketing", ignoreCase = true) -> "বিজ্ঞাপন ও প্রচার"
                    else -> catName
                }
                outflowCategoryList.add(
                    OutflowCategoryItem(
                        categoryKey = catName,
                        categoryNameEn = catName,
                        categoryNameBn = bnName,
                        amount = sum,
                        percentage = pct,
                        transactionCount = expList.size,
                        iconName = "Receipt",
                        colorHex = color
                    )
                )
            }
        }

        // 4. Stock Loss & Wastage Outflow
        if (stockLossSpend > 0.0) {
            val pct = if (totalOutflow > 0) (stockLossSpend / totalOutflow) * 100.0 else 0.0
            outflowCategoryList.add(
                OutflowCategoryItem(
                    categoryKey = "STOCK_LOSS_WASTAGE",
                    categoryNameEn = "Damaged / Expired Stock Loss",
                    categoryNameBn = "নষ্ট ও মেয়াদোত্তীর্ণ মালের ক্ষতি",
                    amount = stockLossSpend,
                    percentage = pct,
                    transactionCount = stockOuts.count { it.isBusinessLoss() },
                    iconName = "DeleteForever",
                    colorHex = 0xFFD32F2F // Bright Red Alert
                )
            )
        }

        val sortedOutflows = outflowCategoryList.sortedByDescending { it.amount }
        val topSpendingCategory = sortedOutflows.firstOrNull()

        // Top individual expenses
        val topExpenseItems = filteredExpenses
            .sortedByDescending { it.amount }
            .take(10)
            .map { exp ->
                TopExpenseItem(
                    titleEn = exp.category,
                    titleBn = exp.category,
                    category = exp.category,
                    amount = exp.amount,
                    date = exp.date,
                    note = exp.note
                )
            }

        // --- 3. PRODUCT SALES VELOCITY & FAST MOVERS ---
        val allVelocityList = mutableListOf<ProductVelocityItem>()

        for (product in products) {
            val pId = product.id
            val unitsSold = max(0.0, productSalesQty[pId] ?: 0.0)
            val rev = max(0.0, productSalesRev[pId] ?: 0.0)
            val bills = productBillCounts[pId]?.size ?: 0
            val dailyVelocity = unitsSold / periodDays
            val revVelocity = rev / periodDays
            val currentStock = product.currentStock

            val daysToRunout = if (dailyVelocity > 0.0001) {
                (currentStock / dailyVelocity).coerceAtLeast(0.0)
            } else {
                Double.POSITIVE_INFINITY
            }

            val classification = when {
                currentStock <= 0.0 && unitsSold > 0.0 -> VelocityClassification.OUT_OF_STOCK
                unitsSold <= 0.0 && currentStock > 0.0 -> VelocityClassification.DEAD_STOCK
                dailyVelocity >= 5.0 || (periodDays >= 7.0 && unitsSold >= 30.0) -> VelocityClassification.SUPER_FAST
                dailyVelocity >= 1.0 || unitsSold >= 5.0 -> VelocityClassification.STEADY
                else -> VelocityClassification.SLOW_MOVER
            }

            val runoutRisk = when {
                currentStock <= 0.0 -> StockRunoutRisk.OUT_OF_STOCK
                dailyVelocity <= 0.0001 -> StockRunoutRisk.SAFE
                daysToRunout < 3.0 -> StockRunoutRisk.CRITICAL
                daysToRunout <= 7.0 -> StockRunoutRisk.WARNING
                else -> StockRunoutRisk.SAFE
            }

            allVelocityList.add(
                ProductVelocityItem(
                    productId = pId,
                    productNameEn = product.nameEn,
                    productNameBn = product.nameBn,
                    category = product.category,
                    unitType = product.unitType,
                    currentStock = currentStock,
                    unitsSold = unitsSold,
                    periodDays = periodDays,
                    dailyVelocity = dailyVelocity,
                    revenueVelocityDaily = revVelocity,
                    totalRevenue = rev,
                    billsCount = bills,
                    daysToRunout = daysToRunout,
                    classification = classification,
                    runoutRisk = runoutRisk
                )
            )
        }

        // Rank velocities by daily unit sales
        val sortedVelocities = allVelocityList
            .sortedByDescending { it.dailyVelocity }
            .mapIndexed { index, item -> item.copy(velocityRank = index + 1) }

        val fastestMovers = sortedVelocities
            .filter { it.unitsSold > 0.0 }
            .take(20)

        val criticalRunouts = sortedVelocities
            .filter { (it.runoutRisk == StockRunoutRisk.CRITICAL || it.runoutRisk == StockRunoutRisk.WARNING) && it.currentStock > 0.0 }
            .sortedBy { it.daysToRunout }

        val deadStock = sortedVelocities
            .filter { it.classification == VelocityClassification.DEAD_STOCK }
            .sortedByDescending { it.currentStock }

        val slowMovers = sortedVelocities
            .filter { it.classification == VelocityClassification.SLOW_MOVER && it.unitsSold > 0.0 }
            .sortedBy { it.dailyVelocity }

        val topFastest = fastestMovers.firstOrNull()

        val totalRev = productSalesRev.values.sum()
        val totalCogsVal = productSalesCogs.values.sum()
        val grossProf = totalRev - totalCogsVal
        val netProf = grossProf - operationalExpensesSpend - stockLossSpend - staffPayrollSpend

        return ComprehensiveBusinessReport(
            startTime = startTime,
            endTime = endTime,
            periodDays = periodDays,
            totalRevenue = totalRev,
            totalCogs = totalCogsVal,
            grossProfit = grossProf,
            totalOutflow = totalOutflow,
            netProfit = netProf,
            
            allProductProfits = allProductProfitList.sortedByDescending { it.netProfit },
            topProfitableProducts = topProfitableProducts,
            lossMakingProducts = lossMakingProducts,
            lowMarginProducts = lowMarginProducts,
            mostProfitableProduct = mostProfitable,
            biggestLossProduct = biggestLoss,
            
            supplierPurchasesSpend = supplierPurchasesSpend,
            operationalExpensesSpend = operationalExpensesSpend,
            staffPayrollSpend = staffPayrollSpend,
            stockLossSpend = stockLossSpend,
            outflowCategories = sortedOutflows,
            topSpendingCategory = topSpendingCategory,
            topExpenseItems = topExpenseItems,
            
            allProductVelocities = sortedVelocities,
            fastestMovingProducts = fastestMovers,
            slowMovingProducts = slowMovers,
            deadStockProducts = deadStock,
            criticalRunoutProducts = criticalRunouts,
            topFastestProduct = topFastest
        )
    }
}
