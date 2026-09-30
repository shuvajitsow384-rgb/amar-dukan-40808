package com.example

import com.example.data.local.entities.Product
import org.junit.Assert.*
import org.junit.Test

class ExampleUnitTest {
  @Test
  fun addition_isCorrect() {
    assertEquals(4, 2 + 2)
  }

  @Test
  fun testProductSecondaryUnitCalculation() {
    val kgProduct = Product(
      id = "test_1",
      nameEn = "Rice",
      nameBn = "চাল",
      category = "Staples",
      unitType = "kg",
      costPrice = 40.0,
      sellingPrice = 50.0,
      currentStock = 100.0
    )

    assertEquals(1000.0, kgProduct.getEffectiveSecondaryRatio(), 0.01)
    assertEquals("gram", kgProduct.getEffectiveSecondaryUnit())

    // 500 grams of Rs 50/kg rice should be Rs 25.0
    val priceFor500Grams = kgProduct.calculatePrice(500.0, "gram")
    assertEquals(25.0, priceFor500Grams, 0.01)

    // Cost for 500 grams of Rs 40/kg rice should be Rs 20.0
    val costFor500Grams = kgProduct.calculateCost(500.0, "gram")
    assertEquals(20.0, costFor500Grams, 0.01)
  }

  @Test
  fun testBarcodeMatchingRetailFormats() {
    val products = listOf(
      Product(
        id = "p1",
        nameEn = "Maggi 2-Minute Noodles",
        nameBn = "ম্যাগি নুডলস",
        category = "Instant Food",
        barcode = "8901058852335", // EAN-13 Indian barcode
        unitType = "piece",
        costPrice = 12.0,
        sellingPrice = 14.0,
        currentStock = 50.0
      ),
      Product(
        id = "p2",
        nameEn = "Coca Cola 750ml",
        nameBn = "কোকা কোলা",
        category = "Cold Drinks",
        barcode = "049000050103", // UPC-A 12-digit (starts with 0)
        unitType = "piece",
        costPrice = 35.0,
        sellingPrice = 40.0,
        currentStock = 25.0
      ),
      Product(
        id = "p3",
        nameEn = "Custom Store Code",
        nameBn = "কাস্টম কোড",
        category = "General",
        barcode = "STORE-ITEM-128", // Code-128 alphanumeric
        unitType = "piece",
        costPrice = 100.0,
        sellingPrice = 150.0,
        currentStock = 10.0
      )
    )

    fun findProductByBarcode(scanned: String): Product? {
      val cleanBarcode = scanned.trim()
      if (cleanBarcode.isBlank()) return null
      val cleanNoZero = cleanBarcode.trimStart('0')
      return products.find { p ->
        val pBarcode = p.barcode?.trim() ?: ""
        pBarcode.isNotBlank() && (
          pBarcode.equals(cleanBarcode, ignoreCase = true) ||
          (cleanNoZero.isNotBlank() && pBarcode.trimStart('0') == cleanNoZero)
        )
      }
    }

    // Exact match EAN-13
    val ean13Match = findProductByBarcode("8901058852335")
    assertNotNull(ean13Match)
    assertEquals("Maggi 2-Minute Noodles", ean13Match?.nameEn)

    // UPC-A scanned with or without leading zero
    val upcExact = findProductByBarcode("049000050103")
    assertNotNull(upcExact)
    assertEquals("Coca Cola 750ml", upcExact?.nameEn)

    val upcTrimmedZero = findProductByBarcode("49000050103")
    assertNotNull(upcTrimmedZero)
    assertEquals("Coca Cola 750ml", upcTrimmedZero?.nameEn)

    // Code-128
    val code128Match = findProductByBarcode("STORE-ITEM-128")
    assertNotNull(code128Match)
    assertEquals("Custom Store Code", code128Match?.nameEn)

    // Whitespace robustness
    val spacePaddedMatch = findProductByBarcode("  8901058852335 \n")
    assertNotNull(spacePaddedMatch)
    assertEquals("Maggi 2-Minute Noodles", spacePaddedMatch?.nameEn)
  }

  @Test
  fun testCartCombiningSingleLineItemPerProduct() {
    val riceProduct = Product(
      id = "rice_1",
      nameEn = "Basmati Rice",
      nameBn = "বাসমতি চাল",
      category = "Groceries",
      unitType = "kg",
      costPrice = 80.0,
      sellingPrice = 100.0,
      currentStock = 50.0
    )

    val cartItems = mutableListOf<com.example.viewmodel.CartItem>()

    fun addToCartWithUnit(product: Product, quantity: Double, selectedUnit: String) {
      if (quantity <= 0.0) return
      val existingIndex = cartItems.indexOfFirst { it.product.id == product.id }
      if (existingIndex >= 0) {
        val existing = cartItems[existingIndex]
        val existingBaseQty = existing.product.convertQuantityToBaseUnit(existing.quantity, existing.unitType)
        val addedBaseQty = product.convertQuantityToBaseUnit(quantity, selectedUnit)
        val totalBaseQty = existingBaseQty + addedBaseQty

        val isExistingBox = existing.unitType.equals("box", ignoreCase = true) || existing.unitType.equals("case", ignoreCase = true)
        val isExistingSubUnit = (existing.unitType.equals(existing.product.getEffectiveSecondaryUnit(), ignoreCase = true) ||
            existing.unitType.equals("gram", ignoreCase = true) ||
            existing.unitType.equals("ml", ignoreCase = true)) &&
            (existing.product.unitType.equals("kg", ignoreCase = true) ||
                existing.product.unitType.equals("litre", ignoreCase = true) ||
                existing.product.unitType.equals("quintal", ignoreCase = true))

        val newQty = when {
          isExistingBox -> totalBaseQty / existing.product.getPiecesPerBoxRatio().toDouble()
          isExistingSubUnit -> totalBaseQty * existing.product.getEffectiveSecondaryRatio()
          else -> totalBaseQty
        }

        cartItems[existingIndex] = existing.copy(quantity = newQty)
      } else {
        val isBox = selectedUnit.equals("box", ignoreCase = true) || selectedUnit.equals("case", ignoreCase = true)
        val unitPrice = if (isBox) (product.boxPrice ?: (product.sellingPrice * product.getPiecesPerBoxRatio())) else product.sellingPrice
        cartItems.add(
          com.example.viewmodel.CartItem(
            product = product,
            quantity = quantity,
            unitPrice = unitPrice,
            unitType = selectedUnit
          )
        )
      }
    }

    // 1. Add 1 kg
    addToCartWithUnit(riceProduct, 1.0, "kg")
    assertEquals(1, cartItems.size)
    assertEquals(1.0, cartItems[0].quantity, 0.001)
    assertEquals("kg", cartItems[0].unitType)

    // 2. Add 1250 grams -> should merge into same line item totaling 2.25 kg
    addToCartWithUnit(riceProduct, 1250.0, "gram")
    assertEquals(1, cartItems.size)
    assertEquals(2.25, cartItems[0].quantity, 0.001)
    assertEquals("kg", cartItems[0].unitType)
    assertEquals(225.0, cartItems[0].subtotal, 0.001)

    // Test box/piece combining
    val soapProduct = Product(
      id = "soap_1",
      nameEn = "Dettol Soap",
      nameBn = "ডেটোল সাবান",
      category = "Personal Care",
      unitType = "piece",
      costPrice = 30.0,
      sellingPrice = 40.0,
      boxPrice = 440.0,
      piecesPerBox = 12,
      currentStock = 100.0
    )

    // 3. Add 2 pieces
    addToCartWithUnit(soapProduct, 2.0, "piece")
    assertEquals(2, cartItems.size)
    val soapItem1 = cartItems.find { it.product.id == soapProduct.id }
    assertNotNull(soapItem1)
    assertEquals(2.0, soapItem1!!.quantity, 0.001)

    // 4. Add 1 Box (12 pieces) -> should combine into the same line item totaling 14 pieces
    addToCartWithUnit(soapProduct, 1.0, "box")
    assertEquals(2, cartItems.size)
    val soapItem2 = cartItems.find { it.product.id == soapProduct.id }
    assertNotNull(soapItem2)
    assertEquals(14.0, soapItem2!!.quantity, 0.001)
    // With 14 pieces >= 12 (pieces per box), bulk tier pricing applies: 14 * (440.0 / 12.0) = 513.33
    assertEquals(14.0 * (440.0 / 12.0), soapItem2.subtotal, 0.01)
  }

  @Test
  fun testCreditLimitUtilizationProportionality() {
    val customer0Percent = com.example.data.local.entities.Customer(
      id = "c0",
      name = "Zero Balance Cust",
      phone = "9876543210",
      balance = 0.0,
      creditLimit = 5000.0
    )
    val customer2Percent = com.example.data.local.entities.Customer(
      id = "c2",
      name = "Two Percent Cust",
      phone = "9876543211",
      balance = 100.0,
      creditLimit = 5000.0
    )
    val customer50Percent = com.example.data.local.entities.Customer(
      id = "c50",
      name = "Fifty Percent Cust",
      phone = "9876543212",
      balance = 2500.0,
      creditLimit = 5000.0
    )

    assertEquals(0.0f, customer0Percent.getCreditUtilizationPercent(), 0.0001f)
    assertEquals(0.02f, customer2Percent.getCreditUtilizationPercent(), 0.0001f)
    assertEquals(0.50f, customer50Percent.getCreditUtilizationPercent(), 0.0001f)

    // Verify progress bar proportion calculation (balance / limit)
    assertEquals(0f, ((customer0Percent.balance / customer0Percent.creditLimit!!).toFloat()).coerceIn(0f, 1f), 0.0001f)
    assertEquals(0.02f, ((customer2Percent.balance / customer2Percent.creditLimit!!).toFloat()).coerceIn(0f, 1f), 0.0001f)
    assertEquals(0.50f, ((customer50Percent.balance / customer50Percent.creditLimit!!).toFloat()).coerceIn(0f, 1f), 0.0001f)
  }

  @Test
  fun testOverdueAlertGrammar() {
    fun formatOverdueAlertTitle(count: Int, amount: Double): String {
      val totalFormatted = "₹%.2f".format(amount)
      val customerNoun = if (count == 1) "Customer" else "Customers"
      return "🔴 Khata Alert: $count Overdue $customerNoun ($totalFormatted)"
    }

    assertEquals("🔴 Khata Alert: 1 Overdue Customer (₹500.00)", formatOverdueAlertTitle(1, 500.0))
    assertEquals("🔴 Khata Alert: 2 Overdue Customers (₹1200.00)", formatOverdueAlertTitle(2, 1200.0))
    assertEquals("🔴 Khata Alert: 5 Overdue Customers (₹3500.00)", formatOverdueAlertTitle(5, 3500.0))
  }

  @Test
  fun testPaymentRepaymentSmsGeneration() {
    val smsEn = com.example.utils.SmsHelper.generatePaymentRepaymentSms(
      customerName = "Rahul",
      storeName = "Amar Dukan",
      repaidAmount = 500.0,
      paymentMode = "UPI",
      remainingBalance = 200.0,
      transactionDateMs = 1750000000000L,
      isBengali = false
    )
    assertTrue(smsEn.contains("Hi Rahul"))
    assertTrue(smsEn.contains("Payment Received: Rs.500 (UPI)"))
    assertTrue(smsEn.contains("Current Due Balance: Rs.200"))

    val smsEnZero = com.example.utils.SmsHelper.generatePaymentRepaymentSms(
      customerName = "Rahul",
      storeName = "Amar Dukan",
      repaidAmount = 700.0,
      paymentMode = "CASH",
      remainingBalance = 0.0,
      transactionDateMs = 1750000000000L,
      isBengali = false
    )
    assertTrue(smsEnZero.contains("All Dues Cleared (Rs.0)"))

    val smsBn = com.example.utils.SmsHelper.generatePaymentRepaymentSms(
      customerName = "রাহুল",
      storeName = "আমার দোকান",
      repaidAmount = 500.0,
      paymentMode = "CASH",
      remainingBalance = 150.0,
      transactionDateMs = 1750000000000L,
      isBengali = true
    )
    assertTrue(smsBn.contains("নমস্কার রাহুল"))
    assertTrue(smsBn.contains("পরিশোধ গৃহীত: ₹500"))
    assertTrue(smsBn.contains("₹150"))
  }

  @Test
  fun testThermal58mmPrinterConfiguration() {
    // Verify default paper size is 58mm (384 dots)
    assertEquals("THERMAL_58MM", com.example.utils.StoreInfoManager.pdfPaperSize)

    // Verify paper size setter updates state
    com.example.utils.StoreInfoManager.setPaperSizeDirect("THERMAL_80MM")
    assertEquals("THERMAL_80MM", com.example.utils.StoreInfoManager.pdfPaperSize)

    com.example.utils.StoreInfoManager.setPaperSizeDirect("THERMAL_58MM")
    assertEquals("THERMAL_58MM", com.example.utils.StoreInfoManager.pdfPaperSize)
  }
}

