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

class TransactionIntelligenceAnalyzerTest {

    private val zone: ZoneId = ZoneId.of("UTC")
    private val nowEpochMillis: Long = Instant.parse("2026-08-15T12:00:00Z").toEpochMilli()
    private val currentStart: Long = Instant.parse("2026-08-01T00:00:00Z").toEpochMilli()
    private val previousStart: Long = Instant.parse("2026-07-01T00:00:00Z").toEpochMilli()
    private val previousEnd: Long = Instant.parse("2026-08-01T00:00:00Z").toEpochMilli()
    private val currentEnd: Long = Instant.parse("2026-09-01T00:00:00Z").toEpochMilli()

    private fun tx(
        amount: Long,
        type: TransactionType = TransactionType.EXPENSE,
        timestamp: Long,
        category: String = "",
        description: String = "tx",
    ): Transaction = Transaction(
        userId = "uid-1",
        type = type,
        amount = amount,
        description = description,
        category = category,
        timestamp = timestamp,
    )

    private fun analyze(transactions: List<Transaction>): TransactionIntelligenceSnapshot =
        TransactionIntelligenceAnalyzer.analyze(transactions, nowEpochMillis, zone)

    // ───────────  Empty & tiny histories  ───────────

    @Test
    fun emptyHistory_noData_noSignals_noOutliers() {
        val snapshot = analyze(emptyList())

        assertEquals(0, snapshot.windowTransactionCount)
        assertFalse(snapshot.hasEnoughData)
        assertEquals(0L, snapshot.currentExpenseTotal)
        assertEquals(0L, snapshot.currentIncomeTotal)
        assertTrue(snapshot.categoryExpenseTotals.isEmpty())
        assertTrue(snapshot.categoryDeltas.isEmpty())
        assertNull(snapshot.largestExpense)
        assertNull(snapshot.largestIncome)
        assertNull(snapshot.expenseChangePercent)
        assertNull(snapshot.incomeChangePercent)
        assertNull(snapshot.frequencyChangePercent)
        assertNull(snapshot.topCategorySharePercent)
        assertNull(snapshot.averageExpense)
        assertNull(snapshot.largestExpenseVsAverageMultiple)
        assertTrue(snapshot.candidateRecurringCategories.isEmpty())
    }

    @Test
    fun singleExpense_insufficientData() {
        val snapshot = analyze(
            listOf(tx(100_00L, TransactionType.EXPENSE, currentStart + 1_000, category = "Food")),
        )

        assertEquals(1, snapshot.windowTransactionCount)
        assertFalse(snapshot.hasEnoughData)
    }

    @Test
    fun singleExpenseEverywhere_stillInsuficient_whenOnlyOneInWindow() {
        // One current expense, nothing else -> no baseline, no claim.
        val snapshot = analyze(
            listOf(tx(100_00L, TransactionType.EXPENSE, currentStart + 1_000, category = "Food")),
        )

        assertFalse(snapshot.hasEnoughData)
    }

    // ───────────  Sufficiency gating  ───────────

    @Test
    fun twoExpensesInWindow_enoughData() {
        val snapshot = analyze(
            listOf(
                tx(50_00L, TransactionType.EXPENSE, currentStart + 1_000, category = "Food"),
                tx(70_00L, TransactionType.EXPENSE, currentStart + 2_000, category = "Transport"),
            ),
        )

        assertTrue(snapshot.hasEnoughData)
        assertEquals(2, snapshot.currentTransactionCount)
        assertEquals(120_00L, snapshot.currentExpenseTotal)
    }

    @Test
    fun currentAndPreviousActivity_enoughData() {
        val snapshot = analyze(
            listOf(
                tx(50_00L, TransactionType.EXPENSE, currentStart + 1_000, category = "Food"),
                tx(40_00L, TransactionType.EXPENSE, previousStart + 5_000, category = "Food"),
            ),
        )

        assertTrue(snapshot.hasEnoughData)
        assertEquals(2, snapshot.windowTransactionCount)
    }

    @Test
    fun previousOnlyActivity_insufficientData() {
        val snapshot = analyze(
            listOf(
                tx(40_00L, TransactionType.EXPENSE, previousStart + 5_000, category = "Food"),
                tx(30_00L, TransactionType.EXPENSE, previousStart + 6_000, category = "Food"),
            ),
        )

        assertEquals(0, snapshot.currentTransactionCount)
        assertFalse(snapshot.hasEnoughData)
    }

    // ───────────  Totals (integer minor-unit arithmetic)  ───────────

    @Test
    fun incomeAndExpenses_totalsAreSeparateAndExact() {
        val snapshot = analyze(
            listOf(
                tx(1000_00L, TransactionType.INCOME, currentStart + 1_000, category = "Salary"),
                tx(300_00L, TransactionType.EXPENSE, currentStart + 2_000, category = "Food"),
                tx(200_50L, TransactionType.EXPENSE, currentStart + 3_000, category = "Rent"),
            ),
        )

        assertEquals(1000_00L, snapshot.currentIncomeTotal)
        assertEquals(500_50L, snapshot.currentExpenseTotal)
        assertEquals(1, snapshot.currentIncomeCount)
        assertEquals(2, snapshot.currentExpenseCount)
        assertTrue(snapshot.hasEnoughData)
    }

    @Test
    fun onlyIncome_noExpenseSignals() {
        val snapshot = analyze(
            listOf(
                tx(1000_00L, TransactionType.INCOME, currentStart + 1_000, category = "Salary"),
                tx(500_00L, TransactionType.INCOME, currentStart + 2_000, category = "Freelance"),
            ),
        )

        assertEquals(1000_00L + 500_00L, snapshot.currentIncomeTotal)
        assertEquals(0L, snapshot.currentExpenseTotal)
        assertTrue(snapshot.categoryExpenseTotals.isEmpty())
        assertEquals(2, snapshot.currentTransactionCount)
        assertTrue(snapshot.hasEnoughData)
    }

    @Test
    fun outOfWindowTransactions_areIgnored() {
        val before = tx(999L, TransactionType.EXPENSE, previousStart - 1_000, category = "Old")
        val after = tx(999L, TransactionType.EXPENSE, currentEnd + 1_000, category = "Future")
        val snapshot = analyze(listOf(before, after))

        assertEquals(0, snapshot.windowTransactionCount)
        assertFalse(snapshot.hasEnoughData)
    }

    // ───────────  Current vs previous comparison  ───────────

    @Test
    fun expenseChangePercent_whenPreviousHasBaseline() {
        val snapshot = analyze(
            listOf(
                tx(150_00L, TransactionType.EXPENSE, currentStart + 1_000, category = "Food"),
                tx(100_00L, TransactionType.EXPENSE, previousStart + 5_000, category = "Food"),
            ),
        )

        // (150 - 100) / 100 = +50%
        assertEquals(50.0, snapshot.expenseChangePercent!!, 0.001)
    }

    @Test
    fun expenseChangePercent_nullWithoutPreviousBaseline() {
        val snapshot = analyze(
            listOf(
                tx(150_00L, TransactionType.EXPENSE, currentStart + 1_000, category = "Food"),
                tx(100_00L, TransactionType.EXPENSE, currentStart + 2_000, category = "Transport"),
            ),
        )

        assertNull(snapshot.expenseChangePercent)
    }

    @Test
    fun expenseChangePercent_negative_whenSpendingFell() {
        val snapshot = analyze(
            listOf(
                tx(50_00L, TransactionType.EXPENSE, currentStart + 1_000, category = "Food"),
                tx(100_00L, TransactionType.EXPENSE, previousStart + 5_000, category = "Food"),
            ),
        )

        assertEquals(-50.0, snapshot.expenseChangePercent!!, 0.001)
    }

    @Test
    fun incomeChangePercent_nullWhereNoBaseline() {
        val snapshot = analyze(
            listOf(
                tx(100_00L, TransactionType.EXPENSE, currentStart + 1_000, category = "Food"),
                tx(200_00L, TransactionType.EXPENSE, currentStart + 2_000, category = "Rent"),
            ),
        )

        assertNull(snapshot.incomeChangePercent)
    }

    @Test
    fun frequencyChangePercent_guardedByZeroPreviousCount() {
        val onlyCurrent = analyze(
            listOf(
                tx(100_00L, TransactionType.EXPENSE, currentStart + 1_000, category = "Food"),
                tx(200_00L, TransactionType.EXPENSE, currentStart + 2_000, category = "Food"),
            ),
        )
        assertNull(onlyCurrent.frequencyChangePercent)
    }

    @Test
    fun frequencyChangePercent_reflectsCountDelta() {
        val snapshot = analyze(
            listOf(
                tx(100_00L, TransactionType.EXPENSE, currentStart + 1_000, category = "Food"),
                tx(200_00L, TransactionType.EXPENSE, currentStart + 2_000, category = "Rent"),
                tx(300_00L, TransactionType.EXPENSE, currentStart + 3_000, category = "Rent"),
                tx(100_00L, TransactionType.EXPENSE, previousStart + 5_000, category = "Food"),
            ),
        )

        // current 3 vs previous 1 -> +200%
        assertEquals(200.0, snapshot.frequencyChangePercent!!, 0.001)
    }

    // ───────────  Categories, deltas & concentration  ───────────

    @Test
    fun categoryRating_sortedByAmountThenName() {
        val snapshot = analyze(
            listOf(
                tx(300_00L, TransactionType.EXPENSE, currentStart + 1_000, category = "Food"),
                tx(600_00L, TransactionType.EXPENSE, currentStart + 2_000, category = "Rent"),
                tx(300_00L, TransactionType.EXPENSE, currentStart + 3_000, category = "Transport"),
            ),
        )

        assertEquals(
            listOf("Rent", "Food", "Transport"),
            snapshot.categoryExpenseTotals.map { it.category },
        )
    }

    @Test
    fun categoryDeltas_computedAcrossBothPeriods() {
        val snapshot = analyze(
            listOf(
                tx(150_00L, TransactionType.EXPENSE, currentStart + 1_000, category = "Food"),
                tx(100_00L, TransactionType.EXPENSE, currentStart + 2_000, category = "Transport"),
                tx(100_00L, TransactionType.EXPENSE, previousStart + 5_000, category = "Food"),
            ),
        )

        val food = snapshot.categoryDeltas.first { it.category == "Food" }
        assertEquals(150_00L, food.currentTotal)
        assertEquals(100_00L, food.previousTotal)
        assertEquals(50_00L, food.delta)
        val transport = snapshot.categoryDeltas.first { it.category == "Transport" }
        assertEquals(100_00L, transport.currentTotal)
        assertEquals(0L, transport.previousTotal)
        assertEquals(100_00L, transport.delta)
    }

    @Test
    fun topCategoryShare_integerPercentOfExpenses() {
        val snapshot = analyze(
            listOf(
                tx(600_00L, TransactionType.EXPENSE, currentStart + 1_000, category = "Rent"),
                tx(300_00L, TransactionType.EXPENSE, currentStart + 2_000, category = "Food"),
                tx(100_00L, TransactionType.EXPENSE, currentStart + 3_000, category = "Transport"),
            ),
        )

        // 600/1000 = 60%
        assertEquals(60, snapshot.topCategorySharePercent)
        assertEquals(60, snapshot.largestExpenseSharePercent)
    }

    @Test
    fun topCategoryShare_nullOnZeroTotal() {
        val snapshot = analyze(emptyList())

        assertNull(snapshot.topCategorySharePercent)
        assertNull(snapshot.largestExpenseSharePercent)
    }

    // ───────────  Unusually large transaction (outlier)  ───────────

    @Test
    fun largestExpenseVsAverage_detectsClearOutlier() {
        val snapshot = analyze(
            listOf(
                tx(500_00L, TransactionType.EXPENSE, currentStart + 1_000, category = "Rent"),
                tx(100_00L, TransactionType.EXPENSE, currentStart + 2_000, category = "Food"),
                tx(100_00L, TransactionType.EXPENSE, currentStart + 3_000, category = "Food"),
            ),
        )

        // mean of the others = (700-500)/2 = 100 -> 500/100 = 5.0x
        assertEquals(5.0, snapshot.largestExpenseVsAverageMultiple!!, 0.001)
    }

    @Test
    fun outlier_notClaimedWithFewerThanThreeTransactions() {
        val snapshot = analyze(
            listOf(
                tx(500_00L, TransactionType.EXPENSE, currentStart + 1_000, category = "Rent"),
                tx(50_00L, TransactionType.EXPENSE, currentStart + 2_000, category = "Food"),
            ),
        )

        assertNull(snapshot.largestExpenseVsAverageMultiple)
    }

    @Test
    fun outlier_notClaimedWhenNotAboveThreshold() {
        val snapshot = analyze(
            listOf(
                tx(200_00L, TransactionType.EXPENSE, currentStart + 1_000, category = "Food"),
                tx(160_00L, TransactionType.EXPENSE, currentStart + 2_000, category = "Food"),
                tx(140_00L, TransactionType.EXPENSE, currentStart + 3_000, category = "Food"),
            ),
        )

        // mean of others = (500-200)/2 = 150 -> 200/150 = 1.33x < 2.0
        assertNull(snapshot.largestExpenseVsAverageMultiple)
    }

    @Test
    fun incomeOutlier_detectedIndependently() {
        val snapshot = analyze(
            listOf(
                tx(900_00L, TransactionType.INCOME, currentStart + 1_000, category = "Bonus"),
                tx(100_00L, TransactionType.INCOME, currentStart + 2_000, category = "Salary"),
                tx(100_00L, TransactionType.INCOME, currentStart + 3_000, category = "Salary"),
            ),
        )

        // mean of the others = (1100-900)/2 = 100 -> 900/100 = 9.0x
        assertEquals(9.0, snapshot.largestIncomeVsAverageMultiple!!, 0.001)
    }

    // ───────────  Recurring candidates (conservative)  ───────────

    @Test
    fun recurringCandidate_requiresBothPeriodsAndEnoughSamples() {
        val snapshot = analyze(
            listOf(
                // Food: 2 transactions per period, totals within 30% -> candidate.
                tx(55_00L, TransactionType.EXPENSE, currentStart + 1_000, category = "Food"),
                tx(45_00L, TransactionType.EXPENSE, currentStart + 2_000, category = "Food"),
                tx(50_00L, TransactionType.EXPENSE, previousStart + 5_000, category = "Food"),
                tx(50_00L, TransactionType.EXPENSE, previousStart + 6_000, category = "Food"),
                // Rent: only one transaction per period -> NOT a recurring candidate.
                tx(200_00L, TransactionType.EXPENSE, currentStart + 4_000, category = "Rent"),
                tx(200_00L, TransactionType.EXPENSE, previousStart + 7_000, category = "Rent"),
            ),
        )

        assertEquals(1, snapshot.candidateRecurringCategories.size)
        val candidate = snapshot.candidateRecurringCategories.single()
        assertEquals("Food", candidate.category)
        assertEquals(100_00L, candidate.currentTotal)
        assertEquals(100_00L, candidate.previousTotal)
        assertEquals(4, candidate.totalTransactions)
    }

    @Test
    fun recurringCandidate_rejectedWhenTotalsDivergeWildly() {
        val snapshot = analyze(
            listOf(
                tx(500_00L, TransactionType.EXPENSE, currentStart + 1_000, category = "Food"),
                tx(500_00L, TransactionType.EXPENSE, currentStart + 2_000, category = "Food"),
                tx(100_00L, TransactionType.EXPENSE, previousStart + 5_000, category = "Food"),
                tx(100_00L, TransactionType.EXPENSE, previousStart + 6_000, category = "Food"),
            ),
        )

        // 1000 vs 200 -> |1000-200| = 800 > 200 * 30/100 = 60
        assertTrue(snapshot.candidateRecurringCategories.isEmpty())
    }

    // ───────────  Averages (integer floor, minor units)  ───────────

    @Test
    fun averageExpense_floorIntegerDivision_noFloatMoney() {
        val snapshot = analyze(
            listOf(
                tx(100_00L, TransactionType.EXPENSE, currentStart + 1_000, category = "Food"),
                tx(99_00L, TransactionType.EXPENSE, currentStart + 2_000, category = "Transport"),
                tx(100_00L, TransactionType.EXPENSE, currentStart + 3_000, category = "Rent"),
            ),
        )

        // (10000+9900+10000)/3 = 9966 piastres (floor) == 99.66 EGP
        assertEquals(9966L, snapshot.averageExpense)
        assertNull(snapshot.averageIncome)
    }

    @Test
    fun blankCategory_mapsToGeneral() {
        val snapshot = analyze(
            listOf(
                tx(50_00L, TransactionType.EXPENSE, currentStart + 1_000, category = ""),
                tx(30_00L, TransactionType.EXPENSE, currentStart + 2_000, category = "  "),
            ),
        )

        assertEquals(1, snapshot.categoryExpenseTotals.size)
        assertEquals("General", snapshot.categoryExpenseTotals.single().category)
        assertEquals(80_00L, snapshot.categoryExpenseTotals.single().totalPiastres)
    }

    @Test
    fun largestExpense_tieBrokenByMostRecent() {
        val snapshot = analyze(
            listOf(
                tx(100_00L, TransactionType.EXPENSE, currentStart + 1_000, category = "Food", description = "older"),
                tx(100_00L, TransactionType.EXPENSE, currentStart + 2_000, category = "Food", description = "newer"),
            ),
        )

        assertEquals("newer", snapshot.largestExpense?.description)
    }
}