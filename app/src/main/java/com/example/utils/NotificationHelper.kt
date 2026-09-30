package com.example.utils

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.MainActivity

object NotificationHelper {
    const val CHANNEL_ID = "low_stock_alerts_channel"
    const val CHANNEL_NAME = "Low Stock Alerts"

    const val CREDIT_CHANNEL_ID = "khata_credit_alerts_channel"
    const val CREDIT_CHANNEL_NAME = "Credit Khata & Payment Alerts"

    const val ONLINE_ORDERS_CHANNEL_ID = "online_orders_alerts_channel"
    const val ONLINE_ORDERS_CHANNEL_NAME = "Online Orders Alerts"

    private const val PREFS_NAME = "notification_prefs"
    private const val KEY_LAST_CREDIT_NOTIF_TIME = "last_credit_notif_time"
    private const val KEY_LAST_CREDIT_NOTIF_COUNT = "last_credit_notif_count"
    private const val COOLDOWN_CREDIT_NOTIF_MS = 24 * 60 * 60 * 1000L // 24 hours

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun createNotificationChannel(context: Context) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val notificationManager: NotificationManager =
                    context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

                val lowStockChannel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Alerts when product stock falls below defined minimum threshold"
                    enableVibration(true)
                }
                notificationManager.createNotificationChannel(lowStockChannel)

                val creditChannel = NotificationChannel(CREDIT_CHANNEL_ID, CREDIT_CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Alerts for overdue customer credit payments and high outstanding balances"
                    enableVibration(true)
                }
                notificationManager.createNotificationChannel(creditChannel)

                val onlineOrdersChannel = NotificationChannel(ONLINE_ORDERS_CHANNEL_ID, ONLINE_ORDERS_CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Alerts for incoming online customer orders"
                    enableVibration(true)
                }
                notificationManager.createNotificationChannel(onlineOrdersChannel)
            }
        } catch (e: Throwable) {
            android.util.Log.w("NotificationHelper", "Could not create notification channels: ${e.message}")
        }
    }

    fun checkAndNotifyCreditOverdue(
        context: Context,
        overdueCount: Int,
        totalOverdueAmount: Double,
        topCustomerName: String? = null,
        forceNotify: Boolean = false
    ) {
        if (overdueCount <= 0 || totalOverdueAmount <= 0.0) return
        createNotificationChannel(context)

        val prefs = getPrefs(context)
        val now = System.currentTimeMillis()
        val lastNotifTime = prefs.getLong(KEY_LAST_CREDIT_NOTIF_TIME, 0L)
        val lastNotifCount = prefs.getInt(KEY_LAST_CREDIT_NOTIF_COUNT, 0)

        // Throttle automated notifications to at most once per 24 hours unless forced or overdue count increased
        if (!forceNotify) {
            val timeElapsed = now - lastNotifTime
            if (timeElapsed in 0 until COOLDOWN_CREDIT_NOTIF_MS && overdueCount <= lastNotifCount) {
                return
            }
        }

        // Save notification timestamp and count
        prefs.edit()
            .putLong(KEY_LAST_CREDIT_NOTIF_TIME, now)
            .putInt(KEY_LAST_CREDIT_NOTIF_COUNT, overdueCount)
            .apply()

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent: PendingIntent = PendingIntent.getActivity(
            context,
            9001,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val totalFormatted = "₹%.2f".format(totalOverdueAmount)
        val customerNoun = if (overdueCount == 1) "Customer" else "Customers"
        val title = "🔴 Khata Alert: $overdueCount Overdue $customerNoun ($totalFormatted)"
        val message = if (!topCustomerName.isNullOrBlank()) {
            if (overdueCount == 1) {
                "1 customer account has overdue payment totaling $totalFormatted for $topCustomerName. Tap to review Khata."
            } else {
                "$overdueCount customer accounts have overdue payments totaling $totalFormatted. Including $topCustomerName. Tap to review Khata."
            }
        } else {
            if (overdueCount == 1) {
                "1 customer account has overdue payment totaling $totalFormatted. Tap to review Khata and send reminders."
            } else {
                "$overdueCount customer accounts have overdue payments totaling $totalFormatted. Tap to review Khata and send reminders."
            }
        }

        val builder = NotificationCompat.Builder(context, CREDIT_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)

        try {
            val notificationManager = NotificationManagerCompat.from(context)
            if (notificationManager.areNotificationsEnabled()) {
                notificationManager.notify(9001, builder.build())
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun checkAndNotifyLowStock(
        context: Context,
        productId: String,
        productName: String,
        currentStock: Double,
        threshold: Double,
        unitType: String,
        forceNotify: Boolean = false
    ) {
        createNotificationChannel(context)

        // Only notify if stock is below or equal to threshold
        if (currentStock <= threshold) {
            val prefs = getPrefs(context)
            val now = System.currentTimeMillis()
            val lastStockNotifKey = "low_stock_${productId}_time"
            val lastStockValKey = "low_stock_${productId}_val"
            val lastNotifTime = prefs.getLong(lastStockNotifKey, 0L)
            val lastNotifStock = prefs.getFloat(lastStockValKey, -1f)

            // Avoid re-notifying for the exact same stock level within 12 hours unless stock reduced further or forced
            if (!forceNotify) {
                val timeElapsed = now - lastNotifTime
                if (timeElapsed in 0 until (12 * 60 * 60 * 1000L) && lastNotifStock >= 0f && currentStock >= lastNotifStock.toDouble()) {
                    return
                }
            }

            prefs.edit()
                .putLong(lastStockNotifKey, now)
                .putFloat(lastStockValKey, currentStock.toFloat())
                .apply()

            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingIntent: PendingIntent = PendingIntent.getActivity(
                context,
                productId.hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val stockFormatted = if (currentStock % 1.0 == 0.0) currentStock.toInt().toString() else "%.2f".format(currentStock)
            val thresholdFormatted = if (threshold % 1.0 == 0.0) threshold.toInt().toString() else "%.2f".format(threshold)

            val title = "⚠️ Low Stock Alert: $productName"
            val message = "$productName stock is low! Only $stockFormatted $unitType remaining (Minimum threshold: $thresholdFormatted $unitType)."

            val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle(title)
                .setContentText(message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)

            try {
                val notificationManager = NotificationManagerCompat.from(context)
                if (notificationManager.areNotificationsEnabled()) {
                    notificationManager.notify(productId.hashCode(), builder.build())
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun notifyNewPaymentClaim(
        context: Context,
        customerName: String,
        claimedAmount: Double,
        claimId: String,
        isBengali: Boolean = false
    ) {
        createNotificationChannel(context)

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("OPEN_TAB", "CREDIT")
            putExtra("OPEN_CLAIM_ID", claimId)
        }
        val pendingIntent: PendingIntent = PendingIntent.getActivity(
            context,
            claimId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val amountFmt = "₹%.2f".format(claimedAmount)
        val title = if (isBengali) "💰 নতুন পেমেন্ট ক্লেইম: $customerName" else "💰 New Payment Claim: $customerName"
        val message = if (isBengali) {
            "$customerName ₹$amountFmt UPI পেমেন্ট রিপোর্ট করেছেন। খাতা আপডেট করতে নিশ্চিত করুন।"
        } else {
            "$customerName reported paying $amountFmt via UPI. Tap to review and confirm ledger entry."
        }

        val builder = NotificationCompat.Builder(context, CREDIT_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)

        try {
            val notificationManager = NotificationManagerCompat.from(context)
            if (notificationManager.areNotificationsEnabled()) {
                notificationManager.notify(claimId.hashCode(), builder.build())
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun playOrderAlertSound(context: Context) {
        try {
            val alertUri = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION)
                ?: android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_ALARM)
            val ringtone = android.media.RingtoneManager.getRingtone(context.applicationContext, alertUri)
            ringtone?.play()
        } catch (e: Exception) {
            android.util.Log.w("NotificationHelper", "Could not play alert sound: ${e.message}")
        }
    }

    fun notifyNewOnlineOrder(
        context: Context,
        order: com.example.data.models.Order,
        isBengali: Boolean = false
    ) {
        createNotificationChannel(context)

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("OPEN_DESTINATION", "incoming_orders")
            putExtra("OPEN_ORDER_ID", order.id)
        }
        val pendingIntent: PendingIntent = PendingIntent.getActivity(
            context,
            order.id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val amountFmt = "₹%.2f".format(order.totalAmount)
        val title = if (isBengali) "🛍️ নতুন অনলাইন অর্ডার: ${order.orderNumber}" else "🛍️ New Online Order: ${order.orderNumber}"
        val fulfillment = if (order.fulfillmentType == "DELIVERY") {
            if (isBengali) "হোম ডেলিভারি" else "Delivery"
        } else {
            if (isBengali) "দোকান থেকে পিকআপ" else "Store Pickup"
        }
        val message = if (isBengali) {
            "${order.customerName} ($fulfillment) $amountFmt মূল্যের অর্ডার দিয়েছেন (${order.itemsCount} পণ্য)। দেখতে ট্যাপ করুন।"
        } else {
            "${order.customerName} placed a $amountFmt $fulfillment order (${order.itemsCount} items). Tap to review."
        }

        val builder = NotificationCompat.Builder(context, ONLINE_ORDERS_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)

        try {
            val notificationManager = NotificationManagerCompat.from(context)
            if (notificationManager.areNotificationsEnabled()) {
                notificationManager.notify(order.id.hashCode(), builder.build())
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
