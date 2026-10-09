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

class TransactionIntelligenceContextBuilderTest {

    private val zone: ZoneId = ZoneId.of("UTC")

    private fun tx(
        amount: Long,
        type: TransactionType = TransactionType.EXPENSE,
        timestamp: Long,
        category: String = "",
        description: String = "coffee shop",
    ): Transaction = Transaction(
        userId = "uid-1",
        type = type,
        amount = amount,
        description = description,
        category = category,
        timestamp = timestamp,
    )

    private fun snapshot(
        currentExpenses: List<Transaction> = emptyList(),
        previousExpenses: List<Transaction> = emptyList(),
        currentIncome: List<Transaction> = emptyList(),
        previousIncome: List<Transaction> = emptyList(),
    ): TransactionIntelligenceSnapshot {
        val all = currentExpenses + previousExpenses + currentIncome + previousIncome
        val nowEpochMillis = Instant.parse("2026-08-15T12:00:00Z").toEpochMilli()
        return TransactionIntelligenceAnalyzer.analyze(all, nowEpochMillis, zone)
    }

    // ───────────  Facts content  ───────────

    @Test
    fun factsContainPeriodsWindowCountAndCategoryTotals() {
        val s = snapshot(
            currentExpenses = listOf(
                tx(500_00L, TransactionType.EXPENSE, Instant.parse("2026-08-01T10:00:00Z").toEpochMilli(), category = "Food"),
                tx(300_00L, TransactionType.EXPENSE, Instant.parse("2026-08-02T10:00:00Z").toEpochMilli(), category = "Rent"),
            ),
        )

        val request = TransactionIntelligenceContextBuilder.build(s, zone)

        assertEquals("August 2026 (current), July 2026 (previous)", request.structuredFacts["periods"])
        assertEquals("2", request.structuredFacts["window_transaction_count"])
        assertTrue(request.structuredFacts["expense_categories_totals"]!!.contains("Food: 500.00 EGP"))
        assertTrue(request.structuredFacts["expense_categories_totals"]!!.contains("Rent: 300.00 EGP"))
        assertNotNull(request.additionalInstructions)
        assertTrue(request.additionalInstructions!!.isNotBlank())
    }

    @Test
    fun expenseChangePercent_reflectedWhenAvailable() {
        val s = snapshot(
            currentExpenses = listOf(
                tx(150_00L, TransactionType.EXPENSE, Instant.parse("2026-08-01T10:00:00Z").toEpochMilli(), category = "Food"),
            ),
            previousExpenses = listOf(
                tx(100_00L, TransactionType.EXPENSE, Instant.parse("2026-07-15T10:00:00Z").toEpochMilli(), category = "Food"),
            ),
        )

        val request = TransactionIntelligenceContextBuilder.build(s, zone)
        val change = request.structuredFacts["expense_change"]!!

        assertTrue(change.contains("+50.0%"))
        assertTrue(change.contains("150.00 EGP"))
        assertTrue(change.contains("100.00 EGP"))
    }

    @Test
    fun noPreviousBaseline_showsNoBaselineMessage() {
        val s = snapshot(
            currentExpenses = listOf(
                tx(100_00L, TransactionType.EXPENSE, Instant.parse("2026-08-01T10:00:00Z").toEpochMilli(), category = "Food"),
            ),
        )

        val request = TransactionIntelligenceContextBuilder.build(s, zone)
        assertEquals("no previous-period baseline", request.structuredFacts["expense_change"])
        assertEquals("no previous-period baseline", request.structuredFacts["transaction_frequency_change"])
    }

    @Test
    fun zeroExpenseTotals_factsAreNone() {
        val s = snapshot() // empty everything

        val request = TransactionIntelligenceContextBuilder.build(s, zone)

        assertEquals("none in current period", request.structuredFacts["expense_categories_totals"])
        assertEquals("none in previous period", request.structuredFacts["previous_expense_categories_totals"])
        assertEquals("none in current period", request.structuredFacts["expense_categories_percent_of_total"])
        assertEquals("none", request.structuredFacts["category_deltas"])
        assertEquals("not enough data", request.structuredFacts["top_category_share"])
        assertEquals("not enough data", request.structuredFacts["largest_expense_share"])
        assertEquals("not enough data", request.structuredFacts["average_expense_per_transaction"])
        assertEquals("not enough data", request.structuredFacts["average_income_per_transaction"])
        assertEquals("none in current period", request.structuredFacts["largest_expense_in_current_period"])
        assertEquals("none in current period", request.structuredFacts["largest_income_in_current_period"])
        assertEquals("not enough data", request.structuredFacts["largest_expense_vs_average"])
        assertEquals("not enough data", request.structuredFacts["largest_income_vs_average"])
        assertEquals("none identified", request.structuredFacts["candidate_recurring_categories"])
    }

    // ───────────  AI-input boundary: no raw descriptions  ───────────

    @Test
    fun largestExpenseFact_omitsRawDescription() {
        val s = snapshot(
            currentExpenses = listOf(
                tx(500_00L, TransactionType.EXPENSE, Instant.parse("2026-08-01T10:00:00Z").toEpochMilli(),
                    category = "Food", description = "Secret coffee shop visit"),
                tx(100_00L, TransactionType.EXPENSE, Instant.parse("2026-08-02T10:00:00Z").toEpochMilli(),
                    category = "Transport"),
            ),
        )

        val request = TransactionIntelligenceContextBuilder.build(s, zone)
        val largest = request.structuredFacts["largest_expense_in_current_period"]!!

        assertTrue(largest.contains("expense"))
        assertTrue(largest.contains("500.00 EGP"))
        assertTrue(largest.contains("Food"))
        assertFalse(largest.contains("Secret coffee shop visit"))
    }

    @Test
    fun noRecentTransactionsKeyExists() {
        val s = snapshot(
            currentExpenses = listOf(
                tx(100_00L, TransactionType.EXPENSE, Instant.parse("2026-08-01T10:00:00Z").toEpochMilli(), category = "Food"),
            ),
        )

        val request = TransactionIntelligenceContextBuilder.build(s, zone)

        assertFalse(request.structuredFacts.containsKey("recent_transactions"))
    }

    // ───────────  Balance never mentioned  ───────────

    @Test
    fun noBalanceFactInFactsOrInstructions() {
        val s = snapshot(
            currentExpenses = listOf(
                tx(100_00L, TransactionType.EXPENSE, Instant.parse("2026-08-01T10:00:00Z").toEpochMilli(), category = "Food"),
            ),
        )

        val request = TransactionIntelligenceContextBuilder.build(s, zone)

        assertFalse(request.structuredFacts.containsKey("current_balance"))
        assertFalse(request.structuredFacts.keys.any { it.contains("balance") })
        assertFalse(request.additionalInstructions!!.contains("your balance"))
        assertTrue(request.additionalInstructions!!.contains("No balance is provided"))
    }

    // ───────────  Instructions & question structure  ───────────

    @Test
    fun instructionsContainExactBehavioralRules() {
        val request = TransactionIntelligenceContextBuilder.build(snapshot(), zone)
        val instr = request.additionalInstructions!!

        assertTrue(instr.contains("Never invent transactions"))
        assertTrue(instr.contains("do not claim a trend or anomaly it would require"))
        assertTrue(instr.contains("Do not assert certainty about future"))
        assertTrue(instr.startsWith("The \"Authoritative facts\""))
    }

    @Test
    fun insightQuestionRequiresInsightFormatAndNoFabrication() {
        val request = TransactionIntelligenceContextBuilder.build(snapshot(), zone)

        assertTrue(request.question.contains("INSIGHT|LEVEL|Title|Explanation"))
        assertTrue(request.question.contains("supported only by the facts above"))
    }

    // ───────────  EGP formatting (integers only)  ───────────

    @Test
    fun egpValues_areFormattedAsCurrencyInFacts() {
        val s = snapshot(
            currentExpenses = listOf(
                tx(1250_75L, TransactionType.EXPENSE, Instant.parse("2026-08-01T10:00:00Z").toEpochMilli(), category = "Rent"),
            ),
            previousExpenses = listOf(
                tx(1250_75L, TransactionType.EXPENSE, Instant.parse("2026-07-10T10:00:00Z").toEpochMilli(), category = "Rent"),
            ),
        )

        val request = TransactionIntelligenceContextBuilder.build(s, zone)
        assertEquals(EGPFormatter.formatAmount(1250_75L), request.structuredFacts["current_expense_total"])
        assertEquals(EGPFormatter.formatAmount(1250_75L), request.structuredFacts["previous_expense_total"])
    }

    @Test
    fun categoryDeltas_useSignedMoneyFormat() {
        val s = snapshot(
            currentExpenses = listOf(
                tx(150_00L, TransactionType.EXPENSE, Instant.parse("2026-08-01T10:00:00Z").toEpochMilli(), category = "Food"),
                tx(100_00L, TransactionType.EXPENSE, Instant.parse("2026-08-02T10:00:00Z").toEpochMilli(), category = "Transport"),
            ),
            previousExpenses = listOf(
                tx(100_00L, TransactionType.EXPENSE, Instant.parse("2026-07-10T10:00:00Z").toEpochMilli(), category = "Food"),
            ),
        )

        val request = TransactionIntelligenceContextBuilder.build(s, zone)
        val deltas = request.structuredFacts["category_deltas"]!!

        assertTrue(deltas.contains("Food: +50.00 EGP"))
        assertTrue(deltas.contains("Transport: +100.00 EGP (current 100.00 EGP vs previous 0.00 EGP)"))
    }

    @Test
    fun instructionsDistinguishObservationsFromSuggestionsAndAvoidBoilerplateDisclaimers() {
        val request = TransactionIntelligenceContextBuilder.build(snapshot(), zone)
        val instr = request.additionalInstructions!!

        // Existing behavioral rules still present.
        assertTrue(instr.contains("Separate observations from suggestions"))
        assertTrue(instr.startsWith("The \"Authoritative facts\""))
        assertTrue(instr.contains("Never invent transactions"))
        assertTrue(instr.contains("Do not assert certainty about future"))
        // New observation/suggestion wording.
        assertTrue(instr.contains("grounded in the exact numbers"))
        assertTrue(instr.contains("general guidance based on the user's own spending"))
        assertTrue(instr.contains("Do not add boilerplate disclaimers"))
        assertFalse(instr.lowercase().contains("cannot provide financial advice"))
    }
}