package com.personal.balance.domain.ai.orchestration

/**
 * Picks which pre-computed financial facts reach the composer for one request,
 * driven by the plan's [AiDataNeed]s. This keeps prompts smaller than
 * always sending the full financial context while reusing exactly the fact keys
 * produced by the existing deterministic builders.
 *
 * Semantics:
 *  - Empty needs -> every fact provided (the capability gets full context).
 *  - Non-empty needs -> only facts in the matching groups, plus `periods`
 *    (the date labels every answer references).
 */
object FinancialFactsSelector {

    private const val PERIODS_KEY = "periods"

    private val FACT_GROUPS = mapOf(
        AiDataNeed.CURRENT_PERIOD_TOTALS to setOf(
            PERIODS_KEY,
            "current_expense_total", "current_income_total", "current_transaction_count",
            "current_net_change", "current_balance", "window_transaction_count",
            "average_expense_per_transaction", "average_income_per_transaction",
        ),
        AiDataNeed.CURRENT_BALANCE to setOf(
            PERIODS_KEY,
            "current_balance", "current_net_change",
        ),
        AiDataNeed.PREVIOUS_PERIOD_TOTALS to setOf(
            PERIODS_KEY,
            "previous_expense_total", "previous_income_total", "previous_transaction_count",
            "previous_expense_count", "previous_income_count", "expense_change_vs_previous",
            "net_change_vs_previous", "expense_change", "income_change", "transaction_frequency_change",
        ),
        AiDataNeed.CATEGORY_BREAKDOWN to setOf(
            PERIODS_KEY,
            "expense_categories_totals", "expense_categories_percent_of_total",
            "income_categories_totals", "previous_expense_categories_totals",
            "category_deltas", "top_category_share", "candidate_recurring_categories",
        ),
        AiDataNeed.LARGEST_SMALLEST to setOf(
            PERIODS_KEY,
            "largest_expense_in_current_period", "smallest_expense_in_current_period",
            "largest_income_in_current_period", "largest_expense_share",
            "largest_expense_vs_average", "largest_income_vs_average",
        ),
        AiDataNeed.RECENT_TRANSACTIONS to setOf(
            PERIODS_KEY,
            "recent_transactions",
        ),
        AiDataNeed.TRANSACTION_PATTERNS to setOf(
            PERIODS_KEY,
            "window_transaction_count", "expense_change", "income_change",
            "transaction_frequency_change", "category_deltas", "top_category_share",
            "largest_expense_share", "average_expense_per_transaction",
            "average_income_per_transaction", "largest_expense_vs_average",
            "largest_income_vs_average", "candidate_recurring_categories",
        ),
        // Goal facts are appended by the orchestrator AFTER selection, so this
        // group exists for plan coherence; the keys themselves always survive.
        AiDataNeed.GOAL_DATA to setOf(
            "goal_name", "goal_target", "goal_current",
        ),
    )

    fun select(facts: Map<String, String>, dataNeeds: Set<AiDataNeed>): Map<String, String> {
        if (dataNeeds.isEmpty()) return facts
        val wanted = dataNeeds.flatMap { FACT_GROUPS[it] ?: emptySet() }.toSet()
        return facts.filterKeys { key -> key in wanted || key == PERIODS_KEY }
    }
}