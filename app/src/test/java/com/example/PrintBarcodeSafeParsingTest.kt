package com.example

import com.example.ui.components.parseSafePriceInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PrintBarcodeSafeParsingTest {

    @Test
    fun testParseSafePriceInput_clearingFieldCompletely() {
        assertEquals(0.0, parseSafePriceInput(""), 0.001)
        assertEquals(0.0, parseSafePriceInput("   "), 0.001)
    }

    @Test
    fun testParseSafePriceInput_typingJustDot() {
        assertEquals(0.0, parseSafePriceInput("."), 0.001)
        assertEquals(0.0, parseSafePriceInput(" . "), 0.001)
    }

    @Test
    fun testParseSafePriceInput_intermediateTypingStates() {
        // Typing "120."
        assertEquals(120.0, parseSafePriceInput("120."), 0.001)
        // Typing ".5"
        assertEquals(0.5, parseSafePriceInput(".5"), 0.001)
        // Typing "0."
        assertEquals(0.0, parseSafePriceInput("0."), 0.001)
        // Typing "120.5"
        assertEquals(120.5, parseSafePriceInput("120.5"), 0.001)
        // Typing "120.50"
        assertEquals(120.50, parseSafePriceInput("120.50"), 0.001)
    }

    @Test
    fun testParseSafePriceInput_multipleDecimalPoints() {
        // "120.50.25"
        assertEquals(0.0, parseSafePriceInput("120.50.25", fallback = 0.0), 0.001)
        // ".."
        assertEquals(0.0, parseSafePriceInput("..", fallback = 0.0), 0.001)
        // "...1"
        assertEquals(0.0, parseSafePriceInput("...1", fallback = 0.0), 0.001)
        // "1.2.3.4"
        assertEquals(50.0, parseSafePriceInput("1.2.3.4", fallback = 50.0), 0.001)
    }

    @Test
    fun testParseSafePriceInput_veryLargeNumbers() {
        val hugeNumber = "99999999999999999999999999999999999999999999"
        val parsed = parseSafePriceInput(hugeNumber)
        assertTrue(parsed <= 99_999_999.0)
        assertTrue(parsed >= 0.0)
    }

    @Test
    fun testParseSafePriceInput_invalidCharacters() {
        assertEquals(0.0, parseSafePriceInput("abc"), 0.001)
        assertEquals(0.0, parseSafePriceInput("₹120"), 0.001)
        assertEquals(0.0, parseSafePriceInput("-50"), 0.001)
        assertEquals(25.0, parseSafePriceInput("invalid", fallback = 25.0), 0.001)
    }

    @Test
    fun testQuantityParsingSafety() {
        val parseQty: (String) -> Int = { text ->
            try {
                val trimmed = text.trim()
                if (trimmed.isEmpty()) {
                    1
                } else {
                    val parsedLong = trimmed.toLongOrNull()
                        ?: if (trimmed.all { it.isDigit() } && trimmed.isNotEmpty()) trimmed.take(6).toLongOrNull() else null
                    val safeLong = parsedLong ?: 1L
                    safeLong.coerceIn(1L, 500L).toInt()
                }
            } catch (_: Throwable) {
                1
            }
        }

        assertEquals(1, parseQty(""))
        assertEquals(1, parseQty("."))
        assertEquals(1, parseQty("abc"))
        assertEquals(1, parseQty("-5"))
        assertEquals(5, parseQty("5"))
        assertEquals(100, parseQty("100"))
        assertEquals(500, parseQty("9999999999999999999999999"))
        assertEquals(500, parseQty("600"))
    }

    @Test
    fun testFormatExpiryDate_sanitizationAndPatterns() {
        // Sanitize double slashes
        assertEquals("02/03/27", com.example.utils.EscPosPrinter.formatExpiryDate("2//03/27"))
        assertEquals("02/03/27", com.example.utils.EscPosPrinter.formatExpiryDate("02//03//2027"))
        
        // Standard ISO date
        assertEquals("06/03/27", com.example.utils.EscPosPrinter.formatExpiryDate("2027-03-06"))
        
        // Single digit day/month
        assertEquals("05/04/26", com.example.utils.EscPosPrinter.formatExpiryDate("5/4/26"))
        assertEquals("05/04/26", com.example.utils.EscPosPrinter.formatExpiryDate("2026-4-5"))
        
        // Month and Year only
        assertEquals("03/27", com.example.utils.EscPosPrinter.formatExpiryDate("03/2027"))
        assertEquals("03/27", com.example.utils.EscPosPrinter.formatExpiryDate("2027-03"))
        
        // Empty / blank input
        assertEquals(null, com.example.utils.EscPosPrinter.formatExpiryDate(null))
        assertEquals(null, com.example.utils.EscPosPrinter.formatExpiryDate(""))
        assertEquals(null, com.example.utils.EscPosPrinter.formatExpiryDate("   "))
        assertEquals(null, com.example.utils.EscPosPrinter.formatExpiryDate("///"))
    }

    @Test
    fun testLabelInitCommands_noHexDumpCorruption() {
        val initBytes = com.example.utils.EscPosPrinter.ESC_LABEL_INIT
        // Must contain ESC @ (1B 40) and ESC 3 0 (1B 33 00)
        assertEquals(0x1B.toByte(), initBytes[0])
        assertEquals(0x40.toByte(), initBytes[1])
        assertEquals(0x1B.toByte(), initBytes[2])
        assertEquals(0x33.toByte(), initBytes[3])
        assertEquals(0x00.toByte(), initBytes[4])
        // Must not contain hex-dump command 1D 28 41
        val hexDumpTrigger = byteArrayOf(0x1D, 0x28, 0x41)
        var foundHexDump = false
        for (i in 0 until initBytes.size - 2) {
            if (initBytes[i] == hexDumpTrigger[0] && initBytes[i+1] == hexDumpTrigger[1] && initBytes[i+2] == hexDumpTrigger[2]) {
                foundHexDump = true
                break
            }
        }
        org.junit.Assert.assertFalse("Label init should never send hex-dump trigger", foundHexDump)
    }
}

