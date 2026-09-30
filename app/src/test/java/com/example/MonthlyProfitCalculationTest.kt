package com.example

import com.example.data.local.entities.EmployeeSalaryPayment
import com.example.data.local.entities.Expense
import com.example.data.local.entities.StockOutEntry
import org.junit.Assert.assertEquals
import org.junit.Test

class MonthlyProfitCalculationTest {

    @Test
    fun testSalaryExpenseDeduplicationAndStockLossLogic() {
        // Sample test data mimicking the scenario in the screenshots:
        // Revenue = 10,296.06, COGS = 8,084.25 -> Gross Profit = 2,211.81
        // Operational Expense = 80.0
        // Salary Payment = 10,000.0 (which was auto-recorded into Expense table as category="Salary", amount=10000.0)
        // Stock Loss = 200.0

        val grossRevenue = 10296.06
        val totalCogs = 8084.25
        val grossProfit = grossRevenue - totalCogs

        val expensesList = listOf(
            Expense(id = "e1", date = System.currentTimeMillis(), category = "Shop", amount = 80.0, note = "Utility"),
            Expense(id = "e2", date = System.currentTimeMillis(), category = "Salary", amount = 10000.0, note = "Staff Payroll")
        )

        val salaryPaymentsList = listOf(
            EmployeeSalaryPayment(
                id = "p1",
                employeeId = "emp1",
                employeeName = "John",
                monthYear = "August 2026",
                paymentDate = System.currentTimeMillis(),
                baseSalary = 10000.0,
                netSalaryPaid = 10000.0
            )
        )

        val stockOutsList = listOf(
            StockOutEntry(
                id = "so1",
                productId = "prod1",
                productNameEn = "Milk",
                quantity = 4.0,
                unitType = "pcs",
                costPrice = 50.0,
                totalCostValue = 200.0,
                reason = "Expired / মেয়াদ উত্তীর্ণ",
                timestamp = System.currentTimeMillis()
            )
        )

        // Calculation logic as updated in MonthlyProfitTrendWidget
        val filteredExpenses = expensesList.filterNot {
            it.category.contains("Stock Loss", ignoreCase = true) || it.category.contains("Wastage", ignoreCase = true)
        }

        val salaryExpensesInTable = filteredExpenses
            .filter { it.category.equals("Salary", ignoreCase = true) }
            .sumOf { it.amount }
        val nonSalaryOpsExpenses = filteredExpenses
            .filterNot { it.category.equals("Salary", ignoreCase = true) }
            .sumOf { it.amount }

        val totalSalariesPaid = salaryPaymentsList.sumOf { it.netSalaryPaid }
        val effectiveSalaryExpense = maxOf(salaryExpensesInTable, totalSalariesPaid)
        val totalExpenseOutflow = nonSalaryOpsExpenses + effectiveSalaryExpense

        val monthStockLoss = stockOutsList.filter { it.isBusinessLoss() }.sumOf { it.totalCostValue }

        val netProfit = grossRevenue - totalCogs - totalExpenseOutflow - monthStockLoss

        // Net Profit must equal 2211.81 - 10080.00 - 200.00 = -8068.19
        assertEquals(80.0, nonSalaryOpsExpenses, 0.001)
        assertEquals(10000.0, effectiveSalaryExpense, 0.001)
        assertEquals(10080.0, totalExpenseOutflow, 0.001)
        assertEquals(200.0, monthStockLoss, 0.001)
        assertEquals(-8068.19, netProfit, 0.001)
    }
}
