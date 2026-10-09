package com.personal.balance.domain.finance

import com.personal.balance.domain.EGPFormatter
import com.personal.balance.domain.ai.AiRequest
import com.personal.balance.model.Transaction
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Builds the compact [AiRequest] sent to the model for a financial question.
 *
 * Only pre-computed facts are included: no documents, no user ids, no account
 * details. The instruction block tells the model what the facts are and are
 * not, and to say when it genuinely cannot answer.
 *
 * AI-INPUT BOUNDARY (safety false-positive fix): the
 * `largest_expense_in_current_period` and `smallest_expense_in_current_period`
 * facts are sent WITHOUT the raw user-entered transaction description, because
 * a production-style largest-expense record containing a real description was
 * confirmed (on-device diagnostic isolation probes) to trigger the model's
 * safety classifier. Those facts keep only safe structured financial metadata:
 * date, type, amount, category. The description itself is NEVER modified in
 * Firestore or the UI — it is omitted only at the Gemini input boundary, for
 * these two facts. `recent_transactions` is unchanged.
 */
object AiFinancialContextBuilder {

    private val DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US)

    private const val GENERAL = "General"

    private val INSTRUCTIONS = buildString {
        append("The \"Authoritative facts\" above are the only financial data provided to you and are exact values computed by the app. ")
        append("Base your answer on them only. Do not invent transactions, amounts, categories, dates, balances, or goals. ")
        append("You do not have access to the user's bank, cards, accounts, or database; do not claim you read their live data. ")
        append("current_balance is the stored account balance (which may include an initial balance set during onboarding and is updated by transactions). ")
        append("It is NOT a transaction total. current_income_total, current_expense_total, and current_net_change are derived purely from income and expense transactions and do NOT include the initial balance. ")
        append("If the facts are insufficient to answer precisely, say so and, if useful, suggest what additional information would help. ")
        append("Distinguish data-backed observations from general suggestions: observations must quote the numbers in the facts, while suggestions (for example budgeting or where to adjust spending) are fine as general guidance based on the user's own numbers. ")
        append("Never present yourself as a licensed financial professional and never promise outcomes. ")
        append("Do not add boilerplate disclaimers; only state a limitation when it genuinely matters for the answer. ")
        append("Do not assert certainty about future financial outcomes. ")
        append("Answer clearly and concisely, in the language of the question.")
    }

    fun build(
        question: String,
        snapshot: FinancialSnapshot,
        zone: ZoneId = ZoneId.systemDefault(),
    ): AiRequest {
        val facts = linkedMapOf<String, String>()
        facts["periods"] = "${snapshot.currentLabel} (current), ${snapshot.previousLabel} (previous)"
        facts["current_expense_total"] = money(snapshot.currentExpenseTotal)
        facts["current_income_total"] = money(snapshot.currentIncomeTotal)
        facts["current_transaction_count"] =
            "${snapshot.currentTransactionCount} total (${snapshot.currentExpenseCount} expense, ${snapshot.currentIncomeCount} income)"
        facts["previous_expense_total"] = money(snapshot.previousExpenseTotal)
        facts["previous_income_total"] = money(snapshot.previousIncomeTotal)
        facts["previous_transaction_count"] = snapshot.previousTransactionCount.toString()
        facts["previous_expense_count"] = snapshot.previousExpenseCount.toString()
        facts["previous_income_count"] = snapshot.previousIncomeCount.toString()
        facts["expense_change_vs_previous"] = changeDescription(snapshot)
        facts["expense_categories_totals"] = if (snapshot.categoryExpenseTotals.isEmpty()) {
            "none in current period"
        } else {
            snapshot.categoryExpenseTotals.joinToString("; ") { "${it.category}: ${money(it.totalPiastres)}" }
        }
        facts["expense_categories_percent_of_total"] = categoryPercentages(snapshot)
        facts["income_categories_totals"] = if (snapshot.incomeCategoryTotals.isEmpty()) {
            "none in current period"
        } else {
            snapshot.incomeCategoryTotals.joinToString("; ") { "${it.category}: ${money(it.totalPiastres)}" }
        }
        facts["largest_income_in_current_period"] = snapshot.largestIncome
            ?.let { describeStructurally(it, zone) }
            ?: "none in current period"
        snapshot.currentBalance?.let { facts["current_balance"] = money(it) }
        facts["current_net_change"] = signedMoney(snapshot.currentNetChange)
        facts["net_change_vs_previous"] = netChangeDescription(snapshot)
        facts["largest_expense_in_current_period"] = snapshot.largestExpense
            ?.let { describeStructurally(it, zone) }
            ?: "none in current period"
        facts["smallest_expense_in_current_period"] = snapshot.smallestExpense
            ?.let { describeStructurally(it, zone) }
            ?: "none in current period"
        facts["recent_transactions"] = if (snapshot.recentTransactions.isEmpty()) {
            "none"
        } else {
            snapshot.recentTransactions.joinToString("; ") { describe(it, zone) }
        }

        return AiRequest(
            question = question,
            structuredFacts = facts,
            additionalInstructions = INSTRUCTIONS,
        )
    }

    private fun money(piastres: Long): String = EGPFormatter.formatAmount(piastres)

    private fun signedMoney(piastres: Long): String {
        val sign = when {
            piastres > 0 -> "+"
            else -> ""
        }
        return "$sign${money(piastres)}"
    }

    /**
     * Share of the current expense total per category as a deterministic integer
     * percentage (floor division, minor-unit arithmetic). Guarded against a zero
     * current total so percentages are never computed from nothing.
     */
    private fun categoryPercentages(snapshot: FinancialSnapshot): String {
        if (snapshot.currentExpenseTotal == 0L) return "none in current period"
        return snapshot.categoryExpenseTotals.joinToString("; ") { categoryTotal ->
            val percent = (categoryTotal.totalPiastres * 100) / snapshot.currentExpenseTotal
            "${categoryTotal.category}: $percent%"
        }
    }

    private fun netChangeDescription(snapshot: FinancialSnapshot): String {
        val difference = snapshot.currentNetChange - snapshot.previousNetChange
        return "${signedMoney(difference)} vs ${snapshot.previousLabel} " +
            "(current net ${signedMoney(snapshot.currentNetChange)})"
    }

    private fun changeDescription(snapshot: FinancialSnapshot): String {
        val difference = snapshot.expenseDifference
        val direction = when {
            difference > 0 -> "+"
            else -> ""
        }
        val percent = snapshot.expenseDifferencePercent
            ?.let { String.format(Locale.US, " (%.1f%%)", it) }
            ?: " (no previous period baseline)"
        return "$direction${money(difference)}$percent vs ${snapshot.previousLabel}"
    }

    /**
     * Describe-format entry WITH the raw description — used only for
     * `recent_transactions`, where the description was verified safe on-device.
     */
    private fun describe(transaction: Transaction, zone: ZoneId): String {
        val date = DATE_FORMAT.format(Instant.ofEpochMilli(transaction.timestamp).atZone(zone))
        val category = FinancialAnalyzer.displayCategory(transaction.category)
        val description = if (transaction.description.isNotBlank()) {
            " \"${transaction.description.trim().take(80)}\""
        } else {
            ""
        }
        return "$date ${transaction.type.name.lowercase()} ${money(transaction.amount)} $category$description"
    }

    /**
     * AI-input boundary form for the largest/smallest expense facts: date, type,
     * amount and category ONLY. The raw user-entered description is intentionally
     * excluded from what is sent to the model (confirmed safety false-positive
     * trigger); it remains fully intact in Firestore and the app UI.
     */
    private fun describeStructurally(transaction: Transaction, zone: ZoneId): String {
        val date = DATE_FORMAT.format(Instant.ofEpochMilli(transaction.timestamp).atZone(zone))
        val category = FinancialAnalyzer.displayCategory(transaction.category)
        return "$date ${transaction.type.name.lowercase()} ${money(transaction.amount)} $category"
    }
}