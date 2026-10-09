package com.personal.balance.domain.ai.orchestration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiRequestPlanParserTest {

    @Test
    fun fullJsonObject_parsedCorrectly() {
        val raw = """{"intent": "get_current_spending", "financial_relevance": true, "data_needs": ["current_period_totals", "category_breakdown"], "capabilities": ["financial_analysis"], "needs_conversation_context": false, "confidence": "high"}"""
        val draft = AiRequestPlanParser.parse(raw)!!

        assertEquals(AiIntent.GET_CURRENT_SPENDING, draft.intent)
        assertEquals(true, draft.financialRelevance)
        assertEquals(
            setOf(AiDataNeed.CURRENT_PERIOD_TOTALS, AiDataNeed.CATEGORY_BREAKDOWN),
            draft.dataNeeds,
        )
        assertEquals(setOf(AiCapability.FINANCIAL_ANALYSIS), draft.capabilities)
        assertFalse(draft.needsConversationContext)
        assertEquals(AiRoutingConfidence.HIGH, draft.confidence)
    }

    @Test
    fun codeFenceJsonBlock_parsed() {
        val raw = "Here is my plan:\n```json\n{\"intent\": \"identify_spending_patterns\", \"financial_relevance\": true, \"data_needs\": []}\n```"
        val draft = AiRequestPlanParser.parse(raw)!!

        assertEquals(AiIntent.IDENTIFY_SPENDING_PATTERNS, draft.intent)
        assertEquals(true, draft.financialRelevance)
        assertTrue(draft.dataNeeds.isEmpty())
    }

    @Test
    fun camelCaseKeys_supported() {
        val raw = """{"intent": "compare_periods", "financialRelevance": true, "dataNeeds": ["previous_period_totals"], "needsConversationContext": true, "confidence": "medium"}"""
        val draft = AiRequestPlanParser.parse(raw)!!

        assertEquals(AiIntent.COMPARE_PERIODS, draft.intent)
        assertEquals(true, draft.financialRelevance)
        assertEquals(setOf(AiDataNeed.PREVIOUS_PERIOD_TOTALS), draft.dataNeeds)
        assertTrue(draft.needsConversationContext)
        assertEquals(AiRoutingConfidence.MEDIUM, draft.confidence)
    }

    @Test
    fun unquotedBooleans_parsed() {
        val raw = """{"intent": "goal_progress", "financial_relevance": true, "needs_conversation_context": false}"""
        val draft = AiRequestPlanParser.parse(raw)!!

        assertEquals(AiIntent.ASK_GOAL_PROGRESS, draft.intent)
        assertEquals(true, draft.financialRelevance)
        assertFalse(draft.needsConversationContext)
    }

    @Test
    fun quotedBooleans_parsed() {
        val raw = """{"intent": "general_conversation", "financial_relevance": "false", "needs_conversation_context": "true"}"""
        val draft = AiRequestPlanParser.parse(raw)!!

        assertEquals(AiIntent.GENERAL_CONVERSATION, draft.intent)
        assertEquals(false, draft.financialRelevance)
        assertTrue(draft.needsConversationContext)
    }

    @Test
    fun numericBooleans_parsed() {
        val raw = """{"intent": "get_current_balance", "financial_relevance": 1, "needs_conversation_context": 0}"""
        val draft = AiRequestPlanParser.parse(raw)!!

        assertEquals(true, draft.financialRelevance)
        assertFalse(draft.needsConversationContext)
    }

    @Test
    fun irrelevantAndFalseRelevance_bothDistinguishable() {
        val raw = """{"intent": "general_conversation", "financial_relevance": false}"""
        val draft = AiRequestPlanParser.parse(raw)!!

        // Explicitly false stays false (not collapsed into "not mentioned").
        assertEquals(false, draft.financialRelevance)
    }

    @Test
    fun unknownIntentValue_intentNull_butOtherFieldsKept() {
        val raw = """{"intent": "break_my_budget_today", "financial_relevance": true, "data_needs": ["category_breakdown"]}"""
        val draft = AiRequestPlanParser.parse(raw)!!

        assertNull(draft.intent)
        assertEquals(true, draft.financialRelevance)
        assertEquals(setOf(AiDataNeed.CATEGORY_BREAKDOWN), draft.dataNeeds)
    }

    @Test
    fun unknownDataNeedIgnored_knownKept() {
        val raw = """{"intent": "summarize_financial_activity", "data_needs": ["category_breakdown", "made_up_need"]}"""
        val draft = AiRequestPlanParser.parse(raw)!!

        assertEquals(setOf(AiDataNeed.CATEGORY_BREAKDOWN), draft.dataNeeds)
    }

    @Test
    fun unknownCapabilitiesIgnored_knownKept() {
        val raw = """{"intent": "explain_spending_change", "capabilities": ["pivot_tables", "financial_analysis"]}"""
        val draft = AiRequestPlanParser.parse(raw)!!

        assertEquals(setOf(AiCapability.FINANCIAL_ANALYSIS), draft.capabilities)
    }

    @Test
    fun confidenceParsedCaseInsensitive() {
        val raw = """{"intent": "get_current_spending", "confidence": "Low"}"""
        val draft = AiRequestPlanParser.parse(raw)!!

        assertEquals(AiRoutingConfidence.LOW, draft.confidence)
    }

    @Test
    fun unknownConfidence_confidenceNull() {
        val raw = """{"intent": "get_current_spending", "confidence": "maybe"}"""
        val draft = AiRequestPlanParser.parse(raw)!!

        assertNull(draft.confidence)
    }

    @Test
    fun intentByEnumName_parsed() {
        val raw = """{"intent": "GET_CURRENT_SPENDING", "financial_relevance": true}"""
        val draft = AiRequestPlanParser.parse(raw)!!

        assertEquals(AiIntent.GET_CURRENT_SPENDING, draft.intent)
    }

    @Test
    fun intentByWireLabel_parsed() {
        val raw = """{"intent": "get_current_income"}"""
        val draft = AiRequestPlanParser.parse(raw)!!

        assertEquals(AiIntent.GET_CURRENT_INCOME, draft.intent)
        assertNull(draft.financialRelevance)
    }

    @Test
    fun duplicateDataNeeds_collapsed() {
        val raw = """{"intent": "compare_periods", "data_needs": ["current_period_totals", "current_period_totals", "transaction_patterns"]}"""
        val draft = AiRequestPlanParser.parse(raw)!!

        assertEquals(
            setOf(AiDataNeed.CURRENT_PERIOD_TOTALS, AiDataNeed.TRANSACTION_PATTERNS),
            draft.dataNeeds,
        )
    }

    @Test
    fun lineBasedFallback_parsed() {
        val raw = "intent: compare_periods\nfinancial_relevance: true\ndata_needs: current_period_totals, previous_period_totals\nconfidence: medium"
        val draft = AiRequestPlanParser.parse(raw)!!

        assertEquals(AiIntent.COMPARE_PERIODS, draft.intent)
        assertEquals(true, draft.financialRelevance)
        assertEquals(
            setOf(AiDataNeed.CURRENT_PERIOD_TOTALS, AiDataNeed.PREVIOUS_PERIOD_TOTALS),
            draft.dataNeeds,
        )
        assertEquals(AiRoutingConfidence.MEDIUM, draft.confidence)
    }

    @Test
    fun lineBasedWithEqualsSeparator_parsed() {
        val raw = "intent=goal_progress\nfinancial_relevance=true"
        val draft = AiRequestPlanParser.parse(raw)!!

        assertEquals(AiIntent.ASK_GOAL_PROGRESS, draft.intent)
        assertEquals(true, draft.financialRelevance)
    }

    @Test
    fun onlyDataNeedsPresent_stillADraft() {
        // A stale "capabilities-only" style reply is nulled out, but data needs
        // alone are a usable financial signal for the sanitizer.
        val raw = """{"data_needs": ["category_breakdown"]}"""
        val draft = AiRequestPlanParser.parse(raw)!!

        assertNotNull(draft)
        assertTrue(draft.dataNeeds.contains(AiDataNeed.CATEGORY_BREAKDOWN))
        assertNull(draft.intent)
    }

    @Test
    fun emptyJsonObject_returnsNull() {
        assertNull(AiRequestPlanParser.parse("{}"))
    }

    @Test
    fun capabilitiesOnly_parsedAsAdvisoryDraft() {
        // Capabilities are advisory; a capabilities-only reply still parses so
        // the sanitizer can recompute the authoritative set from question cues.
        val draft = AiRequestPlanParser.parse("""{"capabilities": ["financial_analysis"]}""")!!
        assertEquals(setOf(AiCapability.FINANCIAL_ANALYSIS), draft.capabilities)
        assertNull(draft.intent)
    }

    @Test
    fun malformedJson_returnsNull() {
        assertNull(AiRequestPlanParser.parse("this is not json at all"))
        assertNull(AiRequestPlanParser.parse("no keys here [[[]]]"))
    }

    @Test
    fun emptyInput_returnsNull() {
        assertNull(AiRequestPlanParser.parse(""))
        assertNull(AiRequestPlanParser.parse("   "))
        assertNull(AiRequestPlanParser.parse("\n\n"))
    }

    @Test
    fun unknownIntentOnly_returnsNull() {
        assertNull(AiRequestPlanParser.parse("""{"intent": "take_me_to_zanzibar"}"""))
    }
}