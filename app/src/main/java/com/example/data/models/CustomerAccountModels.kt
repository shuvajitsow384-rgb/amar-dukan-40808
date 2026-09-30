package com.example.data.models

import com.google.firebase.firestore.DocumentSnapshot

object CustomerLinkStatus {
    const val NONE = "NONE"
    const val PENDING = "PENDING"
    const val APPROVED = "APPROVED"
    const val REJECTED = "REJECTED"
}

data class LiveCustomerDetails(
    val customerId: String = "",
    val name: String = "",
    val phone: String = "",
    val balance: Double = 0.0,
    val billCount: Int = 0,
    val exists: Boolean = true
)

data class PendingLinkRequest(
    val requestId: String = "",
    val uid: String = "",
    val googleEmail: String = "",
    val googleName: String = "",
    val claimedPhone: String = "",
    val customerId: String = "",
    val existingCustomerName: String = "",
    val existingCustomerPhone: String = "",
    val existingCustomerBalance: Double = 0.0,
    val status: String = CustomerLinkStatus.PENDING,
    val requestedAt: Long = System.currentTimeMillis(),
    val reviewedAt: Long? = null,
    val reviewedBy: String? = null,
    // Live details populated on open / render
    val liveDetails: LiveCustomerDetails? = null
) {
    val isPending: Boolean get() = status.equals(CustomerLinkStatus.PENDING, ignoreCase = true)
    val isApproved: Boolean get() = status.equals(CustomerLinkStatus.APPROVED, ignoreCase = true)
    val isRejected: Boolean get() = status.equals(CustomerLinkStatus.REJECTED, ignoreCase = true)

    companion object {
        fun fromDocument(doc: DocumentSnapshot): PendingLinkRequest {
            return PendingLinkRequest(
                requestId = doc.id,
                uid = doc.getString("uid") ?: "",
                googleEmail = doc.getString("googleEmail") ?: "",
                googleName = doc.getString("googleName") ?: "",
                claimedPhone = doc.getString("claimedPhone") ?: "",
                customerId = doc.getString("customerId") ?: "",
                existingCustomerName = doc.getString("existingCustomerName") ?: "",
                existingCustomerPhone = doc.getString("existingCustomerPhone") ?: "",
                existingCustomerBalance = doc.getDouble("existingCustomerBalance") ?: 0.0,
                status = doc.getString("status") ?: CustomerLinkStatus.PENDING,
                requestedAt = doc.getLong("requestedAt") ?: System.currentTimeMillis(),
                reviewedAt = doc.getLong("reviewedAt"),
                reviewedBy = doc.getString("reviewedBy")
            )
        }
    }
}

data class CustomerAccount(
    val uid: String = "",
    val googleEmail: String = "",
    val displayName: String = "",
    val phone: String = "",
    val defaultAddress: String? = null,
    val linkStatus: String = CustomerLinkStatus.NONE,
    val linkedCustomerId: String? = null,
    val completedOrderCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    val isLinked: Boolean get() = linkStatus == CustomerLinkStatus.APPROVED && !linkedCustomerId.isNullOrBlank()

    companion object {
        fun fromDocument(doc: DocumentSnapshot): CustomerAccount {
            return CustomerAccount(
                uid = doc.id,
                googleEmail = doc.getString("googleEmail") ?: "",
                displayName = doc.getString("displayName") ?: "",
                phone = doc.getString("phone") ?: "",
                defaultAddress = doc.getString("defaultAddress"),
                linkStatus = doc.getString("linkStatus") ?: CustomerLinkStatus.NONE,
                linkedCustomerId = doc.getString("linkedCustomerId"),
                completedOrderCount = doc.getLong("completedOrderCount")?.toInt() ?: 0,
                createdAt = doc.getLong("createdAt") ?: System.currentTimeMillis(),
                updatedAt = doc.getLong("updatedAt") ?: System.currentTimeMillis()
            )
        }
    }
}
