package com.example.data.local.entities

import androidx.annotation.Keep
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

@Keep
data class BarcodeVariant(
    val id: String = UUID.randomUUID().toString().take(8),
    val barcode: String,
    val unitType: String,
    val quantity: Double,
    val price: Double,
    val label: String = "",
    val mrp: Double = price,
    val costPrice: Double = 0.0,
    val packagingUnit: String = "pack", // e.g. "pack", "pouch", "box", "bottle", "bag", "strip", "tin", "piece"
    val imageUri: String? = null
) {
    fun getEffectiveMrp(): Double = if (mrp > 0.0) mrp else price

    fun hasDiscount(): Boolean = getEffectiveMrp() > price

    fun getDiscountPercent(): Double = if (hasDiscount()) {
        ((getEffectiveMrp() - price) / getEffectiveMrp() * 100.0)
    } else 0.0

    fun getEffectiveCost(baseProduct: Product? = null): Double {
        if (costPrice > 0.0) return costPrice
        if (baseProduct != null && baseProduct.costPrice > 0.0) {
            val baseQty = baseProduct.convertQuantityToBaseUnit(quantity, unitType)
            return baseQty * baseProduct.costPrice
        }
        return 0.0
    }

    fun getEstimatedProfit(baseProduct: Product? = null): Double {
        val cost = getEffectiveCost(baseProduct)
        return (price - cost).coerceAtLeast(0.0)
    }

    fun getEstimatedMarginPercent(baseProduct: Product? = null): Double {
        if (price <= 0.0) return 0.0
        val cost = getEffectiveCost(baseProduct)
        return ((price - cost) / price * 100.0).coerceIn(-100.0, 100.0)
    }

    fun toMap(): Map<String, Any?> = hashMapOf(
        "id" to id,
        "barcode" to barcode,
        "unitType" to unitType,
        "unit_type" to unitType,
        "quantity" to quantity,
        "price" to price,
        "label" to label,
        "mrp" to mrp,
        "costPrice" to costPrice,
        "cost_price" to costPrice,
        "packagingUnit" to packagingUnit,
        "packaging_unit" to packagingUnit,
        "imageUri" to imageUri,
        "image_uri" to imageUri
    )

    fun toJsonObject(): JSONObject = JSONObject().apply {
        put("id", id)
        put("barcode", barcode)
        put("unitType", unitType)
        put("quantity", quantity)
        put("price", price)
        put("label", label)
        put("mrp", mrp)
        put("costPrice", costPrice)
        put("packagingUnit", packagingUnit)
        if (!imageUri.isNullOrBlank()) {
            put("imageUri", imageUri)
            put("image_uri", imageUri)
        }
    }

    fun getDisplayTitle(productName: String): String {
        return if (label.isNotBlank()) {
            "$productName ($label)"
        } else {
            val qtyStr = if (quantity % 1.0 == 0.0) quantity.toInt().toString() else quantity.toString()
            "$productName ($qtyStr $unitType)"
        }
    }

    fun getShortLabel(): String {
        return if (label.isNotBlank()) {
            label
        } else {
            val qtyStr = if (quantity % 1.0 == 0.0) quantity.toInt().toString() else quantity.toString()
            "$qtyStr $unitType"
        }
    }

    fun getPackagingDisplay(isBn: Boolean = false): String {
        val pUnit = packagingUnit.trim().lowercase()
        return when {
            pUnit in listOf("pouch", "packet", "pack") -> if (isBn) "প্যাকেট" else "Pack"
            pUnit in listOf("box", "carton") -> if (isBn) "বক্স" else "Box"
            pUnit in listOf("bottle", "jar") -> if (isBn) "বোতল" else "Bottle"
            pUnit in listOf("bag", "sack") -> if (isBn) "ব্যাগ" else "Bag"
            pUnit in listOf("strip") -> if (isBn) "পাতা" else "Strip"
            pUnit in listOf("tin", "can") -> if (isBn) "টিন" else "Tin"
            pUnit in listOf("piece", "pcs") -> if (isBn) "পিস" else "Piece"
            else -> packagingUnit.replaceFirstChar { it.uppercase() }
        }
    }

    companion object {
        fun fromJsonObject(json: JSONObject): BarcodeVariant {
            val id = json.optString("id", "").ifBlank { UUID.randomUUID().toString().take(8) }
            val barcode = json.optString("barcode", "").trim()
            val unitType = json.optString("unitType", json.optString("unit_type", "piece"))
            val quantity = json.optDouble("quantity", 1.0)
            val price = json.optDouble("price", 0.0)
            val label = json.optString("label", "")
            val mrp = json.optDouble("mrp", price)
            val costPrice = json.optDouble("costPrice", json.optDouble("cost_price", 0.0))
            val packagingUnit = json.optString("packagingUnit", json.optString("packaging_unit", "pack"))
            val imageUri = json.optString("imageUri", json.optString("image_uri", "")).ifBlank { null }
            return BarcodeVariant(
                id = id,
                barcode = barcode,
                unitType = unitType,
                quantity = quantity,
                price = price,
                label = label,
                mrp = mrp,
                costPrice = costPrice,
                packagingUnit = packagingUnit,
                imageUri = imageUri
            )
        }

        fun fromMap(map: Map<*, *>): BarcodeVariant {
            val id = (map["id"] as? String)?.ifBlank { null } ?: UUID.randomUUID().toString().take(8)
            val barcode = (map["barcode"] as? String ?: "").trim()
            val unitType = (map["unitType"] as? String ?: map["unit_type"] as? String ?: "piece")
            val quantity = (map["quantity"] as? Number)?.toDouble() ?: 1.0
            val price = (map["price"] as? Number)?.toDouble() ?: 0.0
            val label = (map["label"] as? String ?: "")
            val mrp = (map["mrp"] as? Number)?.toDouble() ?: price
            val costPrice = (map["costPrice"] as? Number ?: map["cost_price"] as? Number)?.toDouble() ?: 0.0
            val packagingUnit = (map["packagingUnit"] as? String ?: map["packaging_unit"] as? String ?: "pack")
            val imageUri = (map["imageUri"] as? String ?: map["image_uri"] as? String)?.ifBlank { null }
            return BarcodeVariant(
                id = id,
                barcode = barcode,
                unitType = unitType,
                quantity = quantity,
                price = price,
                label = label,
                mrp = mrp,
                costPrice = costPrice,
                packagingUnit = packagingUnit,
                imageUri = imageUri
            )
        }

        fun parseListFromJson(jsonStr: String?): List<BarcodeVariant> {
            if (jsonStr.isNullOrBlank()) return emptyList()
            return try {
                val array = JSONArray(jsonStr)
                val list = mutableListOf<BarcodeVariant>()
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val variant = fromJsonObject(obj)
                    if (variant.barcode.isNotBlank() && variant.quantity > 0.0) {
                        list.add(variant)
                    }
                }
                list
            } catch (e: Exception) {
                emptyList()
            }
        }

        fun toJsonString(list: List<BarcodeVariant>): String {
            val array = JSONArray()
            list.forEach {
                if (it.barcode.isNotBlank() && it.quantity > 0.0) {
                    array.put(it.toJsonObject())
                }
            }
            return array.toString()
        }

        fun listToJson(list: List<BarcodeVariant>): String = toJsonString(list)
    }
}
