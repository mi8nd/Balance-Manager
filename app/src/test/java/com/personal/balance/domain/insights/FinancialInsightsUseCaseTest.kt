package com.personal.balance.domain.insights

import com.personal.balance.domain.ai.AiAssistantRepository
import com.personal.balance.domain.ai.AiAuthProvider
import com.personal.balance.domain.ai.AiRequest
import com.personal.balance.domain.ai.AiResult
import com.personal.balance.domain.ai.AiUsageController
import com.personal.balance.domain.ai.AskAiUseCase
import com.personal.balance.domain.ai.orchestration.GoalCoachSource
import com.personal.balance.domain.ai.orchestration.GoalCoachState
import com.personal.balance.domain.finance.FinancialTransactionsResult
import com.personal.balance.domain.finance.FinancialTransactionsSource
import com.personal.balance.domain.finance.FinancialWindows
import com.personal.balance.domain.finance.Importance
import com.personal.balance.model.Transaction
import com.personal.balance.model.TransactionType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/**
 * Financial Insights orchestration through the REAL [AskAiUseCase]: auth gating,
 * the shared usage budget, data-sufficiency gates that never touch the model,
 * and parsed Success/Error outcomes.
 */
class FinancialInsightsUseCaseTest {

    private val zone: ZoneId = ZoneId.of("UTC")
    private val nowEpochMillis: Long = Instant.parse("2026-08-15T12:00:00Z").toEpochMilli()
    private val window = FinancialWindows.windowAt(nowEpochMillis, zone)
    private val currentTs = window.currentMonthStartEpochMillis + 1000
    private val previousTs = window.previousMonthStartEpochMillis + 1000

    private class FakeAuthProvider(var authenticated: Boolean = true) : AiAuthProvider {
        override fun isAuthenticated(): Boolean = authenticated
    }

    private class RecordingUsageController(var allowed: Boolean = true) : AiUsageController {
        var calls = 0
        override suspend fun isCallAllowed(): Boolean = allowed
        override suspend fun recordCall() { calls++ }
    }

    private class RecordingAssistant(
        var result: AiResult = AiResult.Success(
            "INSIGHT|HIGH|Spending spike|Transport is up.\nRECOMMENDATION|Cut back|You could consider trimming transport.",
            2,
        ),
    ) : AiAssistantRepository {
        val requests = mutableListOf<AiRequest>()
        override suspend fun ask(request: AiRequest): AiResult {
            requests += request
            return result
        }
    }

    private fun tx(
        amount: Long,
        type: TransactionType = TransactionType.EXPENSE,
        timestamp: Long,
        category: String,
    ): Transaction = Transaction(
        userId = "uid-1",
        type = type,
        amount = amount,
        description = "tx",
        category = category,
        timestamp = timestamp,
    )

    private fun useCase(
        authenticated: Boolean = true,
        sourceResult: FinancialTransactionsResult = FinancialTransactionsResult.Success(emptyList()),
        goal: GoalCoachState = GoalCoachState.Unavailable,
        balance: Long? = null,
        assistant: RecordingAssistant = RecordingAssistant(),
        usage: RecordingUsageController = RecordingUsageController(),
    ): Triple<FinancialInsightsUseCase, RecordingUsageController, RecordingAssistant> {
        val askAi = AskAiUseCase(FakeAuthProvider(authenticated), usage, assistant)
        val source = FinancialTransactionsSource { _: String, _: Long, _: Long -> sourceResult }
        val goalSource = GoalCoachSource { _: String? -> goal }
        val uc = FinancialInsightsUseCase(
            currentUserId = { if (authenticated) "uid-1" else null },
            transactionsSource = source,
            goalCoachSource = goalSource,
            balanceSource = { _: String -> balance },
            askAi = askAi,
            nowEpochMillis = { nowEpochMillis },
            zone = zone,
        )
        return Triple(uc, usage, assistant)
    }

    @Test
    fun unauthenticatedReturnsErrorWithoutCallingAi() = runBlocking {
        val (uc, usage, assistant) = useCase(authenticated = false)

        val result = uc()

        assertTrue(result is FinancialInsightsResult.Error)
        assertTrue((result as FinancialInsightsResult.Error).error is AiResult.Error.Unauthenticated)
        assertEquals(0, assistant.requests.size)
        assertEquals(0, usage.calls)
    }

    @Test
    fun sourceFailureReturnsNetworkErrorWithoutCallingAi() = runBlocking {
        val (uc, usage, assistant) = useCase(
            sourceResult = FinancialTransactionsResult.Error("disk read failed"),
        )

        val result = uc()

        assertTrue(result is FinancialInsightsResult.Error)
        val error = (result as FinancialInsightsResult.Error).error as AiResult.Error.NetworkFailure
        assertEquals("insights.transactions.read_failed", error.diagnostic)
        assertEquals(0, assistant.requests.size)
        assertEquals(0, usage.calls)
    }

    @Test
    fun emptyWindowReturnsNoDataWithoutCallingAi() = runBlocking {
        val (uc, usage, assistant) = useCase(
            sourceResult = FinancialTransactionsResult.Success(emptyList()),
        )

        val result = uc()

        assertEquals(FinancialInsightsResult.NoData, result)
        assertEquals(0, assistant.requests.size)
        assertEquals(0, usage.calls)
    }

    @Test
    fun singleTransactionReturnsInsufficientDataWithoutCallingAi() = runBlocking {
        val (uc, usage, assistant) = useCase(
            sourceResult = FinancialTransactionsResult.Success(
                listOf(tx(1000, type = TransactionType.EXPENSE, timestamp = currentTs, category = "Food")),
            ),
        )

        val result = uc()

        assertEquals(FinancialInsightsResult.InsufficientData, result)
        assertEquals(0, assistant.requests.size)
        assertEquals(0, usage.calls)
    }

    @Test
    fun successParsesInsightAndRecommendationAndRecordsBudget() = runBlocking {
        val (uc, usage, assistant) = useCase(
            sourceResult = FinancialTransactionsResult.Success(
                listOf(
                    tx(10000L, TransactionType.INCOME, currentTs, "Salary"),
                    tx(4000L, TransactionType.EXPENSE, currentTs, "Food"),
                ),
            ),
            goal = GoalCoachState.Active("Emergency fund", 100_000_00L, 25_000_00L, false),
            balance = 500_00L,
        )

        val result = uc()

        assertTrue(result is FinancialInsightsResult.Success)
        val success = result as FinancialInsightsResult.Success
        assertEquals(listOf("Spending spike"), success.insights.map { it.title })
        assertEquals(Importance.HIGH, success.insights.single().importance)
        assertEquals("Cut back", success.recommendations.single().title)
        assertEquals(0, success.snapshot.uncategorizedCurrentCount)
        assertEquals("Emergency fund", success.snapshot.goal?.name)
        assertEquals(500_00L, success.snapshot.currentBalance)
        assertEquals(1, usage.calls)
        assertEquals(1, assistant.requests.size)
        val request = assistant.requests.single()
        assertEquals("500.00 EGP", request.structuredFacts["account_balance"])
    }

    @Test
    fun aiErrorIsSurfacedAsResultError() = runBlocking {
        val assistant = RecordingAssistant(
            result = AiResult.Error.RateLimited("Limit reached", "local usage window exhausted"),
        )
        val (uc, usage, _) = useCase(
            sourceResult = FinancialTransactionsResult.Success(
                listOf(
                    tx(10000L, TransactionType.INCOME, currentTs, "Salary"),
                    tx(4000L, TransactionType.EXPENSE, currentTs, "Food"),
                ),
            ),
            assistant = assistant,
        )

        val result = uc()

        assertTrue(result is FinancialInsightsResult.Error)
        assertTrue((result as FinancialInsightsResult.Error).error is AiResult.Error.RateLimited)
        assertEquals(1, usage.calls)
    }

    @Test
    fun budgetDenyReturnsRateLimitedWithoutTouchingAssistant() = runBlocking {
        val assistant = RecordingAssistant()
        val usage = RecordingUsageController(allowed = false)
        val (uc, _, _) = useCase(
            sourceResult = FinancialTransactionsResult.Success(
                listOf(
                    tx(10000L, TransactionType.INCOME, currentTs, "Salary"),
                    tx(4000L, TransactionType.EXPENSE, currentTs, "Food"),
                ),
            ),
            assistant = assistant,
            usage = usage,
        )

        val result = uc()

        assertTrue(result is FinancialInsightsResult.Error)
        assertTrue((result as FinancialInsightsResult.Error).error is AiResult.Error.RateLimited)
        assertEquals(0, usage.calls)
        assertTrue(assistant.requests.isEmpty())
    }

    @Test
    fun prepareDecidesReadyOnSuccessfulLoadAndRejectedOnSourceError() = runBlocking {
        val (uc, _, _) = useCase(sourceResult = FinancialTransactionsResult.Success(emptyList()))
        val ready = uc.prepare()
        assertTrue(ready is FinancialInsightsUseCase.Preparation.Ready)
        assertEquals(0, (ready as FinancialInsightsUseCase.Preparation.Ready).snapshot.intelligence.windowTransactionCount)

        val (failedUc, _, _) = useCase(sourceResult = FinancialTransactionsResult.Error("disk read failed"))
        val rejected = failedUc.prepare()
        assertTrue(rejected is FinancialInsightsUseCase.Preparation.Rejected)
        assertTrue(
            (rejected as FinancialInsightsUseCase.Preparation.Rejected).result
                is FinancialInsightsResult.Error,
        )
    }
}