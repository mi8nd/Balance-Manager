package com.personal.balance.domain.ai

/**
 * Device-local guard against accidental spending on AI calls.
 *
 * This is OUR OWN budget; it is NOT an implementation of Google's server-side
 * quota. Its purpose is to keep AI calls explicit and deliberate and to bound
 * cost on the no-cost Gemini Developer API quota.
 */
interface AiUsageController {
    suspend fun isCallAllowed(): Boolean
    suspend fun recordCall()
}