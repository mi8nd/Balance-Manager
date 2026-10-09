package com.personal.balance.domain.ai.orchestration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FinancialFactsSelectorTest {

    private val sampleFacts = linkedMapOf(
        "periods" to "Aug 2026, Jul 2026",
        "current_expense_total" to "1000.00",
        "current_income_total" to "3000.00",
        "current_transaction_count" to "8",
        "current_net_change" to "+2000.00",
        "current_balance" to "5000.00",
        "previous_expense_total" to "800.00",
        "previous_income_total" to "2500.00",
        "previous_transaction_count" to "5",
        "previous_expense_count" to "4",
        "previous_income_count" to "1",
        "expense_change_vs_previous" to "+200.00",
        "net_change_vs_previous" to "+500.00",
        "expense_categories_totals" to "Food: 500.00",
        "expense_categories_percent_of_total" to "Food: 50%",
        "income_categories_totals" to "Salary: 2500.00",
        "previous_expense_categories_totals" to "Food: 300.00",
        "largest_expense_in_current_period" to "2026-08-01 expense 500.00 Food",
        "smallest_expense_in_current_period" to "2026-08-02 expense 50.00 Transport",
        "largest_income_in_current_period" to "2026-08-01 income 2500.00 Salary",
        "recent_transactions" to "2026-08-15 expense 100.00 Food \"Lunch\"",
        "category_deltas" to "Food: +200.00",
        "top_category_share" to "Food leads with 50% of current total expenses",
        "candidate_recurring_categories" to "Food: 500.00 this month vs 300.00 last month",
        "average_expense_per_transaction" to "125.00",
    )

    @Test
    fun emptyDataNeeds_returnsAllFacts() {
        val result = FinancialFactsSelector.select(sampleFacts, emptySet())
        assertEquals(sampleFacts, result)
    }

    @Test
    fun currentPeriodTotals_onlyIncludesRelatedKeys() {
        val result = FinancialFactsSelector.select(
            sampleFacts,
            setOf(AiDataNeed.CURRENT_PERIOD_TOTALS),
        )

        assertTrue(result.containsKey("periods"))
        assertTrue(result.containsKey("current_expense_total"))
        assertTrue(result.containsKey("current_income_total"))
        assertTrue(result.containsKey("current_transaction_count"))
        assertTrue(result.containsKey("current_net_change"))
        assertTrue(result.containsKey("current_balance"))
        // Should NOT include previous-period or other unrelated keys:
        assertTrue(!result.containsKey("previous_expense_total"))
        assertTrue(!result.containsKey("expense_categories_totals"))
        assertTrue(!result.containsKey("recent_transactions"))
        assertTrue(!result.containsKey("largest_expense_in_current_period"))
    }

    @Test
    fun categoryBreakdown_includesBothCurrentAndPreviousCategories() {
        val result = FinancialFactsSelector.select(
            sampleFacts,
            setOf(AiDataNeed.CATEGORY_BREAKDOWN),
        )

        assertTrue(result.containsKey("periods"))
        assertTrue(result.containsKey("expense_categories_totals"))
        assertTrue(result.containsKey("previous_expense_categories_totals"))
        assertTrue(result.containsKey("category_deltas"))
        assertTrue(result.containsKey("top_category_share"))
        assertTrue(result.containsKey("candidate_recurring_categories"))
        assertTrue(!result.containsKey("current_expense_total"))
        assertTrue(!result.containsKey("recent_transactions"))
    }

    @Test
    fun recentTransactions_onlyIncludesTransactionsAndPeriods() {
        val result = FinancialFactsSelector.select(
            sampleFacts,
            setOf(AiDataNeed.RECENT_TRANSACTIONS),
        )

        assertEquals(2, result.size)
        assertTrue(result.containsKey("periods"))
        assertTrue(result.containsKey("recent_transactions"))
    }

    @Test
    fun multipleDataNeeds_combinedKeys() {
        val result = FinancialFactsSelector.select(
            sampleFacts,
            setOf(AiDataNeed.CURRENT_PERIOD_TOTALS, AiDataNeed.LARGEST_SMALLEST),
        )

        assertTrue(result.containsKey("periods"))
        assertTrue(result.containsKey("current_expense_total"))
        assertTrue(result.containsKey("largest_expense_in_current_period"))
        assertTrue(result.containsKey("smallest_expense_in_current_period"))
        assertTrue(!result.containsKey("expense_categories_totals"))
    }

    @Test
    fun periodsKeyKeptAlways() {
        val result = FinancialFactsSelector.select(
            mapOf("periods" to "test", "irrelevant" to "value"),
            setOf(AiDataNeed.CURRENT_PERIOD_TOTALS),
        )

        assertTrue(result.containsKey("periods"))
        assertTrue(!result.containsKey("irrelevant"))
    }

    @Test
    fun transactionPatterns_onlyIncludesPatternKeys() {
        val result = FinancialFactsSelector.select(
            sampleFacts,
            setOf(AiDataNeed.TRANSACTION_PATTERNS),
        )

        assertTrue(result.containsKey("periods"))
        assertTrue(result.containsKey("category_deltas"))
        assertTrue(result.containsKey("average_expense_per_transaction"))
        assertTrue(result.containsKey("candidate_recurring_categories"))
        assertTrue(!result.containsKey("current_expense_total"))
        assertTrue(!result.containsKey("recent_transactions"))
        assertTrue(!result.containsKey("expense_categories_totals"))
    }

    @Test
    fun currentBalance_onlyIncludesBalanceAndNetChange() {
        val result = FinancialFactsSelector.select(
            sampleFacts,
            setOf(AiDataNeed.CURRENT_BALANCE),
        )

        assertTrue(result.containsKey("periods"))
        assertTrue(result.containsKey("current_balance"))
        assertTrue(result.containsKey("current_net_change"))
        assertTrue(!result.containsKey("current_expense_total"))
        assertTrue(!result.containsKey("recent_transactions"))
    }

    @Test
    fun goalData_groupCoversGoalFactKeys() {
        val result = FinancialFactsSelector.select(
            sampleFacts + mapOf(
                "goal_name" to "Vacation",
                "goal_target" to "5000",
                "goal_current" to "3000",
            ),
            setOf(AiDataNeed.GOAL_DATA),
        )

        assertTrue(result.containsKey("goal_name"))
        assertTrue(result.containsKey("goal_target"))
        assertTrue(result.containsKey("goal_current"))
        assertTrue(!result.containsKey("current_expense_total"))
    }

    @Test
    fun patternPlusFinance_combinedKeys() {
        val result = FinancialFactsSelector.select(
            sampleFacts,
            setOf(AiDataNeed.TRANSACTION_PATTERNS, AiDataNeed.CURRENT_PERIOD_TOTALS),
        )

        assertTrue(result.containsKey("periods"))
        assertTrue(result.containsKey("current_expense_total"))
        assertTrue(result.containsKey("category_deltas"))
        assertTrue(result.containsKey("average_expense_per_transaction"))
        assertTrue(!result.containsKey("recent_transactions"))
        assertTrue(!result.containsKey("largest_expense_in_current_period"))
    }
}