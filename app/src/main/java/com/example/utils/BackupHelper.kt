package com.example.utils

import android.content.Context
import com.example.data.local.AppDatabase
import com.example.data.local.entities.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream

object BackupHelper {

    suspend fun exportDataToJson(context: Context, outputStream: OutputStream): Result<Boolean> =
        withContext(Dispatchers.IO) {
            try {
                val db = AppDatabase.getDatabase(context)
                val rootJson = JSONObject()

                rootJson.put("exportedAt", System.currentTimeMillis())
                rootJson.put("appVersion", 1)

                // 1. Store Information & Settings
                val storeInfoJson = JSONObject().apply {
                    put("storeName", StoreInfoManager.storeName)
                    put("storeAddress", StoreInfoManager.storeAddress)
                    put("ownerName", StoreInfoManager.ownerName)
                    put("phone", StoreInfoManager.phone)
                    put("tagline", StoreInfoManager.tagline)
                    put("gstin", StoreInfoManager.gstin)
                    put("pdfPaperSize", StoreInfoManager.pdfPaperSize)
                    put("merchantUpiId", StoreInfoManager.merchantUpiId)
                    put("showQrOnPdf", StoreInfoManager.showQrOnPdf)
                }
                rootJson.put("storeInfo", storeInfoJson)

                // 2. Custom Categories
                val categoryPrefs = context.getSharedPreferences("inventory_categories", Context.MODE_PRIVATE)
                val customCatsSet = categoryPrefs.getStringSet("custom_cat_set", null) ?: emptySet()
                val catsArray = JSONArray()
                customCatsSet.forEach { catsArray.put(it) }
                rootJson.put("customCategories", catsArray)

                // 3. Products
                val productsList = db.productDao().getAllProductsList()
                val productsJsonArray = JSONArray()
                for (p in productsList) {
                    val pObj = JSONObject().apply {
                        put("id", p.id)
                        put("nameEn", p.nameEn)
                        put("nameBn", p.nameBn)
                        put("category", p.category)
                        put("unitType", p.unitType)
                        put("costPrice", p.costPrice)
                        put("sellingPrice", p.sellingPrice)
                        put("currentStock", p.currentStock)
                        put("lowStockThreshold", p.lowStockThreshold)
                        put("barcode", p.barcode ?: JSONObject.NULL)
                        put("secondaryUnitType", p.secondaryUnitType ?: JSONObject.NULL)
                        put("secondaryUnitRatio", p.secondaryUnitRatio)
                        val syncableImg = ImageSyncHelper.processImageUriForSync(context, p.imageUri)
                        put("imageUri", syncableImg ?: JSONObject.NULL)
                        put("expiryDate", p.expiryDate ?: JSONObject.NULL)
                        put("wholesalePrice", p.wholesalePrice)
                        put("wholesaleMinQty", p.wholesaleMinQty)
                        put("piecesPerBox", p.piecesPerBox ?: JSONObject.NULL)
                        put("boxPrice", p.boxPrice ?: JSONObject.NULL)
                        put("bulkUnitType", p.bulkUnitType ?: JSONObject.NULL)
                        put("bulkQuantity", p.bulkQuantity ?: JSONObject.NULL)
                        put("bulkPrice", p.bulkPrice ?: JSONObject.NULL)
                        put("mrp", p.mrp ?: JSONObject.NULL)
                        put("barcodeVariantsJson", p.barcodeVariantsJson ?: JSONObject.NULL)
                    }
                    productsJsonArray.put(pObj)
                }
                rootJson.put("products", productsJsonArray)

                // 3.5 Product Batches
                val batchesList = db.productBatchDao().getAllBatchesList()
                val batchesJsonArray = JSONArray()
                for (b in batchesList) {
                    val bObj = JSONObject().apply {
                        put("id", b.id)
                        put("productId", b.productId)
                        put("batchNumber", b.batchNumber)
                        put("quantity", b.quantity)
                        put("expiryDate", b.expiryDate ?: JSONObject.NULL)
                        put("mfgDate", b.mfgDate ?: JSONObject.NULL)
                        put("costPrice", b.costPrice ?: JSONObject.NULL)
                        put("sellingPrice", b.sellingPrice ?: JSONObject.NULL)
                        put("addedTimestamp", b.addedTimestamp)
                    }
                    batchesJsonArray.put(bObj)
                }
                rootJson.put("productBatches", batchesJsonArray)

                // 4. Sales & Sale Items
                val salesList = db.saleDao().getAllSalesList()
                val salesJsonArray = JSONArray()
                for (sWithItems in salesList) {
                    val sale = sWithItems.sale
                    val sObj = JSONObject().apply {
                        put("id", sale.id)
                        put("datetime", sale.datetime)
                        put("totalAmount", sale.totalAmount)
                        put("discount", sale.discount)
                        put("finalAmount", sale.finalAmount)
                        put("paymentMode", sale.paymentMode)
                        put("customerId", sale.customerId ?: JSONObject.NULL)
                        put("customerName", sale.customerName ?: JSONObject.NULL)
                        put("isHeld", sale.isHeld)
                        put("notes", sale.notes ?: JSONObject.NULL)
                        put("dueDate", sale.dueDate ?: JSONObject.NULL)

                        val itemsArray = JSONArray()
                        for (item in sWithItems.items) {
                            val iObj = JSONObject().apply {
                                put("id", item.id)
                                put("saleId", item.saleId)
                                put("productId", item.productId)
                                put("productNameEn", item.productNameEn)
                                put("productNameBn", item.productNameBn)
                                put("unitType", item.unitType)
                                put("quantity", item.quantity)
                                put("unitPrice", item.unitPrice)
                                put("costPrice", item.costPrice)
                                put("subtotal", item.subtotal)
                                put("totalCost", item.totalCost)
                            }
                            itemsArray.put(iObj)
                        }
                        put("items", itemsArray)
                    }
                    salesJsonArray.put(sObj)
                }
                rootJson.put("sales", salesJsonArray)

                // 4.5. Returns & Replacement Items
                val returnsList = db.saleReturnDao().getAllReturnsList()
                val returnsJsonArray = JSONArray()
                for (rWithItems in returnsList) {
                    val r = rWithItems.saleReturn
                    val rObj = JSONObject().apply {
                        put("id", r.id)
                        put("saleId", r.saleId)
                        put("datetime", r.datetime)
                        put("type", r.type)
                        put("customerId", r.customerId ?: JSONObject.NULL)
                        put("customerName", r.customerName ?: JSONObject.NULL)
                        put("totalReturnedAmount", r.totalReturnedAmount)
                        put("totalReplacementAmount", r.totalReplacementAmount)
                        put("netAmount", r.netAmount)
                        put("refundPaymentMode", r.refundPaymentMode)
                        put("notes", r.notes ?: JSONObject.NULL)

                        val itemsArr = JSONArray()
                        for (item in rWithItems.items) {
                            val iObj = JSONObject().apply {
                                put("productId", item.productId)
                                put("productNameEn", item.productNameEn)
                                put("productNameBn", item.productNameBn)
                                put("unitType", item.unitType)
                                put("quantity", item.quantity)
                                put("unitPrice", item.unitPrice)
                                put("subtotal", item.subtotal)
                                put("isReplacement", item.isReplacement)
                            }
                            itemsArr.put(iObj)
                        }
                        put("items", itemsArr)
                    }
                    returnsJsonArray.put(rObj)
                }
                rootJson.put("returns", returnsJsonArray)

                // 5. Purchases & Purchase Items
                val purchasesList = db.purchaseDao().getAllPurchasesList()
                val purchasesJsonArray = JSONArray()
                for (pWithItems in purchasesList) {
                    val purchase = pWithItems.purchase
                    val pObj = JSONObject().apply {
                        put("id", purchase.id)
                        put("datetime", purchase.datetime)
                        put("supplierId", purchase.supplierId ?: JSONObject.NULL)
                        put("supplierName", purchase.supplierName ?: JSONObject.NULL)
                        put("totalAmount", purchase.totalAmount)
                        put("amountPaid", purchase.amountPaid)
                        put("paidVia", purchase.paidVia)
                        put("dueAmount", purchase.dueAmount)
                        put("previousBalance", purchase.previousBalance)
                        put("paymentMode", purchase.paymentMode)
                        put("notes", purchase.notes ?: JSONObject.NULL)

                        val itemsArray = JSONArray()
                        for (item in pWithItems.items) {
                            val iObj = JSONObject().apply {
                                put("id", item.id)
                                put("purchaseId", item.purchaseId)
                                put("productId", item.productId)
                                put("productNameEn", item.productNameEn)
                                put("productNameBn", item.productNameBn)
                                put("quantity", item.quantity)
                                put("costPrice", item.costPrice)
                                put("subtotal", item.subtotal)
                            }
                            itemsArray.put(iObj)
                        }
                        put("items", itemsArray)
                    }
                    purchasesJsonArray.put(pObj)
                }
                rootJson.put("purchases", purchasesJsonArray)

                // 6. Customers (Khata Receivables)
                val customersList = db.customerDao().getAllCustomersList()
                val customersJsonArray = JSONArray()
                for (c in customersList) {
                    val cObj = JSONObject().apply {
                        put("id", c.id)
                        put("name", c.name)
                        put("phone", c.phone)
                        put("balance", c.balance)
                        val syncPhoto = ImageSyncHelper.processImageUriForSync(context, c.photoUri)
                        put("photoUri", syncPhoto ?: JSONObject.NULL)
                    }
                    customersJsonArray.put(cObj)
                }
                rootJson.put("customers", customersJsonArray)

                // 7. Suppliers (Khata Payables)
                val suppliersList = db.supplierDao().getAllSuppliersList()
                val suppliersJsonArray = JSONArray()
                for (s in suppliersList) {
                    val sObj = JSONObject().apply {
                        put("id", s.id)
                        put("name", s.name)
                        put("phone", s.phone)
                        put("balance", s.balance)
                        put("address", s.address ?: JSONObject.NULL)
                        put("gstin", s.gstin ?: JSONObject.NULL)
                        put("notes", s.notes ?: JSONObject.NULL)
                        val syncPhoto = ImageSyncHelper.processImageUriForSync(context, s.photoUri)
                        put("photoUri", syncPhoto ?: JSONObject.NULL)
                    }
                    suppliersJsonArray.put(sObj)
                }
                rootJson.put("suppliers", suppliersJsonArray)

                // 8. Ledger Entries
                val ledgerList = db.ledgerDao().getAllLedgerEntriesList()
                val ledgerJsonArray = JSONArray()
                for (l in ledgerList) {
                    val lObj = JSONObject().apply {
                        put("id", l.id)
                        put("partyType", l.partyType)
                        put("partyId", l.partyId)
                        put("partyName", l.partyName)
                        put("type", l.type)
                        put("amount", l.amount)
                        put("datetime", l.datetime)
                        put("note", l.note ?: JSONObject.NULL)
                        put("referenceId", l.referenceId ?: JSONObject.NULL)
                        put("dueDate", l.dueDate ?: JSONObject.NULL)
                    }
                    ledgerJsonArray.put(lObj)
                }
                rootJson.put("ledgerEntries", ledgerJsonArray)

                // 9. Expenses
                val expensesList = db.expenseDao().getAllExpensesList()
                val expensesJsonArray = JSONArray()
                for (e in expensesList) {
                    val eObj = JSONObject().apply {
                        put("id", e.id)
                        put("date", e.date)
                        put("category", e.category)
                        put("amount", e.amount)
                        put("note", e.note ?: JSONObject.NULL)
                        put("isRecurring", e.isRecurring)
                    }
                    expensesJsonArray.put(eObj)
                }
                rootJson.put("expenses", expensesJsonArray)

                // 10. Employees
                val employeesList = db.employeeDao().getAllEmployeesList()
                val empArray = JSONArray()
                for (emp in employeesList) {
                    val empObj = JSONObject().apply {
                        put("id", emp.id)
                        put("name", emp.name)
                        put("phone", emp.phone)
                        put("email", emp.email)
                        put("pin", emp.pin)
                        put("role", emp.role)
                        put("designation", emp.designation)
                        put("salaryType", emp.salaryType)
                        put("baseSalary", emp.baseSalary)
                        put("joiningDate", emp.joiningDate)
                        put("address", emp.address)
                        put("emergencyContact", emp.emergencyContact)
                        put("notes", emp.notes)
                        put("canMakeSales", emp.canMakeSales)
                        put("canViewCostPrice", emp.canViewCostPrice)
                        put("canManageInventory", emp.canManageInventory)
                        put("canViewKhata", emp.canViewKhata)
                        put("canManageExpenses", emp.canManageExpenses)
                        put("canViewReports", emp.canViewReports)
                        put("canAccessSettings", emp.canAccessSettings)
                        put("canGiveDiscount", emp.canGiveDiscount)
                        put("canDeleteSales", emp.canDeleteSales)
                        put("isActive", emp.isActive)
                        put("createdAt", emp.createdAt)
                    }
                    empArray.put(empObj)
                }
                rootJson.put("employees", empArray)

                // 11. Employee Attendance
                val attList = db.employeeAttendanceDao().getAllAttendanceList()
                val attArray = JSONArray()
                for (att in attList) {
                    val aObj = JSONObject().apply {
                        put("id", att.id)
                        put("employeeId", att.employeeId)
                        put("employeeName", att.employeeName)
                        put("date", att.date)
                        put("status", att.status)
                        put("checkInTime", att.checkInTime)
                        put("checkOutTime", att.checkOutTime)
                        put("overtimeHours", att.overtimeHours)
                        put("notes", att.notes)
                        put("timestamp", att.timestamp)
                    }
                    attArray.put(aObj)
                }
                rootJson.put("employeeAttendance", attArray)

                // 12. Employee Salary Dues
                val duesList = db.employeeSalaryDao().getAllSalaryDuesList()
                val duesArray = JSONArray()
                for (due in duesList) {
                    val dObj = JSONObject().apply {
                        put("id", due.id)
                        put("employeeId", due.employeeId)
                        put("employeeName", due.employeeName)
                        put("monthYear", due.monthYear)
                        put("dueAmount", due.dueAmount)
                        put("dueDate", due.dueDate)
                        put("notes", due.notes)
                    }
                    duesArray.put(dObj)
                }
                rootJson.put("employeeSalaryDues", duesArray)

                // 13. Employee Salary Payments
                val paymentsList = db.employeeSalaryDao().getAllSalaryPaymentsList()
                val payArray = JSONArray()
                for (p in paymentsList) {
                    val pObj = JSONObject().apply {
                        put("id", p.id)
                        put("employeeId", p.employeeId)
                        put("employeeName", p.employeeName)
                        put("monthYear", p.monthYear)
                        put("paymentDate", p.paymentDate)
                        put("baseSalary", p.baseSalary)
                        put("salaryType", p.salaryType)
                        put("presentDays", p.presentDays)
                        put("halfDays", p.halfDays)
                        put("absentDays", p.absentDays)
                        put("paidLeaveDays", p.paidLeaveDays)
                        put("totalWorkingDaysInMonth", p.totalWorkingDaysInMonth)
                        put("overtimeHours", p.overtimeHours)
                        put("overtimePay", p.overtimePay)
                        put("bonus", p.bonus)
                        put("advanceDeduction", p.advanceDeduction)
                        put("otherDeductions", p.otherDeductions)
                        put("netSalaryPaid", p.netSalaryPaid)
                        put("paymentMode", p.paymentMode)
                        put("notes", p.notes)
                        put("syncedToExpense", p.syncedToExpense)
                    }
                    payArray.put(pObj)
                }
                rootJson.put("employeeSalaryPayments", payArray)

                // 14. Employee Advances
                val advList = db.employeeSalaryDao().getAllAdvancesList()
                val advArray = JSONArray()
                for (adv in advList) {
                    val advObj = JSONObject().apply {
                        put("id", adv.id)
                        put("employeeId", adv.employeeId)
                        put("employeeName", adv.employeeName)
                        put("type", adv.type)
                        put("amount", adv.amount)
                        put("date", adv.date)
                        put("reason", adv.reason)
                        put("repaidAmount", adv.repaidAmount)
                        put("status", adv.status)
                        put("paymentMode", adv.paymentMode)
                        put("settledDate", adv.settledDate ?: JSONObject.NULL)
                        put("settlementNotes", adv.settlementNotes)
                    }
                    advArray.put(advObj)
                }
                rootJson.put("employeeAdvances", advArray)

                // Write output stream
                outputStream.write(rootJson.toString(2).toByteArray(Charsets.UTF_8))
                outputStream.flush()
                outputStream.close()
                Result.success(true)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    suspend fun importDataFromJson(context: Context, inputStream: InputStream): Result<Int> =
        withContext(Dispatchers.IO) {
            try {
                val jsonString = inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                inputStream.close()
                val rootJson = JSONObject(jsonString)

                val db = AppDatabase.getDatabase(context)

                // 1. Store Info
                if (rootJson.has("storeInfo")) {
                    val sInfo = rootJson.getJSONObject("storeInfo")
                    StoreInfoManager.updateStoreInfo(
                        name = sInfo.optString("storeName", StoreInfoManager.storeName),
                        address = sInfo.optString("storeAddress", StoreInfoManager.storeAddress),
                        owner = sInfo.optString("ownerName", StoreInfoManager.ownerName),
                        phoneNum = sInfo.optString("phone", StoreInfoManager.phone),
                        taglineStr = sInfo.optString("tagline", StoreInfoManager.tagline),
                        gstinStr = sInfo.optString("gstin", StoreInfoManager.gstin),
                        context = context
                    )

                    val paperSize = sInfo.optString("pdfPaperSize", StoreInfoManager.pdfPaperSize)
                    val upiId = sInfo.optString("merchantUpiId", StoreInfoManager.merchantUpiId)
                    val showQr = sInfo.optBoolean("showQrOnPdf", StoreInfoManager.showQrOnPdf)
                    StoreInfoManager.updatePdfFormatSettings(
                        upiId = upiId,
                        payeeName = StoreInfoManager.merchantPayeeName,
                        showQr = showQr,
                        paperSize = paperSize,
                        footerNote = StoreInfoManager.customFooterNote,
                        headerColor = StoreInfoManager.pdfHeaderColor,
                        context = context
                    )
                }

                // 2. Custom Categories
                if (rootJson.has("customCategories")) {
                    val catsArr = rootJson.getJSONArray("customCategories")
                    val catSet = mutableSetOf<String>()
                    for (i in 0 until catsArr.length()) {
                        catSet.add(catsArr.getString(i))
                    }
                    if (catSet.isNotEmpty()) {
                        context.getSharedPreferences("inventory_categories", Context.MODE_PRIVATE)
                            .edit()
                            .putStringSet("custom_cat_set", catSet)
                            .apply()
                    }
                }

                // Clear current tables before importing to avoid foreign key / duplicate primary key conflicts
                db.clearAllTables()

                var totalRecordsCount = 0

                // 3. Products
                if (rootJson.has("products")) {
                    val pArray = rootJson.getJSONArray("products")
                    val pList = mutableListOf<Product>()
                    for (i in 0 until pArray.length()) {
                        val pObj = pArray.getJSONObject(i)
                        pList.add(
                            Product(
                                id = pObj.getString("id"),
                                nameEn = pObj.optString("nameEn", ""),
                                nameBn = pObj.optString("nameBn", ""),
                                category = pObj.optString("category", "General"),
                                unitType = pObj.optString("unitType", "piece"),
                                costPrice = pObj.optDouble("costPrice", 0.0),
                                sellingPrice = pObj.optDouble("sellingPrice", 0.0),
                                currentStock = pObj.optDouble("currentStock", 0.0),
                                lowStockThreshold = pObj.optDouble("lowStockThreshold", 5.0),
                                barcode = if (pObj.isNull("barcode")) null else pObj.optString("barcode"),
                                secondaryUnitType = if (pObj.isNull("secondaryUnitType")) null else pObj.optString("secondaryUnitType"),
                                secondaryUnitRatio = pObj.optDouble("secondaryUnitRatio", 1.0),
                                imageUri = if (pObj.isNull("imageUri")) null else pObj.optString("imageUri"),
                                expiryDate = if (pObj.isNull("expiryDate")) null else pObj.optString("expiryDate"),
                                wholesalePrice = pObj.optDouble("wholesalePrice", 0.0),
                                wholesaleMinQty = pObj.optDouble("wholesaleMinQty", 0.0),
                                piecesPerBox = if (pObj.isNull("piecesPerBox")) null else pObj.optInt("piecesPerBox"),
                                boxPrice = if (pObj.isNull("boxPrice")) null else pObj.optDouble("boxPrice"),
                                bulkUnitType = if (pObj.isNull("bulkUnitType")) null else pObj.optString("bulkUnitType"),
                                bulkQuantity = if (pObj.isNull("bulkQuantity")) null else pObj.optDouble("bulkQuantity"),
                                bulkPrice = if (pObj.isNull("bulkPrice")) null else pObj.optDouble("bulkPrice"),
                                mrp = if (pObj.isNull("mrp")) null else pObj.optDouble("mrp"),
                                barcodeVariantsJson = if (pObj.has("barcodeVariantsJson") && !pObj.isNull("barcodeVariantsJson")) pObj.optString("barcodeVariantsJson") else null
                            )
                        )
                    }
                    if (pList.isNotEmpty()) {
                        db.productDao().insertProducts(pList)
                        totalRecordsCount += pList.size
                    }
                }

                if (rootJson.has("productBatches")) {
                    val bArray = rootJson.getJSONArray("productBatches")
                    val bList = mutableListOf<ProductBatch>()
                    for (i in 0 until bArray.length()) {
                        val bObj = bArray.getJSONObject(i)
                        bList.add(
                            ProductBatch(
                                id = bObj.getString("id"),
                                productId = bObj.getString("productId"),
                                batchNumber = bObj.getString("batchNumber"),
                                quantity = bObj.optDouble("quantity", 0.0),
                                expiryDate = if (bObj.isNull("expiryDate")) null else bObj.optString("expiryDate"),
                                mfgDate = if (bObj.isNull("mfgDate")) null else bObj.optString("mfgDate"),
                                costPrice = if (bObj.isNull("costPrice")) null else bObj.optDouble("costPrice"),
                                sellingPrice = if (bObj.isNull("sellingPrice")) null else bObj.optDouble("sellingPrice"),
                                addedTimestamp = bObj.optLong("addedTimestamp", System.currentTimeMillis())
                            )
                        )
                    }
                    if (bList.isNotEmpty()) {
                        db.productBatchDao().insertBatches(bList)
                        totalRecordsCount += bList.size
                    }
                }

                // 4. Customers
                if (rootJson.has("customers")) {
                    val cArray = rootJson.getJSONArray("customers")
                    for (i in 0 until cArray.length()) {
                        val cObj = cArray.getJSONObject(i)
                        db.customerDao().insertCustomer(
                            Customer(
                                id = cObj.getString("id"),
                                name = cObj.getString("name"),
                                phone = cObj.optString("phone", ""),
                                balance = cObj.optDouble("balance", 0.0),
                                photoUri = if (cObj.isNull("photoUri")) null else cObj.optString("photoUri")
                            )
                        )
                        totalRecordsCount++
                    }
                }

                // 5. Suppliers
                if (rootJson.has("suppliers")) {
                    val sArray = rootJson.getJSONArray("suppliers")
                    for (i in 0 until sArray.length()) {
                        val sObj = sArray.getJSONObject(i)
                        db.supplierDao().insertSupplier(
                            Supplier(
                                id = sObj.getString("id"),
                                name = sObj.getString("name"),
                                phone = sObj.optString("phone", ""),
                                balance = sObj.optDouble("balance", 0.0),
                                address = if (sObj.isNull("address")) null else sObj.optString("address"),
                                gstin = if (sObj.isNull("gstin")) null else sObj.optString("gstin"),
                                notes = if (sObj.isNull("notes")) null else sObj.optString("notes"),
                                photoUri = if (sObj.isNull("photoUri")) null else sObj.optString("photoUri")
                            )
                        )
                        totalRecordsCount++
                    }
                }

                // 6. Sales & Sale Items
                if (rootJson.has("sales")) {
                    val salesArr = rootJson.getJSONArray("sales")
                    for (i in 0 until salesArr.length()) {
                        val sObj = salesArr.getJSONObject(i)
                        val saleId = sObj.getString("id")
                        val sale = Sale(
                            id = saleId,
                            datetime = sObj.optLong("datetime", System.currentTimeMillis()),
                            totalAmount = sObj.optDouble("totalAmount", 0.0),
                            discount = sObj.optDouble("discount", 0.0),
                            finalAmount = sObj.optDouble("finalAmount", 0.0),
                            paymentMode = sObj.optString("paymentMode", "CASH"),
                            customerId = if (sObj.isNull("customerId")) null else sObj.optString("customerId"),
                            customerName = if (sObj.isNull("customerName")) null else sObj.optString("customerName"),
                            isHeld = sObj.optBoolean("isHeld", false),
                            notes = if (sObj.isNull("notes")) null else sObj.optString("notes"),
                            dueDate = if (sObj.isNull("dueDate")) null else sObj.optLong("dueDate")
                        )

                        val sItems = mutableListOf<SaleItem>()
                        if (sObj.has("items")) {
                            val itemsArr = sObj.getJSONArray("items")
                            for (j in 0 until itemsArr.length()) {
                                val iObj = itemsArr.getJSONObject(j)
                                sItems.add(
                                    SaleItem(
                                        id = iObj.optLong("id", 0),
                                        saleId = saleId,
                                        productId = iObj.getString("productId"),
                                        productNameEn = iObj.optString("productNameEn", ""),
                                        productNameBn = iObj.optString("productNameBn", ""),
                                        unitType = iObj.optString("unitType", "piece"),
                                        quantity = iObj.optDouble("quantity", 1.0),
                                        unitPrice = iObj.optDouble("unitPrice", 0.0),
                                        costPrice = iObj.optDouble("costPrice", 0.0),
                                        subtotal = iObj.optDouble("subtotal", 0.0),
                                        totalCost = iObj.optDouble("totalCost", 0.0)
                                    )
                                )
                            }
                        }
                        val cleanItems = com.example.utils.SaleConsolidationUtils.sanitizeSaleItems(sale, sItems)
                        db.saleDao().replaceSaleWithItems(sale, cleanItems)
                        totalRecordsCount++
                    }
                }

                // 6.5. Sale Returns
                if (rootJson.has("returns")) {
                    val returnsArr = rootJson.getJSONArray("returns")
                    for (i in 0 until returnsArr.length()) {
                        val rObj = returnsArr.getJSONObject(i)
                        val returnId = rObj.getString("id")
                        val saleReturn = SaleReturn(
                            id = returnId,
                            saleId = rObj.getString("saleId"),
                            datetime = rObj.optLong("datetime", System.currentTimeMillis()),
                            type = rObj.optString("type", "RETURN"),
                            customerId = if (rObj.isNull("customerId")) null else rObj.optString("customerId"),
                            customerName = if (rObj.isNull("customerName")) null else rObj.optString("customerName"),
                            totalReturnedAmount = rObj.optDouble("totalReturnedAmount", 0.0),
                            totalReplacementAmount = rObj.optDouble("totalReplacementAmount", 0.0),
                            netAmount = rObj.optDouble("netAmount", 0.0),
                            refundPaymentMode = rObj.optString("refundPaymentMode", "CASH"),
                            notes = if (rObj.isNull("notes")) null else rObj.optString("notes")
                        )
                        val itemsList = mutableListOf<ReturnItem>()
                        if (rObj.has("items")) {
                            val itemsArr = rObj.getJSONArray("items")
                            for (j in 0 until itemsArr.length()) {
                                val itemObj = itemsArr.getJSONObject(j)
                                itemsList.add(
                                    ReturnItem(
                                        returnId = returnId,
                                        productId = itemObj.getString("productId"),
                                        productNameEn = itemObj.optString("productNameEn", ""),
                                        productNameBn = itemObj.optString("productNameBn", ""),
                                        unitType = itemObj.optString("unitType", "piece"),
                                        quantity = itemObj.optDouble("quantity", 1.0),
                                        unitPrice = itemObj.optDouble("unitPrice", 0.0),
                                        subtotal = itemObj.optDouble("subtotal", 0.0),
                                        isReplacement = itemObj.optBoolean("isReplacement", false)
                                    )
                                )
                            }
                        }
                        db.saleReturnDao().insertFullReturn(saleReturn, itemsList)
                        totalRecordsCount++
                    }
                }

                // 7. Purchases & Purchase Items
                if (rootJson.has("purchases")) {
                    val pArr = rootJson.getJSONArray("purchases")
                    for (i in 0 until pArr.length()) {
                        val pObj = pArr.getJSONObject(i)
                        val purchaseId = pObj.getString("id")
                        val totalAmount = pObj.optDouble("totalAmount", 0.0)
                        val paymentMode = pObj.optString("paymentMode", "CASH")
                        val amountPaid = pObj.optDouble("amountPaid", if (paymentMode.equals("CREDIT", ignoreCase = true)) 0.0 else totalAmount)
                        val paidVia = pObj.optString("paidVia", if (paymentMode.equals("CREDIT", ignoreCase = true)) "CASH" else paymentMode)
                        val dueAmount = pObj.optDouble("dueAmount", (totalAmount - amountPaid).coerceAtLeast(0.0))
                        val previousBalance = pObj.optDouble("previousBalance", 0.0)
                        val purchase = Purchase(
                            id = purchaseId,
                            datetime = pObj.optLong("datetime", System.currentTimeMillis()),
                            supplierId = if (pObj.isNull("supplierId")) null else pObj.optString("supplierId"),
                            supplierName = if (pObj.isNull("supplierName")) null else pObj.optString("supplierName"),
                            totalAmount = totalAmount,
                            amountPaid = amountPaid,
                            paidVia = paidVia,
                            dueAmount = dueAmount,
                            previousBalance = previousBalance,
                            paymentMode = paymentMode,
                            notes = if (pObj.isNull("notes")) null else pObj.optString("notes")
                        )

                        val pItems = mutableListOf<PurchaseItem>()
                        if (pObj.has("items")) {
                            val itemsArr = pObj.getJSONArray("items")
                            for (j in 0 until itemsArr.length()) {
                                val iObj = itemsArr.getJSONObject(j)
                                pItems.add(
                                    PurchaseItem(
                                        id = iObj.optLong("id", 0),
                                        purchaseId = purchaseId,
                                        productId = iObj.getString("productId"),
                                        productNameEn = iObj.optString("productNameEn", ""),
                                        productNameBn = iObj.optString("productNameBn", ""),
                                        quantity = iObj.optDouble("quantity", 1.0),
                                        costPrice = iObj.optDouble("costPrice", 0.0),
                                        subtotal = iObj.optDouble("subtotal", 0.0)
                                    )
                                )
                            }
                        }
                        val cleanItems = com.example.utils.SaleConsolidationUtils.sanitizePurchaseItems(purchase, pItems)
                        db.purchaseDao().replacePurchaseWithItems(purchase, cleanItems)
                        totalRecordsCount++
                    }
                }

                // 8. Ledger Entries
                if (rootJson.has("ledgerEntries")) {
                    val lArr = rootJson.getJSONArray("ledgerEntries")
                    for (i in 0 until lArr.length()) {
                        val lObj = lArr.getJSONObject(i)
                        db.ledgerDao().insertLedgerEntry(
                            LedgerEntry(
                                id = lObj.getString("id"),
                                partyType = lObj.getString("partyType"),
                                partyId = lObj.getString("partyId"),
                                partyName = lObj.getString("partyName"),
                                type = lObj.getString("type"),
                                amount = lObj.getDouble("amount"),
                                datetime = lObj.optLong("datetime", System.currentTimeMillis()),
                                note = if (lObj.isNull("note")) null else lObj.optString("note"),
                                referenceId = if (lObj.isNull("referenceId")) null else lObj.optString("referenceId"),
                                dueDate = if (lObj.isNull("dueDate")) null else lObj.optLong("dueDate")
                            )
                        )
                        totalRecordsCount++
                    }
                }

                // 9. Expenses
                if (rootJson.has("expenses")) {
                    val eArr = rootJson.getJSONArray("expenses")
                    for (i in 0 until eArr.length()) {
                        val eObj = eArr.getJSONObject(i)
                        db.expenseDao().insertExpense(
                            Expense(
                                id = eObj.getString("id"),
                                date = eObj.optLong("date", System.currentTimeMillis()),
                                category = eObj.getString("category"),
                                amount = eObj.getDouble("amount"),
                                note = if (eObj.isNull("note")) null else eObj.optString("note"),
                                isRecurring = eObj.optBoolean("isRecurring", false)
                            )
                        )
                        totalRecordsCount++
                    }
                }

                // 10. Employees
                if (rootJson.has("employees")) {
                    val empArr = rootJson.getJSONArray("employees")
                    for (i in 0 until empArr.length()) {
                        val empObj = empArr.getJSONObject(i)
                        db.employeeDao().insertEmployee(
                            Employee(
                                id = empObj.getString("id"),
                                name = empObj.getString("name"),
                                email = empObj.optString("email", ""),
                                phone = empObj.optString("phone", ""),
                                pin = empObj.optString("pin", "1234"),
                                role = empObj.optString("role", "CASHIER"),
                                designation = empObj.optString("designation", "Staff"),
                                salaryType = empObj.optString("salaryType", "MONTHLY"),
                                baseSalary = empObj.optDouble("baseSalary", 0.0),
                                joiningDate = empObj.optLong("joiningDate", System.currentTimeMillis()),
                                address = empObj.optString("address", ""),
                                emergencyContact = empObj.optString("emergencyContact", ""),
                                notes = empObj.optString("notes", ""),
                                canMakeSales = empObj.optBoolean("canMakeSales", true),
                                canViewCostPrice = empObj.optBoolean("canViewCostPrice", false),
                                canManageInventory = empObj.optBoolean("canManageInventory", false),
                                canViewKhata = empObj.optBoolean("canViewKhata", false),
                                canManageExpenses = empObj.optBoolean("canManageExpenses", false),
                                canViewReports = empObj.optBoolean("canViewReports", false),
                                canAccessSettings = empObj.optBoolean("canAccessSettings", false),
                                canGiveDiscount = empObj.optBoolean("canGiveDiscount", true),
                                canDeleteSales = empObj.optBoolean("canDeleteSales", false),
                                isActive = empObj.optBoolean("isActive", true),
                                createdAt = empObj.optLong("createdAt", System.currentTimeMillis())
                            )
                        )
                        totalRecordsCount++
                    }
                }

                // 11. Employee Attendance
                if (rootJson.has("employeeAttendance")) {
                    val attArr = rootJson.getJSONArray("employeeAttendance")
                    val attList = mutableListOf<EmployeeAttendance>()
                    for (i in 0 until attArr.length()) {
                        val aObj = attArr.getJSONObject(i)
                        attList.add(
                            EmployeeAttendance(
                                id = aObj.getString("id"),
                                employeeId = aObj.getString("employeeId"),
                                employeeName = aObj.optString("employeeName", ""),
                                date = aObj.getString("date"),
                                status = aObj.optString("status", "PRESENT"),
                                checkInTime = aObj.optString("checkInTime", ""),
                                checkOutTime = aObj.optString("checkOutTime", ""),
                                overtimeHours = aObj.optDouble("overtimeHours", 0.0),
                                notes = aObj.optString("notes", ""),
                                timestamp = aObj.optLong("timestamp", System.currentTimeMillis())
                            )
                        )
                    }
                    if (attList.isNotEmpty()) {
                        db.employeeAttendanceDao().insertAttendanceBatch(attList)
                        totalRecordsCount += attList.size
                    }
                }

                // 12. Employee Salary Dues
                if (rootJson.has("employeeSalaryDues")) {
                    val duesArr = rootJson.getJSONArray("employeeSalaryDues")
                    val duesList = mutableListOf<EmployeeSalaryDue>()
                    for (i in 0 until duesArr.length()) {
                        val dObj = duesArr.getJSONObject(i)
                        duesList.add(
                            EmployeeSalaryDue(
                                id = dObj.getString("id"),
                                employeeId = dObj.getString("employeeId"),
                                employeeName = dObj.optString("employeeName", ""),
                                monthYear = dObj.getString("monthYear"),
                                dueAmount = dObj.optDouble("dueAmount", 0.0),
                                dueDate = dObj.optLong("dueDate", System.currentTimeMillis()),
                                notes = dObj.optString("notes", "")
                            )
                        )
                    }
                    if (duesList.isNotEmpty()) {
                        db.employeeSalaryDao().insertSalaryDueBatch(duesList)
                        totalRecordsCount += duesList.size
                    }
                }

                // 13. Employee Salary Payments
                if (rootJson.has("employeeSalaryPayments")) {
                    val payArr = rootJson.getJSONArray("employeeSalaryPayments")
                    val payList = mutableListOf<EmployeeSalaryPayment>()
                    for (i in 0 until payArr.length()) {
                        val pObj = payArr.getJSONObject(i)
                        payList.add(
                            EmployeeSalaryPayment(
                                id = pObj.getString("id"),
                                employeeId = pObj.getString("employeeId"),
                                employeeName = pObj.optString("employeeName", ""),
                                monthYear = pObj.optString("monthYear", ""),
                                paymentDate = pObj.optLong("paymentDate", System.currentTimeMillis()),
                                baseSalary = pObj.optDouble("baseSalary", 0.0),
                                salaryType = pObj.optString("salaryType", "MONTHLY"),
                                presentDays = pObj.optInt("presentDays", 0),
                                halfDays = pObj.optInt("halfDays", 0),
                                absentDays = pObj.optInt("absentDays", 0),
                                paidLeaveDays = pObj.optInt("paidLeaveDays", 0),
                                totalWorkingDaysInMonth = pObj.optInt("totalWorkingDaysInMonth", 30),
                                overtimeHours = pObj.optDouble("overtimeHours", 0.0),
                                overtimePay = pObj.optDouble("overtimePay", 0.0),
                                bonus = pObj.optDouble("bonus", 0.0),
                                advanceDeduction = pObj.optDouble("advanceDeduction", 0.0),
                                otherDeductions = pObj.optDouble("otherDeductions", 0.0),
                                netSalaryPaid = pObj.optDouble("netSalaryPaid", pObj.optDouble("netPaidAmount", 0.0)),
                                paymentMode = pObj.optString("paymentMode", "CASH"),
                                notes = pObj.optString("notes", ""),
                                syncedToExpense = pObj.optBoolean("syncedToExpense", true)
                            )
                        )
                    }
                    if (payList.isNotEmpty()) {
                        db.employeeSalaryDao().insertSalaryPayments(payList)
                        totalRecordsCount += payList.size
                    }
                }

                // 14. Employee Advances
                if (rootJson.has("employeeAdvances")) {
                    val advArr = rootJson.getJSONArray("employeeAdvances")
                    val advList = mutableListOf<EmployeeAdvance>()
                    for (i in 0 until advArr.length()) {
                        val aObj = advArr.getJSONObject(i)
                        advList.add(
                            EmployeeAdvance(
                                id = aObj.getString("id"),
                                employeeId = aObj.getString("employeeId"),
                                employeeName = aObj.optString("employeeName", ""),
                                type = aObj.optString("type", "ADVANCE"),
                                amount = aObj.optDouble("amount", 0.0),
                                date = aObj.optLong("date", System.currentTimeMillis()),
                                reason = aObj.optString("reason", ""),
                                repaidAmount = aObj.optDouble("repaidAmount", 0.0),
                                status = aObj.optString("status", "PENDING"),
                                paymentMode = aObj.optString("paymentMode", "CASH"),
                                settledDate = if (aObj.isNull("settledDate")) null else aObj.optLong("settledDate"),
                                settlementNotes = aObj.optString("settlementNotes", "")
                            )
                        )
                    }
                    if (advList.isNotEmpty()) {
                        db.employeeSalaryDao().insertAdvances(advList)
                        totalRecordsCount += advList.size
                    }
                }

                Result.success(totalRecordsCount)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    suspend fun exportDataToJsonString(context: Context): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                val baos = java.io.ByteArrayOutputStream()
                val exportRes = exportDataToJson(context, baos)
                if (exportRes.isSuccess) {
                    Result.success(baos.toString(Charsets.UTF_8.name()))
                } else {
                    Result.failure(exportRes.exceptionOrNull() ?: Exception("Export failed"))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    suspend fun importDataFromJsonString(context: Context, jsonString: String): Result<Int> =
        withContext(Dispatchers.IO) {
            try {
                val bais = java.io.ByteArrayInputStream(jsonString.toByteArray(Charsets.UTF_8))
                importDataFromJson(context, bais)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    suspend fun createLocalAutoBackup(context: Context, label: String = "auto"): Result<java.io.File> =
        withContext(Dispatchers.IO) {
            try {
                val backupDir = java.io.File(context.filesDir, "backups").apply { if (!exists()) mkdirs() }
                val timeFormat = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
                val backupFile = java.io.File(backupDir, "store_backup_${label}_${timeFormat}.json")

                val fos = java.io.FileOutputStream(backupFile)
                val exportResult = exportDataToJson(context, fos)
                if (exportResult.isSuccess) {
                    // Rotate backups - keep last 10 local backup files
                    val files = backupDir.listFiles { f -> f.extension == "json" }?.sortedByDescending { it.lastModified() }
                    if (files != null && files.size > 10) {
                        for (i in 10 until files.size) {
                            files[i].delete()
                        }
                    }
                    Result.success(backupFile)
                } else {
                    backupFile.delete()
                    Result.failure(exportResult.exceptionOrNull() ?: Exception("Auto-backup failed"))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    fun listLocalBackupFiles(context: Context): List<LocalBackupFileInfo> {
        val backupDir = java.io.File(context.filesDir, "backups")
        if (!backupDir.exists()) return emptyList()
        val files = backupDir.listFiles { f -> f.extension == "json" }?.sortedByDescending { it.lastModified() } ?: emptyList()
        return files.map { file ->
            LocalBackupFileInfo(
                file = file,
                fileName = file.name,
                timestamp = file.lastModified(),
                sizeBytes = file.length()
            )
        }
    }

    fun deleteLocalBackupFile(file: java.io.File): Boolean {
        return try {
            file.delete()
        } catch (e: Exception) {
            false
        }
    }

    fun deleteAllLocalBackups(context: Context): Int {
        return try {
            val files = listLocalBackupFiles(context)
            var count = 0
            for (f in files) {
                if (deleteLocalBackupFile(f.file)) {
                    count++
                }
            }
            count
        } catch (e: Exception) {
            0
        }
    }
}

data class LocalBackupFileInfo(
    val file: java.io.File,
    val fileName: String,
    val timestamp: Long,
    val sizeBytes: Long
) {
    val formattedDate: String
        get() = java.text.SimpleDateFormat("dd MMM yyyy, hh:mm a", java.util.Locale.getDefault()).format(java.util.Date(timestamp))

    val formattedSize: String
        get() = when {
            sizeBytes < 1024 -> "$sizeBytes B"
            sizeBytes < 1024 * 1024 -> "${sizeBytes / 1024} KB"
            else -> String.format(java.util.Locale.US, "%.1f MB", sizeBytes.toDouble() / (1024 * 1024))
        }

    val sizeFormatted: String
        get() = formattedSize
}
