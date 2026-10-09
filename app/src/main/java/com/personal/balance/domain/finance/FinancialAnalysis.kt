package com.personal.balance.domain.finance

import com.personal.balance.model.Transaction
import com.personal.balance.model.TransactionType
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Date window for the two bounded months the assistant may look at:
 * the full previous calendar month and the full current calendar month.
 * Everything outside [previousMonthStartEpochMillis, currentMonthEndEpochMillis)
 * is ignored by the AI Financial Assistant.
 */
data class FinancialWindow(
    val previousMonthStartEpochMillis: Long,
    val currentMonthStartEpochMillis: Long,
    val currentMonthEndEpochMillis: Long,
    val currentLabel: String,
    val previousLabel: String,
)

object FinancialWindows {

    private const val LABEL_STYLE_PATTERN = "MMMM yyyy"

    fun windowAt(nowEpochMillis: Long, zone: ZoneId): FinancialWindow {
        val today = ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowEpochMillis), zone)
        val currentMonthStart = today.toLocalDate().withDayOfMonth(1).atStartOfDay(zone)
        val previousMonthStart = currentMonthStart.minusMonths(1)
        val currentMonthEnd = currentMonthStart.plusMonths(1)
        val formatter = DateTimeFormatter.ofPattern(LABEL_STYLE_PATTERN, Locale.ENGLISH)
        return FinancialWindow(
            previousMonthStartEpochMillis = previousMonthStart.toInstant().toEpochMilli(),
            currentMonthStartEpochMillis = currentMonthStart.toInstant().toEpochMilli(),
            currentMonthEndEpochMillis = currentMonthEnd.toInstant().toEpochMilli(),
            currentLabel = formatter.format(currentMonthStart),
            previousLabel = formatter.format(previousMonthStart),
        )
    }
}

/** Total spent per category during the current month, largest first. */
data class CategoryTotal(val category: String, val totalPiastres: Long)

/**
 * Deterministic, pre-computed summary of the user's own transactions for the
 * current and previous calendar month. Computed in Kotlin (piastres / minor
 * units preserved, no floating-point money arithmetic). This is the only
 * financial data ever sent to the model.
 */
data class FinancialSnapshot(
    val currentLabel: String,
    val previousLabel: String,
    val currentExpenseTotal: Long,
    val currentIncomeTotal: Long,
    val currentExpenseCount: Int,
    val currentIncomeCount: Int,
    val previousExpenseTotal: Long,
    val previousIncomeTotal: Long,
    val previousTransactionCount: Int,
    val expenseDifference: Long,
    val expenseDifferencePercent: Double?,
    val categoryExpenseTotals: List<CategoryTotal>,
    val largestExpense: Transaction?,
    val smallestExpense: Transaction?,
    val recentTransactions: List<Transaction>,
    val incomeCategoryTotals: List<CategoryTotal> = emptyList(),
    val largestIncome: Transaction? = null,
    val previousExpenseCount: Int = 0,
    val previousIncomeCount: Int = 0,
    val currentBalance: Long? = null,
) {
    val currentTransactionCount: Int get() = currentExpenseCount + currentIncomeCount
    val currentNetChange: Long get() = currentIncomeTotal - currentExpenseTotal
    val previousNetChange: Long get() = previousIncomeTotal - previousExpenseTotal
}

object FinancialAnalyzer {

    const val RECENT_TRANSACTIONS_LIMIT: Int = 12

    /** Display label for transactions handed to the model ("General" when the category is blank). */
    fun displayCategory(category: String): String =
        category.trim().ifEmpty { "General" }

    fun analyze(
        transactions: List<Transaction>,
        nowEpochMillis: Long,
        zone: ZoneId,
    ): FinancialSnapshot {
        val window = FinancialWindows.windowAt(nowEpochMillis, zone)
        // Everything outside [previous, currentEnd) is ignored, including when
        // building the "recent transactions" list.
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

        val categoryExpenseTotals = currentExpenses
            .groupBy { displayCategory(it.category) }
            .map { (category, categoryTransactions) ->
                CategoryTotal(category, categoryTransactions.sumOf { it.amount })
            }
            .sortedWith(compareByDescending<CategoryTotal> { it.totalPiastres }.thenBy { it.category })

        val incomeCategoryTotals = currentIncome
            .groupBy { displayCategory(it.category) }
            .map { (category, categoryTransactions) ->
                CategoryTotal(category, categoryTransactions.sumOf { it.amount })
            }
            .sortedWith(compareByDescending<CategoryTotal> { it.totalPiastres }.thenBy { it.category })

        // Largest amount first; ties broken by most recent.
        val largestExpense = currentExpenses.maxWithOrNull(
            compareBy<Transaction> { it.amount }.thenBy { it.timestamp }
        )
        // Smallest amount first; ties broken by most recent.
        val smallestExpense = currentExpenses.minWithOrNull(
            compareBy<Transaction> { it.amount }.thenByDescending { it.timestamp }
        )
        // Largest income amount first; ties broken by most recent.
        val largestIncome = currentIncome.maxWithOrNull(
            compareBy<Transaction> { it.amount }.thenBy { it.timestamp }
        )

        val recentTransactions = inWindow
            .sortedByDescending { it.timestamp }
            .take(RECENT_TRANSACTIONS_LIMIT)

        // Never divide by zero: percentage is absent when the previous month
        // spent nothing (even if the current month has spending).
        val expenseDifference = currentExpenseTotal - previousExpenseTotal
        val expenseDifferencePercent = if (previousExpenseTotal != 0L) {
            (expenseDifference.toDouble() / previousExpenseTotal) * 100.0
        } else {
            null
        }

        return FinancialSnapshot(
            currentLabel = window.currentLabel,
            previousLabel = window.previousLabel,
            currentExpenseTotal = currentExpenseTotal,
            currentIncomeTotal = currentIncomeTotal,
            currentExpenseCount = currentExpenses.size,
            currentIncomeCount = currentIncome.size,
            previousExpenseTotal = previousExpenseTotal,
            previousIncomeTotal = previousIncomeTotal,
            previousTransactionCount = previous.size,
            expenseDifference = expenseDifference,
            expenseDifferencePercent = expenseDifferencePercent,
            categoryExpenseTotals = categoryExpenseTotals,
            largestExpense = largestExpense,
            smallestExpense = smallestExpense,
            recentTransactions = recentTransactions,
            incomeCategoryTotals = incomeCategoryTotals,
            largestIncome = largestIncome,
            previousExpenseCount = previousExpenses.size,
            previousIncomeCount = previousIncome.size,
        )
    }
}