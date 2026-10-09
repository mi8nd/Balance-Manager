package com.personal.balance.domain.ai.orchestration

import java.util.Locale

/**
 * Deterministic first gate of the orchestrator. It recognizes ONLY trivial
 * conversational intents — greetings, thanks, farewells, help, and identity —
 * and answers them locally with a canned reply, so those cost zero AI calls and
 * zero data loads.
 *
 * IMPORTANT: This is NOT the capability-selection engine. Any message that even
 * glances at financial content (money words, categories, transactions, savings)
 * or that does not match a conversational intent is passed through to the
 * [AiRequestPlanner]. Being conservative here is intentional: routing an
 * ambiguous message to the model is cheap and correct; answering it locally is
 * wrong.
 */
object ConversationalTriage {

    private val FINANCIAL_CUES = listOf(
        "spend", "spent", "spending", "expense", "expenses", "income", "balance",
        "budget", "category", "categories", "money", "salary", "rent", "food",
        "transaction", "transactions", "buy", "bought", "purchase", "purchased",
        "savings", "saved", "saving", "earn", "earned", "earning", "cost", "price",
        "مصروف", "دخل", "رصيد",
    )

    private val GREETING_WORDS = setOf("hi", "hello", "hey", "yo")
    private val GREETING_PHRASES = setOf(
        "good morning", "good afternoon", "good evening", "good day",
        "hello there", "hi there", "hey there", "what's up", "whats up",
        "السلام عليكم", "مرحبا", "مرحباً", "اهلا", "أهلا", "أهلاً",
    )
    private val THANKS = setOf(
        "thanks", "thank you", "thank you very much", "thanks a lot",
        "much appreciated", "thx", "ty", "شكرا", "شكراً",
    )
    private val FAREWELL = setOf(
        "bye", "goodbye", "bye bye", "see you", "see you later",
        "good night", "goodnight", "catch you later", "مع السلامة",
    )

    private val IDENTITY_REPLY =
        "I'm the AI Financial Assistant in the Balance app. I look at your recent " +
            "transactions, computed on your device, to answer questions about your money."
    private val HELP_REPLY =
        "I can answer questions about your spending and income for this month and last " +
            "month — how much you spent, which categories lead, how this month compares, " +
            "what patterns stand out, or about your savings goals. Just type a question."
    private val GREETING_REPLY =
        "Hello! I'm your AI Financial Assistant. Ask me about your spending, income, " +
            "balance, or anything else about your money."
    private val THANKS_REPLY =
        "You're welcome! Anything else about your money I can help with?"
    private val FAREWELL_REPLY =
        "Goodbye! Come back any time you want to look at your money."

    /**
     * @return a local reply when [question] is a trivial conversational intent,
     * or null when it must be routed (financial cues, or not conversational).
     */
    fun localReply(question: String): String? {
        val trimmed = question.trim()
        if (trimmed.isBlank()) return null
        val normalized = trimmed.lowercase(Locale.US).trim().trimEnd('.', '!', '?', '،').trim()
        if (hasFinancialCue(normalized)) return null

        return when {
            isIdentity(normalized) -> IDENTITY_REPLY
            isHelp(normalized) -> HELP_REPLY
            isGreeting(normalized) -> GREETING_REPLY
            normalized in THANKS || normalized.startsWith("thank you") || normalized.startsWith("thanks ") ->
                THANKS_REPLY
            isFarewell(normalized) -> FAREWELL_REPLY
            else -> null
        }
    }

    /** True when the text contains a financial cue word (shared with [PlanSanitizer]). */
    fun hasFinancialCue(text: String): Boolean = FINANCIAL_CUES.any { text.contains(it) }

    private fun isIdentity(text: String): Boolean =
        text.contains("who are you") ||
            text.contains("what are you") ||
            text.contains("what is your name") ||
            text.contains("what's your name")

    private fun isHelp(text: String): Boolean =
        text == "help" ||
            text.contains("help me") ||
            text.contains("help with") ||
            text.contains("what can you do") ||
            text.contains("what do you do") ||
            text.contains("how do i use") ||
            text.contains("how does this work") ||
            text.contains("how does this app work") ||
            text.contains("what should i ask")

    private fun isGreeting(text: String): Boolean {
        if (text in GREETING_PHRASES) return true
        if (text in GREETING_WORDS) return true
        val words = text.split(' ')
        if (words.size > 4) return false
        val first = words.firstOrNull() ?: return false
        // Leaders like "Hello," or "Hey!" become the plain greeting word.
        val cleanFirst = first.trimEnd(',', ';', ':', '.', '!', '?')
        return cleanFirst in GREETING_WORDS
    }

    private fun isFarewell(text: String): Boolean =
        text in FAREWELL || text.startsWith("bye") || text.startsWith("goodbye")
}