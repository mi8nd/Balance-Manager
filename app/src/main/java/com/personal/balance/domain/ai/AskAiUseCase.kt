package com.personal.balance.domain.ai

/**
 * Orchestrates a single explicit AI request: require an authenticated user,
 * enforce the device-local usage budget, then delegate to the AI backend.
 */
class AskAiUseCase(
    private val authProvider: AiAuthProvider,
    private val usageController: AiUsageController,
    private val assistant: AiAssistantRepository,
) {

    suspend operator fun invoke(request: AiRequest): AiResult {
        if (!authProvider.isAuthenticated()) {
            return AiResult.Error.Unauthenticated(
                userSafeMessage = "You must be signed in to use the AI assistant.",
            )
        }

        if (!usageController.isCallAllowed()) {
            return AiResult.Error.RateLimited(
                userSafeMessage = "You've reached the local limit for AI requests. Try again later.",
                diagnostic = "local usage window exhausted",
            )
        }

        val result = assistant.ask(request)
        usageController.recordCall()
        return result
    }
}