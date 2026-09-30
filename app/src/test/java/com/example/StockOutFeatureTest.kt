package com.example

import com.example.data.local.entities.Product
import com.example.data.local.entities.ProductBatch
import com.example.data.local.entities.StockOutEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StockOutFeatureTest {

    @Test
    fun testStockOutEntryClassification_damagedIsBusinessLoss() {
        val entry = StockOutEntry(
            productId = "P1",
            productNameEn = "5 Star Kali Mehandi",
            quantity = 2.0,
            unitType = "piece",
            costPrice = 7.0,
            totalCostValue = 14.0,
            reason = "Damaged / ক্ষতিগ্রস্ত",
            note = "Broken sachet"
        )

        assertTrue("Damaged stock must be classified as business loss", entry.isBusinessLoss())
        assertFalse("Damaged stock is not personal use", entry.isPersonalUse())
        assertEquals(StockOutEntry.ReasonCategory.DAMAGED, entry.getReasonCategory())
    }

    @Test
    fun testStockOutEntryClassification_expiredIsBusinessLoss() {
        val entry = StockOutEntry(
            productId = "P2",
            productNameEn = "Fresh Milk 500ml",
            quantity = 5.0,
            unitType = "packet",
            costPrice = 28.0,
            totalCostValue = 140.0,
            reason = "Expired / মেয়াদ উত্তীর্ণ"
        )

        assertTrue("Expired stock must be business loss", entry.isBusinessLoss())
        assertFalse("Expired stock is not personal use", entry.isPersonalUse())
        assertEquals(StockOutEntry.ReasonCategory.EXPIRED, entry.getReasonCategory())
    }

    @Test
    fun testStockOutEntryClassification_personalUseIsNotBusinessLoss() {
        val entry = StockOutEntry(
            productId = "P3",
            productNameEn = "Cooking Oil 1L",
            quantity = 1.0,
            unitType = "bottle",
            costPrice = 145.0,
            totalCostValue = 145.0,
            reason = "Personal Use / নিজস্ব ব্যবহার",
            note = "Owner home use"
        )

        assertFalse("Personal use must not be classified as a business loss", entry.isBusinessLoss())
        assertTrue("Personal use must be identified as owner's draw", entry.isPersonalUse())
        assertEquals(StockOutEntry.ReasonCategory.PERSONAL_USE, entry.getReasonCategory())
    }

    @Test
    fun testStockOutUnitConversion_boxToPieces() {
        val product = Product(
            id = "P101",
            nameEn = "5 Star Kali Mehandi",
            nameBn = "৫ স্টার কালি মেহেন্দি",
            category = "Cosmetics",
            unitType = "piece",
            costPrice = 7.0,
            sellingPrice = 10.0,
            currentStock = 14.0,
            piecesPerBox = 12
        )

        val ppb = product.getPiecesPerBoxRatio()
        assertEquals(12, ppb)

        // Removing 1 box = 12 pieces
        val boxQty = 1.0
        val baseQty = boxQty * ppb
        assertEquals(12.0, baseQty, 0.001)

        val remainingStock = product.currentStock - baseQty
        assertEquals(2.0, remainingStock, 0.001)

        val totalCost = baseQty * product.costPrice
        assertEquals(84.0, totalCost, 0.001)
    }

    @Test
    fun testStockOutUnitConversion_kgToGrams() {
        val product = Product(
            id = "P102",
            nameEn = "Basmati Rice",
            nameBn = "বাসমতী চাল",
            category = "Grains",
            unitType = "kg",
            costPrice = 90.0,
            sellingPrice = 120.0,
            currentStock = 25.0
        )

        // Removing 500 grams = 0.5 kg
        val gramQty = 500.0
        val ratioToBase = 0.001
        val baseQty = gramQty * ratioToBase
        assertEquals(0.5, baseQty, 0.001)

        val remainingStock = product.currentStock - baseQty
        assertEquals(24.5, remainingStock, 0.001)

        val totalCost = baseQty * product.costPrice
        assertEquals(45.0, totalCost, 0.001)
    }

    @Test
    fun testStockOutBatchFifoReduction() {
        val batches = listOf(
            ProductBatch(id = "B1", productId = "P201", batchNumber = "LOT-1", quantity = 5.0),
            ProductBatch(id = "B2", productId = "P201", batchNumber = "LOT-2", quantity = 10.0)
        )

        var remainingToDeduct = 7.0
        val updatedBatches = mutableListOf<ProductBatch>()

        for (batch in batches) {
            if (remainingToDeduct <= 0) {
                updatedBatches.add(batch)
                continue
            }
            if (batch.quantity <= remainingToDeduct) {
                remainingToDeduct -= batch.quantity
                updatedBatches.add(batch.copy(quantity = 0.0))
            } else {
                val newQty = Product.roundQuantity(batch.quantity - remainingToDeduct)
                remainingToDeduct = 0.0
                updatedBatches.add(batch.copy(quantity = newQty))
            }
        }

        assertEquals(0.0, updatedBatches[0].quantity, 0.001)
        assertEquals(8.0, updatedBatches[1].quantity, 0.001)
        assertEquals(0.0, remainingToDeduct, 0.001)
    }
}
