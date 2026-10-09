package com.personal.balance.domain.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiSmokeTestTest {

    @Test
    fun smokeRequest_isHarmlessAndAsksForExactReply() {
        assertEquals("Reply with exactly: AI_SMOKE_TEST_OK", AiSmokeTest.request.question)
        assertTrue(AiSmokeTest.request.structuredFacts.isEmpty())
    }

    @Test
    fun smokeRequest_matchesExpectedResponseConstant() {
        assertEquals("AI_SMOKE_TEST_OK", AiSmokeTest.EXPECTED_RESPONSE)
        assertTrue(AiSmokeTest.request.question.contains(AiSmokeTest.EXPECTED_RESPONSE))
    }

    @Test
    fun render_successMarksOkAndExposesReplyText() {
        val render = AiSmokeTest.render(AiResult.Success(text = "AI_SMOKE_TEST_OK", tokenCount = 3))
        assertTrue(render, render.contains("SUCCESS"))
        assertTrue(render, render.contains("AI_SMOKE_TEST_OK"))
        assertTrue(render, render.contains("tokens=3"))
    }

    @Test
    fun render_errorExposesOnlySafeTypeAndDiagnostic() {
        val render = AiSmokeTest.render(
            AiResult.Error.AppCheckFailure(
                userSafeMessage = "The app security check failed. Please try again.",
                diagnostic = "PermissionMissingException",
            )
        )
        assertTrue(render, render.contains("FAIL"))
        assertTrue(render, render.contains("AppCheckFailure"))
        assertTrue(render, render.contains("PermissionMissingException"))
        assertFalse(render, render.contains("The app security check failed"))
    }
}