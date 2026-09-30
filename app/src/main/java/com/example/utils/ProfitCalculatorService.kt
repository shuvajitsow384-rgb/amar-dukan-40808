package com.example.utils

import com.example.data.local.dao.SaleWithItems
import com.example.data.local.entities.Expense
import com.example.data.local.entities.SaleReturnWithItems

/**
 * Data structure representing the complete calculated profit summary.
 */
data class ProfitCalculationResult(
    val salesRevenue: Double,
    val costOfGoodsSold: Double,
    val grossProfit: Double,
    val totalExpenses: Double,
    val stockLoss: Double = 0.0,
    val netProfit: Double,
    val profitMarginPercent: Double,
    val isProfitable: Boolean
)

/**
 * Helper service to calculate net profit by subtracting total expenses, stock loss (damaged/expired/wastage),
 * and COGS (Cost of Goods Sold) from sales revenue:
 * Net Profit = Sales Revenue - COGS - Total Expenses - Stock Loss
 */
object ProfitCalculatorService {

    /**
     * Calculates net profit given raw values of daily sales revenue, COGS, total expenses, and stock loss.
     * Formula: Net Profit = Daily Sales Revenue - COGS - Total Expenses - Stock Loss
     *
     * @param salesRevenue Total daily sales revenue
     * @param cogs Total Cost of Goods Sold (purchase cost of items sold)
     * @param totalExpenses Total operational & daily expenses incurred
     * @param stockLoss Total genuine business stock loss (Damaged + Expired + Wastage cost)
     * @return [ProfitCalculationResult] with gross profit, net profit, margin %, and profitability status.
     */
    fun calculateNetProfit(
        salesRevenue: Double,
        cogs: Double,
        totalExpenses: Double,
        stockLoss: Double = 0.0
    ): ProfitCalculationResult {
        val safeRevenue = salesRevenue.coerceAtLeast(0.0)
        val safeCogs = cogs.coerceAtLeast(0.0)
        val safeExpenses = totalExpenses.coerceAtLeast(0.0)
        val safeStockLoss = stockLoss.coerceAtLeast(0.0)

        val grossProfit = safeRevenue - safeCogs
        val netProfit = grossProfit - safeExpenses - safeStockLoss
        val marginPercent = if (safeRevenue > 0.0) {
            (netProfit / safeRevenue) * 100.0
        } else {
            0.0
        }

        return ProfitCalculationResult(
            salesRevenue = safeRevenue,
            costOfGoodsSold = safeCogs,
            grossProfit = grossProfit,
            totalExpenses = safeExpenses,
            stockLoss = safeStockLoss,
            netProfit = netProfit,
            profitMarginPercent = marginPercent,
            isProfitable = netProfit >= 0.0
        )
    }

    /**
     * Calculates daily net profit from lists of daily sales, expenses, and returns.
     *
     * @param sales List of daily sales with their respective items
     * @param expenses List of daily expense records
     * @param returns Optional list of daily sale returns/replacements
     * @param getProductCost Optional lambda to lookup product cost for returns/replacements
     */
    fun calculateDailyProfitFromRecords(
        sales: List<SaleWithItems>,
        expenses: List<Expense>,
        returns: List<SaleReturnWithItems> = emptyList(),
        getProductCost: ((productId: String) -> Double)? = null
    ): ProfitCalculationResult {
        var salesRevenue = 0.0
        var totalCogs = 0.0

        for (saleWithItems in sales) {
            salesRevenue += saleWithItems.sale.finalAmount
            val consolidatedItems = saleWithItems.consolidatedItems
            for (item in consolidatedItems) {
                totalCogs += item.totalCost
            }
        }

        // Adjust for any sales returns or item replacements during the day
        for (ret in returns) {
            val sr = ret.saleReturn
            salesRevenue -= sr.totalReturnedAmount
            salesRevenue += sr.totalReplacementAmount

            for (rItem in ret.items) {
                val costPrice = getProductCost?.invoke(rItem.productId) ?: (rItem.unitPrice * 0.7)
                val itemCost = costPrice * rItem.quantity
                if (rItem.isReplacement) {
                    totalCogs += itemCost
                } else {
                    totalCogs -= itemCost
                }
            }
        }

        val totalExpenses = expenses.sumOf { it.amount }

        return calculateNetProfit(
            salesRevenue = salesRevenue,
            cogs = totalCogs,
            totalExpenses = totalExpenses
        )
    }
}
