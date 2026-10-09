package com.personal.balance.domain.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiConfigTest {

    private val config = DefaultAiConfig.instance

    @Test
    fun defaults_haveValidModelAndOutput() {
        assertFalse(config.modelName.isBlank())
        assertTrue(config.maxOutputTokens > 0)
    }

    @Test
    fun defaults_producePlainText() {
        assertEquals("text/plain", config.responseMimeType)
    }

    @Test
    fun defaults_haveSystemPromptAndSafetyRules() {
        assertFalse(config.systemPrompt.isBlank())
        assertTrue(config.systemPrompt.contains("facts"))
        assertTrue(config.safetySettings.isNotEmpty())
        val topics = config.safetySettings.map { it.topic }.toSet()
        assertEquals(AiSafetyTopic.entries.toSet(), topics)
    }

    @Test
    fun defaults_haveSaneUsageBudget() {
        assertTrue(config.maxCallsPerWindow > 0)
        assertTrue(config.windowMillis > 0)
    }
}