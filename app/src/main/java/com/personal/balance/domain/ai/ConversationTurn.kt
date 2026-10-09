package com.personal.balance.domain.ai

/**
 * One prior message in the chat, summarized before it is handed to the model.
 *
 * Turns are bounded by the ViewModel (a fixed number of most-recent messages,
 * each truncated), so follow-ups like "What about food?" have context without
 * unbounded prompt growth. Text here is user-entered or assistant prose; it must
 * never carry UID/doc ids/tokens/secrets (production sanitizers never include
 * them).
 */
data class ConversationTurn(
    val sender: ChatSender,
    val text: String,
)

enum class ChatSender {
    USER,
    ASSISTANT,
}