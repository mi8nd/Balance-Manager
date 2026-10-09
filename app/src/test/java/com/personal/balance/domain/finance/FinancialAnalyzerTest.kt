package com.personal.balance.domain.finance

import com.personal.balance.model.Transaction
import com.personal.balance.model.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

class FinancialAnalyzerTest {

    private val zone: ZoneId = ZoneId.of("UTC")
    private val nowEpochMillis: Long = Instant.parse("2026-08-15T12:00:00Z").toEpochMilli()
    private val currentStart: Long = Instant.parse("2026-08-01T00:00:00Z").toEpochMilli()
    private val currentEnd: Long = Instant.parse("2026-09-01T00:00:00Z").toEpochMilli()
    private val previousStart: Long = Instant.parse("2026-07-01T00:00:00Z").toEpochMilli()

    private fun tx(
        amount: Long,
        type: TransactionType = TransactionType.EXPENSE,
        timestamp: Long,
        category: String = "",
        description: String = "tx",
    ): Transaction = Transaction(
        id = "id_${amount}_$timestamp",
        userId = "u1",
        type = type,
        amount = amount,
        description = description,
        category = category,
        timestamp = timestamp,
    )

    private fun analyze(
        transactions: List<Transaction>,
        times: ZoneId = zone,
        at: Long = nowEpochMillis,
    ): FinancialSnapshot = FinancialAnalyzer.analyze(transactions, at, times)

    @Test
    fun emptyTransactions_producesZeroedSnapshot() {
        val snapshot = analyze(emptyList())

        assertEquals(0L, snapshot.currentExpenseTotal)
        assertEquals(0L, snapshot.currentIncomeTotal)
        assertEquals(0, snapshot.currentExpenseCount)
        assertEquals(0, snapshot.currentIncomeCount)
        assertEquals(0, snapshot.previousExpenseTotal)
        assertEquals(0, snapshot.previousIncomeTotal)
        assertEquals(0, snapshot.previousTransactionCount)
        assertEquals(0, snapshot.previousExpenseCount)
        assertEquals(0, snapshot.previousIncomeCount)
        assertEquals(0L, snapshot.expenseDifference)
        assertNull(snapshot.expenseDifferencePercent)
        assertTrue(snapshot.categoryExpenseTotals.isEmpty())
        assertTrue(snapshot.incomeCategoryTotals.isEmpty())
        assertNull(snapshot.largestExpense)
        assertNull(snapshot.smallestExpense)
        assertNull(snapshot.largestIncome)
        assertNull(snapshot.currentBalance)
        assertEquals(0L, snapshot.currentNetChange)
        assertEquals(0L, snapshot.previousNetChange)
        assertTrue(snapshot.recentTransactions.isEmpty())
        assertEquals("August 2026", snapshot.currentLabel)
        assertEquals("July 2026", snapshot.previousLabel)
    }

    @Test
    fun incomeOnly_currentPeriod_totalsAndCounts() {
        val snapshot = analyze(
            listOf(
                tx(100_00L, TransactionType.INCOME, currentStart + 1_000),
                tx(50_00L, TransactionType.INCOME, currentStart + 2_000),
            )
        )

        assertEquals(0L, snapshot.currentExpenseTotal)
        assertEquals(150_00L, snapshot.currentIncomeTotal)
        assertEquals(0, snapshot.currentExpenseCount)
        assertEquals(2, snapshot.currentIncomeCount)
        assertEquals(2, snapshot.currentTransactionCount)
    }

    @Test
    fun expenseOnly_currentPeriod_totalsAndCounts() {
        val snapshot = analyze(
            listOf(
                tx(200_00L, TransactionType.EXPENSE, currentStart + 1_000),
                tx(30_00L, TransactionType.EXPENSE, currentStart + 2_000),
            )
        )

        assertEquals(230_00L, snapshot.currentExpenseTotal)
        assertEquals(0L, snapshot.currentIncomeTotal)
        assertEquals(2, snapshot.currentExpenseCount)
        assertEquals(0, snapshot.currentIncomeCount)
    }

    @Test
    fun mixedCurrentPeriod_totalsAndCounts() {
        val snapshot = analyze(
            listOf(
                tx(400_00L, TransactionType.EXPENSE, currentStart + 1_000),
                tx(900_00L, TransactionType.INCOME, currentStart + 2_000),
                tx(100_00L, TransactionType.EXPENSE, currentStart + 3_000),
            )
        )

        assertEquals(500_00L, snapshot.currentExpenseTotal)
        assertEquals(900_00L, snapshot.currentIncomeTotal)
        assertEquals(2, snapshot.currentExpenseCount)
        assertEquals(1, snapshot.currentIncomeCount)
        assertEquals(3, snapshot.currentTransactionCount)
    }

    @Test
    fun previousPeriodTransactions_becomePreviousTotalsOnly() {
        val snapshot = analyze(
            listOf(
                tx(700_00L, TransactionType.EXPENSE, previousStart + 5_000),
                tx(300_00L, TransactionType.INCOME, previousStart + 6_000),
            )
        )

        assertEquals(0L, snapshot.currentExpenseTotal)
        assertEquals(700_00L, snapshot.previousExpenseTotal)
        assertEquals(300_00L, snapshot.previousIncomeTotal)
        assertEquals(2, snapshot.previousTransactionCount)
        assertEquals(0, snapshot.currentTransactionCount)
    }

    @Test
    fun expenseDifferenceAndPercent_positive() {
        val snapshot = analyze(
            listOf(
                tx(60_00L, TransactionType.EXPENSE, currentStart + 1_000),
                tx(40_00L, TransactionType.EXPENSE, previousStart + 1_000),
            )
        )

        assertEquals(20_00L, snapshot.expenseDifference)
        assertEquals(50.0, snapshot.expenseDifferencePercent!!, 0.001)
    }

    @Test
    fun expenseDifferenceAndPercent_negative() {
        val snapshot = analyze(
            listOf(
                tx(40_00L, TransactionType.EXPENSE, currentStart + 1_000),
                tx(50_00L, TransactionType.EXPENSE, previousStart + 1_000),
            )
        )

        assertEquals(-10_00L, snapshot.expenseDifference)
        assertEquals(-20.0, snapshot.expenseDifferencePercent!!, 0.001)
    }

    @Test
    fun percentNull_whenPreviousSpendingIsZero() {
        val snapshot = analyze(
            listOf(
                tx(60_00L, TransactionType.EXPENSE, currentStart + 1_000),
            )
        )

        assertEquals(60_00L, snapshot.expenseDifference)
        assertNull(snapshot.expenseDifferencePercent)
    }

    @Test
    fun categoryTotals_aggregatedOrderedByTotalThenName() {
        val snapshot = analyze(
            listOf(
                tx(50_00L, TransactionType.EXPENSE, currentStart + 1_000, category = "Food"),
                tx(20_00L, TransactionType.EXPENSE, currentStart + 2_000, category = "Food"),
                tx(40_00L, TransactionType.EXPENSE, currentStart + 3_000, category = "Transport"),
                tx(10_00L, TransactionType.EXPENSE, currentStart + 4_000, category = "Food"),
            )
        )

        assertEquals(listOf("Food" to 80_00L, "Transport" to 40_00L), snapshot.categoryExpenseTotals.map { it.category to it.totalPiastres })
    }

    @Test
    fun blankCategory_displaysAsGeneral() {
        val snapshot = analyze(
            listOf(
                tx(25_00L, TransactionType.EXPENSE, currentStart + 1_000, category = ""),
            )
        )

        assertEquals(listOf("General" to 25_00L), snapshot.categoryExpenseTotals.map { it.category to it.totalPiastres })
    }

    @Test
    fun incomeIsNeverPartOfExpenseCategories() {
        val snapshot = analyze(
            listOf(
                tx(90_00L, TransactionType.INCOME, currentStart + 1_000, category = "Salary"),
            )
        )

        assertTrue(snapshot.categoryExpenseTotals.isEmpty())
    }

    @Test
    fun incomeCategoryTotals_aggregatedOrderedByTotalThenName() {
        val snapshot = analyze(
            listOf(
                tx(100_00L, TransactionType.INCOME, currentStart + 1_000, category = "Salary"),
                tx(70_00L, TransactionType.INCOME, currentStart + 2_000, category = "Freelance"),
                tx(40_00L, TransactionType.INCOME, currentStart + 3_000, category = "Salary"),
                tx(200_00L, TransactionType.EXPENSE, currentStart + 4_000, category = "Food"),
            )
        )

        assertEquals(
            listOf("Salary" to 140_00L, "Freelance" to 70_00L),
            snapshot.incomeCategoryTotals.map { it.category to it.totalPiastres },
        )
        // Expenses never leak into income categories.
        assertFalse(snapshot.incomeCategoryTotals.any { it.category == "Food" })
    }

    @Test
    fun blankIncomeCategory_displaysAsGeneral() {
        val snapshot = analyze(
            listOf(
                tx(25_00L, TransactionType.INCOME, currentStart + 1_000, category = ""),
            )
        )

        assertEquals(listOf("General" to 25_00L), snapshot.incomeCategoryTotals.map { it.category to it.totalPiastres })
    }

    @Test
    fun largestIncome_picksLargestThenMostRecentOnTie() {
        val snapshot = analyze(
            listOf(
                tx(80_00L, TransactionType.INCOME, currentStart + 1_000, description = "old tie income"),
                tx(80_00L, TransactionType.INCOME, currentStart + 4_000, description = "new tie income"),
                tx(30_00L, TransactionType.INCOME, currentStart + 2_000, description = "small income"),
                tx(900_00L, TransactionType.EXPENSE, currentStart + 3_000, description = "huge expense"),
            )
        )

        assertEquals("new tie income", snapshot.largestIncome?.description)
        // An expense can never become the "largest income".
        assertEquals("huge expense", snapshot.largestExpense?.description)
    }

    @Test
    fun noCurrentIncome_largestIncomeIsNull() {
        val snapshot = analyze(
            listOf(
                tx(50_00L, TransactionType.EXPENSE, currentStart + 1_000),
            )
        )

        assertNull(snapshot.largestIncome)
    }

    @Test
    fun previousCountsSplitByType() {
        val snapshot = analyze(
            listOf(
                tx(100_00L, TransactionType.EXPENSE, previousStart + 1_000),
                tx(200_00L, TransactionType.EXPENSE, previousStart + 2_000),
                tx(300_00L, TransactionType.INCOME, previousStart + 3_000),
            )
        )

        assertEquals(2, snapshot.previousExpenseCount)
        assertEquals(1, snapshot.previousIncomeCount)
        assertEquals(3, snapshot.previousTransactionCount)
    }

    @Test
    fun netChangeIsIncomeMinusExpense() {
        val snapshot = analyze(
            listOf(
                tx(400_00L, TransactionType.INCOME, currentStart + 1_000),
                tx(100_00L, TransactionType.EXPENSE, currentStart + 2_000),
                tx(150_00L, TransactionType.INCOME, previousStart + 3_000),
                tx(50_00L, TransactionType.EXPENSE, previousStart + 4_000),
            )
        )

        assertEquals(300_00L, snapshot.currentNetChange)
        assertEquals(100_00L, snapshot.previousNetChange)
    }

    @Test
    fun largestExpense_picksLargestThenMostRecentOnTie() {
        val snapshot = analyze(
            listOf(
                tx(50_00L, TransactionType.EXPENSE, currentStart + 1_000, description = "old tie"),
                tx(50_00L, TransactionType.EXPENSE, currentStart + 4_000, description = "new tie"),
                tx(70_00L, TransactionType.EXPENSE, currentStart + 2_000, description = "leader"),
            )
        )

        assertEquals("leader", snapshot.largestExpense?.description)
    }

    @Test
    fun smallestExpense_picksSmallestThenMostRecentOnTie() {
        val snapshot = analyze(
            listOf(
                tx(10_00L, TransactionType.EXPENSE, currentStart + 1_000, description = "old small"),
                tx(10_00L, TransactionType.EXPENSE, currentStart + 5_000, description = "new small"),
                tx(60_00L, TransactionType.EXPENSE, currentStart + 2_000, description = "big"),
            )
        )

        assertEquals("new small", snapshot.smallestExpense?.description)
    }

    @Test
    fun recentTransactionsBoundedDescendingAcrossWindow() {
        val many = (0 until 15).map { index ->
            tx(
                amount = index.toLong() + 1,
                timestamp = currentStart + index * 1_000,
                description = "tx$index",
            )
        }
        val snapshot = analyze(many)

        assertEquals(FinancialAnalyzer.RECENT_TRANSACTIONS_LIMIT, snapshot.recentTransactions.size)
        // Newest first: tx13, tx12, ... tx2
        assertEquals("tx14", snapshot.recentTransactions.first().description)
        assertEquals("tx3", snapshot.recentTransactions.last().description)
    }

    @Test
    fun dateBoundaries_inclusiveStartExclusiveEnd() {
        val atCurrentStart = tx(11_00L, timestamp = currentStart)
        val atCurrentEnd = tx(22_00L, timestamp = currentEnd)
        val oneMsBeforeCurrentEnd = tx(33_00L, timestamp = currentEnd - 1)
        val atPreviousStart = tx(44_00L, timestamp = previousStart)
        val oneMsBeforeCurrentStart = tx(55_00L, timestamp = currentStart - 1)

        val snapshot = analyze(listOf(atCurrentStart, atCurrentEnd, oneMsBeforeCurrentEnd, atPreviousStart, oneMsBeforeCurrentStart))

        // currentStart is included in the current month; currentEnd is excluded.
        assertEquals(11_00L + 33_00L, snapshot.currentExpenseTotal)
        assertEquals(2, snapshot.currentExpenseCount)
        // previousStart is included in the previous month; the instant just
        // before currentStart is still part of the previous month.
        assertEquals(44_00L + 55_00L, snapshot.previousExpenseTotal)
        assertEquals(2, snapshot.previousTransactionCount)
    }

    @Test
    fun outsideWindowTransactions_ignoredEverywhere() {
        val june = tx(1_000_00L, timestamp = previousStart - 1)
        val september = tx(2_000_00L, timestamp = currentEnd + 1)

        val snapshot = analyze(listOf(june, september))

        assertEquals(0L, snapshot.currentExpenseTotal)
        assertEquals(0L, snapshot.previousExpenseTotal)
        assertTrue(snapshot.categoryExpenseTotals.isEmpty())
        assertTrue(snapshot.recentTransactions.isEmpty())
        assertNull(snapshot.largestExpense)
        assertNull(snapshot.smallestExpense)
    }

    @Test
    fun differentZone_shiftsWindowBoundaries() {
        val offsetZone = ZoneOffset.ofHours(3)
        // UTC+3: local current month starts at UTC 2026-07-31T21:00:00Z.
        val localCurrentStart = Instant.parse("2026-07-31T21:00:00Z").toEpochMilli()
        val snapshot = analyze(
            listOf(tx(5_00L, timestamp = localCurrentStart)),
            times = offsetZone,
        )

        assertEquals(5_00L, snapshot.currentExpenseTotal)
    }
}