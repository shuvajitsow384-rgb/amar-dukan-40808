package com.example.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import com.example.MainActivity
import com.example.R
import com.example.data.local.AppDatabase
import com.example.data.repository.StoreRepository
import com.example.utils.LanguageManager
import com.example.utils.StoreInfoManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.Calendar
import kotlin.math.abs

class TodaySalesWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        // 1. Immediately render cached data to avoid any blank or stuck-loading widget states
        for (appWidgetId in appWidgetIds) {
            try {
                renderCachedWidget(context, appWidgetManager, appWidgetId)
            } catch (e: Throwable) {
                android.util.Log.e("TodaySalesWidget", "Error in onUpdate for widget $appWidgetId: ${e.message}", e)
            }
        }

        // 2. Asynchronously query Room SQLite to get fresh numbers with zero UI freeze
        recalculateAndUpdateWidgets(context)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        try {
            renderCachedWidget(context, appWidgetManager, appWidgetId)
        } catch (e: Throwable) {
            android.util.Log.e("TodaySalesWidget", "Error in onAppWidgetOptionsChanged for widget $appWidgetId: ${e.message}", e)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_REFRESH_WIDGET,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_BOOT_COMPLETED -> {
                recalculateAndUpdateWidgets(context)
            }
            ACTION_TOGGLE_PRIVACY -> {
                WidgetDataManager.togglePrivacyMask(context)
                pushUpdateToAllWidgets(context)
            }
        }
    }

    companion object {
        const val ACTION_REFRESH_WIDGET = "com.example.widget.ACTION_REFRESH_WIDGET"
        const val ACTION_TOGGLE_PRIVACY = "com.example.widget.ACTION_TOGGLE_PRIVACY"
        const val EXTRA_NAV_DESTINATION = "nav_destination"

        private val widgetScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /**
         * Renders the widget RemoteViews using cached numbers and user preferences.
         * Guaranteed to return immediately and render high-contrast responsive styling.
         */
        fun renderCachedWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int
        ) {
            try {
                val views = RemoteViews(context.packageName, R.layout.widget_today_sales)

            val isBn = try { LanguageManager.isBengali } catch (e: Throwable) { false }
            val isMasked = WidgetDataManager.isPrivacyMaskEnabled(context)
            val theme = WidgetDataManager.getWidgetTheme(context)

            val todaySales = WidgetDataManager.getTodaySales(context)
            val todayBills = WidgetDataManager.getTodayBills(context)
            val todayProfit = WidgetDataManager.getTodayProfit(context)
            val todayCash = WidgetDataManager.getTodayCashSales(context)
            val todayUpi = WidgetDataManager.getTodayUpiSales(context)
            val lowStockCount = WidgetDataManager.getLowStockCount(context)
            val yesterdaySales = WidgetDataManager.getYesterdaySalesSameTime(context)
            val showProfit = WidgetDataManager.isShowProfitEnabled(context)
            val showSplit = WidgetDataManager.isShowPaymentSplitEnabled(context)
            val showGoal = WidgetDataManager.isShowGoalProgressEnabled(context)
            val showQuickActions = WidgetDataManager.isShowQuickActionsEnabled(context)
            val showLowStock = WidgetDataManager.isShowLowStockAlertEnabled(context)
            val dailyGoal = WidgetDataManager.getDailySalesGoal(context)
            val updatedStr = WidgetDataManager.getFormattedLastUpdated(context)
            val storeName = StoreInfoManager.storeName.ifBlank { if (isBn) "আমার দোকান" else "Amar Dukan" }

            // Apply Theme Background
            val themeBgDrawable = when (theme) {
                WidgetDataManager.THEME_OBSIDIAN -> R.drawable.bg_widget_card_midnight
                WidgetDataManager.THEME_SAPPHIRE -> R.drawable.bg_widget_card_sapphire
                else -> R.drawable.bg_widget_card
            }
            views.setInt(R.id.widget_root, "setBackgroundResource", themeBgDrawable)

            // Header Elements
            views.setTextViewText(R.id.tv_widget_store_name, storeName)
            views.setImageViewResource(
                R.id.btn_widget_privacy,
                if (isMasked) R.drawable.ic_widget_eye_off else R.drawable.ic_widget_eye
            )

            // Sales Header Label
            views.setTextViewText(
                R.id.tv_widget_sales_label,
                if (isBn) "আজকের মোট বিক্রি" else "TODAY'S SALES"
            )

            // Sales Value (Masked vs Real)
            if (isMasked) {
                views.setTextViewText(R.id.tv_widget_sales_value, "₹ ••••••")
            } else {
                views.setTextViewText(R.id.tv_widget_sales_value, "₹%.2f".format(todaySales))
            }

            // Comparison indicator vs yesterday at same time of day (Status Green ▲ / Status Red ▼ / Neutral •)
            if (!isMasked && (yesterdaySales > 0.0 || todaySales > 0.0)) {
                views.setViewVisibility(R.id.tv_widget_sales_comparison, View.VISIBLE)
                val diff = todaySales - yesterdaySales
                val pct = if (yesterdaySales > 0.0) (abs(diff) / yesterdaySales * 100.0) else 100.0
                if (diff > 0.001) {
                    views.setTextViewText(R.id.tv_widget_sales_comparison, "▲ +₹%.0f (+%.0f%%)".format(diff, pct))
                    views.setTextColor(R.id.tv_widget_sales_comparison, Color.parseColor("#4ADE80"))
                    views.setInt(R.id.tv_widget_sales_comparison, "setBackgroundResource", R.drawable.bg_widget_comp_up)
                } else if (diff < -0.001) {
                    views.setTextViewText(R.id.tv_widget_sales_comparison, "▼ -₹%.0f (-%.0f%%)".format(abs(diff), pct))
                    views.setTextColor(R.id.tv_widget_sales_comparison, Color.parseColor("#F87171"))
                    views.setInt(R.id.tv_widget_sales_comparison, "setBackgroundResource", R.drawable.bg_widget_comp_down)
                } else {
                    views.setTextViewText(R.id.tv_widget_sales_comparison, if (isBn) "• গতকালের সমান" else "• Same as yest")
                    views.setTextColor(R.id.tv_widget_sales_comparison, Color.parseColor("#FFFFFF"))
                    views.setInt(R.id.tv_widget_sales_comparison, "setBackgroundResource", R.drawable.bg_widget_comp_neutral)
                }
            } else {
                views.setViewVisibility(R.id.tv_widget_sales_comparison, View.GONE)
            }

            // Sub-Metric Pills:
            // 1. Bills Count Pill
            views.setTextViewText(
                R.id.tv_widget_bills_count,
                if (isBn) "$todayBills টি বিল" else "$todayBills Bills"
            )

            // 2. Cash vs UPI Payment Split Pill
            if (showSplit && (todayCash > 0.0 || todayUpi > 0.0)) {
                views.setViewVisibility(R.id.tv_widget_payment_split, View.VISIBLE)
                if (isMasked) {
                    views.setTextViewText(R.id.tv_widget_payment_split, "💵 ••• | 📱 •••")
                } else {
                    views.setTextViewText(
                        R.id.tv_widget_payment_split,
                        "💵 ₹%.0f | 📱 ₹%.0f".format(todayCash, todayUpi)
                    )
                }
            } else {
                views.setViewVisibility(R.id.tv_widget_payment_split, View.GONE)
            }

            // 3. Profit Pill (conditionally displayed based on owner's Settings toggle)
            if (showProfit) {
                views.setViewVisibility(R.id.layout_widget_profit_container, View.VISIBLE)
                if (isMasked) {
                    views.setTextViewText(
                        R.id.tv_widget_profit_value,
                        if (isBn) "লাভ: ₹ •••••" else "Profit: ₹ •••••"
                    )
                } else {
                    val marginPct = if (todaySales > 0.0) ((todayProfit / todaySales) * 100.0).coerceAtLeast(0.0) else 0.0
                    views.setTextViewText(
                        R.id.tv_widget_profit_value,
                        if (isBn) "লাভ: ₹%.0f (%.0f%%)".format(todayProfit, marginPct) else "Profit: ₹%.0f (%.0f%%)".format(todayProfit, marginPct)
                    )
                }
            } else {
                views.setViewVisibility(R.id.layout_widget_profit_container, View.GONE)
            }

            // 4. Low Stock Alert Pill
            val isLowStockVisible = showLowStock && lowStockCount > 0
            if (isLowStockVisible) {
                views.setViewVisibility(R.id.tv_widget_low_stock, View.VISIBLE)
                views.setTextViewText(
                    R.id.tv_widget_low_stock,
                    if (isBn) "⚠️ $lowStockCount টি স্টক কম" else "⚠️ $lowStockCount Low Stock"
                )
            } else {
                views.setViewVisibility(R.id.tv_widget_low_stock, View.GONE)
            }

            // Sub-metrics Row 2 visibility (Profit or Low Stock Alert)
            val isRow2Visible = showProfit || isLowStockVisible
            views.setViewVisibility(
                R.id.layout_widget_pills_row2,
                if (isRow2Visible) View.VISIBLE else View.GONE
            )

            // Detect widget height constraints from AppWidgetManager options
            val options = appWidgetManager.getAppWidgetOptions(appWidgetId)
            val minHeight = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0) ?: 0
            val isTightHeight = minHeight in 1..130

            // 5. Daily Sales Target / Goal Progress Bar
            // On tight 2-cell launcher spaces (<130dp), if quick actions are enabled, hide goal to avoid vertical clipping
            val canShowGoal = showGoal && dailyGoal > 0.0 && (!isTightHeight || !showQuickActions)
            if (canShowGoal) {
                views.setViewVisibility(R.id.layout_widget_goal, View.VISIBLE)
                val percent = ((todaySales / dailyGoal) * 100.0).toInt().coerceIn(0, 100)
                if (isMasked) {
                    views.setTextViewText(
                        R.id.tv_widget_goal_text,
                        if (isBn) "🎯 লক্ষ্য অগ্রগতি: $percent%" else "🎯 Goal Progress: $percent%"
                    )
                } else {
                    views.setTextViewText(
                        R.id.tv_widget_goal_text,
                        if (isBn) "🎯 লক্ষ্য: ₹%.0f / ₹%.0f (%d%%)".format(todaySales, dailyGoal, percent)
                        else "🎯 Goal: ₹%.0f / ₹%.0f (%d%%)".format(todaySales, dailyGoal, percent)
                    )
                }
                views.setProgressBar(R.id.pb_widget_goal, 100, percent, false)
            } else {
                views.setViewVisibility(R.id.layout_widget_goal, View.GONE)
            }

            // 6. Quick Action Shortcuts
            if (showQuickActions) {
                views.setViewVisibility(R.id.layout_widget_actions, View.VISIBLE)
                views.setTextViewText(R.id.tv_widget_action_pos_label, if (isBn) "+ বিল" else "+ Sale")
                views.setTextViewText(R.id.tv_widget_action_khata_label, if (isBn) "খাতা" else "Khata")
                views.setTextViewText(R.id.tv_widget_action_stock_label, if (isBn) "স্টক" else "Stock")
                views.setTextViewText(R.id.tv_widget_action_reports_label, if (isBn) "লাভ" else "P&L")

                views.setOnClickPendingIntent(
                    R.id.btn_widget_action_pos,
                    createNavPendingIntent(context, "pos", 201)
                )
                views.setOnClickPendingIntent(
                    R.id.btn_widget_action_khata,
                    createNavPendingIntent(context, "credit", 202)
                )
                views.setOnClickPendingIntent(
                    R.id.btn_widget_action_stock,
                    createNavPendingIntent(context, "inventory", 203)
                )
                views.setOnClickPendingIntent(
                    R.id.btn_widget_action_reports,
                    createNavPendingIntent(context, "reports", 204)
                )
            } else {
                views.setViewVisibility(R.id.layout_widget_actions, View.GONE)
            }

            // Footer updated time
            views.setTextViewText(R.id.tv_widget_updated_time, updatedStr)

            // Clicking on the widget body opens the main app
            val launchIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val launchPendingIntent = PendingIntent.getActivity(
                context,
                0,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_root, launchPendingIntent)

            // Clicking low stock pill navigates to inventory
            views.setOnClickPendingIntent(
                R.id.tv_widget_low_stock,
                createNavPendingIntent(context, "inventory", 205)
            )

            // Clicking the refresh icon triggers an immediate recalculation broadcast
            val refreshIntent = Intent(context, TodaySalesWidgetProvider::class.java).apply {
                action = ACTION_REFRESH_WIDGET
            }
            val refreshPendingIntent = PendingIntent.getBroadcast(
                context,
                101,
                refreshIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.btn_widget_refresh, refreshPendingIntent)

            // Clicking privacy eye icon triggers privacy toggle broadcast
            val privacyIntent = Intent(context, TodaySalesWidgetProvider::class.java).apply {
                action = ACTION_TOGGLE_PRIVACY
            }
            val privacyPendingIntent = PendingIntent.getBroadcast(
                context,
                102,
                privacyIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.btn_widget_privacy, privacyPendingIntent)

            appWidgetManager.updateAppWidget(appWidgetId, views)
        } catch (e: Throwable) {
            android.util.Log.e("TodaySalesWidget", "Error rendering widget $appWidgetId: ${e.message}", e)
        }
    }

        private fun createNavPendingIntent(context: Context, destination: String, requestCode: Int): PendingIntent {
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_NAV_DESTINATION, destination)
            }
            return PendingIntent.getActivity(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        /**
         * Re-calculates today's sales, bills count, profit, payment modes, low stock, and yesterday's comparison from Room DB,
         * persists stats to the cache, and pushes updates to all active home screen widgets.
         */
        fun recalculateAndUpdateWidgets(context: Context) {
            val appContext = context.applicationContext
            widgetScope.launch {
                try {
                    val db = AppDatabase.getDatabase(appContext)
                    val repository = StoreRepository(db, appContext)

                    val startOfDay = Calendar.getInstance().apply {
                        set(Calendar.HOUR_OF_DAY, 0)
                        set(Calendar.MINUTE, 0)
                        set(Calendar.SECOND, 0)
                        set(Calendar.MILLISECOND, 0)
                    }.timeInMillis

                    val endOfDay = Calendar.getInstance().apply {
                        set(Calendar.HOUR_OF_DAY, 23)
                        set(Calendar.MINUTE, 59)
                        set(Calendar.SECOND, 59)
                        set(Calendar.MILLISECOND, 999)
                    }.timeInMillis

                    val startOfYesterday = Calendar.getInstance().apply {
                        add(Calendar.DAY_OF_YEAR, -1)
                        set(Calendar.HOUR_OF_DAY, 0)
                        set(Calendar.MINUTE, 0)
                        set(Calendar.SECOND, 0)
                        set(Calendar.MILLISECOND, 0)
                    }.timeInMillis

                    val yesterdaySameTime = Calendar.getInstance().apply {
                        add(Calendar.DAY_OF_YEAR, -1)
                    }.timeInMillis

                    val todayPnl = repository.generatePnlReport(startOfDay, endOfDay)
                    val yesterdayPnl = repository.generatePnlReport(startOfYesterday, yesterdaySameTime)
                    val lowStockCount = try {
                        db.productDao().getLowStockProductsCount()
                    } catch (e: Exception) {
                        0
                    }

                    val netSalesRevenue = todayPnl.totalRevenue
                    val billsCount = todayPnl.totalSalesCount
                    val todayNetProfit = todayPnl.netProfit
                    val cashSales = todayPnl.cashSales
                    val upiSales = todayPnl.upiSales
                    val creditSales = todayPnl.creditSales
                    val yesterdaySalesRevenue = yesterdayPnl.totalRevenue

                    // Cache values with current timestamp
                    WidgetDataManager.saveWidgetStats(
                        context = appContext,
                        todaySales = netSalesRevenue,
                        todayBills = billsCount,
                        todayProfit = todayNetProfit,
                        todayCashSales = cashSales,
                        todayUpiSales = upiSales,
                        todayCreditSales = creditSales,
                        lowStockCount = lowStockCount,
                        yesterdaySalesSameTime = yesterdaySalesRevenue,
                        timestamp = System.currentTimeMillis()
                    )

                    // Update all widgets
                    pushUpdateToAllWidgets(appContext)
                } catch (e: Exception) {
                    android.util.Log.e("TodaySalesWidget", "Failed to recalculate widget data: ${e.message}", e)
                    // If error occurs, still push cached data to ensure widget isn't stuck
                    pushUpdateToAllWidgets(appContext)
                }
            }
        }

        /**
         * Updates all instances of the TodaySalesWidget on the home screen.
         */
        fun pushUpdateToAllWidgets(context: Context) {
            try {
                val appWidgetManager = AppWidgetManager.getInstance(context)
                val componentName = ComponentName(context, TodaySalesWidgetProvider::class.java)
                val appWidgetIds = appWidgetManager.getAppWidgetIds(componentName)

                for (appWidgetId in appWidgetIds) {
                    renderCachedWidget(context, appWidgetManager, appWidgetId)
                }
            } catch (e: Throwable) {
                android.util.Log.e("TodaySalesWidget", "Error pushing update to widgets: ${e.message}", e)
            }
        }

        /**
         * Public hook to be called from ViewModel or Repository when sales, expenses, or settings change.
         */
        fun triggerWidgetUpdate(context: Context) {
            recalculateAndUpdateWidgets(context)
        }
    }
}
