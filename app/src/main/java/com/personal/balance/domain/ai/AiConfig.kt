package com.personal.balance.domain.ai

/**
 * Central configuration for the AI assistant. Tuning happens here only; no
 * other code hard-codes a model name or generation setting. The data layer
 * maps these plain values onto the Firebase AI Logic SDK types.
 */
data class AiConfig(
    val modelName: String,
    val maxOutputTokens: Int,
    val responseMimeType: String,
    val systemPrompt: String,
    val safetySettings: List<AiSafetySetting>,
    val maxCallsPerWindow: Int,
    val windowMillis: Long,
)

data class AiSafetySetting(
    val topic: AiSafetyTopic,
    val threshold: AiSafetyThreshold,
)

enum class AiSafetyTopic {
    HARASSMENT,
    HATE_SPEECH,
    SEXUALLY_EXPLICIT,
    DANGEROUS_CONTENT,
}

enum class AiSafetyThreshold {
    LOW_AND_ABOVE,
    MEDIUM_AND_ABOVE,
    ONLY_HIGH,
    NONE,
    OFF,
}

/**
 * Production defaults. `modelName` targets the Gemini Developer API free tier
 * (no-cost on a Spark plan); verified on-device before any feature relies on it.
 *
 * Notes on the selected model (`gemini-3.1-flash-lite`):
 * - No `temperature` is configured: per Google's Gemini 3 guidance, explicit
 *   low temperatures can cause looping/degraded performance, so the model's
 *   default (1.0) is deliberately not overridden.
 * - `maxOutputTokens` and `responseMimeType` ("text/plain") are the supported
 *   and meaningful generation settings for this model.
 */
object DefaultAiConfig {

    val instance: AiConfig = AiConfig(
        modelName = "gemini-3.1-flash-lite",
        maxOutputTokens = 1024,
        responseMimeType = "text/plain",
        systemPrompt = "You are the financial assistant inside the Balance app. " +
            "The app provides financial facts that are authoritative and computed " +
            "locally. Use ONLY those facts. Never invent amounts, balances, " +
            "categories, transactions, or goal progress, and never read data you " +
            "were not given. If a fact needed to answer is missing, say so plainly. " +
            "Keep answers concise, clear, and practical.",
        safetySettings = listOf(
            AiSafetySetting(AiSafetyTopic.HARASSMENT, AiSafetyThreshold.MEDIUM_AND_ABOVE),
            AiSafetySetting(AiSafetyTopic.HATE_SPEECH, AiSafetyThreshold.MEDIUM_AND_ABOVE),
            AiSafetySetting(AiSafetyTopic.SEXUALLY_EXPLICIT, AiSafetyThreshold.MEDIUM_AND_ABOVE),
            AiSafetySetting(AiSafetyTopic.DANGEROUS_CONTENT, AiSafetyThreshold.MEDIUM_AND_ABOVE),
        ),
        maxCallsPerWindow = 20,
        windowMillis = 60 * 60 * 1000L,
    )
}