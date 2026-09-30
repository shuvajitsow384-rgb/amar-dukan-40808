package com.example

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.data.local.entities.Product
import com.example.ui.screens.pos.ProductsGridContent
import com.example.ui.theme.MyApplicationTheme
import com.example.viewmodel.CartItem
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PosProductCardCartTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun testPlainKgAndBoxPieceProductsInCartShowBannerAndStepper() {
        val kgProduct = Product(
            id = "prod_kg_vv",
            nameEn = "vv",
            nameBn = "vv",
            category = "Staples",
            costPrice = 150.0,
            sellingPrice = 200.0,
            wholesalePrice = 150.0,
            unitType = "kg",
            currentStock = 81.1144,
            lowStockThreshold = 10.0
        )

        val boxPieceProduct = Product(
            id = "prod_box_ss",
            nameEn = "ss",
            nameBn = "ss",
            category = "General",
            costPrice = 7.0,
            sellingPrice = 10.0,
            wholesalePrice = 8.5,
            unitType = "piece",
            secondaryUnitType = "box",
            piecesPerBox = 30,
            boxPrice = 260.0,
            currentStock = 202.0,
            lowStockThreshold = 20.0
        )

        val products = listOf(kgProduct, boxPieceProduct)
        val cartItems = mutableStateListOf<CartItem>()

        composeTestRule.setContent {
            MyApplicationTheme {
                ProductsGridContent(
                    products = products,
                    filteredProducts = products,
                    cartItems = cartItems,
                    isBn = false,
                    hasCartItems = cartItems.isNotEmpty(),
                    onProductClick = {},
                    onQuickIncrement = { prod ->
                        val existingIndex = cartItems.indexOfFirst { it.product.id == prod.id }
                        if (existingIndex >= 0) {
                            val cur = cartItems[existingIndex]
                            cartItems[existingIndex] = cur.copy(quantity = cur.quantity + 1.0)
                        } else {
                            val unit = if (prod.hasBoxPricing() || prod.unitType.equals("box", ignoreCase = true)) "piece" else prod.unitType
                            cartItems.add(CartItem(prod, 1.0, prod.sellingPrice, unit))
                        }
                    },
                    onQuickDecrement = { prod ->
                        val existingIndex = cartItems.indexOfFirst { it.product.id == prod.id }
                        if (existingIndex >= 0) {
                            val cur = cartItems[existingIndex]
                            if (cur.quantity <= 1.0) {
                                cartItems.removeAt(existingIndex)
                            } else {
                                cartItems[existingIndex] = cur.copy(quantity = cur.quantity - 1.0)
                            }
                        }
                    },
                    onClearFilters = {}
                )
            }
        }

        // 1. Initial State: Neither product is in bill -> Both should have "+ Add" buttons
        composeTestRule.onNodeWithTag("product_add_button_${kgProduct.id}").assertIsDisplayed()
        composeTestRule.onNodeWithTag("product_add_button_${boxPieceProduct.id}").assertIsDisplayed()

        // 2. Add kg product (vv) to cart
        composeTestRule.onNodeWithTag("product_add_button_${kgProduct.id}").performClick()

        // Verify kg product now shows "In Bill: 1 kg" banner and stepper controls
        composeTestRule.onNodeWithText("In Bill: 1 kg").assertIsDisplayed()
        composeTestRule.onNodeWithTag("stepper_decrement_${kgProduct.id}").assertIsDisplayed()
        composeTestRule.onNodeWithTag("stepper_increment_${kgProduct.id}").assertIsDisplayed()
        composeTestRule.onNodeWithTag("stepper_qty_${kgProduct.id}").assertIsDisplayed()

        // 3. Add box/piece product (ss) to cart simultaneously
        composeTestRule.onNodeWithTag("product_add_button_${boxPieceProduct.id}").performClick()

        // Verify BOTH items show their In Bill banners and steppers at the same time
        composeTestRule.onNodeWithText("In Bill: 1 kg").assertIsDisplayed()
        composeTestRule.onNodeWithTag("stepper_decrement_${kgProduct.id}").assertIsDisplayed()
        composeTestRule.onNodeWithTag("stepper_increment_${kgProduct.id}").assertIsDisplayed()

        composeTestRule.onNodeWithText("In Bill: 1 piece").assertIsDisplayed()
        composeTestRule.onNodeWithTag("stepper_decrement_${boxPieceProduct.id}").assertIsDisplayed()
        composeTestRule.onNodeWithTag("stepper_increment_${boxPieceProduct.id}").assertIsDisplayed()

        // 4. Increment ss quantity to 2
        composeTestRule.onNodeWithTag("stepper_increment_${boxPieceProduct.id}").performClick()
        composeTestRule.onNodeWithText("In Bill: 2 piece").assertIsDisplayed()

        // 5. Decrement vv from 1 to 0 -> should remove from cart and show "+ Add" again
        composeTestRule.onNodeWithTag("stepper_decrement_${kgProduct.id}").performClick()
        composeTestRule.onNodeWithTag("product_add_button_${kgProduct.id}").assertIsDisplayed()

        // ss should still remain in bill
        composeTestRule.onNodeWithText("In Bill: 2 piece").assertIsDisplayed()
        composeTestRule.onNodeWithTag("stepper_decrement_${boxPieceProduct.id}").assertIsDisplayed()
    }
}
