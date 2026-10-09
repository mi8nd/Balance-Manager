package com.personal.balance.domain.ai

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AskAiUseCaseTest {

    private val request = AiRequest(question = "How am I doing?", structuredFacts = mapOf("balance" to "25.00 EGP"))

    private class FakeAuthProvider(var authenticated: Boolean) : AiAuthProvider {
        var checks = 0
        override fun isAuthenticated(): Boolean {
            checks++
            return authenticated
        }
    }

    private class FakeUsageController(var allowed: Boolean, private val recorded: MutableList<Int>) : AiUsageController {
        var allowChecks = 0
        override suspend fun isCallAllowed(): Boolean {
            allowChecks++
            return allowed
        }

        override suspend fun recordCall() {
            recorded.add(1)
        }
    }

    private class FakeAssistant(
        private val result: AiResult,
        private val calls: MutableList<AiRequest>,
    ) : AiAssistantRepository {
        override suspend fun ask(request: AiRequest): AiResult {
            calls.add(request)
            return result
        }
    }

    @Test
    fun unauthenticated_userIsRejectedBeforeAnyCall() = runBlocking {
        val recorded = mutableListOf<Int>()
        val calls = mutableListOf<AiRequest>()
        val useCase = AskAiUseCase(
            authProvider = FakeAuthProvider(authenticated = false),
            usageController = FakeUsageController(allowed = true, recorded = recorded),
            assistant = FakeAssistant(AiResult.Success("ok", 5), calls),
        )

        val result = useCase(request)

        assertTrue(result is AiResult.Error.Unauthenticated)
        assertTrue(calls.isEmpty())
        assertTrue(recorded.isEmpty())
    }

    @Test
    fun usageLimited_callsAreRejectedBeforeAssistant() = runBlocking {
        val recorded = mutableListOf<Int>()
        val calls = mutableListOf<AiRequest>()
        val useCase = AskAiUseCase(
            authProvider = FakeAuthProvider(authenticated = true),
            usageController = FakeUsageController(allowed = false, recorded = recorded),
            assistant = FakeAssistant(AiResult.Success("ok", 5), calls),
        )

        val result = useCase(request)

        assertTrue(result is AiResult.Error.RateLimited)
        assertTrue(calls.isEmpty())
        assertTrue(recorded.isEmpty())
    }

    @Test
    fun authenticatedAndAllowed_delegatesAndRecords() = runBlocking {
        val recorded = mutableListOf<Int>()
        val calls = mutableListOf<AiRequest>()
        val useCase = AskAiUseCase(
            authProvider = FakeAuthProvider(authenticated = true),
            usageController = FakeUsageController(allowed = true, recorded = recorded),
            assistant = FakeAssistant(AiResult.Success("answer", 7), calls),
        )

        val result = useCase(request)

        assertEquals(AiResult.Success("answer", 7), result)
        assertEquals(listOf(request), calls)
        assertEquals(1, recorded.size)
    }

    @Test
    fun assistantFailure_passesThroughAndStillRecords() = runBlocking {
        val recorded = mutableListOf<Int>()
        val calls = mutableListOf<AiRequest>()
        val error = AiResult.Error.EmptyResponse(
            userSafeMessage = "no answer",
            diagnostic = "empty",
        )
        val useCase = AskAiUseCase(
            authProvider = FakeAuthProvider(authenticated = true),
            usageController = FakeUsageController(allowed = true, recorded = recorded),
            assistant = FakeAssistant(error, calls),
        )

        val result = useCase(request)

        assertEquals(error, result)
        assertEquals(1, calls.size)
        assertEquals(1, recorded.size)
    }
}