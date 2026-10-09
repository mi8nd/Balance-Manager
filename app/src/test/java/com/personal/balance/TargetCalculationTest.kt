package com.personal.balance

import org.junit.Assert.assertEquals
import org.junit.Test

class TargetCalculationTest {

    private fun calculateProgress(currentBalance: Long, targetAmount: Long): Float {
        return (currentBalance.toFloat() / targetAmount.coerceAtLeast(1L).toFloat()).coerceIn(0f, 1f)
    }

    @Test
    fun targetProgress_zeroBalance() {
        assertEquals(0f, calculateProgress(0L, 500000L), 0.001f)
    }

    @Test
    fun targetProgress_partialProgress() {
        assertEquals(0.5f, calculateProgress(250000L, 500000L), 0.001f)
    }

    @Test
    fun targetProgress_exactTarget_100Percent() {
        assertEquals(1f, calculateProgress(500000L, 500000L), 0.001f)
    }

    @Test
    fun targetProgress_overTarget_clampsTo100Percent() {
        assertEquals(1f, calculateProgress(600000L, 500000L), 0.001f)
    }

    @Test
    fun targetProgress_zeroTarget_avoidsDivisionByZero() {
        assertEquals(0f, calculateProgress(0L, 0L), 0.001f)
        assertEquals(1f, calculateProgress(100L, 0L), 0.001f) // Clamped to 1f if balance > 0
    }

    @Test
    fun targetProgress_negativeBalance_clampsToZero() {
        assertEquals(0f, calculateProgress(-5000L, 5000L), 0.001f)
    }
}
