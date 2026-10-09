package com.personal.balance.domain.ai.orchestration

import com.personal.balance.domain.ai.AiResult
import com.personal.balance.domain.finance.TransactionInsight

/**
 * The capabilities the orchestrator can execute for one message. A chat message
 * may map to several capabilities at once; [GENERAL_ASSISTANT] is the fallback
 * for anything the app's data-backed capabilities cannot answer.
 *
 * Capabilities are NEVER chosen directly by the model: the planner returns an
 * intent + data needs, and [PlanSanitizer] derives the capability set here
 * deterministically. It is the app, not the model, that decides which
 * data-backed runs happen.
 */
enum class AiCapability {
    FINANCIAL_ANALYSIS,
    TRANSACTION_INTELLIGENCE,
    GOAL_COACH,
    GENERAL_ASSISTANT,
    ;

    companion object {
        private val SYNONYMS = mapOf(
            FINANCIAL_ANALYSIS to setOf(
                "financial_analysis", "financial analysis", "finance", "financial",
                "spending analysis", "assistant",
            ),
            TRANSACTION_INTELLIGENCE to setOf(
                "transaction_intelligence", "transaction intelligence", "transaction",
                "intelligence", "pattern", "patterns",
            ),
            GOAL_COACH to setOf(
                "goal_coach", "goal coach", "goal", "goals", "coach",
            ),
            GENERAL_ASSISTANT to setOf(
                "general_assistant", "general assistant", "general",
            ),
        )

        /** Tolerant, case-insensitive lookup; unknown labels yield null. */
        fun from(label: String): AiCapability? {
            val clean = label.trim().lowercase()
            if (clean.isEmpty()) return null
            entries.firstOrNull { it.name.equals(clean, ignoreCase = true) }?.let { return it }
            return entries.firstOrNull { clean in (SYNONYMS[it] ?: emptySet()) }
        }
    }
}

/**
 * Which groups of the pre-computed financial facts a request needs. The
 * [PlanSanitizer] merges the planner's choices with intent-derived defaults and
 * [FinancialFactsSelector] translates the result into concrete fact keys, so
 * prompts carry only the facts the answer depends on (with empty = full context).
 */
enum class AiDataNeed {
    CURRENT_PERIOD_TOTALS,
    PREVIOUS_PERIOD_TOTALS,
    CATEGORY_BREAKDOWN,
    LARGEST_SMALLEST,
    RECENT_TRANSACTIONS,
    TRANSACTION_PATTERNS,
    CURRENT_BALANCE,
    GOAL_DATA,
    ;

    companion object {
        private val SYNONYMS = mapOf(
            CURRENT_PERIOD_TOTALS to setOf(
                "current_period_totals", "current", "current month", "this month", "totals",
            ),
            PREVIOUS_PERIOD_TOTALS to setOf(
                "previous_period_totals", "previous", "previous month", "last month",
                "comparison", "compare", "trend",
            ),
            CATEGORY_BREAKDOWN to setOf("category_breakdown", "category", "categories", "breakdown"),
            LARGEST_SMALLEST to setOf("largest_smallest", "largest", "smallest", "biggest"),
            RECENT_TRANSACTIONS to setOf("recent_transactions", "recent", "transactions"),
            TRANSACTION_PATTERNS to setOf(
                "transaction_patterns", "patterns", "pattern", "behavior", "behaviour",
                "habits", "anomaly", "anomalies", "outlier", "outliers", "recurring",
            ),
            CURRENT_BALANCE to setOf("current_balance", "balance"),
            GOAL_DATA to setOf("goal_data", "goal", "goals", "savings goal"),
        )

        fun from(label: String): AiDataNeed? {
            val clean = label.trim().lowercase()
            if (clean.isEmpty()) return null
            entries.firstOrNull { it.name.equals(clean, ignoreCase = true) }?.let { return it }
            return entries.firstOrNull { clean in (SYNONYMS[it] ?: emptySet()) }
        }
    }
}

/**
 * The semantic intent of the user's latest message — the PRIMARY output of the
 * planner. Capabilities are derived from this plus [AiRequestPlan.financialRelevance]
 * and data needs; the intent alone determines what the app tries to do.
 */
enum class AiIntent {

    /** "How much did I spend this month?" */
    GET_CURRENT_SPENDING,

    /** "What did I earn this month?" */
    GET_CURRENT_INCOME,

    /** "What is my current balance?" */
    GET_CURRENT_BALANCE,

    /** "How does this month compare with last month?" */
    COMPARE_PERIODS,

    /** "Why did my spending change?" */
    EXPLAIN_SPENDING_CHANGE,

    /** "What patterns / trends / anomalies stand out?" */
    IDENTIFY_SPENDING_PATTERNS,

    /** "What was my largest / smallest expense?" */
    IDENTIFY_LARGEST_EXPENSE,

    /** "How am I doing overall?" — broad financial overview. */
    SUMMARIZE_FINANCIAL_ACTIVITY,

    /** Advice grounded in the user's OWN spending behavior. */
    ASK_FOR_SPENDING_ADVICE,

    /** "What's my savings goal progress?" */
    ASK_GOAL_PROGRESS,

    /** "How do I reach my savings goal?" */
    ASK_GOAL_ADVICE,

    /** No financial data needed — general chat, explanations, generic advice. */
    GENERAL_CONVERSATION,

    /** Genuinely ambiguous; the planner could not tell what the user wants. */
    AMBIGUOUS,
    ;

    companion object {
        // Wire names are the exact labels the router prompt lists; enum-name and
        // synonym spellings are tolerated so a model typo never breaks planning.
        private val WIRE_NAMES = mapOf(
            GET_CURRENT_SPENDING to setOf(
                "get_current_spending", "spending_amount", "spending_total", "how much did i spend",
            ),
            GET_CURRENT_INCOME to setOf("get_current_income", "income_amount", "income_total"),
            GET_CURRENT_BALANCE to setOf("get_current_balance", "current_balance"),
            COMPARE_PERIODS to setOf("compare_periods", "period_comparison"),
            EXPLAIN_SPENDING_CHANGE to setOf("explain_spending_change", "spending_change_reason"),
            IDENTIFY_SPENDING_PATTERNS to setOf("identify_spending_patterns", "spending_patterns"),
            IDENTIFY_LARGEST_EXPENSE to setOf("identify_largest_expense", "largest_expense"),
            SUMMARIZE_FINANCIAL_ACTIVITY to setOf("summarize_financial_activity", "financial_summary"),
            ASK_FOR_SPENDING_ADVICE to setOf("ask_for_spending_advice", "spending_advice"),
            ASK_GOAL_PROGRESS to setOf("goal_progress"),
            ASK_GOAL_ADVICE to setOf("goal_advice"),
            GENERAL_CONVERSATION to setOf("general_conversation"),
            AMBIGUOUS to setOf("ambiguous"),
        )

        /** Tolerant, case-insensitive lookup; unknown labels yield null. */
        fun from(label: String): AiIntent? {
            val clean = label.trim().lowercase()
            if (clean.isEmpty()) return null
            entries.firstOrNull { it.name.equals(clean, ignoreCase = true) }?.let { return it }
            return entries.firstOrNull { clean in (WIRE_NAMES[it] ?: emptySet()) }
        }
    }
}

/** How sure the planner is about the rest of the plan. Advisory only. */
enum class AiRoutingConfidence {
    HIGH,
    MEDIUM,
    LOW,
    ;

    companion object {
        fun from(label: String): AiRoutingConfidence? {
            val clean = label.trim().lowercase()
            if (clean.isEmpty()) return null
            return entries.firstOrNull { it.name.lowercase() == clean }
        }
    }
}

/**
 * The planner's RAW verdict for one message, exactly as parsed from the model
 * output. Every field except the two booleans is nullable/empty because a
 * tolerant parser must survive partial or degenerate output; [PlanSanitizer]
 * turns a [AiRequestPlanDraft] into a fully-validated [AiRequestPlan].
 *
 * [financialRelevance] and [needsConversationContext] are nullable in the draft
 * only to distinguish "explicitly false" from "not mentioned"; the sanitized
 * [AiRequestPlan] carries plain booleans.
 */
data class AiRequestPlanDraft(
    val intent: AiIntent? = null,
    val financialRelevance: Boolean? = null,
    val dataNeeds: Set<AiDataNeed> = emptySet(),
    val capabilities: Set<AiCapability> = emptySet(),
    val needsConversationContext: Boolean = false,
    val confidence: AiRoutingConfidence? = null,
)

/**
 * ONE fully-validated plan for the orchestrator. Built by [PlanSanitizer]:
 *
 *  - [intent] is never null (unspecified -> deterministic heuristic fallback).
 *  - [financialRelevance] decided from the model, the intent, or question cues
 *    ("never classify a financial question as GENERAL just because it lacks
 *    financial vocabulary").
 *  - [dataNeeds] is the intent's required facts UNION the model's suggestions.
 *  - [capabilities] is ALWAYS derived deterministically from intent/relevance/
 *    data needs — the model's advisory capabilities are validated away, so the
 *    model can never steer the app into an arbitrary capability run.
 *  - [confidence] defaults to LOW when the app had to fall back entirely.
 */
data class AiRequestPlan(
    val intent: AiIntent,
    val financialRelevance: Boolean,
    val dataNeeds: Set<AiDataNeed>,
    val capabilities: Set<AiCapability>,
    val needsConversationContext: Boolean,
    val confidence: AiRoutingConfidence,
)

/**
 * Outcome of one orchestrated user message.
 *
 * [Text] is a normal assistant reply (either a local reply, a general answer, or
 * the unified composer answer). [Text.insights] carries the deterministic
 * Transaction Intelligence cards extracted from the composer reply — cards are
 * NEVER rendered directly from model output. [Error] surfaces an existing
 * [AiResult.Error] with its safe user message.
 */
sealed interface AiOrchestrationResult {
    data class Text(
        val text: String,
        val insights: List<TransactionInsight> = emptyList(),
    ) : AiOrchestrationResult

    data class Error(val error: AiResult.Error) : AiOrchestrationResult
}