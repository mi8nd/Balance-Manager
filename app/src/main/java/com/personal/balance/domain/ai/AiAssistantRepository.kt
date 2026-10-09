package com.personal.balance.domain.ai

/**
 * Replaceable AI backend. Implementations translate every backend failure into
 * a domain [AiResult.Error]; no Firebase or HTTP exception type reaches callers.
 * Callers must NEVER trigger the model automatically (e.g. on dashboard open,
 * focus/scroll events, or Firestore snapshots) — every call must originate from
 * an explicit user action routed here.
 */
interface AiAssistantRepository {
    suspend fun ask(request: AiRequest): AiResult
}