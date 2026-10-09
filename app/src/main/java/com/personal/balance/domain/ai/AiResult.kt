package com.personal.balance.domain.ai

/**
 * Outcome of an AI request. [AiResult.Error.userSafeMessage] is the only text
 * meant for the user; [AiResult.Error.diagnostic] is for internal logging and
 * must never contain secrets, tokens, or raw API payloads.
 */
sealed interface AiResult {

    data class Success(
        val text: String,
        val tokenCount: Int?,
    ) : AiResult

    sealed interface Error : AiResult {
        val userSafeMessage: String
        val diagnostic: String

        data class Unauthenticated(override val userSafeMessage: String) : Error {
            override val diagnostic: String = "ai.auth.unauthenticated"
        }

        data class AppCheckFailure(
            override val userSafeMessage: String,
            override val diagnostic: String,
        ) : Error

        data class NetworkFailure(
            override val userSafeMessage: String,
            override val diagnostic: String,
        ) : Error

        data class RateLimited(
            override val userSafeMessage: String,
            override val diagnostic: String,
        ) : Error

        data class ModelUnavailable(
            override val userSafeMessage: String,
            override val diagnostic: String,
        ) : Error

        data class InvalidRequest(
            override val userSafeMessage: String,
            override val diagnostic: String,
        ) : Error

        data class BlockedBySafety(
            override val userSafeMessage: String,
            override val diagnostic: String,
        ) : Error

        data class UnexpectedFailure(
            override val userSafeMessage: String,
            override val diagnostic: String,
        ) : Error

        data class EmptyResponse(
            override val userSafeMessage: String,
            override val diagnostic: String,
        ) : Error

        data class Timeout(
            override val userSafeMessage: String,
            override val diagnostic: String,
        ) : Error

        data class Cancelled(
            override val userSafeMessage: String,
            override val diagnostic: String,
        ) : Error
    }
}