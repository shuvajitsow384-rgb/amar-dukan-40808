package com.example.widget

import android.content.Context
import android.content.SharedPreferences
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object WidgetDataManager {
    private const val PREFS_NAME = "amar_dukan_widget_prefs"
    private const val KEY_TODAY_SALES = "widget_today_sales"
    private const val KEY_TODAY_BILLS = "widget_today_bills"
    private const val KEY_TODAY_PROFIT = "widget_today_profit"
    private const val KEY_TODAY_CASH_SALES = "widget_today_cash_sales"
    private const val KEY_TODAY_UPI_SALES = "widget_today_upi_sales"
    private const val KEY_TODAY_CREDIT_SALES = "widget_today_credit_sales"
    private const val KEY_LOW_STOCK_COUNT = "widget_low_stock_count"
    private const val KEY_YESTERDAY_SALES_SAMETIME = "widget_yesterday_sales_sametime"
    private const val KEY_LAST_UPDATED = "widget_last_updated"

    // Configuration Toggles & Preferences
    private const val KEY_SHOW_PROFIT_ENABLED = "widget_show_profit_enabled"
    private const val KEY_SHOW_PAYMENT_SPLIT = "widget_show_payment_split"
    private const val KEY_SHOW_GOAL_PROGRESS = "widget_show_goal_progress"
    private const val KEY_SHOW_QUICK_ACTIONS = "widget_show_quick_actions"
    private const val KEY_SHOW_LOW_STOCK_ALERT = "widget_show_low_stock_alert"
    private const val KEY_PRIVACY_MASK_ENABLED = "widget_privacy_mask_enabled"
    private const val KEY_DAILY_SALES_GOAL = "widget_daily_sales_goal"
    private const val KEY_WIDGET_THEME = "widget_theme"

    const val THEME_CRIMSON = "crimson"
    const val THEME_OBSIDIAN = "obsidian"
    const val THEME_SAPPHIRE = "sapphire"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun getTodaySales(context: Context): Double {
        return getPrefs(context).getFloat(KEY_TODAY_SALES, 0f).toDouble()
    }

    fun getTodayBills(context: Context): Int {
        return getPrefs(context).getInt(KEY_TODAY_BILLS, 0)
    }

    fun getTodayProfit(context: Context): Double {
        return getPrefs(context).getFloat(KEY_TODAY_PROFIT, 0f).toDouble()
    }

    fun getTodayCashSales(context: Context): Double {
        return getPrefs(context).getFloat(KEY_TODAY_CASH_SALES, 0f).toDouble()
    }

    fun getTodayUpiSales(context: Context): Double {
        return getPrefs(context).getFloat(KEY_TODAY_UPI_SALES, 0f).toDouble()
    }

    fun getTodayCreditSales(context: Context): Double {
        return getPrefs(context).getFloat(KEY_TODAY_CREDIT_SALES, 0f).toDouble()
    }

    fun getLowStockCount(context: Context): Int {
        return getPrefs(context).getInt(KEY_LOW_STOCK_COUNT, 0)
    }

    fun getYesterdaySalesSameTime(context: Context): Double {
        return getPrefs(context).getFloat(KEY_YESTERDAY_SALES_SAMETIME, 0f).toDouble()
    }

    fun getLastUpdatedTime(context: Context): Long {
        return getPrefs(context).getLong(KEY_LAST_UPDATED, 0L)
    }

    // Profit toggle (protected, default OFF)
    fun isShowProfitEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_SHOW_PROFIT_ENABLED, false)
    }

    fun setShowProfitEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_SHOW_PROFIT_ENABLED, enabled).apply()
    }

    // Payment Split toggle (Cash / UPI, default ON)
    fun isShowPaymentSplitEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_SHOW_PAYMENT_SPLIT, true)
    }

    fun setShowPaymentSplitEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_SHOW_PAYMENT_SPLIT, enabled).apply()
    }

    // Goal Progress toggle (default ON)
    fun isShowGoalProgressEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_SHOW_GOAL_PROGRESS, true)
    }

    fun setShowGoalProgressEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_SHOW_GOAL_PROGRESS, enabled).apply()
    }

    // Quick Actions Bar toggle (default ON)
    fun isShowQuickActionsEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_SHOW_QUICK_ACTIONS, true)
    }

    fun setShowQuickActionsEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_SHOW_QUICK_ACTIONS, enabled).apply()
    }

    // Low Stock Alert Badge toggle (default ON)
    fun isShowLowStockAlertEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_SHOW_LOW_STOCK_ALERT, true)
    }

    fun setShowLowStockAlertEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_SHOW_LOW_STOCK_ALERT, enabled).apply()
    }

    // Privacy Mask Mode (hide sensitive sales numbers, default OFF)
    fun isPrivacyMaskEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_PRIVACY_MASK_ENABLED, false)
    }

    fun setPrivacyMaskEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_PRIVACY_MASK_ENABLED, enabled).apply()
    }

    fun togglePrivacyMask(context: Context): Boolean {
        val current = isPrivacyMaskEnabled(context)
        val newMask = !current
        setPrivacyMaskEnabled(context, newMask)
        return newMask
    }

    // Daily Sales Goal (default: 5000.0)
    fun getDailySalesGoal(context: Context): Double {
        return getPrefs(context).getFloat(KEY_DAILY_SALES_GOAL, 5000f).toDouble()
    }

    fun setDailySalesGoal(context: Context, goal: Double) {
        getPrefs(context).edit().putFloat(KEY_DAILY_SALES_GOAL, goal.toFloat().coerceAtLeast(100f)).apply()
    }

    // Widget Theme (crimson, obsidian, sapphire)
    fun getWidgetTheme(context: Context): String {
        return getPrefs(context).getString(KEY_WIDGET_THEME, THEME_CRIMSON) ?: THEME_CRIMSON
    }

    fun setWidgetTheme(context: Context, theme: String) {
        getPrefs(context).edit().putString(KEY_WIDGET_THEME, theme).apply()
    }

    fun saveWidgetStats(
        context: Context,
        todaySales: Double,
        todayBills: Int,
        todayProfit: Double,
        todayCashSales: Double = 0.0,
        todayUpiSales: Double = 0.0,
        todayCreditSales: Double = 0.0,
        lowStockCount: Int = 0,
        yesterdaySalesSameTime: Double = 0.0,
        timestamp: Long = System.currentTimeMillis()
    ) {
        getPrefs(context).edit()
            .putFloat(KEY_TODAY_SALES, todaySales.toFloat())
            .putInt(KEY_TODAY_BILLS, todayBills)
            .putFloat(KEY_TODAY_PROFIT, todayProfit.toFloat())
            .putFloat(KEY_TODAY_CASH_SALES, todayCashSales.toFloat())
            .putFloat(KEY_TODAY_UPI_SALES, todayUpiSales.toFloat())
            .putFloat(KEY_TODAY_CREDIT_SALES, todayCreditSales.toFloat())
            .putInt(KEY_LOW_STOCK_COUNT, lowStockCount)
            .putFloat(KEY_YESTERDAY_SALES_SAMETIME, yesterdaySalesSameTime.toFloat())
            .putLong(KEY_LAST_UPDATED, timestamp)
            .apply()
    }

    fun getFormattedLastUpdated(context: Context): String {
        val lastUpdated = getLastUpdatedTime(context)
        if (lastUpdated <= 0L) {
            return "Updated: Just now"
        }
        val formatter = SimpleDateFormat("hh:mm a", Locale.getDefault())
        return "Updated: ${formatter.format(Date(lastUpdated))}"
    }
}
