package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.data.local.dao.*
import com.example.data.local.entities.*

@Database(
    entities = [
        Product::class,
        Sale::class,
        SaleItem::class,
        Purchase::class,
        PurchaseItem::class,
        Customer::class,
        Supplier::class,
        LedgerEntry::class,
        Expense::class,
        SaleReturn::class,
        ReturnItem::class,
        ProductBatch::class,
        Employee::class,
        EmployeeAttendance::class,
        EmployeeSalaryDue::class,
        EmployeeSalaryPayment::class,
        EmployeeAdvance::class,
        StockOutEntry::class,
        Offer::class,
        PaymentClaim::class
    ],
    version = 32,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun productDao(): ProductDao
    abstract fun productBatchDao(): ProductBatchDao
    abstract fun saleDao(): SaleDao
    abstract fun purchaseDao(): PurchaseDao
    abstract fun customerDao(): CustomerDao
    abstract fun supplierDao(): SupplierDao
    abstract fun ledgerDao(): LedgerDao
    abstract fun expenseDao(): ExpenseDao
    abstract fun saleReturnDao(): SaleReturnDao
    abstract fun employeeDao(): EmployeeDao
    abstract fun employeeAttendanceDao(): EmployeeAttendanceDao
    abstract fun employeeSalaryDao(): EmployeeSalaryDao
    abstract fun stockOutDao(): StockOutDao
    abstract fun offerDao(): OfferDao
    abstract fun paymentClaimDao(): PaymentClaimDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        private val MIGRATION_31_32 = object : Migration(31, 32) {
            override fun migrate(database: SupportSQLiteDatabase) {
                try {
                    database.execSQL("ALTER TABLE products ADD COLUMN boxMrp REAL DEFAULT NULL")
                } catch (_: Exception) {}
            }
        }

        private val MIGRATION_30_31 = object : Migration(30, 31) {
            override fun migrate(database: SupportSQLiteDatabase) {
                try {
                    database.execSQL("ALTER TABLE sale_items ADD COLUMN variantBarcode TEXT DEFAULT NULL")
                } catch (_: Exception) {}
            }
        }

        private val MIGRATION_29_30 = object : Migration(29, 30) {
            override fun migrate(database: SupportSQLiteDatabase) {
                val tables = listOf(
                    "products",
                    "product_batches",
                    "sales",
                    "purchases",
                    "customers",
                    "suppliers",
                    "expenses",
                    "ledger_entries",
                    "sale_returns",
                    "employees",
                    "employee_attendance",
                    "employee_salary_dues",
                    "employee_salary_payments",
                    "employee_advances"
                )
                for (table in tables) {
                    try {
                        database.execSQL("ALTER TABLE `$table` ADD COLUMN `needsSync` INTEGER NOT NULL DEFAULT 0")
                    } catch (_: Exception) {}
                }
            }
        }

        private val MIGRATION_28_29 = object : Migration(28, 29) {
            override fun migrate(database: SupportSQLiteDatabase) {
                try {
                    database.execSQL("ALTER TABLE products ADD COLUMN bulkUnitType TEXT DEFAULT NULL")
                    database.execSQL("ALTER TABLE products ADD COLUMN bulkQuantity REAL DEFAULT NULL")
                    database.execSQL("ALTER TABLE products ADD COLUMN bulkPrice REAL DEFAULT NULL")
                } catch (_: Exception) {}
            }
        }

        private val MIGRATION_27_28 = object : Migration(27, 28) {
            override fun migrate(database: SupportSQLiteDatabase) {
                try {
                    database.execSQL("ALTER TABLE products ADD COLUMN isOnlineVisible INTEGER NOT NULL DEFAULT 1")
                    database.execSQL("ALTER TABLE products ADD COLUMN onlineMinOrderQty REAL NOT NULL DEFAULT 1.0")
                    database.execSQL("ALTER TABLE products ADD COLUMN onlineMaxOrderQty REAL NOT NULL DEFAULT 10.0")
                } catch (_: Exception) {}
            }
        }

        private val MIGRATION_26_27 = object : Migration(26, 27) {
            override fun migrate(database: SupportSQLiteDatabase) {
                try {
                    database.execSQL("""
                        CREATE TABLE IF NOT EXISTS `offers` (
                            `id` TEXT NOT NULL PRIMARY KEY,
                            `name` TEXT NOT NULL,
                            `type` TEXT NOT NULL,
                            `applicableProductIds` TEXT NOT NULL,
                            `discountValue` REAL NOT NULL,
                            `buyQty` REAL NOT NULL,
                            `getQty` REAL NOT NULL,
                            `getDiscountPercent` REAL NOT NULL,
                            `comboPrice` REAL NOT NULL,
                            `comboProductsJson` TEXT NOT NULL,
                            `startDate` TEXT,
                            `endDate` TEXT,
                            `isActive` INTEGER NOT NULL,
                            `createdAt` INTEGER NOT NULL,
                            `updatedAt` INTEGER NOT NULL,
                            `minSpendAmount` REAL NOT NULL,
                            `freeProductId` TEXT,
                            `freeProductQty` REAL NOT NULL,
                            `freeProductUnit` TEXT
                        )
                    """.trimIndent())
                    database.execSQL("ALTER TABLE offers ADD COLUMN freeProductUnit TEXT DEFAULT NULL")
                } catch (_: Exception) {}
            }
        }

        private val MIGRATION_25_27 = object : Migration(25, 27) {
            override fun migrate(database: SupportSQLiteDatabase) {
                try {
                    database.execSQL("""
                        CREATE TABLE IF NOT EXISTS `offers` (
                            `id` TEXT NOT NULL PRIMARY KEY,
                            `name` TEXT NOT NULL,
                            `type` TEXT NOT NULL,
                            `applicableProductIds` TEXT NOT NULL,
                            `discountValue` REAL NOT NULL,
                            `buyQty` REAL NOT NULL,
                            `getQty` REAL NOT NULL,
                            `getDiscountPercent` REAL NOT NULL,
                            `comboPrice` REAL NOT NULL,
                            `comboProductsJson` TEXT NOT NULL,
                            `startDate` TEXT,
                            `endDate` TEXT,
                            `isActive` INTEGER NOT NULL,
                            `createdAt` INTEGER NOT NULL,
                            `updatedAt` INTEGER NOT NULL,
                            `minSpendAmount` REAL NOT NULL,
                            `freeProductId` TEXT,
                            `freeProductQty` REAL NOT NULL,
                            `freeProductUnit` TEXT
                        )
                    """.trimIndent())
                } catch (_: Exception) {}
            }
        }

        private fun buildDatabaseInstance(context: Context): AppDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "kali_mata_store_db"
            )
                .addMigrations(MIGRATION_31_32, MIGRATION_30_31, MIGRATION_29_30, MIGRATION_28_29, MIGRATION_27_28, MIGRATION_26_27, MIGRATION_25_27)
                .fallbackToDestructiveMigration()
                .fallbackToDestructiveMigrationOnDowngrade()
                .build()
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: try {
                    val db = buildDatabaseInstance(context)
                    // Pre-warm open to catch any schema validation errors safely
                    db.openHelper.writableDatabase
                    INSTANCE = db
                    db
                } catch (e: Throwable) {
                    android.util.Log.e("AppDatabase", "Error opening database, fallback to recreation: ${e.message}", e)
                    try {
                        context.applicationContext.deleteDatabase("kali_mata_store_db")
                    } catch (_: Throwable) {}
                    val freshDb = buildDatabaseInstance(context)
                    INSTANCE = freshDb
                    freshDb
                }
            }
        }
    }
}
