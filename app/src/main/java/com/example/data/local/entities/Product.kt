package com.example.data.local.entities

import androidx.compose.runtime.Immutable
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.utils.BengaliReceiptTranslator

@Immutable
@Entity(tableName = "products")
data class Product(
    @PrimaryKey val id: String,
    val nameEn: String,
    val nameBn: String,
    val category: String,
    val unitType: String,       // Primary unit: "kg", "litre", "packet", "box", "piece", "dozen", "quintal"
    val costPrice: Double,      // Cost price per primary unit
    val sellingPrice: Double,   // Selling price per primary unit
    val currentStock: Double,   // Current stock in primary unit (pieces)
    val lowStockThreshold: Double = 5.0,
    val barcode: String? = null,
    val secondaryUnitType: String? = null, // Secondary unit e.g. "gram", "ml", "piece", "packet"
    val secondaryUnitRatio: Double = 1.0, // Ratio e.g. 1000 for kg->gram or litre->ml; 12 for dozen->piece
    val imageUri: String? = null,
    val expiryDate: String? = null, // Format: YYYY-MM-DD
    val wholesalePrice: Double = 0.0, // Wholesale selling price per primary unit
    val wholesaleMinQty: Double = 0.0, // Min quantity to auto trigger wholesale price
    val piecesPerBox: Int? = null,     // Optional: how many pieces make up one box (e.g. 15)
    val boxPrice: Double? = null,      // Optional: selling price for the whole box
    val boxMrp: Double? = null,        // Optional: MRP for the whole box
    val mrp: Double? = null,           // Optional: MRP (Maximum Retail Price)
    val barcodeVariantsJson: String? = null, // Stored JSON array of BarcodeVariant
    val isOnlineVisible: Boolean = true, // Visible in online store catalog
    val onlineMinOrderQty: Double = 1.0, // Minimum order quantity in online store
    val onlineMaxOrderQty: Double = 10.0, // Maximum order quantity in online store
    val bulkUnitType: String? = null,    // Optional: bulk unit name (e.g. "box", "bag", "sack", "tin", "jar", "kg", "litre")
    val bulkQuantity: Double? = null,    // Optional: quantity in primary unit (e.g. 5 for 5kg, 12 for 12pcs, 25 for 25kg bag)
    val bulkPrice: Double? = null,       // Optional: selling price for the bulk pack/quantity (e.g. ₹280 for 5kg bag)
    val needsSync: Boolean = true
) {
    fun getEffectiveMrp(): Double {
        return if ((mrp ?: 0.0) > 0.0) mrp!! else sellingPrice
    }

    fun getMrpPerUnit(unit: String = unitType): Double {
        val baseMrp = getEffectiveMrp()
        val isBox = unit.equals("box", ignoreCase = true) || unit.equals("case", ignoreCase = true)
        val secUnit = getEffectiveSecondaryUnit()
        val secRatio = getEffectiveSecondaryRatio()
        val isGram = isGramUnit(unit) && isKgUnit(unitType)
        val isMl = isMlUnit(unit) && isLitreUnit(unitType)
        val isSubUnit = isGram || isMl || (!unit.equals(unitType, ignoreCase = true) && unit.equals(secUnit, ignoreCase = true) && secRatio > 1.0)
        val effRatio = when {
            isGram || isMl -> if (secRatio >= 1000.0) secRatio else 1000.0
            else -> secRatio
        }
        return when {
            isBox -> baseMrp * getPiecesPerBoxRatio()
            isSubUnit && effRatio > 0.0 -> baseMrp / effRatio
            else -> baseMrp
        }
    }

    fun getCostPriceForUnit(unit: String = unitType): Double {
        val isBox = unit.equals("box", ignoreCase = true) || unit.equals("case", ignoreCase = true)
        val secUnit = getEffectiveSecondaryUnit()
        val secRatio = getEffectiveSecondaryRatio()
        val isGram = isGramUnit(unit) && isKgUnit(unitType)
        val isMl = isMlUnit(unit) && isLitreUnit(unitType)
        val isSubUnit = isGram || isMl || (!unit.equals(unitType, ignoreCase = true) && unit.equals(secUnit, ignoreCase = true) && secRatio > 1.0)
        val effRatio = when {
            isGram || isMl -> if (secRatio >= 1000.0) secRatio else 1000.0
            else -> secRatio
        }
        return when {
            isBox -> costPrice * getPiecesPerBoxRatio()
            isSubUnit && effRatio > 0.0 -> costPrice / effRatio
            else -> costPrice
        }
    }

    fun calculateMrpPrice(qty: Double, selectedUnit: String = unitType): Double {
        return qty * getMrpPerUnit(selectedUnit)
    }

    fun calculateMrpDiscount(qty: Double = 1.0, selectedUnit: String = unitType): Double {
        val mrpTotal = calculateMrpPrice(qty, selectedUnit)
        val sellTotal = calculatePrice(qty, selectedUnit)
        return (mrpTotal - sellTotal).coerceAtLeast(0.0)
    }

    fun getDiscountPercent(): Double {
        val effMrp = getEffectiveMrp()
        if (effMrp > sellingPrice && effMrp > 0.0) {
            return ((effMrp - sellingPrice) / effMrp) * 100.0
        }
        return 0.0
    }
    fun getExpiryStatus(): ExpiryStatus {
        if (expiryDate.isNullOrBlank()) return ExpiryStatus.NO_EXPIRY
        return try {
            val parts = expiryDate.split("-")
            if (parts.size != 3) return ExpiryStatus.NO_EXPIRY
            val year = parts[0].toInt()
            val month = parts[1].toInt() - 1
            val day = parts[2].toInt()

            val cal = java.util.Calendar.getInstance()
            val today = java.util.Calendar.getInstance()
            today.set(java.util.Calendar.HOUR_OF_DAY, 0)
            today.set(java.util.Calendar.MINUTE, 0)
            today.set(java.util.Calendar.SECOND, 0)
            today.set(java.util.Calendar.MILLISECOND, 0)

            cal.set(year, month, day, 0, 0, 0)
            cal.set(java.util.Calendar.MILLISECOND, 0)

            val diffMs = cal.timeInMillis - today.timeInMillis
            val diffDays = (diffMs / (1000L * 60 * 60 * 24)).toInt()

            when {
                diffDays < 0 -> ExpiryStatus.EXPIRED
                diffDays <= 7 -> ExpiryStatus.EXPIRING_SOON
                else -> ExpiryStatus.FRESH
            }
        } catch (e: Exception) {
            ExpiryStatus.NO_EXPIRY
        }
    }

    fun getDaysUntilExpiry(): Int? {
        if (expiryDate.isNullOrBlank()) return null
        return try {
            val parts = expiryDate.split("-")
            if (parts.size != 3) return null
            val cal = java.util.Calendar.getInstance()
            val today = java.util.Calendar.getInstance()
            today.set(java.util.Calendar.HOUR_OF_DAY, 0)
            today.set(java.util.Calendar.MINUTE, 0)
            today.set(java.util.Calendar.SECOND, 0)
            today.set(java.util.Calendar.MILLISECOND, 0)

            cal.set(parts[0].toInt(), parts[1].toInt() - 1, parts[2].toInt(), 0, 0, 0)
            cal.set(java.util.Calendar.MILLISECOND, 0)

            val diffMs = cal.timeInMillis - today.timeInMillis
            ((diffMs) / (1000L * 60 * 60 * 24)).toInt()
        } catch (e: Exception) {
            null
        }
    }
    fun getDisplayName(isBengali: Boolean = false): String {
        if (isBengali) {
            if (nameBn.isNotBlank() && BengaliReceiptTranslator.containsBengali(nameBn)) return nameBn
            if (nameEn.isNotBlank()) return BengaliReceiptTranslator.translateItem(nameEn, nameBn)
            if (nameBn.isNotBlank()) return BengaliReceiptTranslator.translateItem(nameBn)
            return "পণ্য"
        }
        if (nameEn.isNotBlank()) return nameEn
        if (nameBn.isNotBlank()) return nameBn
        return "Item"
    }

    fun getEffectiveSecondaryUnit(): String {
        if (!secondaryUnitType.isNullOrBlank()) return secondaryUnitType
        return when {
            isKgUnit(unitType) -> "gram"
            isLitreUnit(unitType) -> "ml"
            unitType.equals("quintal", ignoreCase = true) -> "kg"
            unitType.equals("dozen", ignoreCase = true) -> "piece"
            unitType.equals("box", ignoreCase = true) -> "piece"
            unitType.equals("packet", ignoreCase = true) -> "piece"
            else -> "piece"
        }
    }

    fun getEffectiveSecondaryRatio(): Double {
        if (isKgUnit(unitType) || isLitreUnit(unitType)) {
            return if (secondaryUnitRatio >= 1000.0) secondaryUnitRatio else 1000.0
        }
        if (secondaryUnitRatio > 1.0) return secondaryUnitRatio
        return when {
            isKgUnit(unitType) -> 1000.0
            isLitreUnit(unitType) -> 1000.0
            unitType.equals("quintal", ignoreCase = true) -> 100.0
            unitType.equals("dozen", ignoreCase = true) -> 12.0
            unitType.equals("box", ignoreCase = true) -> 12.0
            unitType.equals("packet", ignoreCase = true) -> 10.0
            else -> 1.0
        }
    }

    fun isSecondaryUnitSupported(): Boolean {
        val secUnit = getEffectiveSecondaryUnit()
        return !secUnit.equals(unitType, ignoreCase = true)
    }

    fun getEffectiveWholesalePrice(): Double = if (wholesalePrice > 0.0) wholesalePrice else sellingPrice

    fun isWholesaleApplicable(qty: Double, isWholesaleMode: Boolean = false): Boolean {
        return isWholesaleMode
    }

    fun hasConversionConflict(): Boolean {
        if (isLooseUnit(unitType)) return false
        val ppb = piecesPerBox ?: return false
        val ratio = secondaryUnitRatio.toInt()
        return ppb > 1 && ratio > 1 && ppb != ratio
    }

    fun getPiecesPerBoxRatio(): Int {
        val bq = bulkQuantity?.toInt()
        if (bq != null && bq > 1) return bq
        if ((piecesPerBox ?: 0) > 1 && !isLooseUnit(unitType)) return piecesPerBox!!
        if (!isLooseUnit(unitType) && secondaryUnitRatio > 1.0) return secondaryUnitRatio.toInt()
        return when (unitType.lowercase()) {
            "box" -> 12
            "dozen" -> 12
            else -> 1
        }
    }

    fun isLooseUnit(unit: String = unitType): Boolean {
        return Companion.isLooseUnit(unit)
    }

    fun isDiscreteUnit(unit: String = unitType): Boolean {
        return !isLooseUnit(unit)
    }

    fun hasBulkPricing(): Boolean {
        val bp = bulkPrice ?: boxPrice
        if (bp != null && bp > 0.0) return true
        if (bulkQuantity != null && bulkQuantity > 1.0) return true
        if ((piecesPerBox ?: 0) > 1) return true
        if (unitType.equals("box", ignoreCase = true) && getPiecesPerBoxRatio() > 1) return true
        return false
    }

    fun hasBoxPricing(): Boolean = hasBulkPricing() || getEffectiveBulkQuantity() > 1.0

    fun getEffectiveBulkPrice(): Double {
        return bulkPrice ?: boxPrice ?: (sellingPrice * getEffectiveBulkQuantity())
    }

    fun getEffectiveBulkQuantity(): Double {
        if (bulkQuantity != null && bulkQuantity > 0.0) return bulkQuantity
        if ((piecesPerBox ?: 0) > 1) return piecesPerBox!!.toDouble()
        if (secondaryUnitRatio > 1.0 && !isLooseUnit(unitType)) return secondaryUnitRatio
        return 1.0
    }

    fun getEffectiveBulkUnit(): String {
        if (!bulkUnitType.isNullOrBlank()) return bulkUnitType.trim()
        if (unitType.equals("box", ignoreCase = true)) return "box"
        if (isKgUnit(unitType)) return "bag"
        if (isLitreUnit(unitType)) return "tin"
        return "box"
    }

    /**
     * Calculates line total for given quantity and unit with optional wholesale mode.
     */
    fun calculatePrice(
        qty: Double,
        unit: String = unitType,
        isWholesale: Boolean = false,
        customUnitPrice: Double? = null
    ): Double {
        val effBulkUnit = getEffectiveBulkUnit()
        val effBulkQty = getEffectiveBulkQuantity()
        val effBulkPrice = getEffectiveBulkPrice()

        val isBulkUnit = hasBulkPricing() && (
            unit.equals(effBulkUnit, ignoreCase = true) ||
            unit.equals("box", ignoreCase = true) ||
            unit.equals("case", ignoreCase = true)
        )

        // Case 1: Sold directly using the bulk unit (e.g. "box", "case", "bag", "sack", "tin", "jar", "carton")
        if (isBulkUnit) {
            val pricePerBulkUnit = if (customUnitPrice != null && customUnitPrice > 0.0) customUnitPrice else effBulkPrice
            if (pricePerBulkUnit > 0.0) {
                return qty * pricePerBulkUnit
            }
        }

        // Case 2: Sold in primary unit or sub-unit, check if quantity meets bulk threshold
        val baseQty = convertQuantityToBaseUnit(qty, unit)
        if (hasBulkPricing() && effBulkPrice > 0.0 && effBulkQty > 0.0 && baseQty >= effBulkQty && !isWholesale && (customUnitPrice == null || customUnitPrice == sellingPrice)) {
            val bulkRatePerBaseUnit = effBulkPrice / effBulkQty
            return baseQty * bulkRatePerBaseUnit
        }

        // Case 3: Standard unit pricing (base unit or converted sub-unit such as gram, ml, piece)
        val baseUnitPrice = if (customUnitPrice != null && customUnitPrice > 0.0) {
            customUnitPrice
        } else if (isWholesale) {
            getEffectiveWholesalePrice()
        } else {
            sellingPrice
        }

        return baseQty * baseUnitPrice
    }

    fun convertQuantityToBaseUnit(qty: Double, unit: String = unitType): Double {
        if (qty == 0.0) return 0.0
        val cleanUnit = unit.trim()
        val cleanUnitType = unitType.trim()

        // 1. Same unit or same metric category
        if (cleanUnit.equals(cleanUnitType, ignoreCase = true) ||
            (isKgUnit(cleanUnit) && isKgUnit(cleanUnitType)) ||
            (isGramUnit(cleanUnit) && isGramUnit(cleanUnitType)) ||
            (isLitreUnit(cleanUnit) && isLitreUnit(cleanUnitType)) ||
            (isMlUnit(cleanUnit) && isMlUnit(cleanUnitType))
        ) {
            return qty
        }

        // 2. Gram -> Kg (Always divide by 1000.0 or secRatio)
        if (isGramUnit(cleanUnit) && isKgUnit(cleanUnitType)) {
            val ratio = if (secondaryUnitRatio >= 1000.0) secondaryUnitRatio else 1000.0
            return qty / ratio
        }

        // 3. Kg -> Gram (Always multiply by 1000.0 or secRatio)
        if (isKgUnit(cleanUnit) && isGramUnit(cleanUnitType)) {
            val ratio = if (secondaryUnitRatio >= 1000.0) secondaryUnitRatio else 1000.0
            return qty * ratio
        }

        // 4. Ml -> Litre (Always divide by 1000.0 or secRatio)
        if (isMlUnit(cleanUnit) && isLitreUnit(cleanUnitType)) {
            val ratio = if (secondaryUnitRatio >= 1000.0) secondaryUnitRatio else 1000.0
            return qty / ratio
        }

        // 5. Litre -> Ml (Always multiply by 1000.0 or secRatio)
        if (isLitreUnit(cleanUnit) && isMlUnit(cleanUnitType)) {
            val ratio = if (secondaryUnitRatio >= 1000.0) secondaryUnitRatio else 1000.0
            return qty * ratio
        }

        // 6. Kg <-> Quintal (1 quintal = 100 kg)
        if (isKgUnit(cleanUnit) && cleanUnitType.equals("quintal", ignoreCase = true)) {
            return qty / 100.0
        }
        if (cleanUnitType.equals("kg", ignoreCase = true) && cleanUnit.equals("quintal", ignoreCase = true)) {
            return qty * 100.0
        }

        // 7. Bulk unit handling (e.g. box, case, sack, bag)
        val effBulkUnit = getEffectiveBulkUnit()
        val effBulkQty = getEffectiveBulkQuantity()
        if (hasBulkPricing() && !effBulkUnit.equals(cleanUnitType, ignoreCase = true) &&
            (cleanUnit.equals(effBulkUnit, ignoreCase = true) || cleanUnit.equals("box", ignoreCase = true) || cleanUnit.equals("case", ignoreCase = true))
        ) {
            return qty * effBulkQty
        }

        // 8. Secondary unit / discrete sub-units (e.g. piece for dozen/box/packet)
        val secUnit = getEffectiveSecondaryUnit()
        val secRatio = getEffectiveSecondaryRatio()

        if (cleanUnit.equals(secUnit, ignoreCase = true) ||
            (cleanUnit.equals("piece", ignoreCase = true) && (cleanUnitType.equals("dozen", ignoreCase = true) || cleanUnitType.equals("box", ignoreCase = true) || cleanUnitType.equals("packet", ignoreCase = true)))
        ) {
            return if (secRatio > 0.0) qty / secRatio else qty
        }

        // 9. Reverse dozen -> piece
        if (cleanUnitType.equals("piece", ignoreCase = true) && cleanUnit.equals("dozen", ignoreCase = true)) {
            return qty * 12.0
        }

        return qty
    }

    fun getBarcodeVariants(): List<BarcodeVariant> {
        return BarcodeVariant.parseListFromJson(barcodeVariantsJson)
    }

    fun withBarcodeVariants(variants: List<BarcodeVariant>): Product {
        return copy(barcodeVariantsJson = BarcodeVariant.toJsonString(variants))
    }

    val hasMultipleVariants: Boolean
        get() = getBarcodeVariants().isNotEmpty()

    fun findVariantByBarcode(scannedCode: String): BarcodeVariant? {
        val clean = scannedCode.trim()
        if (clean.isBlank()) return null
        val cleanNoZero = clean.trimStart('0')
        return getBarcodeVariants().find { v ->
            val vBarcode = v.barcode.trim()
            vBarcode.equals(clean, ignoreCase = true) ||
            (cleanNoZero.isNotBlank() && vBarcode.trimStart('0') == cleanNoZero)
        }
    }

    fun findMatchingVariant(scannedOrQuery: String): BarcodeVariant? {
        val clean = scannedOrQuery.trim()
        if (clean.isBlank()) return null
        val cleanNoZero = clean.trimStart('0')
        val variants = getBarcodeVariants()
        val byBarcode = variants.find { v ->
            val vBarcode = v.barcode.trim()
            vBarcode.equals(clean, ignoreCase = true) ||
            (cleanNoZero.isNotBlank() && vBarcode.trimStart('0') == cleanNoZero)
        }
        if (byBarcode != null) return byBarcode
        return variants.find { v ->
            v.label.equals(clean, ignoreCase = true) ||
            v.getShortLabel().equals(clean, ignoreCase = true)
        }
    }

    fun matchesQueryWithVariants(query: String): Boolean {
        val q = query.trim().lowercase()
        if (q.isBlank()) return true
        if (nameEn.lowercase().contains(q) || nameBn.lowercase().contains(q)) return true
        val pBarcode = barcode?.trim() ?: ""
        if (pBarcode.isNotBlank()) {
            val cleanNoZero = pBarcode.trimStart('0')
            val qNoZero = q.trimStart('0')
            if (pBarcode.lowercase().contains(q) || (cleanNoZero.isNotBlank() && qNoZero.isNotBlank() && cleanNoZero.contains(qNoZero))) {
                return true
            }
        }
        val variants = getBarcodeVariants()
        return variants.any { v ->
            val vb = v.barcode.trim().lowercase()
            val vbNoZero = vb.trimStart('0')
            val qNoZero = q.trimStart('0')
            vb.contains(q) ||
            (vbNoZero.isNotBlank() && qNoZero.isNotBlank() && vbNoZero.contains(qNoZero)) ||
            v.label.lowercase().contains(q) ||
            v.unitType.lowercase().contains(q) ||
            v.packagingUnit.lowercase().contains(q)
        }
    }

    fun calculateVariantBaseDeduction(variant: BarcodeVariant, packCount: Double = 1.0): Double {
        val basePerPack = convertQuantityToBaseUnit(variant.quantity, variant.unitType)
        return packCount * basePerPack
    }

    fun calculateCost(qty: Double, unit: String = unitType): Double {
        val effBulkUnit = getEffectiveBulkUnit()
        val effBulkQty = getEffectiveBulkQuantity()
        if (hasBulkPricing() && !effBulkUnit.equals(unitType, ignoreCase = true) &&
            (unit.equals(effBulkUnit, ignoreCase = true) || unit.equals("box", ignoreCase = true) || unit.equals("case", ignoreCase = true))
        ) {
            return qty * (costPrice * effBulkQty)
        }
        if (unit.equals("box", ignoreCase = true) || unit.equals("case", ignoreCase = true)) {
            val ppb = getPiecesPerBoxRatio()
            return qty * (costPrice * ppb)
        }
        val baseQty = convertQuantityToBaseUnit(qty, unit)
        return baseQty * costPrice
    }

    companion object {
        fun isLitreUnit(unit: String?): Boolean {
            if (unit.isNullOrBlank()) return false
            val u = unit.trim().lowercase(java.util.Locale.ROOT)
            return u in listOf("litre", "liter", "litres", "liters", "ltr", "ltrs", "l", "লিটার", "লি.", "লি", "লিঃ") ||
                   u.contains("লিটার") || u.contains("লি.") ||
                   u == "l" || u == "ltr" || u == "ltrs" || u.startsWith("liter") || u.startsWith("litre")
        }

        fun isKgUnit(unit: String?): Boolean {
            if (unit.isNullOrBlank()) return false
            val u = unit.trim().lowercase(java.util.Locale.ROOT)
            return u in listOf("kg", "kgs", "kilogram", "kilograms", "kilo", "kilos", "কেজি", "কে.জি.", "কে.জি", "কেজি.", "কিলোগ্রাম", "কেজির", "কেঃজিঃ") ||
                   u.contains("কেজি") || u.contains("কিলো") ||
                   (u.contains("kg") && !u.contains("pkg"))
        }

        fun isGramUnit(unit: String?): Boolean {
            if (unit.isNullOrBlank()) return false
            val u = unit.trim().lowercase(java.util.Locale.ROOT)
            return u in listOf("gram", "grams", "gm", "gms", "g", "গ্রাম", "গ্রা.", "গ্রা", "গ্রাম.", "গ্রাঃ") ||
                   u.contains("গ্রাম") || u.contains("গ্রা") ||
                   u == "g" || u == "gm" || u == "gms" || u.startsWith("gram")
        }

        fun isMlUnit(unit: String?): Boolean {
            if (unit.isNullOrBlank()) return false
            val u = unit.trim().lowercase(java.util.Locale.ROOT)
            return u in listOf("ml", "mls", "milliliter", "milliliters", "millilitre", "millilitres", "মিলি", "মি.লি.", "মি.লি", "মিঃলিঃ") ||
                   u.contains("মিলি") || u.contains("মি.লি") ||
                   u == "ml" || u == "mls" || u.startsWith("milli")
        }

        fun isLooseUnit(unit: String): Boolean {
            val u = unit.trim().lowercase(java.util.Locale.ROOT)
            return isKgUnit(u) || isGramUnit(u) || isLitreUnit(u) || isMlUnit(u) ||
                    u in listOf("quintal", "meter", "metre", "m", "cm", "feet", "ft", "কুইন্টাল", "মিটার")
        }

        fun getPluralBulkUnit(unit: String, count: Int): String {
            val clean = unit.trim().lowercase(java.util.Locale.ROOT)
            return if (count == 1) {
                when (clean) {
                    "boxes", "box" -> "box"
                    "tins", "tin" -> "tin"
                    "bags", "bag" -> "bag"
                    "cartons", "carton" -> "carton"
                    "cases", "case" -> "case"
                    "packets", "packet" -> "packet"
                    "bottles", "bottle" -> "bottle"
                    else -> if (clean.endsWith("s")) clean.dropLast(1) else unit
                }
            } else {
                when (clean) {
                    "box", "boxes" -> "boxes"
                    "tin", "tins" -> "tins"
                    "bag", "bags" -> "bags"
                    "carton", "cartons" -> "cartons"
                    "case", "cases" -> "cases"
                    "packet", "packets" -> "packets"
                    "bottle", "bottles" -> "bottles"
                    else -> if (clean.endsWith("s")) clean else "${unit}s"
                }
            }
        }

        /**
         * Rounds quantity to 3 decimal places (metric gram/milliliter precision) to eliminate IEEE 754 floating-point drift.
         */
        fun roundQuantity(qty: Double): Double {
            if (qty.isNaN() || qty.isInfinite()) return 0.0
            val rounded = Math.round(qty * 1000.0) / 1000.0
            return if (Math.abs(rounded) < 0.00001) 0.0 else rounded
        }

        /**
         * Formats numeric quantity cleanly without scientific notation or floating point trailing noise.
         * e.g. 27.826999999999998 -> "27.827"
         *      27.0 -> "27"
         *      27.5 -> "27.5"
         *      0.0000001 -> "0"
         */
        fun formatQuantity(qty: Double): String {
            if (qty.isNaN() || qty.isInfinite()) return "0"
            val cleanVal = roundQuantity(qty)
            if (cleanVal == 0.0) return "0"

            if (Math.abs(cleanVal - Math.round(cleanVal)) < 0.00001) {
                return Math.round(cleanVal).toString()
            }
            val symbols = java.text.DecimalFormatSymbols(java.util.Locale.US)
            val df = java.text.DecimalFormat("0.###", symbols)
            return df.format(cleanVal)
        }
    }

    fun getDisplayStockQty(): String {
        return formatQuantity(currentStock)
    }

    /**
     * Formats stock display with base quantity and approximate bulk/box breakdown if bulk packaging is configured.
     * e.g. "45.5 litre (≈ 3 tins + 0.5 litre)" or "45 pcs (≈ 3 boxes + 9 pcs)"
     */
    fun getFormattedStockDisplay(isBn: Boolean = false): String {
        val cleanQty = formatQuantity(currentStock)
        val effBulkQty = getEffectiveBulkQuantity()
        val effBulkUnit = getEffectiveBulkUnit()

        val pieceLabel = if (isBn) {
            BengaliReceiptTranslator.translateUnit(unitType, true)
        } else if (unitType.equals("box", ignoreCase = true) || unitType.equals("piece", ignoreCase = true) || unitType.equals("pcs", ignoreCase = true) || unitType.equals("pc", ignoreCase = true)) {
            if (Math.abs(currentStock - 1.0) < 0.0001) "pc" else "pcs"
        } else {
            unitType
        }

        val baseStr = if (isGramUnit(unitType)) {
            val kgVal = currentStock / 1000.0
            "${formatQuantity(kgVal)} ${if (isBn) "কেজি" else "kg"}"
        } else {
            "$cleanQty $pieceLabel"
        }

        // Only show bulk packaging breakdown (≈ X boxes/tins) if bulk packaging ratio is > 1.0
        if (hasBoxPricing() && effBulkQty > 1.0) {
            val fullBulk = (currentStock / effBulkQty).toInt()
            val rem = roundQuantity(currentStock - (fullBulk * effBulkQty))
            val bulkLabel = if (isBn) {
                BengaliReceiptTranslator.translateUnit(effBulkUnit, true)
            } else {
                getPluralBulkUnit(effBulkUnit, fullBulk)
            }

            val breakdown = if (rem <= 0.0001) {
                "≈ $fullBulk $bulkLabel"
            } else {
                "≈ $fullBulk $bulkLabel + ${formatQuantity(rem)} $pieceLabel"
            }
            return "$baseStr ($breakdown)"
        }

        return baseStr
    }

    /**
     * Provides an optional detailed breakdown for loose metric items (e.g. "27 kg 827 g" for 27.827 kg)
     */
    fun getDetailedLooseBreakdown(isBn: Boolean = false): String? {
        val isKg = isKgUnit(unitType)
        val isLtr = isLitreUnit(unitType)
        if (!isKg && !isLtr) return null
        val rounded = roundQuantity(currentStock)
        val whole = rounded.toLong()
        val frac = rounded - whole
        if (frac <= 0.0001) return null
        val subValue = Math.round(frac * 1000.0).toInt()
        if (subValue <= 0) return null

        return if (isKg) {
            if (isBn) "$whole কেজি $subValue গ্রাম" else "$whole kg $subValue g"
        } else {
            if (isBn) "$whole লিটার $subValue মিলি" else "$whole L $subValue ml"
        }
    }

    fun toMap(): Map<String, Any?> = hashMapOf(
        "id" to id,
        "nameEn" to nameEn,
        "nameBn" to nameBn,
        "name_en" to nameEn,
        "name_bn" to nameBn,
        "category" to category,
        "unitType" to unitType,
        "unit_type" to unitType,
        "costPrice" to costPrice,
        "cost_price" to costPrice,
        "sellingPrice" to sellingPrice,
        "selling_price" to sellingPrice,
        "currentStock" to currentStock,
        "current_stock" to currentStock,
        "lowStockThreshold" to lowStockThreshold,
        "low_stock_threshold" to lowStockThreshold,
        "barcode" to barcode,
        "secondaryUnitType" to secondaryUnitType,
        "secondary_unit_type" to secondaryUnitType,
        "secondaryUnitRatio" to secondaryUnitRatio,
        "secondary_unit_ratio" to secondaryUnitRatio,
        "imageUri" to imageUri,
        "image_uri" to imageUri,
        "expiryDate" to expiryDate,
        "expiry_date" to expiryDate,
        "wholesalePrice" to wholesalePrice,
        "wholesale_price" to wholesalePrice,
        "wholesaleMinQty" to wholesaleMinQty,
        "wholesale_min_qty" to wholesaleMinQty,
        "piecesPerBox" to piecesPerBox,
        "pieces_per_box" to piecesPerBox,
        "boxPrice" to boxPrice,
        "box_price" to boxPrice,
        "boxMrp" to boxMrp,
        "box_mrp" to boxMrp,
        "bulkUnitType" to bulkUnitType,
        "bulk_unit_type" to bulkUnitType,
        "bulkQuantity" to bulkQuantity,
        "bulk_quantity" to bulkQuantity,
        "bulkPrice" to bulkPrice,
        "bulk_price" to bulkPrice,
        "mrp" to mrp,
        "barcodeVariantsJson" to barcodeVariantsJson,
        "barcode_variants_json" to barcodeVariantsJson,
        "barcode_variants" to getBarcodeVariants().map { it.toMap() }
    )

    fun hasMrpDiscount(): Boolean {
        val mrpVal = mrp ?: return false
        return mrpVal > sellingPrice && sellingPrice > 0.0
    }

    fun hasDiscount(): Boolean = hasMrpDiscount()

    fun getMrpSavings(qty: Double = 1.0): Double {
        val mrpVal = mrp ?: return 0.0
        return if (mrpVal > sellingPrice) (mrpVal - sellingPrice) * qty else 0.0
    }

    fun getDiscountAmount(qty: Double = 1.0): Double = getMrpSavings(qty)
}

enum class ExpiryStatus {
    NO_EXPIRY,
    FRESH,
    EXPIRING_SOON,
    EXPIRED
}
