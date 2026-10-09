package com.personal.balance.domain.insights

import com.personal.balance.domain.finance.FinancialWindows
import com.personal.balance.domain.finance.TransactionIntelligenceSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class FinancialInsightsContextBuilderTest {

    private val zone: ZoneId = ZoneId.of("UTC")
    private val nowEpochMillis: Long = Instant.parse("2026-08-15T12:00:00Z").toEpochMilli()

    private fun snapshot(
        currentBalance: Long? = 500_00L,
        netChangeCurrent: Long = 1000_00L,
        netChangePrevious: Long = -500_00L,
        uncategorizedCount: Int = 2,
        goal: GoalInsightFacts? = GoalInsightFacts(
            name = "Emergency fund",
            targetPiastres = 100_000_00L,
            currentPiastres = 25_000_00L,
            completed = false,
        ),
    ): FinancialInsightsSnapshot {
        val window = FinancialWindows.windowAt(nowEpochMillis, zone)
        val intelligence = TransactionIntelligenceAnalyzerStub.analyze(window.currentLabel, window.previousLabel)
        return FinancialInsightsSnapshot(
            intelligence = intelligence,
            currentBalance = currentBalance,
            netChangeCurrent = netChangeCurrent,
            netChangePrevious = netChangePrevious,
            uncategorizedCurrentCount = uncategorizedCount,
            goal = goal,
        )
    }

    @Test
    fun buildsRequestWithAccountAndGoalFacts() {
        val request = FinancialInsightsContextBuilder.build(snapshot(), zone)

        assertTrue(request.question.contains("INSIGHT|LEVEL|Title|Explanation"))
        val facts = request.structuredFacts
        assertEquals("500.00 EGP", facts["account_balance"])
        assertTrue(facts["net_change_current"]!!.contains("+1,000.00 EGP"))
        assertTrue(facts["net_change_previous"]!!.contains("-500.00 EGP"))
        assertEquals("2 transaction(s) in the current period have no category yet", facts["uncategorized_current_transactions"])
        assertEquals("Emergency fund", facts["goal_name"])
        assertEquals("100,000.00 EGP", facts["goal_target"])
        assertEquals("25,000.00 EGP", facts["goal_current"])
        assertEquals("75,000.00 EGP", facts["goal_remaining"])
        assertEquals("25%", facts["goal_progress_percent"])
        assertEquals("false", facts["goal_completed"])
        assertTrue(request.additionalInstructions!!.contains("account_balance"))
    }

    @Test
    fun missingBalanceAndGoalAreReportedHonestly() {
        val request = FinancialInsightsContextBuilder.build(
            snapshot(currentBalance = null, goal = null),
            zone,
        )

        val facts = request.structuredFacts
        assertEquals("not available", facts["account_balance"])
        assertEquals("No savings goal is currently tracked in the app.", facts["goal_status"])
        assertNull("should have no goal facts", facts["goal_name"])
    }

    @Test
    fun zeroUncategorizedIsReportedPositively() {
        val request = FinancialInsightsContextBuilder.build(
            snapshot(uncategorizedCount = 0, goal = null),
            zone,
        )

        assertEquals(
            "every transaction in the current period already has a category",
            request.structuredFacts["uncategorized_current_transactions"],
        )
    }

    /**
     * A minimal deterministic snapshot suffices here — the base facts come from
     * [TransactionIntelligenceContextBuilder] (already covered by its own tests)
     * and only the insights-specific additions are exercised.
     */
    private object TransactionIntelligenceAnalyzerStub {
        fun analyze(currentLabel: String, previousLabel: String): TransactionIntelligenceSnapshot =
            TransactionIntelligenceSnapshot(
                currentLabel = currentLabel,
                previousLabel = previousLabel,
                hasEnoughData = true,
                windowTransactionCount = 0,
                currentExpenseTotal = 0L,
                currentIncomeTotal = 0L,
                previousExpenseTotal = 0L,
                previousIncomeTotal = 0L,
                currentExpenseCount = 0,
                currentIncomeCount = 0,
                previousExpenseCount = 0,
                previousIncomeCount = 0,
                categoryExpenseTotals = emptyList(),
                previousCategoryExpenseTotals = emptyList(),
                categoryDeltas = emptyList(),
                largestExpense = null,
                largestIncome = null,
                expenseChangePercent = null,
                incomeChangePercent = null,
                frequencyChangePercent = null,
                topCategorySharePercent = null,
                largestExpenseSharePercent = null,
                averageExpense = null,
                averageIncome = null,
                largestExpenseVsAverageMultiple = null,
                largestIncomeVsAverageMultiple = null,
                candidateRecurringCategories = emptyList(),
            )
    }
}