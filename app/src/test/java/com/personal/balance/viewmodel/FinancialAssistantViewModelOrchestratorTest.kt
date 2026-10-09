package com.personal.balance.viewmodel

import com.personal.balance.domain.ai.AiAssistantRepository
import com.personal.balance.domain.ai.AiAuthProvider
import com.personal.balance.domain.ai.AiRequest
import com.personal.balance.domain.ai.AiResult
import com.personal.balance.domain.ai.AiUsageController
import com.personal.balance.domain.ai.AskAiUseCase
import com.personal.balance.domain.ai.ChatSender
import com.personal.balance.domain.ai.ConversationTurn
import com.personal.balance.domain.ai.orchestration.AiCapability
import com.personal.balance.domain.ai.orchestration.AiIntent
import com.personal.balance.domain.ai.orchestration.AiOrchestrationResult
import com.personal.balance.domain.ai.orchestration.AiOrchestrator
import com.personal.balance.domain.ai.orchestration.AiRequestPlan
import com.personal.balance.domain.ai.orchestration.AiRequestPlanDraft
import com.personal.balance.domain.ai.orchestration.AiRequestPlanner
import com.personal.balance.domain.ai.orchestration.AiRoutingConfidence
import com.personal.balance.domain.ai.orchestration.PlanSanitizer
import com.personal.balance.domain.ai.orchestration.PlanningOutcome
import com.personal.balance.domain.finance.FinancialTransactionsResult
import com.personal.balance.domain.finance.FinancialTransactionsSource
import kotlinx.coroutines.CompletableDeferred
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
 * Minimal fakes to build a real [AiOrchestrator] whose planning is
 * deterministic and whose outputs can be pre-seeded.
 */
@OptIn(ExperimentalCoroutinesApi::class)
private class TestPlanner(
    private val intent: AiIntent,
    private val relevance: Boolean = true,
    private val confident: AiRoutingConfidence = AiRoutingConfidence.HIGH,
) : AiRequestPlanner {
    var calls = 0
    var lastConversation: List<ConversationTurn> = emptyList()

    override suspend fun plan(
        userQuestion: String,
        conversation: List<ConversationTurn>,
    ): PlanningOutcome {
        calls++
        lastConversation = conversation
        return PlanningOutcome.Planned(
            AiRequestPlanDraft(intent = intent, financialRelevance = relevance, confidence = confident),
        )
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
private class QueuedAssistant(
    private val responses: List<AiResult>,
) : AiAssistantRepository {
    val requests = mutableListOf<AiRequest>()
    override suspend fun ask(request: AiRequest): AiResult {
        requests.add(request)
        return responses[requests.size - 1]
    }
}

private object AlwaysPermittedAuth : AiAuthProvider {
    override fun isAuthenticated() = true
}

private object NoOpUsage : AiUsageController {
    var calls = 0
    override suspend fun isCallAllowed() = true
    override suspend fun recordCall() { calls++ }
}

@OptIn(ExperimentalCoroutinesApi::class)
class FinancialAssistantViewModelOrchestratorTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val scheduler: TestCoroutineScheduler
        get() = mainDispatcherRule.dispatcher.scheduler

    private val zone = ZoneId.of("UTC")
    private val nowEpochMillis = Instant.parse("2026-08-15T12:00:00Z").toEpochMilli()

    private fun tx(
        amount: Long,
        timestamp: Long,
        category: String = "Food",
    ) = com.personal.balance.model.Transaction(
        userId = "uid-1",
        type = com.personal.balance.model.TransactionType.EXPENSE,
        amount = amount,
        description = "tx",
        category = category,
        timestamp = timestamp,
    )

    private fun vm(
        intent: AiIntent,
        assistantResponses: List<AiResult>,
        sourceResult: FinancialTransactionsResult = FinancialTransactionsResult.Success(emptyList()),
        relevance: Boolean = true,
        planner: TestPlanner = TestPlanner(intent, relevance),
    ): Pair<FinancialAssistantViewModel, TestPlanner> {
        val usage = NoOpUsage.also { it.calls = 0 }
        val askAi = AskAiUseCase(AlwaysPermittedAuth, usage, QueuedAssistant(assistantResponses))
        val orch = AiOrchestrator(
            currentUserId = { "uid-1" },
            transactionsSource = FinancialTransactionsSource { _, _, _ -> sourceResult },
            planner = planner,
            askAi = askAi,
            nowEpochMillis = { nowEpochMillis },
            zone = zone,
        )
        return FinancialAssistantViewModel(lazyOrchestrator = { orch }) to planner
    }

    @Test
    fun generalOnly_appendsAssistantText_noInsights() {
        val (vm, _) = vm(
            intent = AiIntent.GENERAL_CONVERSATION,
            relevance = false,
            assistantResponses = listOf(AiResult.Success("The best way to save is to track spending.", 4)),
        )

        vm.askSuggestion("How do I save money?")
        scheduler.runCurrent()
        scheduler.advanceUntilIdle()

        val messages = vm.uiState.value.messages
        assertEquals(2, messages.size)
        assertTrue(messages[1] is ChatMessage.Assistant)
        val assistant = messages[1] as ChatMessage.Assistant
        assertEquals("The best way to save is to track spending.", assistant.text)
        assertTrue(assistant.insights.isEmpty())
        assertFalse(vm.uiState.value.running)
    }

    @Test
    fun tiOnly_withInsights_appendsCards() {
        val tx1 = tx(150_00L, Instant.parse("2026-08-01T10:00:00Z").toEpochMilli())
        val tx2 = tx(200_00L, Instant.parse("2026-08-02T10:00:00Z").toEpochMilli())
        val (vm, _) = vm(
            intent = AiIntent.IDENTIFY_SPENDING_PATTERNS,
            assistantResponses = listOf(
                AiResult.Success(
                    "Your transactions show a shift.\nINSIGHT|HIGH|Food spike|Food spending rose 50%.\nINSIGHT|LOW|Stable|Rent held steady.",
                    12,
                ),
            ),
            sourceResult = FinancialTransactionsResult.Success(listOf(tx1, tx2)),
        )

        vm.askSuggestion("What patterns?")
        scheduler.runCurrent()
        scheduler.advanceUntilIdle()

        val messages = vm.uiState.value.messages
        assertEquals(2, messages.size)
        val assistant = messages[1] as ChatMessage.Assistant
        assertEquals(2, assistant.insights.size)
        assertEquals("Food spike", assistant.insights[0].title)
        assertEquals("Stable", assistant.insights[1].title)
        assertTrue(assistant.text.contains("shift"))
    }

    @Test
    fun tiOnlyNoData_localReply_noComposerCalls() {
        val (vm, planner) = vm(
            intent = AiIntent.IDENTIFY_SPENDING_PATTERNS,
            assistantResponses = emptyList(), // composer should never be called
            sourceResult = FinancialTransactionsResult.Success(emptyList()),
        )

        vm.askSuggestion("Any patterns?")
        scheduler.runCurrent()
        scheduler.advanceUntilIdle()

        val messages = vm.uiState.value.messages
        assertEquals(2, messages.size)
        val assistant = messages[1] as ChatMessage.Assistant
        assertTrue(assistant.text.contains("aren't any transactions"))
        assertTrue(assistant.insights.isEmpty())
        assertEquals(1, planner.calls)
    }

    @Test
    fun plannerError_appendsErrorBubble() {
        val usage = NoOpUsage.also { it.calls = 0 }
        val askAi = AskAiUseCase(AlwaysPermittedAuth, usage, QueuedAssistant(emptyList()))
        val planner = object : AiRequestPlanner {
            override suspend fun plan(
                userQuestion: String,
                conversation: List<ConversationTurn>,
            ): PlanningOutcome = PlanningOutcome.Failed(
                AiResult.Error.NetworkFailure("Router down", "net"),
            )
        }
        val orch = AiOrchestrator(
            currentUserId = { "uid-1" },
            transactionsSource = FinancialTransactionsSource { _, _, _ -> FinancialTransactionsResult.Success(emptyList()) },
            planner = planner,
            askAi = askAi,
            nowEpochMillis = { nowEpochMillis },
            zone = zone,
        )
        val vm = FinancialAssistantViewModel(lazyOrchestrator = { orch })

        vm.askSuggestion("Spend?")
        scheduler.runCurrent()
        scheduler.advanceUntilIdle()

        val messages = vm.uiState.value.messages
        assertEquals(2, messages.size)
        assertTrue(messages[1] is ChatMessage.Error)
        assertEquals("Router down", (messages[1] as ChatMessage.Error).text)
        assertEquals("Spend?", (messages[1] as ChatMessage.Error).questionToRetry)
    }

    @Test
    fun conversationTurns_excludeCurrentQuestion() {
        val (vm, planner) = vm(
            intent = AiIntent.GENERAL_CONVERSATION,
            relevance = false,
            assistantResponses = listOf(AiResult.Success("A", 1), AiResult.Success("B", 1)),
        )

        vm.askSuggestion("Q1")
        scheduler.runCurrent()
        scheduler.advanceUntilIdle()
        assertEquals(2, vm.uiState.value.messages.size)

        vm.askSuggestion("Q2")
        scheduler.runCurrent()
        scheduler.advanceUntilIdle()

        assertEquals(4, vm.uiState.value.messages.size)
        // Planner should have been called twice; second call gets conversation with Q1.
        assertEquals(2, planner.calls)
        // Q1 is a user turn in the conversation for the second request.
        assertTrue(planner.lastConversation.any { it.sender == ChatSender.USER && it.text == "Q1" })
        // Q2 (current question) must not appear in conversation.
        assertFalse(planner.lastConversation.any { it.text == "Q2" })
    }

    @Test
    fun retryAfterError_reusesSameQuestion() {
        val (vm, _) = vm(
            intent = AiIntent.GENERAL_CONVERSATION,
            relevance = false,
            assistantResponses = listOf(
                AiResult.Error.NetworkFailure("fail", "net"),
                AiResult.Success("A second attempt", 1),
            ),
        )

        vm.askSuggestion("How much?")
        scheduler.runCurrent()
        scheduler.advanceUntilIdle()

        val errorId = (vm.uiState.value.messages.last() as ChatMessage.Error).id
        vm.retryAfterError(errorId)
        scheduler.runCurrent()
        scheduler.advanceUntilIdle()

        val messages = vm.uiState.value.messages
        assertEquals(2, messages.size)
        assertTrue(messages[1] is ChatMessage.Assistant)
        assertEquals("A second attempt", (messages[1] as ChatMessage.Assistant).text)
        assertFalse(vm.uiState.value.running)
    }

    @Test
    fun financeOnly_messagesStayInOneFeed() {
        val tx1 = tx(150_00L, Instant.parse("2026-08-01T10:00:00Z").toEpochMilli())
        val (vm, _) = vm(
            intent = AiIntent.GET_CURRENT_SPENDING,
            assistantResponses = listOf(AiResult.Success("You spent 1500.00 EGP.", 6)),
            sourceResult = FinancialTransactionsResult.Success(listOf(tx1)),
        )

        vm.askSuggestion("How much did I spend?")
        scheduler.runCurrent()
        scheduler.advanceUntilIdle()

        val messages = vm.uiState.value.messages
        assertEquals(2, messages.size)
        val assistant = messages[1] as ChatMessage.Assistant
        assertEquals("You spent 1500.00 EGP.", assistant.text)
        assertTrue(assistant.insights.isEmpty())
        assertFalse(vm.uiState.value.running)
    }
}