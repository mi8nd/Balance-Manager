package com.personal.balance.domain.ai

import com.personal.balance.data.ai.FirebaseAiAssistantRepository
import com.personal.balance.data.ai.FirebaseAiAuthProvider
import com.personal.balance.data.ai.SharedAiUsageController

/**
 * Single point of construction for the shared AI stack.
 *
 * Ensures the app has exactly one [AskAiUseCase] wiring — auth gate → shared
 * device-local budget → assistant repository — matching the architecture
 * invariant documented in AGENTS.md and enforced by the static
 * *VerificationTest suites.
 *
 * Do NOT create additional AskAiUseCase instances elsewhere; inject this
 * factory where needed.
 */
class AiStackFactory {
    fun createAskAiUseCase(): AskAiUseCase = AskAiUseCase(
        authProvider = FirebaseAiAuthProvider(),
        usageController = SharedAiUsageController.instance,
        assistant = FirebaseAiAssistantRepository(),
    )
}