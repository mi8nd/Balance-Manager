package com.personal.balance.viewmodel

import com.personal.balance.domain.ai.AiResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description

@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(
    val dispatcher: TestDispatcher = StandardTestDispatcher(),
) : TestWatcher() {
    override fun starting(description: Description) {
        Dispatchers.setMain(dispatcher)
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}

/**
 * Controllable fake for `suspend (String) -> AiResult`. Each submission pops the
 * next queued gate and suspends until the test completes it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
private class FakeAssistant {
    private val pending = ArrayDeque<CompletableDeferred<AiResult>>()

    val ask: suspend (String) -> AiResult = { pending.removeFirst().await() }

    fun nextGate(): CompletableDeferred<AiResult> {
        val gate = CompletableDeferred<AiResult>()
        pending.addLast(gate)
        return gate
    }

    fun gateForCurrentQuestion(): CompletableDeferred<AiResult> {
        check(pending.isNotEmpty()) { "no in-flight question to gate" }
        return pending.last()
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class FinancialAssistantViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val scheduler: TestCoroutineScheduler
        get() = mainDispatcherRule.dispatcher.scheduler

    private fun viewModelWith(assistant: FakeAssistant): FinancialAssistantViewModel =
        FinancialAssistantViewModel(askFinancialQuestion = assistant.ask)

    @Test
    fun userMessageAppendedImmediately_withRunningFlagAndClearedInput() {
        val assistant = FakeAssistant()
        val viewModel = viewModelWith(assistant)
        val gate = assistant.nextGate()

        viewModel.onInputChange("  How much did I spend?  ")
        viewModel.ask()
        scheduler.runCurrent()

        // The request is still in flight, but the user's bubble is already visible.
        val state = viewModel.uiState.value
        assertTrue(state.running)
        assertTrue(state.input.isEmpty())
        assertEquals(1, state.messages.size)
        assertEquals(ChatMessage.User(0, "How much did I spend?"), state.messages[0])

        gate.complete(AiResult.Success("A reply", 5))
        scheduler.advanceUntilIdle()
    }

    @Test
    fun success_appendsAssistantMessage_preservingOrder() {
        val assistant = FakeAssistant()
        val viewModel = viewModelWith(assistant)
        val gate = assistant.nextGate()

        viewModel.onInputChange("Compare spending?")
        viewModel.ask()
        scheduler.runCurrent()

        gate.complete(AiResult.Success("You spent more this month.", 12))
        scheduler.advanceUntilIdle()

        val messages = viewModel.uiState.value.messages
        assertEquals(2, messages.size)
        assertEquals(ChatMessage.User(0, "Compare spending?"), messages[0])
        assertEquals(ChatMessage.Assistant(1, "You spent more this month."), messages[1])
        assertFalse(viewModel.uiState.value.running)
    }

    @Test
    fun error_appendsSafeMessage_preservingConversation() {
        val assistant = FakeAssistant()
        val viewModel = viewModelWith(assistant)
        val gate = assistant.nextGate()

        viewModel.onInputChange("What happened?")
        viewModel.ask()
        scheduler.runCurrent()

        gate.complete(
            AiResult.Error.NetworkFailure(
                userSafeMessage = "Couldn't load your recent transactions. Please try again.",
                diagnostic = "transactions.read_failed",
            ),
        )
        scheduler.advanceUntilIdle()

        assertEquals(listOf(
            ChatMessage.User(0, "What happened?"),
            ChatMessage.Error(
                id = 1,
                text = "Couldn't load your recent transactions. Please try again.",
                questionToRetry = "What happened?",
            ),
        ), viewModel.uiState.value.messages)
        assertFalse(viewModel.uiState.value.running)
    }

    @Test
    fun retry_dropsErrorAndReusesSameUserQuestion_withoutDuplicateUserBubble() {
        val assistant = FakeAssistant()
        val viewModel = viewModelWith(assistant)

        val firstGate = assistant.nextGate()
        viewModel.onInputChange("How am I doing?")
        viewModel.ask()
        scheduler.runCurrent()
        firstGate.complete(AiResult.Error.RateLimited("Too many requests.", "budget"))
        scheduler.advanceUntilIdle()

        val errorId = (viewModel.uiState.value.messages.last() as ChatMessage.Error).id
        val retryGate = assistant.nextGate()
        viewModel.retryAfterError(errorId)
        scheduler.runCurrent()

        // Error dropped; the user question remains (no duplicate bubble); running.
        val between = viewModel.uiState.value.messages
        assertEquals(1, between.size)
        assertEquals(ChatMessage.User(0, "How am I doing?"), between[0])
        assertTrue(viewModel.uiState.value.running)

        retryGate.complete(AiResult.Success("You're on track.", 4))
        scheduler.advanceUntilIdle()

        assertEquals(listOf(
            ChatMessage.User(0, "How am I doing?"),
            ChatMessage.Assistant(2, "You're on track."),
        ), viewModel.uiState.value.messages)
        assertFalse(viewModel.uiState.value.running)
    }

    @Test
    fun secondQuestion_isBlockedWhileRunning() {
        val assistant = FakeAssistant()
        val viewModel = viewModelWith(assistant)
        val gate = assistant.nextGate()

        viewModel.onInputChange("First?")
        viewModel.ask()
        scheduler.runCurrent()

        viewModel.onInputChange("Second?")
        viewModel.ask()
        scheduler.runCurrent()

        assertEquals(1, viewModel.uiState.value.messages.size)

        gate.complete(AiResult.Success("One answer", 2))
        scheduler.advanceUntilIdle()
    }

    @Test
    fun blankQuestion_isIgnored() {
        val assistant = FakeAssistant()
        val viewModel = viewModelWith(assistant)

        viewModel.onInputChange("   ")
        viewModel.ask()
        scheduler.runCurrent()

        assertFalse(viewModel.uiState.value.running)
        assertTrue(viewModel.uiState.value.messages.isEmpty())
    }

    @Test
    fun suggestionSendsDirectly_clearingParkedInput() {
        val assistant = FakeAssistant()
        val viewModel = viewModelWith(assistant)
        val gate = assistant.nextGate()

        viewModel.onInputChange("unrelated parked input")
        viewModel.askSuggestion("What is my current balance?")
        scheduler.runCurrent()

        val state = viewModel.uiState.value
        assertTrue(state.running)
        assertEquals(1, state.messages.size)
        assertEquals(ChatMessage.User(0, "What is my current balance?"), state.messages[0])

        gate.complete(AiResult.Success("It is 250.00 EGP.", 6))
        scheduler.advanceUntilIdle()
    }

    @Test
    fun consecutiveConversation_messagesHaveDistinctIds() {
        val assistant = FakeAssistant()
        val viewModel = viewModelWith(assistant)

        val gate1 = assistant.nextGate()
        viewModel.askSuggestion("Q1")
        scheduler.runCurrent()
        gate1.complete(AiResult.Success("A1", 1))
        scheduler.advanceUntilIdle()

        val gate2 = assistant.nextGate()
        viewModel.askSuggestion("Q2")
        scheduler.runCurrent()
        gate2.complete(AiResult.Success("A2", 1))
        scheduler.advanceUntilIdle()

        assertEquals(
            listOf(0L, 1L, 2L, 3L),
            viewModel.uiState.value.messages.map { it.id },
        )
    }
}