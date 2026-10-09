package com.personal.balance.domain.ai

/**
 * Development-only AI connectivity smoke test definition.
 *
 * Pure Kotlin — no Firebase references. It is only reachable from debug builds
 * (the UI entry and navigation route are gated on `BuildConfig.DEBUG`); release
 * builds never reference it and R8 strips it. It sends no financial data and
 * goes through the normal [AskAiUseCase] / [AiAssistantRepository] path.
 */
object AiSmokeTest {
    const val EXPECTED_RESPONSE: String = "AI_SMOKE_TEST_OK"

    const val QUESTION: String = "Reply with exactly: $EXPECTED_RESPONSE"

    val request: AiRequest = AiRequest(
        question = QUESTION,
        structuredFacts = emptyMap(),
    )

    /**
     * Safe one-line rendering for Logcat and diagnostics. For errors it uses only
     * what the existing [AiResult.Error] design already permits (error type +
     * diagnostic class name); it never includes raw API messages or secrets.
     */
    fun render(result: AiResult): String = when (result) {
        is AiResult.Success -> "AI_SMOKE_TEST SUCCESS tokens=${result.tokenCount} reply=\"${result.text}\""
        is AiResult.Error -> "AI_SMOKE_TEST FAIL type=${result::class.simpleName} diagnostic=${result.diagnostic}"
    }
}