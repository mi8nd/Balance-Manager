package com.personal.balance.domain.finance

import com.personal.balance.model.Transaction
import com.personal.balance.model.TransactionType
import java.time.ZoneId

/**
 * One expense category's month-over-month comparison.
 *
 * [delta] is `currentTotal - previousTotal` (positive = spent more this month),
 * computed in piastres. A category present in only one period gets a zero total
 * for the other, so "new spending" appears as a positive delta from zero.
 */
data class CategoryDelta(
    val category: String,
    val currentTotal: Long,
    val previousTotal: Long,
) {
    val delta: Long get() = currentTotal - previousTotal
}

/**
 * A category that looks recurring (spent in BOTH periods, multiple transactions
 * per period, totals within ±30%) — a deliberately conservative signal so the
 * model is never asked to infer a pattern from just one or two transactions.
 */
data class RecurringCandidate(
    val category: String,
    val currentTotal: Long,
    val previousTotal: Long,
    val totalTransactions: Int,
)

/**
 * Deterministic, pre-computed snapshot for Transaction Intelligence: the bounded
 * two-calendar-month window reduced to integer minor-unit facts plus conservative
 * behavioral signals. Computed in Kotlin only — nothing here is deduced by the
 * model, and no raw transaction descriptions ever reach the AI input boundary.
 *
 * [hasEnoughData] gates whether there is any evidence worth sending to the model:
 * at least one transaction in the current period AND at least two transactions in
 * the window. With less than that, no trend claim could be grounded in data.
 */
data class TransactionIntelligenceSnapshot(
    val currentLabel: String,
    val previousLabel: String,
    val hasEnoughData: Boolean,
    val windowTransactionCount: Int,
    val currentExpenseTotal: Long,
    val currentIncomeTotal: Long,
    val previousExpenseTotal: Long,
    val previousIncomeTotal: Long,
    val currentExpenseCount: Int,
    val currentIncomeCount: Int,
    val previousExpenseCount: Int,
    val previousIncomeCount: Int,
    val categoryExpenseTotals: List<CategoryTotal>,
    val previousCategoryExpenseTotals: List<CategoryTotal>,
    val categoryDeltas: List<CategoryDelta>,
    val largestExpense: Transaction?,
    val largestIncome: Transaction?,
    val expenseChangePercent: Double?,
    val incomeChangePercent: Double?,
    val frequencyChangePercent: Double?,
    val topCategorySharePercent: Int?,
    val largestExpenseSharePercent: Int?,
    val averageExpense: Long?,
    val averageIncome: Long?,
    val largestExpenseVsAverageMultiple: Double?,
    val largestIncomeVsAverageMultiple: Double?,
    val candidateRecurringCategories: List<RecurringCandidate>,
) {
    val currentTransactionCount: Int get() = currentExpenseCount + currentIncomeCount
    val previousTransactionCount: Int get() = previousExpenseCount + previousIncomeCount
}

object TransactionIntelligenceAnalyzer {

    /** How close two monthly category totals must be (in %) to look recurring. */
    private const val RECURRING_TOLERANCE_PERCENT = 30

    /** Minimum transactions per period before a category may look recurring. */
    private const val MIN_TRANSACTIONS_PER_PERIOD = 2

    /** Minimum expense/income count before an "unusually large" outlier is claimed. */
    private const val MIN_COUNT_FOR_OUTLIER = 3

    /** A transaction at least this many times the average of the others is an outlier. */
    private const val OUTLIER_MULTIPLE_THRESHOLD = 2.0

    fun analyze(
        transactions: List<Transaction>,
        nowEpochMillis: Long,
        zone: ZoneId,
    ): TransactionIntelligenceSnapshot {
        val window = FinancialWindows.windowAt(nowEpochMillis, zone)
        val inWindow = transactions.filter {
            it.timestamp in window.previousMonthStartEpochMillis until window.currentMonthEndEpochMillis
        }
        val current = inWindow.filter { it.timestamp >= window.currentMonthStartEpochMillis }
        val previous = inWindow.filter { it.timestamp < window.currentMonthStartEpochMillis }

        val currentExpenses = current.filter { it.type == TransactionType.EXPENSE }
        val currentIncome = current.filter { it.type == TransactionType.INCOME }
        val previousExpenses = previous.filter { it.type == TransactionType.EXPENSE }
        val previousIncome = previous.filter { it.type == TransactionType.INCOME }

        val currentExpenseTotal = currentExpenses.sumOf { it.amount }
        val currentIncomeTotal = currentIncome.sumOf { it.amount }
        val previousExpenseTotal = previousExpenses.sumOf { it.amount }
        val previousIncomeTotal = previousIncome.sumOf { it.amount }

        fun categoryTotals(transactions: List<Transaction>): List<CategoryTotal> =
            transactions
                .groupBy { FinancialAnalyzer.displayCategory(it.category) }
                .map { (category, group) -> CategoryTotal(category, group.sumOf { it.amount }) }
                .sortedWith(compareByDescending<CategoryTotal> { it.totalPiastres }.thenBy { it.category })

        val categoryExpenseTotals = categoryTotals(currentExpenses)
        val previousCategoryExpenseTotals = categoryTotals(previousExpenses)

        // Union of categories across both periods; delta = current − previous.
        val allCategoryNames = (categoryExpenseTotals.map { it.category } +
            previousCategoryExpenseTotals.map { it.category }).distinct().sorted()
        val categoryDeltas = allCategoryNames.map { name ->
            CategoryDelta(
                category = name,
                currentTotal = categoryExpenseTotals.firstOrNull { it.category == name }?.totalPiastres ?: 0L,
                previousTotal = previousCategoryExpenseTotals.firstOrNull { it.category == name }?.totalPiastres ?: 0L,
            )
        }.sortedWith(compareByDescending<CategoryDelta> { it.delta }.thenBy { it.category })

        // Largest amount first; ties broken by most recent (mirrors FinancialAnalyzer).
        val largestExpense = currentExpenses.maxWithOrNull(
            compareBy<Transaction> { it.amount }.thenBy { it.timestamp },
        )
        val largestIncome = currentIncome.maxWithOrNull(
            compareBy<Transaction> { it.amount }.thenBy { it.timestamp },
        )

        fun percentChange(current: Long, previous: Long): Double? =
            if (previous > 0L) (current - previous) * 100.0 / previous else null

        val expenseChangePercent = percentChange(currentExpenseTotal, previousExpenseTotal)
        val incomeChangePercent = percentChange(currentIncomeTotal, previousIncomeTotal)
        val currentCount = currentExpenses.size + currentIncome.size
        val previousCount = previousExpenses.size + previousIncome.size
        val frequencyChangePercent = if (previousCount > 0) (currentCount - previousCount) * 100.0 / previousCount else null

        // Category concentration: top category's share of the current expense total.
        val topCategorySharePercent = if (currentExpenseTotal > 0L) {
            categoryExpenseTotals.firstOrNull()?.let { ((it.totalPiastres * 100) / currentExpenseTotal).toInt() }
        } else {
            null
        }

        val largestExpenseSharePercent = if (currentExpenseTotal > 0L) {
            largestExpense?.let { ((it.amount * 100) / currentExpenseTotal).toInt() }
        } else {
            null
        }

        fun average(total: Long, count: Int): Long? =
            if (count > 0) total / count else null

        val averageExpense = average(currentExpenseTotal, currentExpenses.size)
        val averageIncome = average(currentIncomeTotal, currentIncome.size)

        // Unusually large transaction: how many times the largest is the average of
        // the OTHER transactions of the same type. Requires ≥3 transactions so a
        // single-pair comparison can never be called an "anomaly".
        fun outlierMultiple(
            largest: Transaction?,
            count: Int,
            total: Long,
        ): Double? {
            if (largest == null || count < MIN_COUNT_FOR_OUTLIER) return null
            val meanOfOthers = (total - largest.amount) / (count - 1)
            if (meanOfOthers <= 0L) return null
            val multiple = largest.amount.toDouble() / meanOfOthers
            return if (multiple >= OUTLIER_MULTIPLE_THRESHOLD) multiple else null
        }

        val largestExpenseVsAverageMultiple = outlierMultiple(
            largest = largestExpense,
            count = currentExpenses.size,
            total = currentExpenseTotal,
        )
        val largestIncomeVsAverageMultiple = outlierMultiple(
            largest = largestIncome,
            count = currentIncome.size,
            total = currentIncomeTotal,
        )

        // Recurring-looking categories: present in both periods, ≥2 transactions in
        // each, totals within ±30%. Conservative so a pattern claim is grounded.
        val recurringCategories = (categoryExpenseTotals.map { it.category } +
            previousCategoryExpenseTotals.map { it.category }).distinct().map { name ->
            val currentTransactions = currentExpenses.filter {
                FinancialAnalyzer.displayCategory(it.category) == name
            }
            val previousTransactions = previousExpenses.filter {
                FinancialAnalyzer.displayCategory(it.category) == name
            }
            val currentTotal = currentTransactions.sumOf { it.amount }
            val previousTotal = previousTransactions.sumOf { it.amount }
            Triple(name, currentTotal, previousTotal)
        }.filter { (_, currentTotal, previousTotal) ->
            currentTotal > 0L && previousTotal > 0L &&
                kotlin.math.abs(currentTotal - previousTotal) <=
                previousTotal * RECURRING_TOLERANCE_PERCENT / 100
        }.mapNotNull { (name, currentTotal, previousTotal) ->
            val inCurrent = current.filter { FinancialAnalyzer.displayCategory(it.category) == name && it.type == TransactionType.EXPENSE }
            val inPrevious = previous.filter { FinancialAnalyzer.displayCategory(it.category) == name && it.type == TransactionType.EXPENSE }
            if (inCurrent.size >= MIN_TRANSACTIONS_PER_PERIOD &&
                inPrevious.size >= MIN_TRANSACTIONS_PER_PERIOD
            ) {
                RecurringCandidate(
                    category = name,
                    currentTotal = currentTotal,
                    previousTotal = previousTotal,
                    totalTransactions = inCurrent.size + inPrevious.size,
                )
            } else {
                null
            }
        }.sortedBy { it.category }

        val windowTransactionCount = currentExpenses.size + currentIncome.size +
            previousExpenses.size + previousIncome.size

        return TransactionIntelligenceSnapshot(
            currentLabel = window.currentLabel,
            previousLabel = window.previousLabel,
            hasEnoughData = currentCount >= 1 && windowTransactionCount >= 2,
            windowTransactionCount = windowTransactionCount,
            currentExpenseTotal = currentExpenseTotal,
            currentIncomeTotal = currentIncomeTotal,
            previousExpenseTotal = previousExpenseTotal,
            previousIncomeTotal = previousIncomeTotal,
            currentExpenseCount = currentExpenses.size,
            currentIncomeCount = currentIncome.size,
            previousExpenseCount = previousExpenses.size,
            previousIncomeCount = previousIncome.size,
            categoryExpenseTotals = categoryExpenseTotals,
            previousCategoryExpenseTotals = previousCategoryExpenseTotals,
            categoryDeltas = categoryDeltas,
            largestExpense = largestExpense,
            largestIncome = largestIncome,
            expenseChangePercent = expenseChangePercent,
            incomeChangePercent = incomeChangePercent,
            frequencyChangePercent = frequencyChangePercent,
            topCategorySharePercent = topCategorySharePercent,
            largestExpenseSharePercent = largestExpenseSharePercent,
            averageExpense = averageExpense,
            averageIncome = averageIncome,
            largestExpenseVsAverageMultiple = largestExpenseVsAverageMultiple,
            largestIncomeVsAverageMultiple = largestIncomeVsAverageMultiple,
            candidateRecurringCategories = recurringCategories,
        )
    }
}