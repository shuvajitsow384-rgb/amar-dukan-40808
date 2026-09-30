package com.example.utils

import com.example.data.local.entities.Customer
import com.example.data.local.entities.LedgerEntry
import com.example.data.local.entities.Supplier

object LedgerCalculator {

    /**
     * Calculates the exact live customer credit balance by replaying the FULL ledger history.
     * Positive balance (> 0) means the customer owes money (Due/Baki).
     * Zero or negative balance (<= 0) means the customer is settled or has advance credit.
     */
    fun calculateCustomerBalance(customerId: String, ledgerEntries: List<LedgerEntry>): Double {
        val entries = ledgerEntries.filter { 
            it.partyId == customerId && (it.partyType.equals("CUSTOMER", ignoreCase = true) || it.partyType.isBlank()) 
        }
        var balance = 0.0
        for (entry in entries) {
            val type = entry.type.uppercase()
            when {
                type in listOf("SALE_CREDIT", "CREDIT_GIVEN", "CREDIT", "REPLACEMENT_DUE", "DUE", "OPENING_BALANCE", "INITIAL_DUE", "OPENING_DUE", "OPENING_CREDIT", "INTEREST", "INTEREST_ACCRUED", "INTEREST_CHARGED") -> {
                    balance += entry.amount
                }
                type in listOf("PAYMENT_RECEIVED", "PAYMENT", "RETURN_REFUND", "CREDIT_REDUCED", "REFUND") -> {
                    balance -= entry.amount
                }
                entry.note?.contains("credit", ignoreCase = true) == true || 
                entry.note?.contains("due", ignoreCase = true) == true ||
                entry.note?.contains("opening", ignoreCase = true) == true -> {
                    balance += entry.amount
                }
                else -> {
                    balance -= entry.amount
                }
            }
        }
        return balance
    }

    /**
     * Calculates the exact live supplier payable balance by replaying the FULL ledger history.
     * Positive balance (> 0) means the store owes money to the supplier (Payable).
     * Zero or negative balance (<= 0) means settled.
     */
    fun calculateSupplierBalance(supplierId: String, ledgerEntries: List<LedgerEntry>): Double {
        val entries = ledgerEntries.filter { 
            it.partyId == supplierId && (it.partyType.equals("SUPPLIER", ignoreCase = true) || it.partyType.isBlank()) 
        }
        var balance = 0.0
        for (entry in entries) {
            val type = entry.type.uppercase()
            when {
                type in listOf("PURCHASE_CREDIT", "CREDIT_TAKEN", "PURCHASE_DUE", "DUE", "OPENING_BALANCE", "INITIAL_DUE", "OPENING_DUE", "OPENING_CREDIT") -> {
                    balance += entry.amount
                }
                type in listOf("PAYMENT_MADE", "PAYMENT", "PURCHASE_EXTRA_PAID") -> {
                    balance -= entry.amount
                }
                entry.note?.contains("purchase", ignoreCase = true) == true || 
                entry.note?.contains("due", ignoreCase = true) == true ||
                entry.note?.contains("opening", ignoreCase = true) == true -> {
                    balance += entry.amount
                }
                else -> {
                    balance -= entry.amount
                }
            }
        }
        return balance
    }

    /**
     * Returns a customer copy with its balance recalculated from the full ledger history.
     */
    fun reconcileCustomer(customer: Customer, ledgerEntries: List<LedgerEntry>): Customer {
        val trueBalance = calculateCustomerBalance(customer.id, ledgerEntries)
        return customer.copy(balance = trueBalance)
    }

    /**
     * Returns a supplier copy with its balance recalculated from the full ledger history.
     */
    fun reconcileSupplier(supplier: Supplier, ledgerEntries: List<LedgerEntry>): Supplier {
        val trueBalance = calculateSupplierBalance(supplier.id, ledgerEntries)
        return supplier.copy(balance = trueBalance)
    }
}
