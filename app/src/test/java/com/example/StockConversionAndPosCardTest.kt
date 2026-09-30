package com.example

import com.example.data.local.entities.Product
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StockConversionAndPosCardTest {

    @Test
    fun testBulkPackTinConversionCalculatesRealBulkRatio() {
        val oilTinProduct = Product(
            id = "prod_mustard_oil_tin",
            nameEn = "Mustard Oil",
            nameBn = "সর্ষের তেল",
            category = "Oil",
            costPrice = 1800.0,
            sellingPrice = 2100.0,
            unitType = "litre",
            bulkUnitType = "tin",
            bulkQuantity = 15.0,
            bulkPrice = 2050.0,
            currentStock = 45.5,
            lowStockThreshold = 15.0
        )

        val stockDisplayEn = oilTinProduct.getFormattedStockDisplay(isBn = false)
        assertEquals("45.5 litre (≈ 3 tins + 0.5 litre)", stockDisplayEn)
        assertFalse("Must not say boxes", stockDisplayEn.contains("box", ignoreCase = true))
        assertFalse("Must not round 45.5 up to 46", stockDisplayEn.contains("46"))

        val stockDisplayBn = oilTinProduct.getFormattedStockDisplay(isBn = true)
        assertTrue("Bengali display contains tin translation", stockDisplayBn.contains("টিন"))
        assertTrue("Bengali display contains 3 tins", stockDisplayBn.contains("3 টিন"))
        assertFalse("Bengali display must not say 46", stockDisplayBn.contains("46"))

        // Exact multiple test (e.g. 45 litres = exactly 3 tins)
        val exactTinProduct = oilTinProduct.copy(currentStock = 45.0)
        assertEquals("45 litre (≈ 3 tins)", exactTinProduct.getFormattedStockDisplay(isBn = false))
    }

    @Test
    fun testPieceBoxConversionBreakdown() {
        val biscuitProduct = Product(
            id = "prod_biscuit_box",
            nameEn = "Parle-G",
            nameBn = "পারলে-জি",
            category = "Biscuits",
            costPrice = 80.0,
            sellingPrice = 100.0,
            unitType = "pcs",
            secondaryUnitType = "box",
            piecesPerBox = 12,
            boxPrice = 95.0,
            currentStock = 45.0,
            lowStockThreshold = 12.0
        )

        val stockDisplay = biscuitProduct.getFormattedStockDisplay(isBn = false)
        assertEquals("45 pcs (≈ 3 boxes + 9 pcs)", stockDisplay)
    }

    @Test
    fun testStandardProductWithoutBulkPackagingHasNoBoxBreakdown() {
        val riceProduct = Product(
            id = "prod_rice_loose",
            nameEn = "Minikit Rice",
            nameBn = "মিনিকেট চাল",
            category = "Grains",
            costPrice = 40.0,
            sellingPrice = 45.0,
            unitType = "kg",
            currentStock = 25.5,
            lowStockThreshold = 5.0
        )

        val stockDisplay = riceProduct.getFormattedStockDisplay(isBn = false)
        assertEquals("25.5 kg", stockDisplay)
        assertFalse(stockDisplay.contains("≈"))
        assertFalse(stockDisplay.contains("box"))
    }
}
