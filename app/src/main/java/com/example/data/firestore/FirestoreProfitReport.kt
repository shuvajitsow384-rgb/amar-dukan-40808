package com.example.data.firestore

data class FirestoreProfitReport(
    val reportId: String = "",
    val period: String = "TODAY",
    val startTime: Long = 0L,
    val endTime: Long = 0L,
    val totalRevenue: Double = 0.0,
    val totalCostOfGoods: Double = 0.0,
    val grossProfit: Double = 0.0,
    val totalExpenses: Double = 0.0,
    val stockLoss: Double = 0.0,
    val netProfit: Double = 0.0,
    val profitMarginPercent: Double = 0.0,
    val totalSalesCount: Int = 0,
    val generatedAt: Long = System.currentTimeMillis(),
    val generatedByUid: String = "",
    val generatedByName: String = "Admin",
    val requiredRole: String = "ADMIN", // Protected from regular employees
    val storeId: String = "default_store"
)
