package com.personal.balance.domain.ai.orchestration

import java.util.Locale

/**
 * The deterministic brain that turns the planner's raw draft into ONE valid
 * [AiRequestPlan]. Nothing here is guessable by the model:
 *
 *  - [capabilitiesFor] is the authoritative, intent-driven capability mapping —
 *    the model's advisory capabilities are discarded, so a hallucinated
 *    capability can never steer the app.
 *  - [toPlan] fills any gap the tolerant parser left behind (missing intent,
 *    missing relevance, missing confidence) from deterministic question cues,
 *    so a financial question is NEVER silently dumped on the general assistant
 *    just because the model output was malformed or vocabulary-light.
 */
object PlanSanitizer {

    private val FINANCIAL_NEEDS = setOf(
        AiDataNeed.CURRENT_PERIOD_TOTALS,
        AiDataNeed.PREVIOUS_PERIOD_TOTALS,
        AiDataNeed.CATEGORY_BREAKDOWN,
        AiDataNeed.LARGEST_SMALLEST,
        AiDataNeed.RECENT_TRANSACTIONS,
        AiDataNeed.CURRENT_BALANCE,
    )

    private val PATTERN_CUES = listOf(
        "pattern", "patterns", "trend", "trends", "habit", "habits", "behavior",
        "behaviour", "anomaly", "anomalies", "outlier", "outliers", "recurring",
        "recur", "recurrence", "stand out", "spike", "irregular", "unusual",
    )

    /**
     * Sanitizes one raw draft into a fully-validated [AiRequestPlan].
     *
     * @param draft the parsed model verdict, or null when the output was
     *   unrecognizable (a deterministic heuristic then decides).
     * @param question the user's message, used only as the fallback signal.
     */
    fun toPlan(draft: AiRequestPlanDraft?, question: String): AiRequestPlan {
        val intent = draft?.intent ?: fallbackIntent(question)
        val relevance = draft?.financialRelevance ?: when {
            intentRequiresData(intent) -> true
            draft != null && draft.dataNeeds.isNotEmpty() -> true
            else -> hasFinancialSignals(question)
        }
        val dataNeeds = DEFAULT_DATA_NEEDS[intent].orEmpty() + (draft?.dataNeeds ?: emptySet())
        val capabilities = capabilitiesFor(intent, relevance, dataNeeds)
        val needsContext = draft?.needsConversationContext ?: false
        val confidence = draft?.confidence
            ?: if (draft == null) AiRoutingConfidence.LOW else AiRoutingConfidence.MEDIUM
        return AiRequestPlan(
            intent = intent,
            financialRelevance = relevance,
            dataNeeds = dataNeeds,
            capabilities = capabilities,
            needsConversationContext = needsContext,
            confidence = confidence,
        )
    }

    /**
     * The authoritative capability mapping. Financially relevant questions that
     * get NO specific capability still land on a safe broad financial plan
     * (Financial Analysis + Transaction Intelligence), never on GENERAL.
     *
     * Interpreted intents take their base capability; GOAL intents additionally
     * gain the financial capabilities the data needs imply; a contradiction
     * (e.g. GENERAL intent with financial relevance) is resolved in favor of
     * the user's data.
     */
    fun capabilitiesFor(
        intent: AiIntent,
        financialRelevance: Boolean,
        dataNeeds: Set<AiDataNeed>,
    ): Set<AiCapability> {
        val base = when (intent) {
            AiIntent.GET_CURRENT_SPENDING,
            AiIntent.GET_CURRENT_INCOME,
            AiIntent.GET_CURRENT_BALANCE,
            AiIntent.COMPARE_PERIODS,
            AiIntent.IDENTIFY_LARGEST_EXPENSE,
            -> setOf(AiCapability.FINANCIAL_ANALYSIS)

            AiIntent.EXPLAIN_SPENDING_CHANGE,
            AiIntent.SUMMARIZE_FINANCIAL_ACTIVITY,
            AiIntent.ASK_FOR_SPENDING_ADVICE,
            -> setOf(AiCapability.FINANCIAL_ANALYSIS, AiCapability.TRANSACTION_INTELLIGENCE)

            AiIntent.IDENTIFY_SPENDING_PATTERNS -> setOf(AiCapability.TRANSACTION_INTELLIGENCE)

            AiIntent.ASK_GOAL_PROGRESS,
            AiIntent.ASK_GOAL_ADVICE,
            -> setOf(AiCapability.GOAL_COACH)

            AiIntent.GENERAL_CONVERSATION,
            AiIntent.AMBIGUOUS,
            -> emptySet()
        }

        val result = base.toMutableSet()
        if (AiCapability.GOAL_COACH in result) {
            val nonGoalNeeds = dataNeeds - AiDataNeed.GOAL_DATA
            if (nonGoalNeeds.any { it in FINANCIAL_NEEDS }) {
                result.add(AiCapability.FINANCIAL_ANALYSIS)
            }
            if (AiDataNeed.TRANSACTION_PATTERNS in dataNeeds) {
                result.add(AiCapability.TRANSACTION_INTELLIGENCE)
            }
        }
        if (result.isEmpty() && financialRelevance) {
            result.add(AiCapability.FINANCIAL_ANALYSIS)
            result.add(AiCapability.TRANSACTION_INTELLIGENCE)
        }
        return result
    }

    /** The intent's own minimum facts; the planner's suggestions are unioned on top. */
    private val DEFAULT_DATA_NEEDS = mapOf(
        AiIntent.GET_CURRENT_SPENDING to setOf(AiDataNeed.CURRENT_PERIOD_TOTALS),
        AiIntent.GET_CURRENT_INCOME to setOf(AiDataNeed.CURRENT_PERIOD_TOTALS),
        AiIntent.GET_CURRENT_BALANCE to setOf(AiDataNeed.CURRENT_BALANCE),
        AiIntent.COMPARE_PERIODS to setOf(
            AiDataNeed.CURRENT_PERIOD_TOTALS,
            AiDataNeed.PREVIOUS_PERIOD_TOTALS,
        ),
        AiIntent.EXPLAIN_SPENDING_CHANGE to setOf(
            AiDataNeed.CURRENT_PERIOD_TOTALS,
            AiDataNeed.PREVIOUS_PERIOD_TOTALS,
            AiDataNeed.CATEGORY_BREAKDOWN,
            AiDataNeed.TRANSACTION_PATTERNS,
        ),
        AiIntent.IDENTIFY_SPENDING_PATTERNS to setOf(AiDataNeed.TRANSACTION_PATTERNS),
        AiIntent.IDENTIFY_LARGEST_EXPENSE to setOf(
            AiDataNeed.CURRENT_PERIOD_TOTALS,
            AiDataNeed.LARGEST_SMALLEST,
        ),
        AiIntent.SUMMARIZE_FINANCIAL_ACTIVITY to emptySet(),
        AiIntent.ASK_FOR_SPENDING_ADVICE to setOf(
            AiDataNeed.CATEGORY_BREAKDOWN,
            AiDataNeed.TRANSACTION_PATTERNS,
        ),
        AiIntent.ASK_GOAL_PROGRESS to setOf(AiDataNeed.GOAL_DATA),
        AiIntent.ASK_GOAL_ADVICE to setOf(AiDataNeed.GOAL_DATA),
        AiIntent.GENERAL_CONVERSATION to emptySet(),
        AiIntent.AMBIGUOUS to emptySet(),
    )

    private fun intentRequiresData(intent: AiIntent): Boolean =
        intent != AiIntent.GENERAL_CONVERSATION && intent != AiIntent.AMBIGUOUS

    /**
     * Deterministic intent fallback when the model said nothing usable. Rules:
     * a clearly financial question -> a safe broad financial plan; a
     * pattern/behavior question -> Transaction Intelligence; a goal question ->
     * the Goal Coach (extension point); everything else -> general conversation.
     */
    fun fallbackIntent(question: String): AiIntent {
        val text = normalize(question)
        return when {
            hasGoalCue(text) -> AiIntent.ASK_GOAL_PROGRESS
            text.contains("why") && ConversationalTriage.hasFinancialCue(text) ->
                AiIntent.EXPLAIN_SPENDING_CHANGE
            hasPatternCue(text) -> AiIntent.IDENTIFY_SPENDING_PATTERNS
            ConversationalTriage.hasFinancialCue(text) -> AiIntent.SUMMARIZE_FINANCIAL_ACTIVITY
            else -> AiIntent.GENERAL_CONVERSATION
        }
    }

    /** Question-level financial relevance signal, used only when the model stayed silent. */
    fun hasFinancialSignals(question: String): Boolean {
        val text = normalize(question)
        return ConversationalTriage.hasFinancialCue(text) ||
            hasPatternCue(text) ||
            hasGoalCue(text)
    }

    private fun hasPatternCue(text: String): Boolean = PATTERN_CUES.any { text.contains(it) }

    private fun hasGoalCue(text: String): Boolean =
        text.contains("goal") || text.contains("savings")

    private fun normalize(text: String): String = text.lowercase(Locale.US)
}