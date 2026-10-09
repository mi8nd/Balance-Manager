package com.personal.balance.domain.finance

import com.personal.balance.domain.EGPFormatter
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
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class AskFinancialQuestionUseCaseTest {

    private val zone: ZoneId = ZoneId.of("UTC")
    private val nowEpochMillis: Long = Instant.parse("2026-08-15T12:00:00Z").toEpochMilli()
    private val currentStart: Long = Instant.parse("2026-08-01T00:00:00Z").toEpochMilli()
    private val previousStart: Long = Instant.parse("2026-07-01T00:00:00Z").toEpochMilli()
    private val currentEnd: Long = Instant.parse("2026-09-01T00:00:00Z").toEpochMilli()

    private class FakeAuthProvider(var authenticated: Boolean) : AiAuthProvider {
        override fun isAuthenticated(): Boolean = authenticated
    }

    private class RecordingUsageController : AiUsageController {
        var allowed = true
        var calls = 0
        override suspend fun isCallAllowed(): Boolean = allowed
        override suspend fun recordCall() {
            calls++
        }
    }

    private class RecordingAssistant(var result: AiResult = AiResult.Success("answer", 3)) : AiAssistantRepository {
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
        recordedSourceWindows: MutableList<Pair<Long, Long>> = mutableListOf(),
        assistant: RecordingAssistant = RecordingAssistant(),
        balanceSource: suspend (String) -> Long? = { null },
    ): Pair<AskFinancialQuestionUseCase, Pair<RecordingUsageController, RecordingAssistant>> {
        val usage = RecordingUsageController()
        val askAi = AskAiUseCase(
            authProvider = FakeAuthProvider(authenticated),
            usageController = usage,
            assistant = assistant,
        )
        val source = FinancialTransactionsSource { _: String, start: Long, end: Long ->
            recordedSourceWindows.add(start to end)
            sourceResult
        }
        val askFinancial = AskFinancialQuestionUseCase(
            currentUserId = { if (authenticated) "uid-1" else null },
            transactionsSource = source,
            askAi = askAi,
            nowEpochMillis = { nowEpochMillis },
            zone = zone,
            balanceSource = balanceSource,
        )
        return askFinancial to (usage to assistant)
    }

    @Test
    fun unauthenticated_userRejectedWithoutLoadingTransactions() = runBlocking {
        val windows = mutableListOf<Pair<Long, Long>>()
        val (useCase, _) = useCase(authenticated = false, recordedSourceWindows = windows)

        val result = useCase("How much did I spend?")

        assertTrue(result is AiResult.Error.Unauthenticated)
        assertTrue(windows.isEmpty())
    }

    @Test
    fun sourceFailure_returnsNetworkFailureWithDiagnostic() = runBlocking {
        val (useCase, _) = useCase(
            sourceResult = FinancialTransactionsResult.Error("transactions.read_failed"),
        )

        val result = useCase("How much did I spend?")

        assertTrue(result is AiResult.Error.NetworkFailure)
        assertEquals("transactions.read_failed", (result as AiResult.Error.NetworkFailure).diagnostic)
    }

    @Test
    fun success_loadsBoundedWindowAndSendsFacts() = runBlocking {
        val windows = mutableListOf<Pair<Long, Long>>()
        val assistant = RecordingAssistant()
        val transactions = listOf(
            tx(100_00L, TransactionType.EXPENSE, currentStart + 1_000, category = "Food"),
        )
        val (useCase, _) = useCase(
            sourceResult = FinancialTransactionsResult.Success(transactions),
            recordedSourceWindows = windows,
            assistant = assistant,
        )

        val result = useCase("How much did I spend?")

        // Bounded window: entire previous + current calendar month.
        assertEquals(listOf(previousStart to currentEnd), windows)
        assertTrue(result is AiResult.Success)
        assertEquals(1, assistant.requests.size)
        val request = assistant.requests.single()
        assertEquals("How much did I spend?", request.question)
        assertEquals(EGPFormatter.formatAmount(100_00L), request.structuredFacts["current_expense_total"])
        assertTrue(!request.additionalInstructions.isNullOrBlank())
    }

    @Test
    fun emptyTransactions_stillReachesAssistantWithEmptyFacts() = runBlocking {
        val assistant = RecordingAssistant()
        val (useCase, _) = useCase(assistant = assistant)

        val result = useCase("Any spending last month?")

        assertTrue(result is AiResult.Success)
        assertEquals("none in current period", assistant.requests.single().structuredFacts["expense_categories_totals"])
    }

    @Test
    fun usageLimited_goesThroughAskAiGateAndRejects() = runBlocking {
        val windows = mutableListOf<Pair<Long, Long>>()
        val assistant = RecordingAssistant()
        val (useCase, usageAndAssistant) = useCase(recordedSourceWindows = windows, assistant = assistant)
        val usage = usageAndAssistant.first
        usage.allowed = false

        val result = useCase("How much did I spend?")

        assertTrue(result is AiResult.Error.RateLimited)
        assertTrue(assistant.requests.isEmpty())
    }

    @Test
    fun assistantFailure_passesThroughAndStillRecordsBudget() = runBlocking {
        val assistant = RecordingAssistant(
            result = AiResult.Error.UnexpectedFailure("down", "backend_down"),
        )
        val (useCase, usageAndAssistant) = useCase(assistant = assistant)

        val result = useCase("How much did I spend?")

        assertEquals(AiResult.Error.UnexpectedFailure("down", "backend_down"), result)
        // The budget is recorded by the existing AskAiUseCase gate, exactly once,
        // and not by the financial use case itself.
        assertEquals(1, usageAndAssistant.first.calls)
    }

    @Test
    fun balanceFromSource_isIncludedAsCurrentBalanceFact() = runBlocking {
        val assistant = RecordingAssistant()
        val (useCase, _) = useCase(
            assistant = assistant,
            balanceSource = { uid -> if (uid == "uid-1") 250_00L else null },
        )

        useCase("How much do I have?")

        val facts = assistant.requests.single().structuredFacts
        assertEquals("250.00 EGP", facts["current_balance"])
    }

    @Test
    fun nullBalanceFromSource_omitsCurrentBalanceFact() = runBlocking {
        val assistant = RecordingAssistant()
        val (useCase, _) = useCase(
            assistant = assistant,
            balanceSource = { null },
        )

        useCase("How much do I have?")

        val facts = assistant.requests.single().structuredFacts
        assertTrue(!facts.containsKey("current_balance"))
    }
}