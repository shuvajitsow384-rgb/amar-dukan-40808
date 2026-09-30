package com.example

import com.example.data.local.entities.BarcodeVariant
import com.example.data.local.entities.Product
import com.example.data.local.entities.SaleItem
import com.example.utils.SaleConsolidationUtils
import com.example.viewmodel.CartItem
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BarcodeVariantTest {

    @Test
    fun testBarcodeVariantSerialization() {
        val variant1 = BarcodeVariant(
            barcode = "8901234567890",
            unitType = "gram",
            quantity = 500.0,
            price = 45.0,
            label = "500g Pouch"
        )
        val variant2 = BarcodeVariant(
            barcode = "8909876543210",
            unitType = "kg",
            quantity = 1.0,
            price = 85.0,
            label = "1kg Family Pack"
        )

        val json = BarcodeVariant.listToJson(listOf(variant1, variant2))
        val parsed = BarcodeVariant.parseListFromJson(json)

        assertEquals(2, parsed.size)
        assertEquals("8901234567890", parsed[0].barcode)
        assertEquals("gram", parsed[0].unitType)
        assertEquals(500.0, parsed[0].quantity, 0.001)
        assertEquals(45.0, parsed[0].price, 0.001)
        assertEquals("500g Pouch", parsed[0].label)

        assertEquals("8909876543210", parsed[1].barcode)
        assertEquals("kg", parsed[1].unitType)
        assertEquals(1.0, parsed[1].quantity, 0.001)
        assertEquals(85.0, parsed[1].price, 0.001)
        assertEquals("1kg Family Pack", parsed[1].label)
    }

    @Test
    fun testVariantConversionToBaseStock() {
        val productKg = Product(
            id = "prod_rice",
            nameEn = "Basmati Rice",
            nameBn = "বাসমতি চাল",
            category = "Grains",
            unitType = "kg",
            costPrice = 60.0,
            sellingPrice = 80.0,
            currentStock = 10.0,
            secondaryUnitType = "gram",
            secondaryUnitRatio = 1000.0
        )

        // 500 grams should convert to 0.5 kg of base stock
        val baseDeducted500g = productKg.convertQuantityToBaseUnit(500.0, "gram")
        assertEquals(0.5, baseDeducted500g, 0.001)

        // 1 kg should convert to 1.0 kg of base stock
        val baseDeducted1kg = productKg.convertQuantityToBaseUnit(1.0, "kg")
        assertEquals(1.0, baseDeducted1kg, 0.001)

        // Box to piece conversion
        val productBox = Product(
            id = "prod_biscuit",
            nameEn = "Marie Gold",
            nameBn = "ম্যারি গোল্ড",
            category = "Snacks",
            unitType = "box",
            costPrice = 200.0,
            sellingPrice = 10.0, // per piece
            currentStock = 5.0, // 5 boxes
            secondaryUnitType = "piece",
            secondaryUnitRatio = 24.0 // 24 pieces per box
        )

        // 1 piece should convert to 1/24 boxes
        val baseDeducted1Piece = productBox.convertQuantityToBaseUnit(1.0, "piece")
        assertEquals(1.0 / 24.0, baseDeducted1Piece, 0.0001)

        // 1 box should convert to 1.0 box
        val baseDeducted1Box = productBox.convertQuantityToBaseUnit(1.0, "box")
        assertEquals(1.0, baseDeducted1Box, 0.0001)
    }

    @Test
    fun testCartItemWithVariantDeductionsAndPricing() {
        val product = Product(
            id = "prod_oil",
            nameEn = "Sunflower Oil",
            nameBn = "সূর্যমুখী তেল",
            category = "Oil",
            unitType = "litre",
            costPrice = 110.0,
            sellingPrice = 140.0,
            currentStock = 2.0, // 2 litres
            secondaryUnitType = "ml",
            secondaryUnitRatio = 1000.0
        )

        val variant500ml = BarcodeVariant(
            barcode = "8905555555555",
            unitType = "ml",
            quantity = 500.0,
            price = 75.0,
            label = "500ml Pouch"
        )

        val cartItem = CartItem(
            product = product,
            quantity = 3.0, // 3 pouches of 500ml = 1.5 litres total
            unitPrice = variant500ml.price,
            unitType = variant500ml.unitType,
            variantBarcode = variant500ml.barcode,
            variantLabel = variant500ml.label,
            variantQuantity = variant500ml.quantity,
            variantUnitType = variant500ml.unitType
        )

        // Line item total should be 3 * 75 = 225.0
        assertEquals(225.0, cartItem.getSubtotal(), 0.001)

        // Base stock deducted should be 3 * 0.5 = 1.5 litres
        assertEquals(1.5, cartItem.getBaseQuantityDeducted(), 0.001)

        // Display name should clearly show the variant label
        assertEquals("Sunflower Oil (500ml Pouch)", cartItem.getDisplayName())
    }

    @Test
    fun testSaleConsolidationPreservesDistinctVariants() {
        val product = Product(
            id = "prod_atta",
            nameEn = "Whole Wheat Atta",
            nameBn = "আটা",
            category = "Flour",
            unitType = "kg",
            costPrice = 30.0,
            sellingPrice = 40.0,
            currentStock = 50.0
        )

        val saleItems = listOf(
            SaleItem(
                id = 1L,
                saleId = "sale_1",
                productId = product.id,
                productNameEn = product.nameEn,
                productNameBn = product.nameBn,
                unitType = "kg",
                quantity = 2.0,
                unitPrice = 40.0,
                costPrice = 30.0,
                subtotal = 80.0,
                totalCost = 60.0
            ),
            SaleItem(
                id = 2L,
                saleId = "sale_1",
                productId = product.id,
                productNameEn = "Whole Wheat Atta (5kg Bag)",
                productNameBn = "আটা (5kg Bag)",
                unitType = "kg",
                quantity = 1.0,
                unitPrice = 195.0,
                costPrice = 150.0,
                subtotal = 195.0,
                totalCost = 150.0
            ),
            SaleItem(
                id = 3L,
                saleId = "sale_1",
                productId = product.id,
                productNameEn = "Whole Wheat Atta (10kg Sack)",
                productNameBn = "আটা (10kg Sack)",
                unitType = "kg",
                quantity = 2.0,
                unitPrice = 380.0,
                costPrice = 300.0,
                subtotal = 760.0,
                totalCost = 600.0
            )
        )

        val consolidated = SaleConsolidationUtils.consolidateSaleItems(saleItems)

        // Should keep all 3 lines distinct because of different display names / variants
        assertEquals(3, consolidated.size)
    }
}
