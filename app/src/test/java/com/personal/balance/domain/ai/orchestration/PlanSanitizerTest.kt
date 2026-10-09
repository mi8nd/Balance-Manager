package com.personal.balance.domain.ai.orchestration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanSanitizerTest {

    @Test
    fun getCurrentSpending_capabilitiesFinancialAnalysis() {
        val p = PlanSanitizer.toPlan(
            AiRequestPlanDraft(intent = AiIntent.GET_CURRENT_SPENDING, financialRelevance = true),
            "How much did I spend?",
        )
        assertEquals(setOf(AiCapability.FINANCIAL_ANALYSIS), p.capabilities)
        assertTrue(AiDataNeed.CURRENT_PERIOD_TOTALS in p.dataNeeds)
        assertEquals(AiRoutingConfidence.MEDIUM, p.confidence)
    }

    @Test
    fun comparePeriods_capabilitiesFinanceAndBothPeriodNeeds() {
        val p = PlanSanitizer.toPlan(
            AiRequestPlanDraft(intent = AiIntent.COMPARE_PERIODS, financialRelevance = true),
            "Compare months",
        )
        assertEquals(setOf(AiCapability.FINANCIAL_ANALYSIS), p.capabilities)
        assertTrue(AiDataNeed.CURRENT_PERIOD_TOTALS in p.dataNeeds)
        assertTrue(AiDataNeed.PREVIOUS_PERIOD_TOTALS in p.dataNeeds)
    }

    @Test
    fun explainSpendingChange_capabilitiesFinancePlusTi() {
        val p = PlanSanitizer.toPlan(
            AiRequestPlanDraft(intent = AiIntent.EXPLAIN_SPENDING_CHANGE, financialRelevance = true),
            "Why did my spending rise?",
        )
        assertEquals(
            setOf(AiCapability.FINANCIAL_ANALYSIS, AiCapability.TRANSACTION_INTELLIGENCE),
            p.capabilities,
        )
        assertTrue(AiDataNeed.TRANSACTION_PATTERNS in p.dataNeeds)
        assertTrue(AiDataNeed.CATEGORY_BREAKDOWN in p.dataNeeds)
        assertTrue(AiDataNeed.PREVIOUS_PERIOD_TOTALS in p.dataNeeds)
    }

    @Test
    fun identifyPatterns_capabilitiesTiOnly() {
        val p = PlanSanitizer.toPlan(
            AiRequestPlanDraft(intent = AiIntent.IDENTIFY_SPENDING_PATTERNS, financialRelevance = true),
            "What patterns?",
        )
        assertEquals(setOf(AiCapability.TRANSACTION_INTELLIGENCE), p.capabilities)
        assertTrue(AiDataNeed.TRANSACTION_PATTERNS in p.dataNeeds)
    }

    @Test
    fun summarize_capabilitiesFinancePlusTi_fullContext() {
        val p = PlanSanitizer.toPlan(
            AiRequestPlanDraft(intent = AiIntent.SUMMARIZE_FINANCIAL_ACTIVITY, financialRelevance = true),
            "How am I doing?",
        )
        assertEquals(
            setOf(AiCapability.FINANCIAL_ANALYSIS, AiCapability.TRANSACTION_INTELLIGENCE),
            p.capabilities,
        )
        assertTrue(p.dataNeeds.isEmpty())
    }

    @Test
    fun goalWithFinancialNeed_capabilitiesGoalPlusFinance() {
        val p = PlanSanitizer.toPlan(
            AiRequestPlanDraft(
                intent = AiIntent.ASK_GOAL_PROGRESS,
                financialRelevance = true,
                dataNeeds = setOf(AiDataNeed.GOAL_DATA, AiDataNeed.CURRENT_PERIOD_TOTALS),
            ),
            "How's my goal and spending?",
        )
        assertEquals(
            setOf(AiCapability.GOAL_COACH, AiCapability.FINANCIAL_ANALYSIS),
            p.capabilities,
        )
    }

    @Test
    fun goalWithPatternNeed_capabilitiesGoalPlusTi() {
        val p = PlanSanitizer.toPlan(
            AiRequestPlanDraft(
                intent = AiIntent.ASK_GOAL_ADVICE,
                financialRelevance = true,
                dataNeeds = setOf(AiDataNeed.GOAL_DATA, AiDataNeed.TRANSACTION_PATTERNS),
            ),
            "Help me reach my goal",
        )
        assertEquals(
            setOf(AiCapability.GOAL_COACH, AiCapability.TRANSACTION_INTELLIGENCE),
            p.capabilities,
        )
    }

    @Test
    fun goalOnly_capabilitiesGoalCoach() {
        val p = PlanSanitizer.toPlan(
            AiRequestPlanDraft(intent = AiIntent.ASK_GOAL_PROGRESS, financialRelevance = true),
            "Goal progress?",
        )
        assertEquals(setOf(AiCapability.GOAL_COACH), p.capabilities)
        assertEquals(setOf(AiDataNeed.GOAL_DATA), p.dataNeeds)
    }

    @Test
    fun generalNotRelevant_noCapabilities() {
        val p = PlanSanitizer.toPlan(
            AiRequestPlanDraft(intent = AiIntent.GENERAL_CONVERSATION, financialRelevance = false),
            "Tell me a joke",
        )
        assertTrue(p.capabilities.isEmpty())
        assertFalse(p.financialRelevance)
    }

    @Test
    fun generalRelevant_contradictionResolvedToData() {
        val p = PlanSanitizer.toPlan(
            AiRequestPlanDraft(intent = AiIntent.GENERAL_CONVERSATION, financialRelevance = true),
            "Anything wrong with my money?",
        )
        assertEquals(
            setOf(AiCapability.FINANCIAL_ANALYSIS, AiCapability.TRANSACTION_INTELLIGENCE),
            p.capabilities,
        )
    }

    @Test
    fun ambiguousNotRelevant_noCapabilities() {
        val p = PlanSanitizer.toPlan(
            AiRequestPlanDraft(intent = AiIntent.AMBIGUOUS, financialRelevance = false),
            "Hmm...",
        )
        assertTrue(p.capabilities.isEmpty())
    }

    @Test
    fun ambiguousRelevant_broadFinancialPlan() {
        val p = PlanSanitizer.toPlan(
            AiRequestPlanDraft(intent = AiIntent.AMBIGUOUS, financialRelevance = true),
            "Tell me about my money",
        )
        assertEquals(
            setOf(AiCapability.FINANCIAL_ANALYSIS, AiCapability.TRANSACTION_INTELLIGENCE),
            p.capabilities,
        )
    }

    @Test
    fun missingRelevance_derivedFromIntent() {
        val p = PlanSanitizer.toPlan(
            AiRequestPlanDraft(intent = AiIntent.GET_CURRENT_INCOME),
            "What did I earn?",
        )
        assertTrue(p.financialRelevance)
        assertEquals(setOf(AiCapability.FINANCIAL_ANALYSIS), p.capabilities)
    }

    @Test
    fun missingRelevance_derivedFromFinancialQuestion() {
        val p = PlanSanitizer.toPlan(null, "What did I spend yesterday?")
        assertTrue(p.financialRelevance)
    }

    @Test
    fun modelCapabilities_ignoredInFavorOfDerivedMapping() {
        val p = PlanSanitizer.toPlan(
            AiRequestPlanDraft(
                intent = AiIntent.GET_CURRENT_BALANCE,
                financialRelevance = true,
                capabilities = setOf(AiCapability.GENERAL_ASSISTANT, AiCapability.TRANSACTION_INTELLIGENCE),
            ),
            "Balance?",
        )
        // Hallucinated capabilities are validated away; the intent wins.
        assertEquals(setOf(AiCapability.FINANCIAL_ANALYSIS), p.capabilities)
    }

    @Test
    fun dataNeeds_unionOfIntentDefaultsAndModelSuggestions() {
        val p = PlanSanitizer.toPlan(
            AiRequestPlanDraft(
                intent = AiIntent.COMPARE_PERIODS,
                financialRelevance = true,
                dataNeeds = setOf(AiDataNeed.CATEGORY_BREAKDOWN),
            ),
            "Compare months",
        )
        assertEquals(
            setOf(
                AiDataNeed.CURRENT_PERIOD_TOTALS,
                AiDataNeed.PREVIOUS_PERIOD_TOTALS,
                AiDataNeed.CATEGORY_BREAKDOWN,
            ),
            p.dataNeeds,
        )
    }

    @Test
    fun needsConversationContext_passedThrough() {
        val p = PlanSanitizer.toPlan(
            AiRequestPlanDraft(intent = AiIntent.COMPARE_PERIODS, financialRelevance = true, needsConversationContext = true),
            "And last month?",
        )
        assertTrue(p.needsConversationContext)
    }

    @Test
    fun confidenceDefaults_mediumWithDraft_lowWithout() {
        val withDraft = PlanSanitizer.toPlan(
            AiRequestPlanDraft(intent = AiIntent.GET_CURRENT_SPENDING, financialRelevance = true),
            "Spend?",
        )
        assertEquals(AiRoutingConfidence.MEDIUM, withDraft.confidence)

        val withoutDraft = PlanSanitizer.toPlan(null, "How much did I spend?")
        assertEquals(AiRoutingConfidence.LOW, withoutDraft.confidence)
    }

    // ---- Deterministic fallback (no usable model output) ----

    @Test
    fun fallback_financialQuestion_broadSummarizePlan() {
        val p = PlanSanitizer.toPlan(null, "How much did I spend this month?")
        assertEquals(AiIntent.SUMMARIZE_FINANCIAL_ACTIVITY, p.intent)
        assertEquals(setOf(AiCapability.FINANCIAL_ANALYSIS, AiCapability.TRANSACTION_INTELLIGENCE), p.capabilities)
    }

    @Test
    fun fallback_patternQuestion_transactionIntelligence() {
        val p = PlanSanitizer.toPlan(null, "What patterns stand out in my spending?")
        assertEquals(AiIntent.IDENTIFY_SPENDING_PATTERNS, p.intent)
        assertEquals(setOf(AiCapability.TRANSACTION_INTELLIGENCE), p.capabilities)
    }

    @Test
    fun fallback_generalQuestion_noCapabilities() {
        val p = PlanSanitizer.toPlan(null, "Tell me a joke")
        assertEquals(AiIntent.GENERAL_CONVERSATION, p.intent)
        assertTrue(p.capabilities.isEmpty())
        assertFalse(p.financialRelevance)
    }

    @Test
    fun fallback_goalQuestion_goalCoach() {
        val p = PlanSanitizer.toPlan(null, "How is my savings goal going?")
        assertEquals(AiIntent.ASK_GOAL_PROGRESS, p.intent)
        assertEquals(setOf(AiCapability.GOAL_COACH), p.capabilities)
    }

    @Test
    fun fallback_whyWithFinancialCue_explainChange() {
        val p = PlanSanitizer.toPlan(null, "Why did my food spending increase?")
        assertEquals(AiIntent.EXPLAIN_SPENDING_CHANGE, p.intent)
    }

    @Test
    fun fallback_arabicFinancialCue_financialPlan() {
        val p = PlanSanitizer.toPlan(null, "رصيدي كام")
        assertTrue(p.financialRelevance)
        assertEquals(
            setOf(AiCapability.FINANCIAL_ANALYSIS, AiCapability.TRANSACTION_INTELLIGENCE),
            p.capabilities,
        )
    }

    @Test
    fun fallback_danglingReferenceNoCues_general() {
        val p = PlanSanitizer.toPlan(null, "What about it?")
        assertEquals(AiIntent.GENERAL_CONVERSATION, p.intent)
        assertFalse(p.financialRelevance)
    }
}