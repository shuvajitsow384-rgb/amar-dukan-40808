package com.example

import com.example.data.local.dao.SaleWithItems
import com.example.data.local.entities.ReturnItem
import com.example.data.local.entities.Sale
import com.example.data.local.entities.SaleItem
import com.example.data.local.entities.SaleReturn
import com.example.data.local.entities.SaleReturnWithItems
import com.example.ui.screens.pos.CategoryProfitSummary
import com.example.utils.DailySummaryReportHelper
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DailySalesSummaryTest {

    @Test
    fun testEmptySalesSummaryGeneration() {
        val summary = DailySummaryReportHelper.generateDailySummaryText(
            storeName = "Test Store",
            periodLabel = "Today",
            sales = emptyList(),
            totalRevenue = 0.0,
            grossProfit = 0.0,
            totalExpenses = 0.0,
            netProfit = 0.0,
            overallGrossMarginPct = 0.0,
            cashSales = 0.0,
            upiSales = 0.0,
            creditSales = 0.0,
            totalItemsCount = 0.0,
            averageBillValue = 0.0,
            categorySummaries = emptyList(),
            returns = emptyList()
        )

        assertNotNull(summary)
        assertTrue(summary.contains("0 Bills"))
        assertTrue(summary.contains("Total Revenue: ₹0.00"))
        assertTrue(summary.contains("Test Store"))
    }

    @Test
    fun testFewSalesSummaryGeneration() {
        val now = System.currentTimeMillis()
        val sales = listOf(
            SaleWithItems(
                sale = Sale(
                    id = "1",
                    datetime = now,
                    totalAmount = 250.0,
                    finalAmount = 250.0,
                    paymentMode = "CASH",
                    customerName = "Rahim"
                ),
                items = listOf(
                    SaleItem(
                        id = 1L,
                        saleId = "1",
                        productId = "101",
                        productNameEn = "Chips",
                        productNameBn = "চিপস",
                        unitPrice = 50.0,
                        costPrice = 35.0,
                        quantity = 5.0,
                        subtotal = 250.0,
                        totalCost = 175.0,
                        unitType = "piece"
                    )
                )
            ),
            SaleWithItems(
                sale = Sale(
                    id = "2",
                    datetime = now + 1000,
                    totalAmount = 500.0,
                    finalAmount = 500.0,
                    paymentMode = "UPI",
                    customerName = "Karim"
                ),
                items = listOf(
                    SaleItem(
                        id = 2L,
                        saleId = "2",
                        productId = "102",
                        productNameEn = "Cooking Oil 1L",
                        productNameBn = "তেল ১ লিটার",
                        unitPrice = 180.0,
                        costPrice = 150.0,
                        quantity = 2.0,
                        subtotal = 360.0,
                        totalCost = 300.0,
                        unitType = "piece"
                    )
                )
            )
        )

        val categories = listOf(
            CategoryProfitSummary(
                categoryName = "Snacks & 100% Organic",
                totalRevenue = 250.0,
                totalCost = 175.0,
                totalProfit = 75.0,
                profitMarginPct = 30.0,
                itemsCount = 5.0,
                revenueSharePct = 33.3
            ),
            CategoryProfitSummary(
                categoryName = "Grocery",
                totalRevenue = 500.0,
                totalCost = 300.0,
                totalProfit = 200.0,
                profitMarginPct = 40.0,
                itemsCount = 2.0,
                revenueSharePct = 66.7
            )
        )

        val summary = DailySummaryReportHelper.generateDailySummaryText(
            storeName = "Special 10% Off Mart",
            periodLabel = "Today",
            sales = sales,
            totalRevenue = 750.0,
            grossProfit = 275.0,
            totalExpenses = 50.0,
            netProfit = 225.0,
            overallGrossMarginPct = 36.67,
            cashSales = 250.0,
            upiSales = 500.0,
            creditSales = 0.0,
            totalItemsCount = 7.0,
            averageBillValue = 375.0,
            categorySummaries = categories,
            returns = emptyList()
        )

        assertNotNull(summary)
        assertTrue(summary.contains("2 Bills"))
        assertTrue(summary.contains("Special 10% Off Mart"))
        assertTrue(summary.contains("Snacks & 100% Organic"))
        assertTrue(summary.contains("30.0% Margin"))
        assertTrue(summary.contains("Rahim"))
        assertTrue(summary.contains("Karim"))
    }

    @Test
    fun testManySalesSummaryGenerationWith15BillsAndSpecialCharacters() {
        val now = System.currentTimeMillis()
        val sales = (1..15).map { i ->
            val isEven = i % 2 == 0
            SaleWithItems(
                sale = Sale(
                    id = "$i",
                    datetime = now + (i * 60000),
                    totalAmount = (i * 100).toDouble(),
                    finalAmount = (i * 100).toDouble(),
                    paymentMode = if (isEven) "UPI" else "CASH",
                    customerName = if (i % 3 == 0) null else "Customer #$i (100% VIP)"
                ),
                items = listOf(
                    SaleItem(
                        id = i.toLong(),
                        saleId = "$i",
                        productId = "${100 + i}",
                        productNameEn = "Product $i",
                        productNameBn = "পণ্য $i",
                        unitPrice = 50.0,
                        costPrice = 35.0,
                        quantity = 2.0,
                        subtotal = 100.0,
                        totalCost = 70.0,
                        unitType = "piece"
                    )
                )
            )
        }

        val categories = (1..8).map { catIdx ->
            CategoryProfitSummary(
                categoryName = "Category $catIdx (50% Promo)",
                totalRevenue = (catIdx * 1000).toDouble(),
                totalCost = (catIdx * 700).toDouble(),
                totalProfit = (catIdx * 300).toDouble(),
                profitMarginPct = 30.0,
                itemsCount = 20.0,
                revenueSharePct = 12.5
            )
        }

        val returns = listOf(
            SaleReturnWithItems(
                saleReturn = SaleReturn(
                    id = "RET-1",
                    saleId = "1",
                    datetime = now,
                    totalReturnedAmount = 100.0,
                    totalReplacementAmount = 0.0,
                    netAmount = 100.0,
                    refundPaymentMode = "CASH"
                ),
                items = listOf(
                    ReturnItem(
                        id = 1L,
                        returnId = "RET-1",
                        productId = "101",
                        productNameEn = "Product 1",
                        productNameBn = "পণ্য ১",
                        quantity = 2.0,
                        unitPrice = 50.0,
                        subtotal = 100.0,
                        isReplacement = false,
                        unitType = "piece"
                    )
                )
            )
        )

        val summary = DailySummaryReportHelper.generateDailySummaryText(
            storeName = "Mega Store & 20% Discount Zone",
            periodLabel = "Yesterday",
            sales = sales,
            totalRevenue = 12000.0,
            grossProfit = 3600.0,
            totalExpenses = 400.0,
            netProfit = 3200.0,
            overallGrossMarginPct = 30.0,
            cashSales = 6000.0,
            upiSales = 6000.0,
            creditSales = 0.0,
            totalItemsCount = 30.0,
            averageBillValue = 800.0,
            categorySummaries = categories,
            returns = returns
        )

        assertNotNull(summary)
        assertTrue(summary.contains("15 Bills"))
        assertTrue(summary.contains("Mega Store & 20% Discount Zone"))
        assertTrue(summary.contains("Walk-in")) // Handled null customer name
        assertTrue(summary.contains("more bills")) // safely truncated to keep lightweight
        assertTrue(summary.contains("RETURNS / REPLACEMENTS"))
        assertFalse(summary.isEmpty())
    }
}
