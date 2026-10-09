package com.personal.balance.domain.insights

import com.personal.balance.domain.ai.orchestration.GoalCoachState
import com.personal.balance.domain.finance.FinancialWindows
import com.personal.balance.model.Transaction
import com.personal.balance.model.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class FinancialInsightsAnalyzerTest {

    private val zone: ZoneId = ZoneId.of("UTC")
    private val nowEpochMillis: Long = Instant.parse("2026-08-15T12:00:00Z").toEpochMilli()
    private val window = FinancialWindows.windowAt(nowEpochMillis, zone)

    private fun tx(
        amount: Long,
        type: TransactionType = TransactionType.EXPENSE,
        timestamp: Long,
        category: String = "",
    ): Transaction = Transaction(
        userId = "uid-1",
        type = type,
        amount = amount,
        description = "tx",
        category = category,
        timestamp = timestamp,
    )

    @Test
    fun computesNetChangesUncategorizedCountAndGoalFacts() {
        val cur = window.currentMonthStartEpochMillis + 1000
        val prev = window.previousMonthStartEpochMillis + 1000
        val transactions = listOf(
            // Current month: income 10000, expenses 4000 (one uncategorized).
            tx(10000, TransactionType.INCOME, cur, category = "Salary"),
            tx(2500, type = TransactionType.EXPENSE, timestamp = cur, category = "Food"),
            tx(1500, type = TransactionType.EXPENSE, timestamp = cur, category = ""),
            // Previous month: income 8000, expenses 7000.
            tx(8000, TransactionType.INCOME, prev, category = "Salary"),
            tx(7000, type = TransactionType.EXPENSE, timestamp = prev, category = "Housing"),
        )

        val snapshot = FinancialInsightsAnalyzer.analyze(
            transactions = transactions,
            nowEpochMillis = nowEpochMillis,
            zone = zone,
            currentBalance = 500_00L,
            goal = GoalCoachState.Active(
                goalName = "Emergency fund",
                targetPiastres = 100_000_00L,
                currentPiastres = 25_000_00L,
                completed = false,
            ),
        )

        assertEquals(6000L, snapshot.netChangeCurrent)
        assertEquals(1000L, snapshot.netChangePrevious)
        assertEquals(1, snapshot.uncategorizedCurrentCount)
        assertEquals(500_00L, snapshot.currentBalance)
        assertTrue(snapshot.hasEnoughData)
        val goal = snapshot.goal!!
        assertEquals("Emergency fund", goal.name)
        assertEquals(100_000_00L, goal.targetPiastres)
        assertEquals(25_000_00L, goal.currentPiastres)
        assertFalse(goal.completed)
        assertEquals(5, snapshot.intelligence.windowTransactionCount)
    }

    @Test
    fun uncategorizedCountOnlyCountsCurrentPeriod() {
        val cur = window.currentMonthStartEpochMillis + 1000
        val prev = window.previousMonthStartEpochMillis + 1000
        val transactions = listOf(
            tx(100, type = TransactionType.EXPENSE, timestamp = cur, category = "Food"),
            tx(100, type = TransactionType.EXPENSE, timestamp = prev, category = ""),
        )

        val snapshot = FinancialInsightsAnalyzer.analyze(transactions, nowEpochMillis, zone, null, GoalCoachState.Unavailable)

        assertEquals(0, snapshot.uncategorizedCurrentCount)
        assertNull(snapshot.currentBalance)
        assertNull(snapshot.goal)
    }
}