package com.example.utils

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.example.MainActivity
import com.example.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class AppShortcutAction(val actionId: String) {
    NEW_SALE("new_sale"),
    ADD_EXPENSE("add_expense"),
    ADD_PRODUCT("add_product"),
    TODAYS_REPORT("todays_report");

    companion object {
        fun fromActionId(id: String?): AppShortcutAction? {
            return values().find { it.actionId.equals(id, ignoreCase = true) }
        }
    }
}

object ShortcutHelper {
    const val EXTRA_SHORTCUT_ACTION = "shortcut_action"

    private val _pendingShortcut = MutableStateFlow<AppShortcutAction?>(null)
    val pendingShortcut = _pendingShortcut.asStateFlow()

    fun setPendingShortcut(action: AppShortcutAction?) {
        _pendingShortcut.value = action
    }

    fun clearPendingShortcut() {
        _pendingShortcut.value = null
    }

    /**
     * Initializes dynamic shortcuts on supported Android devices (Android 7.1+ / API 25+).
     */
    fun initDynamicShortcuts(context: Context) {
        try {
            val shortcuts = mutableListOf<ShortcutInfoCompat>()

            // 1. New Sale Shortcut
            val saleIntent = Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                putExtra(EXTRA_SHORTCUT_ACTION, AppShortcutAction.NEW_SALE.actionId)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val saleShortcut = ShortcutInfoCompat.Builder(context, "shortcut_new_sale")
                .setShortLabel(context.getString(R.string.shortcut_new_sale_short))
                .setLongLabel(context.getString(R.string.shortcut_new_sale_long))
                .setIcon(IconCompat.createWithResource(context, R.drawable.ic_shortcut_sale))
                .setIntent(saleIntent)
                .build()
            shortcuts.add(saleShortcut)

            // 2. Add Expense Shortcut
            val expenseIntent = Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                putExtra(EXTRA_SHORTCUT_ACTION, AppShortcutAction.ADD_EXPENSE.actionId)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val expenseShortcut = ShortcutInfoCompat.Builder(context, "shortcut_add_expense")
                .setShortLabel(context.getString(R.string.shortcut_add_expense_short))
                .setLongLabel(context.getString(R.string.shortcut_add_expense_long))
                .setIcon(IconCompat.createWithResource(context, R.drawable.ic_shortcut_expense))
                .setIntent(expenseIntent)
                .build()
            shortcuts.add(expenseShortcut)

            // 3. Add Product Shortcut
            val productIntent = Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                putExtra(EXTRA_SHORTCUT_ACTION, AppShortcutAction.ADD_PRODUCT.actionId)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val productShortcut = ShortcutInfoCompat.Builder(context, "shortcut_add_product")
                .setShortLabel(context.getString(R.string.shortcut_add_product_short))
                .setLongLabel(context.getString(R.string.shortcut_add_product_long))
                .setIcon(IconCompat.createWithResource(context, R.drawable.ic_shortcut_product))
                .setIntent(productIntent)
                .build()
            shortcuts.add(productShortcut)

            // 4. Today's Report Shortcut
            val reportIntent = Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                putExtra(EXTRA_SHORTCUT_ACTION, AppShortcutAction.TODAYS_REPORT.actionId)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val reportShortcut = ShortcutInfoCompat.Builder(context, "shortcut_todays_report")
                .setShortLabel(context.getString(R.string.shortcut_todays_report_short))
                .setLongLabel(context.getString(R.string.shortcut_todays_report_long))
                .setIcon(IconCompat.createWithResource(context, R.drawable.ic_shortcut_report))
                .setIntent(reportIntent)
                .build()
            shortcuts.add(reportShortcut)

            ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts)
        } catch (e: Exception) {
            android.util.Log.e("ShortcutHelper", "Failed to init dynamic shortcuts: ${e.message}")
        }
    }

    /**
     * Extracts shortcut action from incoming launch intent.
     */
    fun extractShortcutFromIntent(intent: Intent?): AppShortcutAction? {
        if (intent == null) return null
        val actionId = intent.getStringExtra(EXTRA_SHORTCUT_ACTION)
            ?: intent.getStringExtra("shortcut_destination")
        return AppShortcutAction.fromActionId(actionId)
    }
}
