package com.example.data.models

import com.google.firebase.firestore.DocumentSnapshot
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Order status states according to online ordering lifecycle.
 */
object OrderStatus {
    const val PLACED = "PLACED"
    const val CONFIRMED = "CONFIRMED"
    const val READY = "READY"
    const val OUT_FOR_DELIVERY = "OUT_FOR_DELIVERY"
    const val COMPLETED = "COMPLETED"
    const val CANCELLED = "CANCELLED"
}

/**
 * Fulfillment types supported by the online shop.
 */
object FulfillmentType {
    const val PICKUP = "PICKUP"
    const val DELIVERY = "DELIVERY"
}

/**
 * Payment methods for customer orders.
 */
object OrderPaymentMethod {
    const val UPI = "UPI"
    const val CASH = "CASH"
    const val CREDIT = "CREDIT"
}

/**
 * Payment status lifecycle states.
 */
object OrderPaymentStatus {
    const val PENDING = "PENDING"
    const val PAID = "PAID"
    const val COD = "COD"
}

/**
 * Customer delivery address for home delivery orders.
 */
data class DeliveryAddress(
    val streetAddress: String = "",
    val landmark: String? = null,
    val pinCode: String = "",
    val latitude: Double? = null,
    val longitude: Double? = null
) {
    fun toMap(): Map<String, Any?> {
        val map = mutableMapOf<String, Any?>(
            "streetAddress" to streetAddress,
            "pinCode" to pinCode
        )
        if (!landmark.isNullOrBlank()) {
            map["landmark"] = landmark
        }
        if (latitude != null) {
            map["latitude"] = latitude
        }
        if (longitude != null) {
            map["longitude"] = longitude
        }
        return map
    }

    companion object {
        fun fromMap(map: Map<String, Any?>?): DeliveryAddress? {
            if (map == null) return null
            val street = (map["streetAddress"] as? String) ?: (map["street_address"] as? String) ?: ""
            val landmark = (map["landmark"] as? String)
            val pin = (map["pinCode"] as? String) ?: (map["pin_code"] as? String) ?: ""
            val lat = (map["latitude"] as? Number)?.toDouble() ?: (map["lat"] as? Number)?.toDouble()
            val lng = (map["longitude"] as? Number)?.toDouble() ?: (map["lng"] as? Number)?.toDouble()
            return DeliveryAddress(
                streetAddress = street,
                landmark = landmark,
                pinCode = pin,
                latitude = lat,
                longitude = lng
            )
        }
    }
}

/**
 * Individual item inside an online order.
 */
data class OrderItem(
    val productId: String,
    val name: String,
    val quantity: Double,
    val unit: String,
    val price: Double,
    val subtotal: Double,
    val variantBarcode: String? = null,
    val variantLabel: String? = null
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "productId" to productId,
        "name" to name,
        "quantity" to quantity,
        "unit" to unit,
        "price" to price,
        "subtotal" to subtotal,
        "variantBarcode" to variantBarcode,
        "variant_barcode" to variantBarcode,
        "variantLabel" to variantLabel,
        "variant_label" to variantLabel
    )

    companion object {
        fun fromMap(map: Map<String, Any?>): OrderItem {
            val productId = (map["productId"] as? String) ?: (map["product_id"] as? String) ?: ""
            val name = (map["name"] as? String) ?: ""
            val quantity = (map["quantity"] as? Number)?.toDouble() ?: 1.0
            val unit = (map["unit"] as? String) ?: "piece"
            val price = (map["price"] as? Number)?.toDouble() ?: 0.0
            val subtotal = (map["subtotal"] as? Number)?.toDouble() ?: (price * quantity)
            val variantBarcode = (map["variantBarcode"] as? String) ?: (map["variant_barcode"] as? String)
            val variantLabel = (map["variantLabel"] as? String) ?: (map["variant_label"] as? String)
            return OrderItem(
                productId = productId,
                name = name,
                quantity = quantity,
                unit = unit,
                price = price,
                subtotal = subtotal,
                variantBarcode = variantBarcode,
                variantLabel = variantLabel
            )
        }
    }
}

/**
 * Online customer order data model stored at orders/{orderId}.
 * The document ID itself is a 20+ character unguessable random string that acts as the secret access key.
 */
data class Order(
    val id: String, // Secret document ID (20+ chars random string)
    val orderNumber: String, // Cosmetic display label, e.g. "ORD-20260904-8921"
    val customerName: String,
    val customerPhone: String,
    val fulfillmentType: String, // PICKUP or DELIVERY
    val deliveryAddress: DeliveryAddress? = null,
    val customerNotes: String = "",
    val items: List<OrderItem> = emptyList(),
    val itemsCount: Int = items.size,
    val subtotal: Double = 0.0,
    val deliveryFee: Double = 0.0,
    val deliverySlot: String? = null,
    val totalAmount: Double = 0.0,
    val status: String = OrderStatus.PLACED,
    val cancelReason: String? = null,
    val paymentMethod: String = OrderPaymentMethod.UPI,
    val paymentStatus: String = OrderPaymentStatus.PENDING,
    val paymentReference: String? = null, // Customer's UTR, entered directly on this document
    val paymentScreenshotData: String? = null, // Base64 payment screenshot compressed to max 800px / 0.5 quality
    val paymentSubmittedAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val confirmedAt: Long? = null,
    val completedAt: Long? = null,
    val saleId: String? = null, // Links to sales/{saleId} once fulfilled
    val confirmedByStaffName: String? = null,
    val dispatchedByStaffName: String? = null,
    val customerUid: String? = null
) {
    fun toMap(): Map<String, Any?> {
        val map = mutableMapOf<String, Any?>(
            "id" to id,
            "orderNumber" to orderNumber,
            "customerName" to customerName,
            "customerPhone" to customerPhone,
            "fulfillmentType" to fulfillmentType,
            "deliverySlot" to deliverySlot,
            "deliveryFee" to deliveryFee,
            "customerNotes" to customerNotes,
            "customerUid" to customerUid,
            "items" to items.map { it.toMap() },
            "itemsCount" to (if (itemsCount > 0) itemsCount else items.size),
            "subtotal" to subtotal,
            "totalAmount" to totalAmount,
            "status" to status,
            "cancelReason" to cancelReason,
            "paymentMethod" to paymentMethod,
            "paymentStatus" to paymentStatus,
            "paymentReference" to paymentReference,
            "paymentScreenshotData" to paymentScreenshotData,
            "paymentSubmittedAt" to paymentSubmittedAt,
            "createdAt" to createdAt,
            "updatedAt" to updatedAt,
            "confirmedAt" to confirmedAt,
            "completedAt" to completedAt,
            "saleId" to saleId,
            "confirmedByStaffName" to confirmedByStaffName,
            "dispatchedByStaffName" to dispatchedByStaffName
        )
        if (fulfillmentType == FulfillmentType.DELIVERY && deliveryAddress != null) {
            map["deliveryAddress"] = deliveryAddress.toMap()
        }
        return map
    }

    companion object {
        /**
         * Generates a 24+ character cryptographically secure random string for document ID.
         * Same style as the existing Khata shareToken.
         */
        fun generateOrderId(): String {
            val randomBytes = ByteArray(24)
            SecureRandom().nextBytes(randomBytes)
            val hex = randomBytes.joinToString("") { "%02x".format(it) }
            val uuidPart = UUID.randomUUID().toString().replace("-", "")
            return (hex + uuidPart).take(48)
        }

        /**
         * Generates a cosmetic display label, e.g. "ORD-20260905-8921".
         */
        fun generateOrderNumber(date: Date = Date()): String {
            val dateStr = SimpleDateFormat("yyyyMMdd", Locale.US).format(date)
            val randomDigits = (1000..9999).random()
            return "ORD-$dateStr-$randomDigits"
        }

        @Suppress("UNCHECKED_CAST")
        fun fromDocument(doc: DocumentSnapshot): Order? {
            if (!doc.exists()) return null
            return fromMap(doc.id, doc.data ?: emptyMap())
        }

        @Suppress("UNCHECKED_CAST")
        fun fromMap(id: String, data: Map<String, Any?>): Order {
            val orderNumber = (data["orderNumber"] as? String)
                ?: (data["order_number"] as? String)
                ?: "ORD-${id.takeLast(6).uppercase()}"
            val customerName = (data["customerName"] as? String) ?: (data["customer_name"] as? String) ?: ""
            val customerPhone = (data["customerPhone"] as? String) ?: (data["customer_phone"] as? String) ?: ""
            val fulfillmentType = (data["fulfillmentType"] as? String)
                ?: (data["fulfillment_type"] as? String)
                ?: FulfillmentType.PICKUP
            val addressMap = data["deliveryAddress"] as? Map<String, Any?>
                ?: data["delivery_address"] as? Map<String, Any?>
            val deliveryAddress = DeliveryAddress.fromMap(addressMap)
            val customerNotes = (data["customerNotes"] as? String) ?: (data["customer_notes"] as? String) ?: ""

            val rawItems = data["items"] as? List<*> ?: emptyList<Any>()
            val items = rawItems.mapNotNull {
                if (it is Map<*, *>) {
                    OrderItem.fromMap(it as Map<String, Any?>)
                } else null
            }
            val itemsCount = (data["itemsCount"] as? Number)?.toInt()
                ?: (data["items_count"] as? Number)?.toInt()
                ?: items.size
            val subtotal = (data["subtotal"] as? Number)?.toDouble() ?: 0.0
            val deliveryFee = (data["deliveryFee"] as? Number)?.toDouble()
                ?: (data["delivery_fee"] as? Number)?.toDouble()
                ?: 0.0
            val deliverySlot = (data["deliverySlot"] as? String)
                ?: (data["delivery_slot"] as? String)
            val totalAmount = (data["totalAmount"] as? Number)?.toDouble()
                ?: (data["total_amount"] as? Number)?.toDouble()
                ?: (subtotal + deliveryFee)

            val status = (data["status"] as? String) ?: OrderStatus.PLACED
            val cancelReason = (data["cancelReason"] as? String) ?: (data["cancel_reason"] as? String)
            val paymentMethod = (data["paymentMethod"] as? String)
                ?: (data["payment_method"] as? String)
                ?: OrderPaymentMethod.UPI
            val paymentStatus = (data["paymentStatus"] as? String)
                ?: (data["payment_status"] as? String)
                ?: OrderPaymentStatus.PENDING
            val paymentReference = (data["paymentReference"] as? String)
                ?: (data["payment_reference"] as? String)
            val paymentScreenshotData = (data["paymentScreenshotData"] as? String)
                ?: (data["payment_screenshot_data"] as? String)
            val paymentSubmittedAt = (data["paymentSubmittedAt"] as? Number)?.toLong()
                ?: (data["payment_submitted_at"] as? Number)?.toLong()

            val createdAt = (data["createdAt"] as? Number)?.toLong()
                ?: (data["created_at"] as? Number)?.toLong()
                ?: System.currentTimeMillis()
            val updatedAt = (data["updatedAt"] as? Number)?.toLong()
                ?: (data["updated_at"] as? Number)?.toLong()
                ?: createdAt
            val confirmedAt = (data["confirmedAt"] as? Number)?.toLong()
                ?: (data["confirmed_at"] as? Number)?.toLong()
            val completedAt = (data["completedAt"] as? Number)?.toLong()
                ?: (data["completed_at"] as? Number)?.toLong()
            val saleId = (data["saleId"] as? String) ?: (data["sale_id"] as? String)
            val confirmedByStaffName = (data["confirmedByStaffName"] as? String)
                ?: (data["confirmed_by_staff_name"] as? String)
            val dispatchedByStaffName = (data["dispatchedByStaffName"] as? String)
                ?: (data["dispatched_by_staff_name"] as? String)
            val customerUid = (data["customerUid"] as? String)
                ?: (data["customer_uid"] as? String)

            return Order(
                id = id,
                orderNumber = orderNumber,
                customerName = customerName,
                customerPhone = customerPhone,
                fulfillmentType = fulfillmentType,
                deliveryAddress = deliveryAddress,
                customerNotes = customerNotes,
                items = items,
                itemsCount = itemsCount,
                subtotal = subtotal,
                deliveryFee = deliveryFee,
                deliverySlot = deliverySlot,
                totalAmount = totalAmount,
                status = status,
                cancelReason = cancelReason,
                paymentMethod = paymentMethod,
                paymentStatus = paymentStatus,
                paymentReference = paymentReference,
                paymentScreenshotData = paymentScreenshotData,
                paymentSubmittedAt = paymentSubmittedAt,
                createdAt = createdAt,
                updatedAt = updatedAt,
                confirmedAt = confirmedAt,
                completedAt = completedAt,
                saleId = saleId,
                confirmedByStaffName = confirmedByStaffName,
                dispatchedByStaffName = dispatchedByStaffName,
                customerUid = customerUid
            )
        }
    }
}
