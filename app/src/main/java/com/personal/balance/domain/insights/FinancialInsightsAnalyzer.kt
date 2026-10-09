package com.personal.balance.domain.insights

import com.personal.balance.domain.ai.orchestration.GoalCoachState
import com.personal.balance.domain.finance.FinancialWindows
import com.personal.balance.domain.finance.TransactionIntelligenceAnalyzer
import com.personal.balance.model.Transaction
import java.time.ZoneId

/**
 * Deterministic analysis for Financial Insights. Reuses the Transaction
 * Intelligence analyzer for the transaction window and adds account-context
 * facts: current balance (nullable — unavailable when the config read fails),
 * month-over-month net change, the count of yet-uncategorized transactions in
 * the current period, and the active goal (or null). Kotlin-only; never routed
 * through the model.
 */
object FinancialInsightsAnalyzer {

    fun analyze(
        transactions: List<Transaction>,
        nowEpochMillis: Long,
        zone: ZoneId,
        currentBalance: Long?,
        goal: GoalCoachState,
    ): FinancialInsightsSnapshot {
        val intelligence = TransactionIntelligenceAnalyzer.analyze(transactions, nowEpochMillis, zone)
        val window = FinancialWindows.windowAt(nowEpochMillis, zone)
        val inWindow = transactions.filter {
            it.timestamp in window.previousMonthStartEpochMillis until window.currentMonthEndEpochMillis
        }
        val current = inWindow.filter { it.timestamp >= window.currentMonthStartEpochMillis }
        val uncategorizedCurrentCount = current.count { it.category.isBlank() }

        return FinancialInsightsSnapshot(
            intelligence = intelligence,
            currentBalance = currentBalance,
            netChangeCurrent = intelligence.currentIncomeTotal - intelligence.currentExpenseTotal,
            netChangePrevious = intelligence.previousIncomeTotal - intelligence.previousExpenseTotal,
            uncategorizedCurrentCount = uncategorizedCurrentCount,
            goal = when (goal) {
                is GoalCoachState.Active -> GoalInsightFacts(
                    name = goal.goalName.trim(),
                    targetPiastres = goal.targetPiastres,
                    currentPiastres = goal.currentPiastres,
                    completed = goal.completed,
                )

                GoalCoachState.Unavailable -> null
            },
        )
    }
}