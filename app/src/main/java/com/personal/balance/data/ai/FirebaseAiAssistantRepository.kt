package com.personal.balance.data.ai

import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.APINotConfiguredException
import com.google.firebase.ai.type.Content
import com.google.firebase.ai.type.FinishReason
import com.google.firebase.ai.type.FirebaseAIException
import com.google.firebase.ai.type.GenerationConfig
import com.google.firebase.ai.type.HarmBlockThreshold
import com.google.firebase.ai.type.HarmCategory
import com.google.firebase.ai.type.InvalidAPIKeyException
import com.google.firebase.ai.type.InvalidLocationException
import com.google.firebase.ai.type.InvalidStateException
import com.google.firebase.ai.type.PermissionMissingException
import com.google.firebase.ai.type.PromptBlockedException
import com.google.firebase.ai.type.ContentBlockedException
import com.google.firebase.ai.type.QuotaExceededException
import com.google.firebase.ai.type.RequestTimeoutException
import com.google.firebase.ai.type.ResponseStoppedException
import com.google.firebase.ai.type.SafetySetting
import com.google.firebase.ai.type.ServerException
import com.google.firebase.ai.type.ServiceDisabledException
import com.google.firebase.ai.type.TextPart
import com.google.firebase.ai.type.UnknownException
import com.google.firebase.ai.type.UnsupportedUserLocationException
import com.personal.balance.domain.ai.AiAssistantRepository
import com.personal.balance.domain.ai.AiConfig
import com.personal.balance.domain.ai.AiResult
import com.personal.balance.domain.ai.AiRequest
import com.personal.balance.domain.ai.ChatSender
import com.personal.balance.domain.ai.DefaultAiConfig
import java.io.IOException
import kotlinx.coroutines.CancellationException

/**
 * Firebase AI Logic (Gemini Developer API) backend. Config values come from the
 * domain [AiConfig]; Gemini is given only the structured facts of an [AiRequest]
 * and never any database access. Every failure is translated into a domain
 * [AiResult.Error] — nothing throws at the boundary, and raw API messages are
 * never exported (diagnostics carry only the exception class name).
 */
class FirebaseAiAssistantRepository(
    private val aiConfig: AiConfig = DefaultAiConfig.instance,
) : AiAssistantRepository {

    private val model: com.google.firebase.ai.GenerativeModel by lazy {
        val firebaseAi = Firebase.ai(backend = GenerativeBackend.googleAI())
        firebaseAi.generativeModel(
            modelName = aiConfig.modelName,
            generationConfig = GenerationConfig.builder()
                .setMaxOutputTokens(aiConfig.maxOutputTokens)
                .setResponseMimeType(aiConfig.responseMimeType)
                .build(),
            safetySettings = aiConfig.safetySettings.map { setting ->
                SafetySetting(
                    setting.topic.toFirebase(),
                    setting.threshold.toFirebase(),
                )
            },
            systemInstruction = Content(parts = listOf(TextPart(aiConfig.systemPrompt))),
        )
    }

    override suspend fun ask(request: AiRequest): AiResult {
        return try {
            val response = model.generateContent(userContent(request))
            toSuccess(response)
        } catch (e: CancellationException) {
            throw e
        } catch (e: PromptBlockedException) {
            blocked(e)
        } catch (e: ContentBlockedException) {
            blocked(e)
        } catch (e: QuotaExceededException) {
            AiResult.Error.RateLimited(
                userSafeMessage = "Too many requests. Please try again later.",
                diagnostic = diagnostic(e),
            )
        } catch (e: RequestTimeoutException) {
            AiResult.Error.Timeout(
                userSafeMessage = "The assistant took too long. Please try again.",
                diagnostic = diagnostic(e),
            )
        } catch (e: APINotConfiguredException) {
            modelUnavailable(e)
        } catch (e: ServiceDisabledException) {
            modelUnavailable(e)
        } catch (e: UnsupportedUserLocationException) {
            modelUnavailable(e)
        } catch (e: PermissionMissingException) {
            AiResult.Error.AppCheckFailure(
                userSafeMessage = "The app security check failed. Please try again.",
                diagnostic = diagnostic(e),
            )
        } catch (e: InvalidAPIKeyException) {
            unexpected(e)
        } catch (e: InvalidStateException) {
            unexpected(e)
        } catch (e: InvalidLocationException) {
            unexpected(e)
        } catch (e: ServerException) {
            AiResult.Error.NetworkFailure(
                userSafeMessage = "Could not reach the assistant. Check your connection and try again.",
                diagnostic = diagnostic(e),
            )
        } catch (e: UnknownException) {
            unexpected(e)
        } catch (e: IOException) {
            AiResult.Error.NetworkFailure(
                userSafeMessage = "Could not reach the assistant. Check your connection and try again.",
                diagnostic = diagnostic(e),
            )
        } catch (e: ResponseStoppedException) {
            unexpected(e)
        } catch (e: FirebaseAIException) {
            unexpected(e)
        }
    }

    private fun userContent(request: AiRequest): Content {
        val message = buildString {
            if (request.conversation.isNotEmpty()) {
                appendLine("Recent conversation (oldest first):")
                request.conversation.forEach { turn ->
                    when (turn.sender) {
                        ChatSender.USER -> appendLine("  user: ${turn.text}")
                        ChatSender.ASSISTANT -> appendLine("  assistant: ${turn.text}")
                    }
                }
                appendLine()
            }
            if (request.structuredFacts.isNotEmpty()) {
                appendLine("Authoritative facts computed by the app (use these only):")
                request.structuredFacts.forEach { (label, value) ->
                    appendLine("  - $label: $value")
                }
                appendLine()
            }
            append("Question: ").append(request.question)
            request.additionalInstructions?.let {
                appendLine()
                appendLine()
                append(it)
            }
        }
        return Content(role = "user", parts = listOf(TextPart(message)))
    }

    private fun toSuccess(response: com.google.firebase.ai.type.GenerateContentResponse): AiResult {
        val finishReason = response.candidates.firstOrNull()?.finishReason
        if (finishReason == FinishReason.SAFETY ||
            finishReason == FinishReason.BLOCKLIST ||
            finishReason == FinishReason.PROHIBITED_CONTENT
        ) {
            return AiResult.Error.BlockedBySafety(
                userSafeMessage = "This question can't be answered by the assistant.",
                diagnostic = "finishReason=$finishReason",
            )
        }

        val text = response.text
        if (text.isNullOrBlank()) {
            return AiResult.Error.EmptyResponse(
                userSafeMessage = "The assistant returned no answer. Please try a different question.",
                diagnostic = "finishReason=$finishReason",
            )
        }

        val tokenCount = response.usageMetadata?.let { it.totalTokenCount }
        return AiResult.Success(text = text, tokenCount = tokenCount)
    }

    private fun blocked(e: Exception) = AiResult.Error.BlockedBySafety(
        userSafeMessage = "This question can't be answered by the assistant.",
        diagnostic = diagnostic(e),
    )

    private fun modelUnavailable(e: Exception) = AiResult.Error.ModelUnavailable(
        userSafeMessage = "The assistant is temporarily unavailable. Please try again later.",
        diagnostic = diagnostic(e),
    )

    private fun unexpected(e: Exception) = AiResult.Error.UnexpectedFailure(
        userSafeMessage = "Something went wrong. Please try again later.",
        diagnostic = diagnostic(e),
    )

    private fun diagnostic(e: Exception): String {
        if (e is ResponseStoppedException) {
            e.message?.let { message ->
                if (message.isNotBlank()) return "${e::class.java.simpleName}: $message"
            }
            e.response.candidates.firstOrNull()?.finishReason?.let { reason ->
                return "${e::class.java.simpleName}: $reason"
            }
        }
        return e::class.java.simpleName
    }
}

private fun com.personal.balance.domain.ai.AiSafetyTopic.toFirebase(): HarmCategory =
    when (this) {
        com.personal.balance.domain.ai.AiSafetyTopic.HARASSMENT -> HarmCategory.HARASSMENT
        com.personal.balance.domain.ai.AiSafetyTopic.HATE_SPEECH -> HarmCategory.HATE_SPEECH
        com.personal.balance.domain.ai.AiSafetyTopic.SEXUALLY_EXPLICIT -> HarmCategory.SEXUALLY_EXPLICIT
        com.personal.balance.domain.ai.AiSafetyTopic.DANGEROUS_CONTENT -> HarmCategory.DANGEROUS_CONTENT
    }

private fun com.personal.balance.domain.ai.AiSafetyThreshold.toFirebase(): HarmBlockThreshold =
    when (this) {
        com.personal.balance.domain.ai.AiSafetyThreshold.LOW_AND_ABOVE -> HarmBlockThreshold.LOW_AND_ABOVE
        com.personal.balance.domain.ai.AiSafetyThreshold.MEDIUM_AND_ABOVE -> HarmBlockThreshold.MEDIUM_AND_ABOVE
        com.personal.balance.domain.ai.AiSafetyThreshold.ONLY_HIGH -> HarmBlockThreshold.ONLY_HIGH
        com.personal.balance.domain.ai.AiSafetyThreshold.NONE -> HarmBlockThreshold.NONE
        com.personal.balance.domain.ai.AiSafetyThreshold.OFF -> HarmBlockThreshold.OFF
    }