package com.personal.balance.domain.finance

import com.personal.balance.domain.EGPFormatter
import com.personal.balance.domain.ai.AiRequest
import com.personal.balance.model.Transaction
import com.personal.balance.model.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class AiFinancialContextBuilderTest {

    private val zone: ZoneId = ZoneId.of("UTC")
    private val nowEpochMillis: Long = Instant.parse("2026-08-15T12:00:00Z").toEpochMilli()
    private val currentStart: Long = Instant.parse("2026-08-01T00:00:00Z").toEpochMilli()
    private val previousStart: Long = Instant.parse("2026-07-01T00:00:00Z").toEpochMilli()

    private fun tx(
        amount: Long,
        type: TransactionType = TransactionType.EXPENSE,
        timestamp: Long,
        category: String = "",
        description: String = "tx",
    ): Transaction = Transaction(
        userId = "user-123",
        type = type,
        amount = amount,
        description = description,
        category = category,
        timestamp = timestamp,
    )

    private fun snapshot(transactions: List<Transaction>): FinancialSnapshot =
        FinancialAnalyzer.analyze(transactions, nowEpochMillis, zone)

    private fun build(
        question: String = "How am I doing?",
        transactions: List<Transaction> = emptyList(),
    ): AiRequest = AiFinancialContextBuilder.build(question, snapshot(transactions), zone)

    private fun buildWith(
        snapshot: FinancialSnapshot,
        question: String = "How am I doing?",
    ): AiRequest = AiFinancialContextBuilder.build(question, snapshot, zone)

    // ───────────  Expanded deterministic financial facts  ───────────

    @Test
    fun currentBalanceFact_includedWhenAvailable() {
        val request = buildWith(snapshot(emptyList()).copy(currentBalance = 1250_50L))

        assertEquals(EGPFormatter.formatAmount(1250_50L), request.structuredFacts["current_balance"])
    }

    @Test
    fun currentBalanceFact_omittedWhenUnavailable() {
        val request = buildWith(snapshot(emptyList()))

        assertFalse(request.structuredFacts.containsKey("current_balance"))
    }

    @Test
    fun netChangeFacts_areSignedAndDeterministic() {
        val request = buildWith(
            snapshot(
                listOf(
                    tx(450_00L, TransactionType.INCOME, currentStart + 1_000),
                    tx(150_00L, TransactionType.EXPENSE, currentStart + 2_000),
                    tx(200_00L, TransactionType.INCOME, previousStart + 3_000),
                    tx(300_00L, TransactionType.EXPENSE, previousStart + 4_000),
                )
            ),
        )

        // Current net = +300.00 EGP; previous net = -100.00 EGP; change = 400.00.
        assertEquals("+300.00 EGP", request.structuredFacts["current_net_change"])
        assertEquals(
            "+400.00 EGP vs July 2026 (current net +300.00 EGP)",
            request.structuredFacts["net_change_vs_previous"],
        )
    }

    @Test
    fun negativeNetChange_hasMinusButNoPlus() {
        val request = build(
            transactions = listOf(
                tx(50_00L, TransactionType.INCOME, currentStart + 1_000),
                tx(200_00L, TransactionType.EXPENSE, currentStart + 2_000),
            )
        )

        assertEquals("-150.00 EGP", request.structuredFacts["current_net_change"])
    }

    @Test
    fun incomeCategoryTotalsFact_listsSortedTotals() {
        val request = build(
            transactions = listOf(
                tx(100_00L, TransactionType.INCOME, currentStart + 1_000, category = "Salary"),
                tx(70_00L, TransactionType.INCOME, currentStart + 2_000, category = "Freelance"),
            )
        )

        assertEquals(
            "Salary: ${EGPFormatter.formatAmount(100_00L)}; Freelance: ${EGPFormatter.formatAmount(70_00L)}",
            request.structuredFacts["income_categories_totals"],
        )
    }

    @Test
    fun incomeCategoryTotalsFact_noneWhenNoIncome() {
        val request = build(
            transactions = listOf(
                tx(50_00L, TransactionType.EXPENSE, currentStart + 1_000, category = "Food"),
            )
        )

        assertEquals("none in current period", request.structuredFacts["income_categories_totals"])
    }

    @Test
    fun expenseCategoryPercentages_areIntegerFloorPercent() {
        val request = build(
            transactions = listOf(
                tx(30_00L, TransactionType.EXPENSE, currentStart + 1_000, category = "Food"),
                tx(20_00L, TransactionType.EXPENSE, currentStart + 2_000, category = "Transport"),
            )
        )

        assertEquals("Food: 60%; Transport: 40%", request.structuredFacts["expense_categories_percent_of_total"])
    }

    @Test
    fun expenseCategoryPercentages_noneWhenNoSpending() {
        val request = build(
            transactions = listOf(
                tx(90_00L, TransactionType.INCOME, currentStart + 1_000, category = "Salary"),
            )
        )

        assertEquals("none in current period", request.structuredFacts["expense_categories_percent_of_total"])
    }

    @Test
    fun largestIncomeFact_usesStructuralBoundaryWithoutDescription() {
        val description = "bonus payment cheque"
        val request = build(
            transactions = listOf(
                tx(500_00L, TransactionType.INCOME, currentStart + 1_000, category = "Bonus", description = description),
                tx(60_00L, TransactionType.EXPENSE, currentStart + 2_000, category = "Food", description = "small"),
            )
        )

        val largestIncome = request.structuredFacts.getValue("largest_income_in_current_period")
        // Same AI-input boundary as largest/smallest expense: no raw description.
        assertFalse("leaks description: $largestIncome", largestIncome.contains(description))
        assertFalse("contains a quoted token: $largestIncome", largestIncome.contains('"'))
        assertEquals("2026-08-01 income 500.00 EGP Bonus", largestIncome)
        // The description still lives in `recent_transactions`.
        assertTrue(request.structuredFacts.getValue("recent_transactions").contains(description))
    }

    @Test
    fun largestIncomeFact_noneWhenNoIncome() {
        val request = build()

        assertEquals("none in current period", request.structuredFacts["largest_income_in_current_period"])
    }

    @Test
    fun previousCountsFacts_reflectSplitByType() {
        val request = build(
            transactions = listOf(
                tx(10_00L, TransactionType.EXPENSE, previousStart + 1_000),
                tx(20_00L, TransactionType.EXPENSE, previousStart + 2_000),
                tx(30_00L, TransactionType.INCOME, previousStart + 3_000),
            )
        )

        assertEquals("2", request.structuredFacts["previous_expense_count"])
        assertEquals("1", request.structuredFacts["previous_income_count"])
    }

    @Test
    fun includesQuestionAndInstructions() {
        val request = build(question = "How much did I spend?")

        assertEquals("How much did I spend?", request.question)
        assertNotNull(request.additionalInstructions)
        assertTrue(request.additionalInstructions!!.isNotBlank())
    }

    @Test
    fun factsContainTotalsFormattedInEGP() {
        val request = build(
            transactions = listOf(
                tx(1250_00L, TransactionType.EXPENSE, currentStart + 1_000, category = "Food"),
                tx(300_00L, TransactionType.INCOME, currentStart + 2_000, category = "Salary"),
            )
        )

        val facts = request.structuredFacts
        assertEquals("August 2026 (current), July 2026 (previous)", facts["periods"])
        assertEquals(EGPFormatter.formatAmount(1250_00L), facts["current_expense_total"])
        assertEquals(EGPFormatter.formatAmount(300_00L), facts["current_income_total"])
        assertEquals("2 total (1 expense, 1 income)", facts["current_transaction_count"])
    }

    @Test
    fun changeFactIncludesDifferenceAndPercent() {
        val request = build(
            transactions = listOf(
                tx(60_00L, TransactionType.EXPENSE, currentStart + 1_000),
                tx(40_00L, TransactionType.EXPENSE, previousStart + 1_000),
            )
        )

        assertEquals("+20.00 EGP (50.0%) vs July 2026", request.structuredFacts["expense_change_vs_previous"])
    }

    @Test
    fun changeFactWithoutPreviousBaseline() {
        val request = build(
            transactions = listOf(tx(60_00L, TransactionType.EXPENSE, currentStart + 1_000))
        )

        assertTrue(request.structuredFacts["expense_change_vs_previous"]!!.contains("no previous period baseline"))
    }

    @Test
    fun categoriesSortedAndBlankShownAsGeneral() {
        val request = build(
            transactions = listOf(
                tx(50_00L, TransactionType.EXPENSE, currentStart + 1_000, category = "Food"),
                tx(20_00L, TransactionType.EXPENSE, currentStart + 2_000, category = "Food"),
                tx(90_00L, TransactionType.EXPENSE, currentStart + 3_000, category = ""),
            )
        )

        val categories = request.structuredFacts["expense_categories_totals"]!!
        assertTrue(categories.indexOf("General: 90.00 EGP") < categories.indexOf("Food: 70.00 EGP"))
    }

    @Test
    fun emptyDataset_hasExplicitNonePlaceholders() {
        val request = build()

        assertEquals("none in current period", request.structuredFacts["expense_categories_totals"])
        assertEquals("none in current period", request.structuredFacts["largest_expense_in_current_period"])
        assertEquals("none in current period", request.structuredFacts["smallest_expense_in_current_period"])
        assertEquals("none", request.structuredFacts["recent_transactions"])
    }

    @Test
    fun recentTransactionsAreBounded() {
        val many = (0 until 20).map { index ->
            tx(index.toLong() + 1, timestamp = currentStart + index * 1_000, description = "tx$index")
        }
        val request = build(transactions = many)

        // 12 transactions -> at most 12 descriptions in the recent fact.
        val recent = request.structuredFacts["recent_transactions"]!!
        assertEquals(12, recent.split(";").size)
    }

    @Test
    fun neverExposesUserIdOrDocumentIds() {
        val request = build(
            transactions = listOf(
                tx(10_00L, timestamp = currentStart + 1_000, description = "woopsie"),
            )
        )

        val allText = (request.structuredFacts.values + request.question + request.additionalInstructions!!)
            .joinToString { it.uppercase() }
        assertFalse("uid leaked: $allText", allText.contains("USER-123"))
        assertFalse("doc id leaked: $allText", allText.contains("id_") || allText.contains("document"))
    }

    @Test
    fun recentFactDescribesOnlyNecessaryFields() {
        val request = build(
            transactions = listOf(
                tx(12_50L, TransactionType.INCOME, currentStart + 1_000, category = "Freelance", description = "Some client"),
            )
        )

        val recent = request.structuredFacts["recent_transactions"]!!
        assertTrue(recent.contains("2026-08-01 income 12.50 EGP Freelance \"Some client\""))
    }

    // ─────────────────  AI-INPUT BOUNDARY (safety fix)  ─────────────────

    @Test
    fun largestAndSmallestFactsExcludeRawDescription_butKeepStructuredMetadata() {
        val description = "family secret receipt"
        val request = build(
            transactions = listOf(
                tx(100_00L, TransactionType.EXPENSE, currentStart + 1_000, category = "Food", description = description),
                tx(50_00L, TransactionType.EXPENSE, currentStart + 2_000, category = "Food", description = "another receipt"),
                tx(30_00L, TransactionType.INCOME, currentStart + 3_000, category = "Salary", description = "income description"),
            )
        )

        val largest = request.structuredFacts.getValue("largest_expense_in_current_period")
        val smallest = request.structuredFacts.getValue("smallest_expense_in_current_period")

        // AI-input boundary: no raw user-entered description, no quoted token.
        assertFalse("largest leaks description: $largest", largest.contains(description))
        assertFalse("smallest leaks description: $smallest", smallest.contains(description))
        assertFalse("largest leaks any description", largest.contains("another receipt"))
        assertFalse("largest contains a quoted token: $largest", largest.contains('"'))
        assertFalse("smallest contains a quoted token: $smallest", smallest.contains('"'))

        // Structured metadata (date, type, amount, category) is preserved.
        assertEquals("2026-08-01 expense 100.00 EGP Food", largest)
        assertEquals("2026-08-01 expense 50.00 EGP Food", smallest)

        // `recent_transactions` is unchanged: it still carries the raw description.
        val recent = request.structuredFacts.getValue("recent_transactions")
        assertTrue(recent.contains("\"family secret receipt\""))
        assertTrue(recent.contains("\"another receipt\""))
        assertTrue(recent.contains("\"income description\""))
    }

    @Test
    fun descriptionExclusionIsInputBoundaryOnly_underlyingTransactionUnchanged() {
        val description = "client payment memo"
        val transactions = listOf(
            tx(70_00L, TransactionType.EXPENSE, currentStart + 1_000, category = "Transport", description = description),
        )
        val snapshot = snapshot(transactions)
        val request = AiFinancialContextBuilder.build("hi", snapshot, zone)

        // Stored data is untouched: the model/snapshot keeps the raw description.
        assertEquals(description, snapshot.largestExpense?.description)
        assertEquals(description, snapshot.recentTransactions.single().description)

        // Omitted only from the largest/smallest AI facts; recent still has it.
        assertFalse(
            request.structuredFacts.getValue("largest_expense_in_current_period").contains(description),
        )
        assertTrue(request.structuredFacts.getValue("recent_transactions").contains(description))
    }

    // ───────────  Initial balance semantics (balance ≠ transactions)  ───────────

    @Test
    fun initialBalance_only_noTransactions_balanceIsInitialAndNetChangeZero() {
        val snapshot = FinancialSnapshot(
            currentLabel = "August 2026",
            previousLabel = "July 2026",
            currentExpenseTotal = 0L,
            currentIncomeTotal = 0L,
            currentExpenseCount = 0,
            currentIncomeCount = 0,
            previousExpenseTotal = 0L,
            previousIncomeTotal = 0L,
            previousTransactionCount = 0,
            expenseDifference = 0L,
            expenseDifferencePercent = null,
            categoryExpenseTotals = emptyList(),
            largestExpense = null,
            smallestExpense = null,
            recentTransactions = emptyList(),
            currentBalance = 5000_00L,
        )
        val request = AiFinancialContextBuilder.build("How am I doing?", snapshot, zone)

        assertEquals("5,000.00 EGP", request.structuredFacts["current_balance"])
        assertEquals("0.00 EGP", request.structuredFacts["current_expense_total"])
        assertEquals("0.00 EGP", request.structuredFacts["current_income_total"])
        assertEquals("0.00 EGP", request.structuredFacts["current_net_change"])
    }

    @Test
    fun initialBalance_withTransactions_netChangeIsPurelyFromTransactions() {
        val transactions = listOf(
            tx(1000_00L, TransactionType.INCOME, currentStart + 1_000, category = "Salary"),
            tx(300_00L, TransactionType.EXPENSE, currentStart + 2_000, category = "Food"),
        )
        val snapshot = snapshot(transactions).copy(currentBalance = 5000_00L)
        val request = AiFinancialContextBuilder.build("How am I doing?", snapshot, zone)

        assertEquals("5,000.00 EGP", request.structuredFacts["current_balance"])
        assertEquals("1,000.00 EGP", request.structuredFacts["current_income_total"])
        assertEquals("300.00 EGP", request.structuredFacts["current_expense_total"])
        assertEquals("+700.00 EGP", request.structuredFacts["current_net_change"])
    }

    @Test
    fun instructionsClarifyBalanceVsTransactions() {
        val snapshot = FinancialSnapshot(
            currentLabel = "August 2026",
            previousLabel = "July 2026",
            currentExpenseTotal = 0L,
            currentIncomeTotal = 0L,
            currentExpenseCount = 0,
            currentIncomeCount = 0,
            previousExpenseTotal = 0L,
            previousIncomeTotal = 0L,
            previousTransactionCount = 0,
            expenseDifference = 0L,
            expenseDifferencePercent = null,
            categoryExpenseTotals = emptyList(),
            largestExpense = null,
            smallestExpense = null,
            recentTransactions = emptyList(),
            currentBalance = 5000_00L,
        )
        val request = AiFinancialContextBuilder.build("How am I doing?", snapshot, zone)

        // The instructions must explicitly state that current_balance is NOT a
        // transaction total and that net_change is derived purely from transactions.
        assertTrue(request.additionalInstructions!!.contains("current_balance"))
        assertTrue(request.additionalInstructions!!.contains("NOT a transaction total"))
        assertTrue(request.additionalInstructions!!.contains("current_net_change"))
        assertTrue(request.additionalInstructions!!.contains("do NOT include the initial balance"))
    }

    @Test
    fun instructionsEncourageObservationsAndGeneralSuggestions() {
        val request = build()

        val instr = request.additionalInstructions!!
        assertTrue(instr.contains("Distinguish data-backed observations from general suggestions"))
        assertTrue(instr.contains("quote the numbers"))
        assertTrue(instr.contains("general guidance based on the user's own numbers"))
        assertTrue(instr.contains("Do not add boilerplate disclaimers"))
        assertTrue(instr.contains("Never present yourself as a licensed financial professional"))
        // The overly defensive disclaimer must NOT appear in the instructions.
        assertFalse(instr.lowercase().contains("cannot provide financial advice"))
    }
}