package com.personal.balance

import org.junit.Assert.assertEquals
import org.junit.Test

class CircularProgressTest {

    private fun calculateProgress(currentBalance: Long, targetAmount: Long): Float {
        return (currentBalance.toFloat() / targetAmount.coerceAtLeast(1L).toFloat()).coerceIn(0f, 1f)
    }

    private fun formatPercentage(progress: Float): String {
        return "${(progress * 100).toInt()}%"
    }

    @Test
    fun circularProgress_zeroBalance() {
        val progress = calculateProgress(0L, 500000L)
        assertEquals(0f, progress, 0.001f)
        assertEquals("0%", formatPercentage(progress))
    }

    @Test
    fun circularProgress_partialProgress() {
        val progress = calculateProgress(250000L, 500000L)
        assertEquals(0.5f, progress, 0.001f)
        assertEquals("50%", formatPercentage(progress))
    }

    @Test
    fun circularProgress_exactTarget() {
        val progress = calculateProgress(500000L, 500000L)
        assertEquals(1f, progress, 0.001f)
        assertEquals("100%", formatPercentage(progress))
    }

    @Test
    fun circularProgress_overTarget_clampsTo100Percent() {
        val progress = calculateProgress(600000L, 500000L)
        assertEquals(1f, progress, 0.001f)
        assertEquals("100%", formatPercentage(progress))
    }

    @Test
    fun circularProgress_zeroTarget_avoidsDivisionByZero() {
        val progress = calculateProgress(0L, 0L)
        assertEquals(0f, progress, 0.001f)

        val progressWithBalance = calculateProgress(100L, 0L)
        assertEquals(1f, progressWithBalance, 0.001f)
    }

    @Test
    fun circularProgress_negativeBalance_clampsToZero() {
        val progress = calculateProgress(-5000L, 5000L)
        assertEquals(0f, progress, 0.001f)
    }
}
