package com.personal.balance.domain.finance

import com.personal.balance.domain.ai.AiAssistantRepository
import com.personal.balance.domain.ai.AiAuthProvider
import com.personal.balance.domain.ai.AiRequest
import com.personal.balance.domain.ai.AiResult
import com.personal.balance.domain.ai.AiUsageController
import com.personal.balance.domain.ai.AskAiUseCase
import com.personal.balance.model.Transaction
import com.personal.balance.model.TransactionType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class TransactionIntelligenceUseCaseTest {

    private val zone: ZoneId = ZoneId.of("UTC")
    private val nowEpochMillis: Long = Instant.parse("2026-08-15T12:00:00Z").toEpochMilli()

    private class FakeAuthProvider(var authenticated: Boolean) : AiAuthProvider {
        override fun isAuthenticated(): Boolean = authenticated
    }

    private class RecordingUsageController : AiUsageController {
        var allowed = true
        var calls = 0
        override suspend fun isCallAllowed(): Boolean = allowed
        override suspend fun recordCall() { calls++ }
    }

    private class RecordingAssistant(var result: AiResult = AiResult.Success("INSIGHT|INFO|Test|Explanation.", 3)) : AiAssistantRepository {
        val requests = mutableListOf<AiRequest>()
        override suspend fun ask(request: AiRequest): AiResult {
            requests.add(request)
            return result
        }
    }

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

    private fun useCase(
        authenticated: Boolean = true,
        sourceResult: FinancialTransactionsResult = FinancialTransactionsResult.Success(emptyList()),
        assistant: RecordingAssistant = RecordingAssistant(),
        usage: RecordingUsageController = RecordingUsageController(),
    ): Triple<TransactionIntelligenceUseCase, RecordingUsageController, RecordingAssistant> {
        val askAi = AskAiUseCase(
            authProvider = FakeAuthProvider(authenticated),
            usageController = usage,
            assistant = assistant,
        )
        val source = FinancialTransactionsSource { _: String, _: Long, _: Long -> sourceResult }
        val uc = TransactionIntelligenceUseCase(
            currentUserId = { if (authenticated) "uid-1" else null },
            transactionsSource = source,
            askAi = askAi,
            nowEpochMillis = { nowEpochMillis },
            zone = zone,
        )
        return Triple(uc, usage, assistant)
    }

    // ───────────  Auth gating  ───────────

    @Test
    fun unauthenticated_rejectedWithNoTransactionsLoaded() = runBlocking {
        val (uc, usage, assistant) = useCase(authenticated = false)

        val result = uc()

        assertTrue(result is TransactionIntelligenceResult.Error)
        val error = result as TransactionIntelligenceResult.Error
        assertTrue(error.error is AiResult.Error.Unauthenticated)
        assertTrue(assistant.requests.isEmpty())
        assertEquals(0, usage.calls)
    }

    // ───────────  Source failure  ───────────

    @Test
    fun sourceFailure_wrapsNetworkFailure() = runBlocking {
        val (uc, usage, assistant) = useCase(
            sourceResult = FinancialTransactionsResult.Error("transactions.read_failed"),
        )

        val result = uc()

        assertTrue(result is TransactionIntelligenceResult.Error)
        val error = result as TransactionIntelligenceResult.Error
        assertTrue(error.error is AiResult.Error.NetworkFailure)
        assertEquals("transactions.read_failed", (error.error as AiResult.Error.NetworkFailure).diagnostic)
        assertTrue(assistant.requests.isEmpty())
        assertEquals(0, usage.calls)
    }

    // ───────────  Empty & insufficient data (no AI call)  ───────────

    @Test
    fun noTransactions_returnsNoData_withoutCallingAi() = runBlocking {
        val (uc, usage, assistant) = useCase(
            sourceResult = FinancialTransactionsResult.Success(emptyList()),
        )

        val result = uc()

        assertTrue(result is TransactionIntelligenceResult.NoData)
        assertTrue(assistant.requests.isEmpty())
        assertEquals(0, usage.calls)
    }

    @Test
    fun singleTransaction_returnsInsufficientData_withoutCallingAi() = runBlocking {
        val (uc, usage, assistant) = useCase(
            sourceResult = FinancialTransactionsResult.Success(
                listOf(tx(100_00L, TransactionType.EXPENSE,
                    Instant.parse("2026-08-05T10:00:00Z").toEpochMilli(), category = "Food")),
            ),
        )

        val result = uc()

        assertTrue(result is TransactionIntelligenceResult.InsufficientData)
        assertTrue(assistant.requests.isEmpty())
        assertEquals(0, usage.calls)
    }

    @Test
    fun onlyPreviousPeriodTransactions_returnsInsufficientData() = runBlocking {
        val (uc, usage, assistant) = useCase(
            sourceResult = FinancialTransactionsResult.Success(
                listOf(
                    tx(100_00L, TransactionType.EXPENSE,
                        Instant.parse("2026-07-10T10:00:00Z").toEpochMilli(), category = "Food"),
                    tx(50_00L, TransactionType.EXPENSE,
                        Instant.parse("2026-07-20T10:00:00Z").toEpochMilli(), category = "Rent"),
                ),
            ),
        )

        val result = uc()

        assertTrue(result is TransactionIntelligenceResult.InsufficientData)
        assertTrue(assistant.requests.isEmpty())
        assertEquals(0, usage.calls)
    }

    // ───────────  Success path  ───────────

    @Test
    fun sufficientData_callsAiOnce_recordsUsage() = runBlocking {
        val assistant = RecordingAssistant(
            result = AiResult.Success(
                "INSIGHT|HIGH|Big shift|Food spending rose 50% this month.",
                8,
            ),
        )
        val (uc, usage, _) = useCase(
            sourceResult = FinancialTransactionsResult.Success(
                listOf(
                    tx(150_00L, TransactionType.EXPENSE,
                        Instant.parse("2026-08-01T10:00:00Z").toEpochMilli(), category = "Food"),
                    tx(100_00L, TransactionType.EXPENSE,
                        Instant.parse("2026-08-02T10:00:00Z").toEpochMilli(), category = "Rent"),
                ),
            ),
            assistant = assistant,
        )

        val result = uc()

        assertTrue(result is TransactionIntelligenceResult.Success)
        val success = result as TransactionIntelligenceResult.Success
        assertEquals(1, success.insights.size)
        assertEquals("Big shift", success.insights[0].title)
        assertEquals(1, assistant.requests.size)
        assertEquals(1, usage.calls)
    }

    @Test
    fun aiFailure_wrappedAsError() = runBlocking {
        val assistant = RecordingAssistant(
            result = AiResult.Error.UnexpectedFailure("backend down", "backend_down"),
        )
        val (uc, usage, _) = useCase(
            sourceResult = FinancialTransactionsResult.Success(
                listOf(
                    tx(100_00L, TransactionType.EXPENSE,
                        Instant.parse("2026-08-01T10:00:00Z").toEpochMilli(), category = "Food"),
                    tx(100_00L, TransactionType.EXPENSE,
                        Instant.parse("2026-08-02T10:00:00Z").toEpochMilli(), category = "Food"),
                ),
            ),
            assistant = assistant,
        )

        val result = uc()

        assertTrue(result is TransactionIntelligenceResult.Error)
        val error = result as TransactionIntelligenceResult.Error
        assertTrue(error.error is AiResult.Error.UnexpectedFailure)
        assertEquals(1, usage.calls)
    }

    // ───────────  Usage budget  ───────────

    @Test
    fun usageBudgetExhausted_returnsErrorWithoutCallingAi() = runBlocking {
        val assistant = RecordingAssistant()
        val usage = RecordingUsageController()
        usage.allowed = false
        val (uc, _, _) = useCase(
            sourceResult = FinancialTransactionsResult.Success(
                listOf(
                    tx(100_00L, TransactionType.EXPENSE,
                        Instant.parse("2026-08-01T10:00:00Z").toEpochMilli(), category = "Food"),
                    tx(100_00L, TransactionType.EXPENSE,
                        Instant.parse("2026-08-02T10:00:00Z").toEpochMilli(), category = "Food"),
                ),
            ),
            assistant = assistant,
            usage = usage,
        )

        val result = uc()

        assertTrue(result is TransactionIntelligenceResult.Error)
        val error = result as TransactionIntelligenceResult.Error
        assertTrue(error.error is AiResult.Error.RateLimited)
        assertTrue(assistant.requests.isEmpty())
    }

    // ───────────  Factual integrity: no descriptions reach AI  ───────────

    @Test
    fun requestFactsOmitRawDescriptions() = runBlocking {
        val assistant = RecordingAssistant()
        val (uc, _, _) = useCase(
            sourceResult = FinancialTransactionsResult.Success(
                listOf(
                    tx(500_00L, TransactionType.EXPENSE,
                        Instant.parse("2026-08-01T10:00:00Z").toEpochMilli(),
                        category = "Food", description = "Secret cafe"),
                    tx(100_00L, TransactionType.EXPENSE,
                        Instant.parse("2026-08-02T10:00:00Z").toEpochMilli(),
                        category = "Rent"),
                ),
            ),
            assistant = assistant,
        )

        uc()

        val facts = assistant.requests.single().structuredFacts
        val allValues = facts.values.joinToString(" ")
        assertFalse(allValues.contains("Secret cafe"))
        assertFalse(facts.containsKey("recent_transactions"))
    }

    // ───────────  Parsed insights delivered on success  ───────────

    @Test
    fun multipleInsightsParsedFromAiReply() = runBlocking {
        val assistant = RecordingAssistant(
            result = AiResult.Success(
                "Preamble line\n" +
                    "INSIGHT|HIGH|Food spike|Food spending rose 50% this month.\n" +
                    "INSIGHT|LOW|Stable rent|Rent held steady at 1,000.00 EGP.\n",
                12,
            ),
        )
        val (uc, _, _) = useCase(
            sourceResult = FinancialTransactionsResult.Success(
                listOf(
                    tx(150_00L, TransactionType.EXPENSE,
                        Instant.parse("2026-08-01T10:00:00Z").toEpochMilli(), category = "Food"),
                    tx(100_00L, TransactionType.EXPENSE,
                        Instant.parse("2026-08-02T10:00:00Z").toEpochMilli(), category = "Rent"),
                ),
            ),
            assistant = assistant,
        )

        val result = uc()

        assertTrue(result is TransactionIntelligenceResult.Success)
        val insights = (result as TransactionIntelligenceResult.Success).insights
        assertEquals(2, insights.size)
        assertEquals("Food spike", insights[0].title)
        assertEquals(Importance.HIGH, insights[0].importance)
        assertEquals("Stable rent", insights[1].title)
        assertEquals(Importance.LOW, insights[1].importance)
    }

    // ───────────  Request window matches expected bounds  ───────────

    @Test
    fun requestQuestionContainsInsightFormatInstruction() = runBlocking {
        val assistant = RecordingAssistant()
        val (uc, _, _) = useCase(
            sourceResult = FinancialTransactionsResult.Success(
                listOf(
                    tx(100_00L, TransactionType.EXPENSE,
                        Instant.parse("2026-08-01T10:00:00Z").toEpochMilli(), category = "Food"),
                    tx(100_00L, TransactionType.EXPENSE,
                        Instant.parse("2026-08-02T10:00:00Z").toEpochMilli(), category = "Food"),
                ),
            ),
            assistant = assistant,
        )

        uc()

        val request = assistant.requests.single()
        assertTrue(request.question.contains("INSIGHT|LEVEL|Title|Explanation"))
        assertTrue(request.question.contains("supported only by the facts above"))
    }
}