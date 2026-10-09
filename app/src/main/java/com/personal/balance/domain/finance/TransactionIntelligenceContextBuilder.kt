package com.personal.balance.domain.finance

import com.personal.balance.domain.EGPFormatter
import com.personal.balance.domain.ai.AiRequest
import com.personal.balance.model.Transaction
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Builds the [AiRequest] for Transaction Intelligence from a deterministic
 * [TransactionIntelligenceSnapshot].
 *
 * AI-INPUT BOUNDARY: like the Financial Assistant, individual transactions are
 * sent only in a structural form (date/type/amount/category) with the raw
 * user-entered description omitted; `recent_transactions` is NOT included here at
 * all. The model receives pre-computed integer facts and is told they are exact.
 * No balance is included, so the balance can never be conflated with totals.
 */
object TransactionIntelligenceContextBuilder {

    private val DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US)

    private const val GENERAL = "General"

    private val INSTRUCTIONS = buildString {
        append("The \"Authoritative facts\" above are the only financial data you were given and are exact values computed by the app. ")
        append("Base your insights on them only. Never invent transactions, amounts, categories, dates, values, or patterns. ")
        append("A fact that is absent means there is not enough evidence — do not claim a trend or anomaly it would require. ")
        append("No balance is provided; never speak about the account balance. ")
        append("Use the supplied EGP amounts exactly as given. ")
        append("Separate observations from suggestions: start any recommendation with 'Suggestion:'. ")
        append("Observations must be grounded in the exact numbers provided; suggestions are general guidance based on the user's own spending. ")
        append("Do not add boilerplate disclaimers; only state a limitation when it genuinely matters. Never present yourself as a licensed financial professional and never promise outcomes. ")
        append("Do not assert certainty about future financial outcomes. ")
        append("Be concise and practical; explain why an insight matters when that is not obvious.")
    }

    private const val INSIGHT_QUESTION =
        "Analyze these authoritative facts from my last two months of transactions. " +
            "Identify genuinely notable patterns, changes, anomalies, or behaviors (for example " +
            "large spending shifts, an unusually large transaction, heavy category concentration, " +
            "or a recurring-looking category). Reply ONLY with one line per insight, each formatted " +
            "exactly as: INSIGHT|LEVEL|Title|Explanation, where LEVEL is HIGH, MEDIUM, LOW, or INFO. " +
            "Every insight must be supported only by the facts above; if the facts cannot support a " +
            "claim, do not make it. Use 1-2 sentences per explanation."

    fun build(
        snapshot: TransactionIntelligenceSnapshot,
        zone: ZoneId = ZoneId.systemDefault(),
    ): AiRequest {
        val facts = linkedMapOf<String, String>()

        facts["periods"] = "${snapshot.currentLabel} (current), ${snapshot.previousLabel} (previous)"
        facts["window_transaction_count"] = snapshot.windowTransactionCount.toString()

        facts["current_expense_total"] = money(snapshot.currentExpenseTotal)
        facts["previous_expense_total"] = money(snapshot.previousExpenseTotal)
        facts["expense_change"] = percentComparison(
            snapshot.expenseChangePercent,
            snapshot.currentExpenseTotal,
            snapshot.previousExpenseTotal,
            "expenses",
        )

        facts["current_income_total"] = money(snapshot.currentIncomeTotal)
        facts["previous_income_total"] = money(snapshot.previousIncomeTotal)
        facts["income_change"] = percentComparison(
            snapshot.incomeChangePercent,
            snapshot.currentIncomeTotal,
            snapshot.previousIncomeTotal,
            "income",
        )

        facts["current_transaction_count"] =
            "${snapshot.currentTransactionCount} total (${snapshot.currentExpenseCount} expense, " +
                "${snapshot.currentIncomeCount} income)"
        facts["previous_transaction_count"] =
            "${snapshot.previousTransactionCount} total (${snapshot.previousExpenseCount} expense, " +
                "${snapshot.previousIncomeCount} income)"
        facts["transaction_frequency_change"] = snapshot.frequencyChangePercent?.let { p ->
            "${signedPercent(p)} transaction count vs ${snapshot.previousLabel}"
        } ?: "no previous-period baseline"

        facts["expense_categories_totals"] = if (snapshot.categoryExpenseTotals.isEmpty()) {
            "none in current period"
        } else {
            snapshot.categoryExpenseTotals.joinToString("; ") { "${it.category}: ${money(it.totalPiastres)}" }
        }
        facts["previous_expense_categories_totals"] = if (snapshot.previousCategoryExpenseTotals.isEmpty()) {
            "none in previous period"
        } else {
            snapshot.previousCategoryExpenseTotals.joinToString("; ") { "${it.category}: ${money(it.totalPiastres)}" }
        }
        facts["expense_categories_percent_of_total"] = if (snapshot.categoryExpenseTotals.isEmpty() ||
            snapshot.currentExpenseTotal <= 0L
        ) {
            "none in current period"
        } else {
            snapshot.categoryExpenseTotals.joinToString("; ") { categoryTotal ->
                val percent = (categoryTotal.totalPiastres * 100) / snapshot.currentExpenseTotal
                "${categoryTotal.category}: $percent%"
            }
        }
        facts["category_deltas"] = if (snapshot.categoryDeltas.isEmpty()) {
            "none"
        } else {
            snapshot.categoryDeltas.joinToString("; ") {
                "${it.category}: ${signedMoney(it.delta)} (current ${money(it.currentTotal)} vs previous ${money(it.previousTotal)})"
            }
        }
        facts["top_category_share"] = snapshot.topCategorySharePercent?.let { p ->
            val top = snapshot.categoryExpenseTotals.firstOrNull()
            "${top?.category ?: GENERAL} leads with $p% of current total expenses"
        } ?: "not enough data"
        facts["largest_expense_share"] = snapshot.largestExpenseSharePercent?.let { p ->
            "largest expense is $p% of current total expenses"
        } ?: "not enough data"

        facts["average_expense_per_transaction"] = snapshot.averageExpense?.let { money(it) } ?: "not enough data"
        facts["average_income_per_transaction"] = snapshot.averageIncome?.let { money(it) } ?: "not enough data"

        facts["largest_expense_in_current_period"] = snapshot.largestExpense
            ?.let { describeStructurally(it, zone) }
            ?: "none in current period"
        facts["largest_income_in_current_period"] = snapshot.largestIncome
            ?.let { describeStructurally(it, zone) }
            ?: "none in current period"
        facts["largest_expense_vs_average"] = snapshot.largestExpenseVsAverageMultiple?.let {
            String.format(Locale.US, "%.1fx the average of the other current expenses", it)
        } ?: "not enough data"
        facts["largest_income_vs_average"] = snapshot.largestIncomeVsAverageMultiple?.let {
            String.format(Locale.US, "%.1fx the average of the other current income", it)
        } ?: "not enough data"

        facts["candidate_recurring_categories"] = if (snapshot.candidateRecurringCategories.isEmpty()) {
            "none identified"
        } else {
            snapshot.candidateRecurringCategories.joinToString("; ") {
                "${it.category}: ${money(it.currentTotal)} this month vs ${money(it.previousTotal)} last month " +
                    "(supported by ${it.totalTransactions} transactions across both months)"
            }
        }

        return AiRequest(
            question = INSIGHT_QUESTION,
            structuredFacts = facts,
            additionalInstructions = INSTRUCTIONS,
        )
    }

    private fun money(piastres: Long): String = EGPFormatter.formatAmount(piastres)

    private fun signedMoney(piastres: Long): String {
        val sign = if (piastres > 0) "+" else ""
        return "$sign${money(piastres)}"
    }

    private fun signedPercent(percent: Double): String {
        val sign = if (percent > 0) "+" else ""
        return "$sign${String.format(Locale.US, "%.1f%%", percent)}"
    }

    private fun percentComparison(
        percent: Double?,
        current: Long,
        previous: Long,
        label: String,
    ): String = percent?.let { p ->
        "${signedPercent(p)} (${money(current)} vs ${money(previous)}) $label"
    } ?: "no previous-period baseline"

    /**
     * AI-input boundary form: date, type, amount and category ONLY. The raw
     * user-entered description is intentionally excluded (same safety boundary
     * as the Financial Assistant's largest/smallest facts).
     */
    private fun describeStructurally(transaction: Transaction, zone: ZoneId): String {
        val date = DATE_FORMAT.format(Instant.ofEpochMilli(transaction.timestamp).atZone(zone))
        val category = FinancialAnalyzer.displayCategory(transaction.category)
        return "$date ${transaction.type.name.lowercase()} ${money(transaction.amount)} $category"
    }
}