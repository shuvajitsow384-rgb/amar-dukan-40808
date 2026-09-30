package com.example

import android.graphics.Bitmap
import android.graphics.Color
import com.example.utils.EscPosPrinter
import com.example.utils.StoreInfoManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ThermalPrinterTestScreenTest {

    @Test
    fun testEvaluateThermalDensityStatus_ranges() {
        // Optimal range: 5.0% .. 22.0% -> PASS
        assertEquals("PASS", EscPosPrinter.evaluateThermalDensityStatus(5.0))
        assertEquals("PASS", EscPosPrinter.evaluateThermalDensityStatus(14.5))
        assertEquals("PASS", EscPosPrinter.evaluateThermalDensityStatus(22.0))

        // Marginal / warning ranges: 2.5..<5.0 or 22.0<..28.0 -> WARN
        assertEquals("WARN", EscPosPrinter.evaluateThermalDensityStatus(2.5))
        assertEquals("WARN", EscPosPrinter.evaluateThermalDensityStatus(4.9))
        assertEquals("WARN", EscPosPrinter.evaluateThermalDensityStatus(22.1))
        assertEquals("WARN", EscPosPrinter.evaluateThermalDensityStatus(27.9))

        // Extreme ranges: < 2.5 or > 28.0 -> FAIL
        assertEquals("FAIL", EscPosPrinter.evaluateThermalDensityStatus(2.4))
        assertEquals("FAIL", EscPosPrinter.evaluateThermalDensityStatus(0.0))
        assertEquals("FAIL", EscPosPrinter.evaluateThermalDensityStatus(28.1))
        assertEquals("FAIL", EscPosPrinter.evaluateThermalDensityStatus(50.0))
    }

    @Test
    fun testCalculate1BitBlackPixelPercentage_pureColors() {
        // All white bitmap -> 0.0%
        val whiteBmp = Bitmap.createBitmap(50, 50, Bitmap.Config.ARGB_8888)
        whiteBmp.eraseColor(Color.WHITE)
        val whitePct = EscPosPrinter.calculate1BitBlackPixelPercentage(whiteBmp, threshold = 150)
        assertEquals(0.0, whitePct, 0.001)

        // All black bitmap -> 100.0%
        val blackBmp = Bitmap.createBitmap(50, 50, Bitmap.Config.ARGB_8888)
        blackBmp.eraseColor(Color.BLACK)
        val blackPct = EscPosPrinter.calculate1BitBlackPixelPercentage(blackBmp, threshold = 150)
        assertEquals(100.0, blackPct, 0.001)

        // Half black, half white bitmap -> 50.0%
        val halfBmp = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        halfBmp.eraseColor(Color.WHITE)
        for (y in 0 until 50) {
            for (x in 0 until 100) {
                halfBmp.setPixel(x, y, Color.BLACK)
            }
        }
        val halfPct = EscPosPrinter.calculate1BitBlackPixelPercentage(halfBmp, threshold = 150)
        assertEquals(50.0, halfPct, 0.001)
    }

    @Test
    fun testGenerateTestReceiptBitmap_normalDensityPass() {
        val bitmap = EscPosPrinter.generateTestReceiptBitmap(
            isBengali = false,
            widthDots = 384,
            threshold = 150
        )
        assertNotNull(bitmap)
        assertEquals(384, bitmap.width)
        assertTrue(bitmap.height > 100)

        // Verify black pixel percentage of the generated 1-bit receipt template falls within expected range
        val percentage = EscPosPrinter.calculate1BitBlackPixelPercentage(bitmap, threshold = 150)
        assertTrue("Expected percentage >= 5.0 but was $percentage", percentage >= 5.0)
        assertTrue("Expected percentage <= 22.0 but was $percentage", percentage <= 22.0)

        val status = EscPosPrinter.evaluateThermalDensityStatus(percentage)
        assertEquals("PASS", status)
    }

    @Test
    fun testGenerateTestReceiptBitmap_bengaliMode() {
        val bitmap = EscPosPrinter.generateTestReceiptBitmap(
            isBengali = true,
            widthDots = 384,
            threshold = 150
        )
        assertNotNull(bitmap)
        assertEquals(384, bitmap.width)

        val percentage = EscPosPrinter.calculate1BitBlackPixelPercentage(bitmap, threshold = 150)
        assertTrue("Bengali test receipt coverage should be >= 5.0 but was $percentage", percentage >= 5.0)
        assertTrue("Bengali test receipt coverage should be <= 22.0 but was $percentage", percentage <= 22.0)

        val status = EscPosPrinter.evaluateThermalDensityStatus(percentage)
        assertEquals("PASS", status)
    }

    @Test
    fun testDensityChangeReflectsInPercentage() {
        val lowThresholdBmp = EscPosPrinter.generateTestReceiptBitmap(
            isBengali = false,
            widthDots = 384,
            threshold = 40
        )
        val highThresholdBmp = EscPosPrinter.generateTestReceiptBitmap(
            isBengali = false,
            widthDots = 384,
            threshold = 240
        )

        val lowPct = EscPosPrinter.calculate1BitBlackPixelPercentage(lowThresholdBmp, threshold = 40)
        val highPct = EscPosPrinter.calculate1BitBlackPixelPercentage(highThresholdBmp, threshold = 240)

        // On pure binary renderers, lowPct is equal; on anti-aliased renderers, lowPct < highPct
        assertTrue("Lower threshold should produce lower or equal black pixel percentage: low=$lowPct, high=$highPct", lowPct <= highPct)

        // Deterministic grayscale sensitivity verification:
        val grayBmp = Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888)
        grayBmp.eraseColor(Color.rgb(128, 128, 128))
        val grayBelowThreshold = EscPosPrinter.calculate1BitBlackPixelPercentage(grayBmp, threshold = 100)
        val grayAboveThreshold = EscPosPrinter.calculate1BitBlackPixelPercentage(grayBmp, threshold = 150)

        assertEquals(0.0, grayBelowThreshold, 0.001)
        assertEquals(100.0, grayAboveThreshold, 0.001)
    }
}
