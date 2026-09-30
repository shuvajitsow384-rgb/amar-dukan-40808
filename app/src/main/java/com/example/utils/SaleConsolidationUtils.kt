package com.example.utils

import com.example.data.local.entities.Product
import com.example.data.local.entities.Sale
import com.example.data.local.entities.SaleItem
import com.example.data.local.entities.Purchase
import com.example.data.local.entities.PurchaseItem
import com.example.data.local.dao.SaleWithItems

/**
 * Utility functions for deduplicating, consolidating, and auto-healing SaleItems, PurchaseItems,
 * and CartItems across checkout transactions, sync reconciliation, COGS computations, and historical audits.
 */
object SaleConsolidationUtils {

    /**
     * Sanitizes and heals a list of [SaleItem]s for a given [Sale].
     * 
     * Handles:
     * 1. Eliminates duplicate identical items caused by concurrent background sync interleaving.
     * 2. Consolidates multiple items for the same product into a single line.
     * 3. Self-heals multiplied quantities / inflated COGS where sync duplication caused item
     *    subtotals to exceed the bill total by an integer multiplier factor (e.g. 2x, 3x, 4x).
     */
    fun sanitizeSaleItems(
        sale: Sale,
        items: List<SaleItem>,
        productLookup: ((productId: String) -> Product?)? = null
    ): List<SaleItem> {
        if (items.isEmpty()) return emptyList()

        val expectedBillTotal = if (sale.totalAmount > 0.0) sale.totalAmount else (sale.finalAmount + sale.discount)

        // 1. Group items by product ID and variant
        val groupedByProduct = items.groupBy { "${it.productId}__${it.variantBarcode ?: ""}" }
        val intermediateList = mutableListOf<SaleItem>()

        for ((productId, productItems) in groupedByProduct) {
            if (productId.startsWith("quick_") || productItems.size == 1) {
                intermediateList.addAll(productItems)
                continue
            }

            val first = productItems.first()
            val totalItemsSubtotal = productItems.sumOf { it.subtotal }
            // Check if all rows are exact duplicate clones (same quantity, unitPrice, costPrice, unitType)
            val allIdenticalClones = productItems.all {
                it.quantity == first.quantity &&
                it.unitPrice == first.unitPrice &&
                it.costPrice == first.costPrice &&
                it.unitType.equals(first.unitType, ignoreCase = true)
            }

            if (allIdenticalClones && expectedBillTotal > 0.0 && (Math.abs(expectedBillTotal - first.subtotal) <= 0.05 || totalItemsSubtotal > expectedBillTotal + 0.05)) {
                // If the single item subtotal already satisfies expectedBillTotal or sum exceeds bill total, keep only a single copy
                intermediateList.add(first)
            } else {
                val consolidated = consolidateSaleItems(productItems, productLookup)
                intermediateList.addAll(consolidated)
            }
        }

        // 2. Detect and repair multiplier compounding (e.g. 1L + 1L + 1L saved as 3L for ₹180 bill)
        val currentSubtotalSum = intermediateList.sumOf { it.subtotal }
        if (expectedBillTotal > 0.0 && currentSubtotalSum > expectedBillTotal + 0.05) {
            val ratio = currentSubtotalSum / expectedBillTotal
            val roundedMultiplier = Math.round(ratio).toDouble()
            // If the subtotal sum matches an integer multiplier within reasonable floating tolerance
            if (roundedMultiplier >= 2.0 && Math.abs(currentSubtotalSum - (expectedBillTotal * roundedMultiplier)) <= (0.10 * roundedMultiplier)) {
                return intermediateList.map { item ->
                    val healedQty = (item.quantity / roundedMultiplier)
                    val healedSubtotal = (item.subtotal / roundedMultiplier)
                    val healedCost = (item.totalCost / roundedMultiplier)
                    item.copy(
                        quantity = healedQty,
                        subtotal = healedSubtotal,
                        totalCost = healedCost
                    )
                }
            }
        }

        return intermediateList
    }

    /**
     * Sanitizes and heals a list of [PurchaseItem]s for a given [Purchase].
     * 
     * Eliminates duplicate sync-generated items and normalizes multiplied quantities.
     */
    fun sanitizePurchaseItems(
        purchase: Purchase,
        items: List<PurchaseItem>
    ): List<PurchaseItem> {
        if (items.isEmpty()) return emptyList()

        val expectedTotal = purchase.totalAmount
        val groupedByProduct = items.groupBy { it.productId }
        val intermediateList = mutableListOf<PurchaseItem>()

        for ((_, productItems) in groupedByProduct) {
            if (productItems.size == 1) {
                intermediateList.add(productItems.first())
                continue
            }

            val first = productItems.first()
            val allIdenticalClones = productItems.all {
                it.quantity == first.quantity &&
                it.costPrice == first.costPrice
            }

            if (allIdenticalClones) {
                intermediateList.add(first)
            } else {
                val totalQty = productItems.sumOf { it.quantity }
                val totalSubtotal = productItems.sumOf { it.subtotal }
                val effectiveCostPrice = if (totalQty > 0.0) totalSubtotal / totalQty else first.costPrice
                intermediateList.add(
                    first.copy(
                        quantity = totalQty,
                        costPrice = effectiveCostPrice,
                        subtotal = totalSubtotal
                    )
                )
            }
        }

        val currentSubtotalSum = intermediateList.sumOf { it.subtotal }
        if (expectedTotal > 0.0 && currentSubtotalSum > expectedTotal + 0.05) {
            val ratio = currentSubtotalSum / expectedTotal
            val roundedMultiplier = Math.round(ratio).toDouble()
            if (roundedMultiplier >= 2.0 && Math.abs(currentSubtotalSum - (expectedTotal * roundedMultiplier)) <= (0.10 * roundedMultiplier)) {
                return intermediateList.map { item ->
                    item.copy(
                        quantity = (item.quantity / roundedMultiplier),
                        subtotal = (item.subtotal / roundedMultiplier)
                    )
                }
            }
        }

        return intermediateList
    }

    /**
     * Consolidates a list of [SaleItem]s so that each unique [productId] (except quick custom items)
     * appears at most once in the finalized list.
     */
    fun consolidateSaleItems(
        items: List<SaleItem>,
        productLookup: ((productId: String) -> Product?)? = null
    ): List<SaleItem> {
        if (items.isEmpty()) return emptyList()

        val consolidated = mutableListOf<SaleItem>()
        // Group by productId + variantBarcode + productNameEn so different pre-packed variants of the same product are preserved as distinct line items
        val groupedByProduct = items.groupBy { "${it.productId}__${it.variantBarcode ?: ""}__${it.productNameEn}" }

        for ((_, itemList) in groupedByProduct) {
            val productId = itemList.first().productId
            // Quick custom items have unique IDs like "quick_12345" or are intended as separate custom rows
            if (productId.startsWith("quick_") || itemList.size == 1) {
                consolidated.addAll(itemList)
                continue
            }

            // Multiple items for the same standard inventory product exist
            val first = itemList.first()
            val product = productLookup?.invoke(productId)

            val allSameUnit = itemList.all { it.unitType.equals(first.unitType, ignoreCase = true) }

            if (allSameUnit) {
                val totalQty = itemList.sumOf { it.quantity }
                val totalSubtotal = itemList.sumOf { it.subtotal }
                val totalCostSum = if (product != null) {
                    product.calculateCost(totalQty, first.unitType)
                } else {
                    itemList.sumOf { it.totalCost }
                }
                val effectiveUnitPrice = if (totalQty > 0.0) totalSubtotal / totalQty else first.unitPrice
                val effectiveMrp = if (product != null) product.getMrpPerUnit(first.unitType) else (if (first.mrp > 0.0) first.mrp else first.unitPrice)

                consolidated.add(
                    first.copy(
                        unitType = first.unitType,
                        quantity = totalQty,
                        unitPrice = effectiveUnitPrice,
                        subtotal = totalSubtotal,
                        totalCost = totalCostSum,
                        mrp = effectiveMrp
                    )
                )
            } else {
                var totalBaseQuantity = 0.0
                var totalSubtotal = 0.0
                var totalCostSum = 0.0

                for (item in itemList) {
                    val baseQty = if (product != null) {
                        product.convertQuantityToBaseUnit(item.quantity, item.unitType)
                    } else {
                        item.quantity
                    }
                    totalBaseQuantity += baseQty
                    totalSubtotal += item.subtotal
                    totalCostSum += item.totalCost
                }

                val finalCost = if (product != null) {
                    product.calculateCost(totalBaseQuantity, product.unitType)
                } else {
                    totalCostSum
                }

                val finalUnitType = product?.unitType ?: first.unitType
                val finalQuantity = totalBaseQuantity
                val effectiveUnitPrice = if (finalQuantity > 0.0) totalSubtotal / finalQuantity else first.unitPrice
                val effectiveMrp = if (product != null) product.getMrpPerUnit(finalUnitType) else (if (first.mrp > 0.0) first.mrp else first.unitPrice)

                consolidated.add(
                    first.copy(
                        unitType = finalUnitType,
                        quantity = finalQuantity,
                        unitPrice = effectiveUnitPrice,
                        subtotal = totalSubtotal,
                        totalCost = finalCost,
                        mrp = effectiveMrp
                    )
                )
            }
        }

        return consolidated
    }

    /**
     * Audit model representing a past sale that has duplicate product lines.
     */
    data class DuplicateSaleAuditResult(
        val saleId: String,
        val datetime: Long,
        val customerName: String?,
        val finalAmount: Double,
        val paymentMode: String,
        val duplicateEntries: List<DuplicateProductEntry>
    )

    data class DuplicateProductEntry(
        val productId: String,
        val productName: String,
        val lineCount: Int,
        val lineQuantities: List<Double>,
        val units: List<String>,
        val lineSubtotals: List<Double>,
        val totalQuantity: Double,
        val baseUnit: String,
        val totalExcessCost: Double
    )

    /**
     * Inspects a list of [SaleWithItems] and returns all sales containing duplicated product lines.
     */
    fun auditSalesForDuplicates(
        sales: List<SaleWithItems>,
        productLookup: ((productId: String) -> Product?)? = null
    ): List<DuplicateSaleAuditResult> {
        val results = mutableListOf<DuplicateSaleAuditResult>()

        for (saleWithItems in sales) {
            val sale = saleWithItems.sale
            val items = saleWithItems.items

            // Find any standard product ID that appears more than once in this single bill (excluding distinct free gifts)
            val duplicateGroups = items
                .filterNot { it.productId.startsWith("quick_") || it.isFreeGift() }
                .groupBy { it.productId }
                .filter { it.value.size > 1 }

            if (duplicateGroups.isNotEmpty()) {
                val duplicateEntries = duplicateGroups.map { (productId, duplicateLines) ->
                    val first = duplicateLines.first()
                    val product = productLookup?.invoke(productId)
                    val baseUnit = product?.unitType ?: first.unitType

                    val lineQuantities = duplicateLines.map { it.quantity }
                    val units = duplicateLines.map { it.unitType }
                    val lineSubtotals = duplicateLines.map { it.subtotal }

                    val totalQuantity = duplicateLines.sumOf { item ->
                        product?.convertQuantityToBaseUnit(item.quantity, item.unitType) ?: item.quantity
                    }

                    // Excess cost calculated as duplicate lines beyond the primary single sale
                    val primaryCost = duplicateLines.first().totalCost
                    val allCost = duplicateLines.sumOf { it.totalCost }
                    val excessCost = (allCost - primaryCost).coerceAtLeast(0.0)

                    DuplicateProductEntry(
                        productId = productId,
                        productName = first.productNameEn.ifBlank { first.productNameBn }.ifBlank { "Product #$productId" },
                        lineCount = duplicateLines.size,
                        lineQuantities = lineQuantities,
                        units = units,
                        lineSubtotals = lineSubtotals,
                        totalQuantity = totalQuantity,
                        baseUnit = baseUnit,
                        totalExcessCost = excessCost
                    )
                }

                results.add(
                    DuplicateSaleAuditResult(
                        saleId = sale.id,
                        datetime = sale.datetime,
                        customerName = sale.customerName,
                        finalAmount = sale.finalAmount,
                        paymentMode = sale.paymentMode,
                        duplicateEntries = duplicateEntries
                    )
                )
            }
        }

        return results
    }
}
