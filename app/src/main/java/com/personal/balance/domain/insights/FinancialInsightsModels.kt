package com.personal.balance.domain.insights

import com.personal.balance.domain.finance.TransactionIntelligenceSnapshot

/**
 * One actionable, grounded recommendation produced by Financial Insights. The
 * model must phrase these as suggestions ("You could consider…") derived ONLY
 * from the deterministic facts; never as taken/factual actions or as a promise
 * of future outcomes.
 */
data class FinancialRecommendation(
    val title: String,
    val explanation: String,
)

/**
 * Goal facts surfaced to Financial Insights, computed deterministically from
 * the authoritative Goal Manager data (see GoalCoachSource). Never fabricated.
 */
data class GoalInsightFacts(
    val name: String,
    val targetPiastres: Long,
    val currentPiastres: Long,
    val completed: Boolean,
)

/**
 * Deterministic, pre-computed snapshot for Financial Insights: the bounded
 * two-calendar-month transaction analysis (a [TransactionIntelligenceSnapshot])
 * extended with account-context facts the insights are allowed to reference —
 * the current balance, month-over-month net change, the count of uncategorized
 * transactions in the current period, and the active savings goal. Nothing here
 * is deduced by the model, and no raw transaction descriptions cross the AI
 * input boundary.
 */
data class FinancialInsightsSnapshot(
    val intelligence: TransactionIntelligenceSnapshot,
    val currentBalance: Long?,
    val netChangeCurrent: Long,
    val netChangePrevious: Long,
    val uncategorizedCurrentCount: Int,
    val goal: GoalInsightFacts?,
) {
    val hasEnoughData: Boolean get() = intelligence.hasEnoughData
}