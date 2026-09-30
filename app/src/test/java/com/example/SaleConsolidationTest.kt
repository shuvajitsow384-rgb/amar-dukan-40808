package com.example

import com.example.data.local.entities.Product
import com.example.data.local.entities.Sale
import com.example.data.local.entities.SaleItem
import com.example.data.local.dao.SaleWithItems
import com.example.utils.SaleConsolidationUtils
import com.example.utils.ProfitCalculatorService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SaleConsolidationTest {

    @Test
    fun testConsolidationOfDuplicateProductLines() {
        val testProduct = Product(
            id = "prod_rice_1",
            nameEn = "Basmati Rice 1kg",
            nameBn = "বাসমতী চাল ১ কেজি",
            category = "Grains",
            unitType = "kg",
            costPrice = 60.0,
            sellingPrice = 80.0,
            currentStock = 100.0
        )

        // Simulate two duplicate sale item rows for the same product in a single bill
        val duplicateLine1 = SaleItem(
            saleId = "bill_101",
            productId = testProduct.id,
            productNameEn = testProduct.nameEn,
            productNameBn = testProduct.nameBn,
            unitType = "kg",
            quantity = 2.0,
            unitPrice = 80.0,
            costPrice = 60.0,
            subtotal = 160.0,
            totalCost = 120.0
        )

        val duplicateLine2 = SaleItem(
            saleId = "bill_101",
            productId = testProduct.id,
            productNameEn = testProduct.nameEn,
            productNameBn = testProduct.nameBn,
            unitType = "kg",
            quantity = 3.0,
            unitPrice = 80.0,
            costPrice = 60.0,
            subtotal = 240.0,
            totalCost = 180.0
        )

        val rawItems = listOf(duplicateLine1, duplicateLine2)
        val consolidated = SaleConsolidationUtils.consolidateSaleItems(rawItems) { id ->
            if (id == testProduct.id) testProduct else null
        }

        // Must be consolidated to exactly 1 line item
        assertEquals(1, consolidated.size)
        val mergedItem = consolidated.first()
        assertEquals(testProduct.id, mergedItem.productId)
        assertEquals(5.0, mergedItem.quantity, 0.0001) // 2 + 3 = 5 kg
        assertEquals(400.0, mergedItem.subtotal, 0.0001) // 160 + 240 = 400
        assertEquals(300.0, mergedItem.totalCost, 0.0001) // 5 kg * 60 = 300 (not double counted)
    }

    @Test
    fun testAuditDetectsDuplicateBills() {
        val testProduct = Product(
            id = "prod_oil_1",
            nameEn = "Mustard Oil 1L",
            nameBn = "সরিষার তেল ১ লিটার",
            category = "Oil",
            unitType = "litre",
            costPrice = 120.0,
            sellingPrice = 150.0,
            currentStock = 50.0
        )

        val sale = Sale(
            id = "sale_dup_001",
            datetime = 1700000000000L,
            totalAmount = 300.0,
            discount = 0.0,
            finalAmount = 300.0,
            paymentMode = "CASH",
            customerId = null,
            customerName = "Walk-in",
            isHeld = false
        )

        val item1 = SaleItem(
            saleId = sale.id,
            productId = testProduct.id,
            productNameEn = testProduct.nameEn,
            productNameBn = testProduct.nameBn,
            unitType = "litre",
            quantity = 1.0,
            unitPrice = 150.0,
            costPrice = 120.0,
            subtotal = 150.0,
            totalCost = 120.0
        )

        val item2 = SaleItem(
            saleId = sale.id,
            productId = testProduct.id,
            productNameEn = testProduct.nameEn,
            productNameBn = testProduct.nameBn,
            unitType = "litre",
            quantity = 1.0,
            unitPrice = 150.0,
            costPrice = 120.0,
            subtotal = 150.0,
            totalCost = 120.0
        )

        val saleWithItems = SaleWithItems(sale, listOf(item1, item2))
        val auditResults = SaleConsolidationUtils.auditSalesForDuplicates(listOf(saleWithItems)) { id ->
            if (id == testProduct.id) testProduct else null
        }

        assertEquals(1, auditResults.size)
        val audit = auditResults.first()
        assertEquals("sale_dup_001", audit.saleId)
        assertEquals(1, audit.duplicateEntries.size)
        val dupEntry = audit.duplicateEntries.first()
        assertEquals("prod_oil_1", dupEntry.productId)
        assertEquals(2, dupEntry.lineCount)
        assertEquals(120.0, dupEntry.totalExcessCost, 0.001)
    }

    @Test
    fun testProfitCalculatorResilientToDuplicateLines() {
        val sale = Sale(
            id = "sale_profit_test",
            datetime = 1700000000000L,
            totalAmount = 200.0,
            discount = 0.0,
            finalAmount = 200.0,
            paymentMode = "CASH",
            customerId = null,
            customerName = "Customer A",
            isHeld = false
        )

        // Raw items with duplicated product line
        val item1 = SaleItem(
            saleId = sale.id,
            productId = "prod_soap",
            productNameEn = "Soap",
            productNameBn = "সাবান",
            unitType = "piece",
            quantity = 2.0,
            unitPrice = 50.0,
            costPrice = 30.0,
            subtotal = 100.0,
            totalCost = 60.0
        )

        val item2 = SaleItem(
            saleId = sale.id,
            productId = "prod_soap",
            productNameEn = "Soap",
            productNameBn = "সাবান",
            unitType = "piece",
            quantity = 2.0,
            unitPrice = 50.0,
            costPrice = 30.0,
            subtotal = 100.0,
            totalCost = 60.0
        )

        val saleWithItems = SaleWithItems(sale, listOf(item1, item2))
        val profitResult = ProfitCalculatorService.calculateDailyProfitFromRecords(
            sales = listOf(saleWithItems),
            expenses = emptyList()
        )

        // Revenue = 200, COGS = 120 (4 * 30), Profit = 80
        assertEquals(200.0, profitResult.salesRevenue, 0.001)
        assertEquals(120.0, profitResult.costOfGoodsSold, 0.001)
        assertEquals(80.0, profitResult.netProfit, 0.001)
    }
}
