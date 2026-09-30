package com.example.data.models

/**
 * Represents an item in the cart or sale that has insufficient stock.
 */
data class InsufficientStockItem(
    val productId: String,
    val productNameEn: String,
    val productNameBn: String = "",
    val unitType: String,
    val availableStock: Double,
    val requestedQuantity: Double,
    val shortageQuantity: Double = (requestedQuantity - availableStock).coerceAtLeast(0.0)
) {
    companion object {
        fun formatQuantity(value: Double): String {
            val rounded = Math.round(value * 1000.0) / 1000.0
            return if (rounded % 1.0 == 0.0) {
                "%.0f".format(rounded)
            } else {
                "%.3f".format(rounded).trimEnd('0').trimEnd('.')
            }
        }
    }

    fun formatAvailable(): String = formatQuantity(availableStock)
    fun formatRequested(): String = formatQuantity(requestedQuantity)
    fun formatShortage(): String = formatQuantity(shortageQuantity)

    fun getFormattedMessage(isBengali: Boolean = false): String {
        val name = if (isBengali && productNameBn.isNotBlank()) productNameBn else productNameEn
        val availStr = formatAvailable()
        val reqStr = formatRequested()
        return if (isBengali) {
            "শুধুমাত্র $availStr $unitType মজুদ আছে (অনুরোধ: $reqStr $unitType) - $name"
        } else {
            "Only $availStr $unitType available for $name (Requested: $reqStr $unitType)"
        }
    }
}

/**
 * Exception thrown when a sale cannot be completed because requested item quantities
 * exceed current stock, or when a concurrent multi-device transaction detects stock depletion.
 */
class InsufficientStockException(
    val items: List<InsufficientStockItem>,
    message: String = buildErrorMessage(items)
) : Exception(message) {
    companion object {
        fun buildErrorMessage(items: List<InsufficientStockItem>): String {
            if (items.isEmpty()) return "Insufficient stock available for one or more items."
            return items.joinToString("\n") { it.getFormattedMessage(false) }
        }
    }
}

/**
 * Exception thrown when a credit sale or credit entry exceeds customer's credit limit,
 * and no Owner/Admin override flag was provided.
 */
class CreditLimitExceededException(
    val customerId: String = "",
    val customerName: String = "Customer",
    val currentBalance: Double = 0.0,
    val creditLimit: Double = 0.0,
    val attemptedDue: Double = 0.0,
    val excessAmount: Double = (attemptedDue - creditLimit).coerceAtLeast(0.0),
    message: String = "Credit limit exceeded for $customerName. Current Due: ₹%.2f, Limit: ₹%.0f, Attempted: ₹%.2f (Exceeds by ₹%.2f)".format(
        currentBalance, creditLimit, attemptedDue, excessAmount
    )
) : Exception(message) {
    val attemptedCreditAmount: Double get() = (attemptedDue - currentBalance).coerceAtLeast(0.0)
    val newBalance: Double get() = attemptedDue

    fun getFormattedMessage(isBengali: Boolean = false): String {
        return if (isBengali) {
            "${customerName}-এর বাকী সীমা ₹%.0f অতিক্রম করেছে! বর্তমান বাকি: ₹%.2f, নতুন মোট বাকি: ₹%.2f (অতিরিক্ত: ₹%.2f)। শুধুমাত্র Owner/Admin এই সীমা ওভাররাইড করতে পারবেন।".format(
                creditLimit, currentBalance, attemptedDue, excessAmount
            )
        } else {
            "Credit limit of ₹%.0f exceeded for $customerName! Current Due: ₹%.2f, New Total: ₹%.2f (Excess: ₹%.2f). Owner/Admin authorization required to override.".format(
                creditLimit, currentBalance, attemptedDue, excessAmount
            )
        }
    }
}
