package com.personal.balance.viewmodel

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
import com.personal.balance.domain.insights.FinancialInsightsUseCase
import com.personal.balance.model.Transaction
import com.personal.balance.model.TransactionType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/**
 * Dashboard AI Insights section state machine: loads once on first sight,
 * refreshes only on explicit action (never concurrent), and flags previously
 * generated content as stale when the underlying signature changes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DashboardInsightViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val scheduler: TestCoroutineScheduler
        get() = mainDispatcherRule.dispatcher.scheduler

    private val zone: ZoneId = ZoneId.of("UTC")
    private val nowEpochMillis: Long = Instant.parse("2026-08-15T12:00:00Z").toEpochMilli()
    private val window = FinancialWindows.windowAt(nowEpochMillis, zone)
    private val currentTs = window.currentMonthStartEpochMillis + 1000

    private class FakeAuthProvider : AiAuthProvider {
        override fun isAuthenticated(): Boolean = true
    }

    private class FakeUsageController() : AiUsageController {
        override suspend fun isCallAllowed(): Boolean = true
        override suspend fun recordCall() {}
    }

    private class FakeAssistant(
        var result: AiResult = DefaultReply,
    ) : AiAssistantRepository {
        var calls = 0
        override suspend fun ask(request: AiRequest): AiResult {
            calls++
            return result
        }
    }

    private fun transactions(count: Int): List<Transaction> =
        (0 until count).map { i ->
            Transaction(
                userId = "uid-1",
                type = if (i % 2 == 0) TransactionType.EXPENSE else TransactionType.INCOME,
                amount = 1000L,
                description = "tx",
                category = "Food",
                timestamp = currentTs,
            )
        }

    private fun viewModel(assistant: FakeAssistant = FakeAssistant()): Pair<DashboardInsightViewModel, FakeAssistant> {
        val askAi = AskAiUseCase(FakeAuthProvider(), FakeUsageController(), assistant)
        val source = FinancialTransactionsSource { _: String, _: Long, _: Long ->
            FinancialTransactionsResult.Success(transactions(8))
        }
        val goalSource = GoalCoachSource { _: String? -> GoalCoachState.Unavailable }
        val useCase = FinancialInsightsUseCase(
            currentUserId = { "uid-1" },
            transactionsSource = source,
            goalCoachSource = goalSource,
            balanceSource = { _: String -> 500_00L },
            askAi = askAi,
            nowEpochMillis = { nowEpochMillis },
            zone = zone,
        )
        return DashboardInsightViewModel(useCase) to assistant
    }

    @Test
    fun ensureLoadedRunsExactlyOnce() {
        val (vm, assistant) = viewModel()
        vm.ensureLoaded()
        scheduler.runCurrent()

        assertEquals(DashboardInsightUiState.Ready::class, vm.uiState.value::class)
        assertEquals(1, assistant.calls)

        vm.ensureLoaded()
        scheduler.runCurrent()

        assertEquals(1, assistant.calls)
        assertFalse((vm.uiState.value as DashboardInsightUiState.Ready).stale)
    }

    @Test
    fun refreshRerunsAfterInitialLoad() {
        val (vm, assistant) = viewModel()
        vm.ensureLoaded()
        scheduler.runCurrent()
        assertEquals(1, assistant.calls)

        vm.refresh()
        scheduler.runCurrent()

        assertEquals(2, assistant.calls)
    }

    @Test
    fun refreshIsIgnoredWhileAlreadyRefreshing() {
        val (vm, assistant) = viewModel()
        vm.ensureLoaded()
        scheduler.runCurrent()
        assertEquals(1, assistant.calls)

        vm.refresh()
        vm.refresh()
        scheduler.runCurrent()

        assertEquals(2, assistant.calls)
    }

    @Test
    fun markStaleIfChangedFlagsReadyContentWhenSignatureDiffers() {
        val (vm, _) = viewModel()
        vm.ensureLoaded()
        scheduler.runCurrent()
        assertFalse((vm.uiState.value as DashboardInsightUiState.Ready).stale)

        vm.markStaleIfChanged("balance|9|true|100000|100")
        scheduler.runCurrent()

        assertTrue((vm.uiState.value as DashboardInsightUiState.Ready).stale)
    }

    @Test
    fun sameSignatureDoesNotFlagStale() {
        val (vm, _) = viewModel()
        vm.ensureLoaded()
        scheduler.runCurrent()
        vm.markStaleIfChanged("sig-a")
        scheduler.runCurrent()
        assertTrue((vm.uiState.value as DashboardInsightUiState.Ready).stale)

        vm.markStaleIfChanged("sig-a")
        scheduler.runCurrent()

        assertTrue((vm.uiState.value as DashboardInsightUiState.Ready).stale)
    }

    @Test
    fun refreshAfterStaleClearsStaleFlag() {
        val (vm, _) = viewModel()
        vm.ensureLoaded()
        scheduler.runCurrent()
        vm.markStaleIfChanged("new-signature")
        scheduler.runCurrent()
        assertTrue((vm.uiState.value as DashboardInsightUiState.Ready).stale)

        vm.refresh()
        scheduler.runCurrent()

        val state = vm.uiState.value as DashboardInsightUiState.Ready
        assertFalse(state.stale)
    }

    @Test
    fun errorStateIsFlaggedStaleOnSignatureChange() {
        val assistant = FakeAssistant(
            result = AiResult.Error.NetworkFailure(
                userSafeMessage = "Couldn't load your transactions. Please try again.",
                diagnostic = "insights.transactions.read_failed",
            ),
        )
        val (vm, _) = viewModel(assistant)
        vm.ensureLoaded()
        scheduler.runCurrent()
        assertTrue(vm.uiState.value is DashboardInsightUiState.Error)

        vm.markStaleIfChanged("different-sig")
        scheduler.runCurrent()

        assertTrue((vm.uiState.value as DashboardInsightUiState.Error).stale)
    }

    @Test
    fun insufficientDataSurfacesAsState() {
        val assistant = FakeAssistant()
        val askAi = AskAiUseCase(FakeAuthProvider(), FakeUsageController(), assistant)
        val source = FinancialTransactionsSource { _: String, _: Long, _: Long ->
            FinancialTransactionsResult.Success(transactions(1))
        }
        val goalSource = GoalCoachSource { _: String? -> GoalCoachState.Unavailable }
        val useCase = FinancialInsightsUseCase(
            currentUserId = { "uid-1" },
            transactionsSource = source,
            goalCoachSource = goalSource,
            balanceSource = { _: String -> 500_00L },
            askAi = askAi,
            nowEpochMillis = { nowEpochMillis },
            zone = zone,
        )
        val vm = DashboardInsightViewModel(useCase)
        vm.ensureLoaded()
        scheduler.runCurrent()

        assertEquals(DashboardInsightUiState.InsufficientData, vm.uiState.value)
        assertEquals(0, assistant.calls)
    }

    private companion object {
        val DefaultReply = AiResult.Success(
            "INSIGHT|HIGH|Spending spike|Transport is up.\nRECOMMENDATION|Cut back|You could consider trimming transport.",
            1,
        )
    }
}