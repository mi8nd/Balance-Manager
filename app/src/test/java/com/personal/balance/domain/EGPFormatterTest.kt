package com.personal.balance.domain

import org.junit.Assert.*
import org.junit.Test

class EGPFormatterTest {

    @Test
    fun parseInputToPiastres_validEGP() {
        val result = EGPFormatter.parseInputToPiastres("25.50")
        assertEquals(2550L, result)
    }

    @Test
    fun parseInputToPiastres_wholeNumber() {
        val result = EGPFormatter.parseInputToPiastres("100")
        assertEquals(10000L, result)
    }

    @Test
    fun parseInputToPiastres_negativeValue() {
        val result = EGPFormatter.parseInputToPiastres("-50.00")
        assertNull(result)
    }

    @Test
    fun parseInputToPiastres_emptyString() {
        val result = EGPFormatter.parseInputToPiastres("")
        assertNull(result)
    }

    @Test
    fun parseInputToPiastres_invalidFormat() {
        val result = EGPFormatter.parseInputToPiastres("abc")
        assertNull(result)
    }

    @Test
    fun parseInputToPiastres_commaDecimal() {
        val result = EGPFormatter.parseInputToPiastres("50,25")
        assertEquals(5025L, result)
    }

    @Test
    fun parseInputToPiastres_zero() {
        val result = EGPFormatter.parseInputToPiastres("0")
        assertEquals(0L, result)
    }

    @Test
    fun parseInputToPiastres_largeAmount() {
        val result = EGPFormatter.parseInputToPiastres("999999.99")
        assertEquals(99999999L, result)
    }

    @Test
    fun formatAmount_EGP() {
        val result = EGPFormatter.formatAmount(2550)
        assertEquals("25.50 EGP", result)
    }

    @Test
    fun formatAmount_zero() {
        val result = EGPFormatter.formatAmount(0)
        assertEquals("0.00 EGP", result)
    }
}
