package com.example

import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.example.ui.screens.settings.BillingAndHardwareSection
import com.example.ui.theme.MyApplicationTheme
import com.example.viewmodel.StoreViewModel
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class BillingAndHardwareTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        if (FirebaseApp.getApps(context).isEmpty()) {
            val options = FirebaseOptions.Builder()
                .setApplicationId("1:1234567890:android:abcdef")
                .setApiKey("fake-api-key")
                .setProjectId("kalimata-store")
                .build()
            FirebaseApp.initializeApp(context, options)
        }
        try {
            androidx.work.WorkManager.initialize(context, androidx.work.Configuration.Builder().build())
        } catch (_: Exception) {}
    }

    @Test
    fun testBillingAndHardwareSectionRendering() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val viewModel = StoreViewModel(app)

        composeTestRule.setContent {
            MyApplicationTheme {
                BillingAndHardwareSection(
                    context = context,
                    viewModel = viewModel,
                    pairedPrinters = emptyList(),
                    selectedPrinterAddress = null,
                    selectedDensity = "150",
                    onDensityChange = {},
                    selectedFontSize = "NORMAL",
                    onFontSizeChange = {},
                    selectedPaperSize = "THERMAL_58MM",
                    onPaperSizeChange = {},
                    onEditPdfFormatClicked = {},
                    onConfigureKhataInterestClicked = {},
                    onRequestSmsPermission = {},
                    onOpenOffersHubClicked = {}
                )
            }
        }
    }

    @Test
    fun testSettingsScreenBillingAndHardwareTabClick() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val viewModel = StoreViewModel(app)

        composeTestRule.setContent {
            MyApplicationTheme {
                com.example.ui.screens.settings.SettingsScreen(viewModel = viewModel)
            }
        }
        composeTestRule.onNodeWithText("Billing", substring = true).performClick()
    }

    @Test
    fun testOpenEditPdfFormatDialog() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val viewModel = StoreViewModel(app)

        composeTestRule.setContent {
            MyApplicationTheme {
                com.example.ui.components.EditPdfFormatDialog(onDismiss = {})
            }
        }
    }

    @Test
    fun testOpenKhataInterestDialog() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val viewModel = StoreViewModel(app)

        composeTestRule.setContent {
            MyApplicationTheme {
                com.example.ui.screens.settings.KhataInterestSettingsDialog(
                    viewModel = viewModel,
                    onDismiss = {}
                )
            }
        }
    }

    @Test
    fun testOpenOffersHubDialog() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val viewModel = StoreViewModel(app)

        composeTestRule.setContent {
            MyApplicationTheme {
                com.example.ui.screens.offers.OffersManagementDialog(
                    viewModel = viewModel,
                    onDismiss = {}
                )
            }
        }
    }
}
