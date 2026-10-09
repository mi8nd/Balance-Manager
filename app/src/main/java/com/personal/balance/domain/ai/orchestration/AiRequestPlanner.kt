package com.personal.balance.domain.ai.orchestration

import com.personal.balance.domain.ai.AiRequest
import com.personal.balance.domain.ai.AiResult
import com.personal.balance.domain.ai.AskAiUseCase
import com.personal.balance.domain.ai.ChatSender
import com.personal.balance.domain.ai.ConversationTurn

/**
 * Outcome of one semantic planning round: either a raw [AiRequestPlanDraft]
 * (never sanitized here — [PlanSanitizer] does that), or the routing call
 * failed with an [AiResult.Error] (authentic errors are never swallowed into a
 * blind general fallback).
 */
sealed interface PlanningOutcome {
    data class Planned(val draft: AiRequestPlanDraft) : PlanningOutcome
    data class Failed(val error: AiResult.Error) : PlanningOutcome
}

/**
 * Plans one user message: understands WHAT the user is trying to do (intent),
 * whether it needs their own financial data at all (financial relevance), which
 * fact groups to prepare (data needs), whether prior turns are required, and
 * how confident the interpretation is. Capability selection is deliberately NOT
 * the model's job — the app derives it from the plan.
 */
interface AiRequestPlanner {
    suspend fun plan(userQuestion: String, conversation: List<ConversationTurn>): PlanningOutcome
}

/**
 * Production planner: a single compact call through the existing [AskAiUseCase]
 * gate (auth + the app-wide shared usage budget). The model is asked to reply
 * with ONLY one strict JSON object describing the intent and data needs
 * (plus advisory capabilities / confidence), which [AiRequestPlanParser]
 * validates. The conversation is embedded in the prompt under clearly separated
 * "Previous conversation:" / "Current user message:" headings, so follow-ups
 * resolve against prior turns; the same conversation is never injected as facts.
 *
 * A malformed or unrecognizable reply is NOT a failure: it yields an empty
 * draft, which [PlanSanitizer] fills from deterministic question cues — so a
 * clearly financial question whose model output is garbage still gets routed to
 * the financial capabilities instead of being dumped on the general assistant.
 */
class GeminiAiRequestPlanner(
    private val askAi: AskAiUseCase,
) : AiRequestPlanner {

    override suspend fun plan(
        userQuestion: String,
        conversation: List<ConversationTurn>,
    ): PlanningOutcome {
        val request = AiRequest(
            question = buildRoutingPrompt(userQuestion, conversation),
            structuredFacts = emptyMap(),
            additionalInstructions = ROUTING_INSTRUCTIONS,
            // The conversation is already embedded in the prompt under its own
            // headings; leaving this empty stops the repository from also
            // appending a second copy.
            conversation = emptyList(),
        )
        return when (val result = askAi(request)) {
            is AiResult.Success -> PlanningOutcome.Planned(
                AiRequestPlanParser.parse(result.text) ?: AiRequestPlanDraft(),
            )

            is AiResult.Error -> PlanningOutcome.Failed(result)
        }
    }

    private fun buildRoutingPrompt(question: String, conversation: List<ConversationTurn>): String =
        buildString {
            appendLine("Previous conversation (oldest first):")
            if (conversation.isEmpty()) {
                appendLine("(none)")
            } else {
                conversation.forEach { turn ->
                    when (turn.sender) {
                        ChatSender.USER -> appendLine("User: ${turn.text}")
                        ChatSender.ASSISTANT -> appendLine("Assistant: ${turn.text}")
                    }
                }
            }
            appendLine()
            append("Current user message: ").append(question)
        }

    companion object {
        private val ROUTING_INSTRUCTIONS = """
            You are the intent planner inside the Balance personal finance app. Given the bounded conversation and the user's latest message, decide WHAT the user is trying to do (intent) and WHICH financial facts the app must prepare (data needs). You are NOT picking a destination screen and NOT choosing where to navigate.
            Reply with ONLY one JSON object and no other text, in this exact shape:
            {"intent": "<one intent>", "financial_relevance": <true or false>, "data_needs": ["...", ...], "capabilities": ["...", ...], "needs_conversation_context": <true or false>, "confidence": "high"|"medium"|"low"}

            intent must be exactly one of these values:
            - get_current_spending: how much the user spent this period.
            - get_current_income: how much the user earned this period.
            - get_current_balance: the stored account balance.
            - compare_periods: this month vs last month totals.
            - explain_spending_change: why a change in their spending happened.
            - identify_spending_patterns: patterns, trends, habits, anomalies, outliers, recurring expenses, "what stands out".
            - identify_largest_expense: the largest/smallest expense or the top category.
            - summarize_financial_activity: a broad overview of the user's finances ("How am I doing this month?").
            - ask_for_spending_advice: advice grounded in the user's OWN spending behavior ("What habits should I change?", "Where can I cut back?").
            - goal_progress: the progress of a savings goal.
            - goal_advice: advice about reaching a savings goal.
            - general_conversation: anything that does NOT need the user's own financial numbers (explanations, generic advice, casual chat).
            - ambiguous: you genuinely cannot tell what the user wants.

            financial_relevance: true when answering needs ANY of the user's OWN financial data (their transactions, balance, or goals). A message can be financially relevant without the usual money words — "What about food?", "Why?", "Is that a lot?", "And last month?" all need the user's numbers. NEVER set this false just because the question has no financial vocabulary.

            data_needs: only these known groups — current_period_totals, previous_period_totals, category_breakdown, largest_smallest, recent_transactions, transaction_patterns, current_balance, goal_data. List only what is needed to answer; unknown values are ignored by the app. An empty list [] means the capability receives its FULL prepared financial context (right for broad summaries).

            capabilities: advisory hints only (financial_analysis, transaction_intelligence, goal_coach, general_assistant). The app computes the final set from the intent, so this may be an empty list.

            needs_conversation_context: true when the latest message ONLY makes sense together with the earlier turns (pronouns, "what about food?", "was that bad?", "and last month?").

            confidence: "high" when you are sure about every field, "medium" when reasonably sure, "low" when guessing or genuinely torn.

            Rules:
            - The message under "Current user message:" is the ONLY thing you plan for; the turns under "Previous conversation:" exist only to resolve references.
            - Follow-ups like "What about food?", "Why?", "And last month?" must reuse the subject from the conversation; set needs_conversation_context true and plan for that subject.
            - Never route a financial question to general_conversation just because it lacks financial vocabulary.
            - Use a semantic boundary: totals/amounts/comparisons/largest/smallest/balance = the financial-amount capability; patterns/habits/trends/behavior = the pattern capability; both when the question is quantitative AND behavioral; goals only when the question is about the user's tracked savings goal; general only when no financial data is needed.
            - Never invent intents, capabilities, or data needs outside the lists above.
        """.trimIndent()
    }
}