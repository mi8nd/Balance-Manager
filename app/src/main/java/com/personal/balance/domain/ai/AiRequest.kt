package com.personal.balance.domain.ai

/**
 * One AI prompt. [structuredFacts] are values computed deterministically by the
 * app (balances, totals, date ranges, goal progress). Gemini is never given
 * database access; it must base its answer on these facts only.
 *
 * [additionalInstructions] is optional, question-scoped guidance appended to the
 * prompt (for example a bounded financial context's instruction block). It must
 * never contain secrets; it is sent to the model as plain text.
 *
 * [conversation] is an optional, bounded list of recent turns so follow-ups like
 * "What about food?" resolve against prior context. It is always empty for
 * one-shot requests (smoke test, legacy callers).
 */
data class AiRequest(
    val question: String,
    val structuredFacts: Map<String, String> = emptyMap(),
    val additionalInstructions: String? = null,
    val conversation: List<ConversationTurn> = emptyList(),
) {
    init {
        require(question.isNotBlank()) { "question must not be blank" }
    }
}