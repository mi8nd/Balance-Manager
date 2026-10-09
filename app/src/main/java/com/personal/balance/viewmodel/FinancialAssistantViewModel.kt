package com.personal.balance.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.personal.balance.data.FirebaseFinancialTransactionsSource
import com.personal.balance.data.FirestoreGoalCoachSource
import com.personal.balance.data.FirestoreUserRepository
import com.personal.balance.data.UserConfigResult
import com.personal.balance.data.ai.FirebaseAiAssistantRepository
import com.personal.balance.data.ai.FirebaseAiAuthProvider
import com.personal.balance.data.ai.SharedAiUsageController
import com.personal.balance.domain.ai.AiResult
import com.personal.balance.domain.ai.AskAiUseCase
import com.personal.balance.domain.ai.ChatSender
import com.personal.balance.domain.ai.ConversationTurn
import com.personal.balance.domain.ai.orchestration.AiOrchestrationResult
import com.personal.balance.domain.ai.orchestration.AiOrchestrator
import com.personal.balance.domain.ai.orchestration.GeminiAiRequestPlanner
import com.personal.balance.domain.finance.TransactionInsight
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * One message in the conversation. [ChatMessage.id] is a monotonically increasing
 * session id used as a stable key for LazyColumn items.
 */
sealed interface ChatMessage {
    val id: Long

    data class User(override val id: Long, val text: String) : ChatMessage

    data class Assistant(override val id: Long, val text: String, val insights: List<TransactionInsight> = emptyList()) : ChatMessage

    data class Error(
        override val id: Long,
        val text: String,
        val questionToRetry: String,
    ) : ChatMessage
}

data class FinancialAssistantUiState(
    val messages: List<ChatMessage> = emptyList(),
    val running: Boolean = false,
    val input: String = "",
)

/**
 * Production "AI Financial Assistant". Routes a question through the intelligent
 * [AiOrchestrator] (or, in test mode, through a user-supplied legacy lambda) and
 * appends the reply to the session conversation. The conversation is session-only;
 * nothing here touches Firestore or the AI SDK directly.
 *
 * Legacy callers (e.g. the existing unit test suite) may provide
 * [askFinancialQuestion] directly, bypassing the orchestrator; in production it
 * is null and the default orchestrator builds its own infrastructure lazily so
 * test classloading never touches Firebase.
 */
class FinancialAssistantViewModel(
    private val askFinancialQuestion: (suspend (String) -> AiResult)? = null,
    private val lazyOrchestrator: (() -> AiOrchestrator)? = null,
) : ViewModel() {

    private val _uiState = MutableStateFlow(FinancialAssistantUiState())
    val uiState: StateFlow<FinancialAssistantUiState> = _uiState.asStateFlow()

    private var nextMessageId: Long = 0L
    private var builtOrchestrator: AiOrchestrator? = null

    fun onInputChange(value: String) {
        _uiState.update { it.copy(input = value) }
    }

    fun ask() {
        val question = _uiState.value.input.trim()
        if (question.isBlank() || _uiState.value.running) return
        submit(question)
    }

    fun askSuggestion(question: String) {
        if (question.isBlank() || _uiState.value.running) return
        submit(question)
    }

    fun retryAfterError(messageId: Long) {
        if (_uiState.value.running) return
        val question = _uiState.value.messages
            .firstOrNull { it.id == messageId && it is ChatMessage.Error }
            ?.let { it as ChatMessage.Error }
            ?.questionToRetry
            ?: return
        _uiState.update { state ->
            state.copy(
                running = true,
                messages = state.messages.filterNot { it.id == messageId },
            )
        }
        runQuestion(question)
    }

    private fun submit(question: String) {
        val userMessageId = newMessageId()
        _uiState.update {
            it.copy(
                running = true,
                input = "",
                messages = it.messages + ChatMessage.User(userMessageId, question),
            )
        }
        runQuestion(question)
    }

    private fun runQuestion(question: String) {
        viewModelScope.launch {
            val result = executeQuestion(question)
            _uiState.update { state ->
                when (result) {
                    is AiOrchestrationResult.Text -> state.copy(
                        running = false,
                        messages = state.messages + ChatMessage.Assistant(
                            newMessageId(),
                            result.text,
                            result.insights,
                        ),
                    )

                    is AiOrchestrationResult.Error -> state.copy(
                        running = false,
                        messages = state.messages + ChatMessage.Error(
                            id = newMessageId(),
                            text = result.error.userSafeMessage,
                            questionToRetry = question,
                        ),
                    )
                }
            }
        }
    }

    private suspend fun executeQuestion(question: String): AiOrchestrationResult {
        val legacy = askFinancialQuestion
        if (legacy != null) {
            return when (val raw = legacy(question)) {
                is AiResult.Success -> AiOrchestrationResult.Text(raw.text)
                is AiResult.Error -> AiOrchestrationResult.Error(raw)
                null -> AiOrchestrationResult.Error(
                    AiResult.Error.UnexpectedFailure(
                        "Something went wrong. Please try again later.",
                        "ai.legacy.null",
                    ),
                )
            }
        }
        val orch = lazyOrchestrator?.invoke() ?: builtOrchestrator ?: buildDefaultOrchestrator().also {
            builtOrchestrator = it
        }
        return orch.route(question, recentTurns())
    }

    private fun recentTurns(): List<ConversationTurn> {
        val messages = _uiState.value.messages
        if (messages.isEmpty()) return emptyList()
        return messages
            .dropLast(1)
            .mapNotNull { message ->
                when (message) {
                    is ChatMessage.User ->
                        ConversationTurn(ChatSender.USER, message.text.trim().take(TURN_TEXT_LIMIT))

                    is ChatMessage.Assistant ->
                        ConversationTurn(ChatSender.ASSISTANT, message.text.trim().take(TURN_TEXT_LIMIT))

                    is ChatMessage.Error -> null
                }
            }
            .takeLast(MAX_CONVERSATION_TURNS)
    }

    private fun newMessageId(): Long = nextMessageId++

    companion object {
        val SUGGESTIONS = listOf(
            "How much did I spend this month?",
            "Which category did I spend the most on?",
            "What patterns stand out in my spending?",
            "What was my largest expense this month?",
            "What is my current balance?",
            "How does this month compare with last month?",
            "Do I have a savings goal tracked?",
            "How much is left on my savings goal?",
        )

        private const val MAX_CONVERSATION_TURNS = 6
        private const val TURN_TEXT_LIMIT = 200

        private fun buildDefaultOrchestrator(): AiOrchestrator {
            val askAi = AskAiUseCase(
                authProvider = FirebaseAiAuthProvider(),
                usageController = SharedAiUsageController.instance,
                assistant = FirebaseAiAssistantRepository(),
            )
            return AiOrchestrator(
                currentUserId = { FirebaseAuth.getInstance().currentUser?.uid },
                transactionsSource = FirebaseFinancialTransactionsSource(),
                planner = GeminiAiRequestPlanner(askAi),
                askAi = askAi,
                balanceSource = { uid ->
                    val userRepo = FirestoreUserRepository()
                    val result = userRepo.getUserConfig(uid)
                    if (result is UserConfigResult.Success) result.config.currentBalance else null
                },
                goalCoachSource = FirestoreGoalCoachSource(),
            )
        }
    }
}