package com.personal.balance.domain.category

import com.personal.balance.domain.ai.AiRequest
import com.personal.balance.domain.ai.AiResult
import com.personal.balance.domain.ai.AiAssistantRepository
import com.personal.balance.domain.ai.AiAuthProvider
import com.personal.balance.domain.ai.AiUsageController
import com.personal.balance.domain.ai.AskAiUseCase
import com.personal.balance.domain.finance.FinancialAnalyzer
import com.personal.balance.domain.finance.TransactionIntelligenceAnalyzer
import com.personal.balance.model.Transaction
import com.personal.balance.model.TransactionType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class CategorizeTransactionUseCaseTest {

    private val zone = ZoneId.of("UTC")
    private val timestamp = Instant.parse("2025-06-10T09:30:00Z").toEpochMilli()

    private fun expense(description: String, amount: Long = 100_00, category: String = "") = Transaction(
        id = "tx-1",
        userId = "user-123",
        type = TransactionType.EXPENSE,
        amount = amount,
        description = description,
        category = category,
        timestamp = timestamp,
    )

    private fun income(description: String, amount: Long = 100_00, category: String = "") = Transaction(
        id = "tx-1",
        userId = "user-123",
        type = TransactionType.INCOME,
        amount = amount,
        description = description,
        category = category,
        timestamp = timestamp,
    )

    private class FakeAuthProvider(var authenticated: Boolean) : AiAuthProvider {
        override fun isAuthenticated(): Boolean = authenticated
    }

    private class FakeUsageController(var allowed: Boolean) : AiUsageController {
        override suspend fun isCallAllowed(): Boolean = allowed
        override suspend fun recordCall() = Unit
    }

    private class FakeAssistant(
        var result: (AiRequest) -> AiResult,
        val calls: MutableList<AiRequest> = mutableListOf(),
        private val throwOn: (AiRequest) -> Boolean = { false },
    ) : AiAssistantRepository {
        override suspend fun ask(request: AiRequest): AiResult {
            calls.add(request)
            if (throwOn(request)) throw RuntimeException("backend exploded")
            return result(request)
        }
    }

    private fun useCase(
        authenticated: Boolean = true,
        allowed: Boolean = true,
        assistant: FakeAssistant,
    ): Pair<CategorizeTransactionUseCase, FakeAssistant> {
        val askAi = AskAiUseCase(
            authProvider = FakeAuthProvider(authenticated),
            usageController = FakeUsageController(allowed),
            assistant = assistant,
        )
        return CategorizeTransactionUseCase(askAi, zone) to assistant
    }

    // ---------------------------------------------------------------- AI success

    @Test
    fun aiSuccess_storesValidatedCategory_preservingAllOtherFields() = runBlocking {
        val success = { _: AiRequest -> AiResult.Success("""{"category": "Food"}""", 3) }
        val (categorizer, _) = useCase(assistant = FakeAssistant(success))

        val stored = categorizer.categorize(expense("Groceries"))

        assertEquals("Food", stored.category)
        assertEquals("tx-1", stored.id)
        assertEquals("user-123", stored.userId)
        assertEquals(TransactionType.EXPENSE, stored.type)
        assertEquals(100_00, stored.amount)
        assertEquals("Groceries", stored.description)
        assertEquals(timestamp, stored.timestamp)
    }

    @Test
    fun aiSuccess_canonicalizesSpellingAndIsTypeAware() = runBlocking {
        val cased = { _: AiRequest -> AiResult.Success("""{"category": "food"}""", 3) }
        val (categorizer, _) = useCase(assistant = FakeAssistant(cased))
        assertEquals("Food", categorizer.categorize(expense("Groceries")).category)

        val incomeCased = { _: AiRequest -> AiResult.Success("""{"category": "GIFTS"}""", 3) }
        val (incomeCategorizer, _) = useCase(assistant = FakeAssistant(incomeCased))
        assertEquals("Gifts", incomeCategorizer.categorize(income("Gift")).category)
    }

    // ------------------------------------------------- AI validation failures

    @Test
    fun aiReturnsUnknownCategory_fallsBackToDeterministicClassifier() = runBlocking {
        val reply = { _: AiRequest -> AiResult.Success("""{"category": "Restaurants & Dining"}""", 4) }
        val (categorizer, _) = useCase(assistant = FakeAssistant(reply))

        assertEquals("Food", categorizer.categorize(expense("Restaurant dinner")).category)
    }

    @Test
    fun aiReturnsMalformedReply_fallsBackToDeterministicClassifier() = runBlocking {
        val reply = { _: AiRequest -> AiResult.Success("this is not json at all", 5) }
        val (categorizer, _) = useCase(assistant = FakeAssistant(reply))

        assertEquals("Transport", categorizer.categorize(expense("Took an Uber home")).category)
    }

    @Test
    fun aiReturnsEmptyReply_fallsBackToDeterministicClassifier() = runBlocking {
        val reply = { _: AiRequest -> AiResult.Success("", 0) }
        val (categorizer, _) = useCase(assistant = FakeAssistant(reply))

        assertEquals("Housing", categorizer.categorize(expense("Paid my apartment")).category)
    }

    // ------------------------------------------------------------- failure modes

    @Test
    fun budgetUnavailable_stillCategorizesLocally() = runBlocking {
        // AskAiUseCase turns isCallAllowed() == false into AiResult.Error.RateLimited.
        val (categorizer, assistant) = useCase(allowed = false, assistant = FakeAssistant(result = { AiResult.Success("x", 1) }))

        val stored = categorizer.categorize(expense("Groceries"))

        // Assistant must never be reached when the shared budget is exhausted.
        assertTrue(assistant.calls.isEmpty())
        assertEquals("Food", stored.category)
        assertEquals("Groceries", stored.description) // never lost or corrupted
    }

    @Test
    fun unauthenticated_stillCategorizesLocally() = runBlocking {
        val (categorizer, assistant) = useCase(authenticated = false, assistant = FakeAssistant(result = { AiResult.Success("x", 1) }))

        val stored = categorizer.categorize(expense("Groceries"))

        assertTrue(assistant.calls.isEmpty())
        assertEquals("Food", stored.category)
    }

    @Test
    fun aiErrorResponses_fallBackToDeterministicClassifier() = runBlocking {
        val errors = listOf<AiResult>(
            AiResult.Error.NetworkFailure(userSafeMessage = "offline", diagnostic = "net"),
            AiResult.Error.ModelUnavailable(userSafeMessage = "down", diagnostic = "model"),
            AiResult.Error.BlockedBySafety(userSafeMessage = "blocked", diagnostic = "safety"),
            AiResult.Error.UnexpectedFailure(userSafeMessage = "boom", diagnostic = "boom"),
            AiResult.Error.Timeout(userSafeMessage = "late", diagnostic = "timeout"),
        )
        errors.forEach { error ->
            val (categorizer, _) = useCase(assistant = FakeAssistant(result = { error }))
            assertEquals("Food", categorizer.categorize(expense("Groceries")).category)
        }
    }

    @Test
    fun aiAssistantThrowsException_fallsBackToDeterministicClassifier() = runBlocking {
        val (categorizer, assistant) = useCase(
            assistant = FakeAssistant(
                result = { AiResult.Success("x", 1) },
                throwOn = { true },
            ),
        )

        val stored = categorizer.categorize(expense("Clothes"))

        assertEquals(1, assistant.calls.size)
        assertEquals("Shopping", stored.category)
    }

    @Test
    fun aiAssistantThrowsCoroutineCancellation_isRethrownNotSwallowed() = runBlocking {
        val throwing = object : AiAssistantRepository {
            override suspend fun ask(request: AiRequest): AiResult {
                throw CancellationException("cancelled")
            }
        }
        val askAi = AskAiUseCase(
            authProvider = FakeAuthProvider(true),
            usageController = FakeUsageController(true),
            assistant = throwing,
        )
        val categorizer = CategorizeTransactionUseCase(askAi, zone)

        var rethrown = false
        try {
            categorizer.categorize(expense("Groceries"))
        } catch (e: CancellationException) {
            rethrown = true
        }
        assertTrue("cancellation must propagate to keep coroutine cancellation semantics", rethrown)
    }

    // ------------------------------------------------------- blank & unknown

    @Test
    fun blankDescription_shortCircuitsToGeneral_withoutCallingAssistant() = runBlocking {
        val (categorizer, assistant) = useCase(assistant = FakeAssistant(result = { AiResult.Success("""{"category": "Food"}""", 1) }))

        val stored = categorizer.categorize(expense("   "))

        assertTrue(assistant.calls.isEmpty())
        assertEquals("General", stored.category)
    }

    @Test
    fun unrecognizableDescription_withAiUnavailable_usesOtherBucket() = runBlocking {
        val (categorizer, _) = useCase(authenticated = false, assistant = FakeAssistant(result = { AiResult.Success("x", 1) }))

        assertEquals("Other Expense", categorizer.categorize(expense("Unidentifiable line item")).category)
        assertEquals("Other Income", categorizer.categorize(income("Mysterious envelope of cash")).category)
    }

    // -------------------------------------------------------------- privacy

    @Test
    fun aiRequest_sendsOnlyOwnTransactionFields_neverPrivateIdentifiers() = runBlocking {
        val captured = mutableListOf<AiRequest>()
        val (categorizer, _) = useCase(assistant = FakeAssistant(
            result = { AiResult.Success("""{"category": "Transport"}""", 2) },
            calls = captured,
        ))

        categorizer.categorize(expense("Uber", amount = 25_00))

        assertEquals(1, captured.size)
        val facts = captured[0].structuredFacts
        assertEquals(
            setOf(
                "transaction_type",
                "transaction_description",
                "transaction_amount_piastres",
                "transaction_amount",
                "transaction_date",
            ),
            facts.keys,
        )
        assertEquals("EXPENSE", facts["transaction_type"])
        assertEquals("Uber", facts["transaction_description"])
        assertEquals("2500", facts["transaction_amount_piastres"])
        assertEquals("25.00 EGP", facts["transaction_amount"])
        assertEquals("2025-06-10", facts["transaction_date"])

        // No UID, document id, token, password, or balance may ever be sent.
        val flat = (facts.keys + facts.values + captured[0].additionalInstructions + captured[0].question)
            .joinToString(" ").lowercase()
        listOf("uid", "doc", "token", "password", "currentbalance", "balance", "othertransaction")
            .forEach { forbidden ->
                assertTrue("AI request must not leak '$forbidden'", !flat.contains(forbidden))
            }
    }

    @Test
    fun aiRequest_instructsExactAllowedCategoriesAndJsonReply() = runBlocking {
        val captured = mutableListOf<AiRequest>()
        val assistant = FakeAssistant(
            result = { AiResult.Success("""{"category": "Food"}""", 1) },
            calls = captured,
        )
        val (categorizer, _) = useCase(assistant = assistant)
        categorizer.categorize(expense("Groceries"))

        val instructions = captured[0].additionalInstructions.orEmpty()
        assertTrue(instructions.contains("Housing"))
        assertTrue(instructions.contains("Other Expense"))
        assertTrue(instructions.contains("""{"category": "<one allowed category>"}"""))
    }

    // ------------------------------------------------------------- invariant

    @Test
    fun everyResultCategory_isAuthoritative() = runBlocking {
        val descriptions = listOf(
            "Groceries", "Uber", "Games", "Medicine", "Paid my apartment",
            "Bought a new pair of jeans", "Something indescribable",
        )
        descriptions.forEach { description ->
            val (categorizer, _) = useCase(authenticated = false, assistant = FakeAssistant(result = { AiResult.Success("x", 1) }))
            val stored = categorizer.categorize(expense(description))
            assertTrue(
                "stored category '${stored.category}' must be from the authoritative set",
                stored.category == TransactionCategorySet.GENERAL ||
                    stored.category in TransactionCategorySet.allowedFor(TransactionType.EXPENSE),
            )
        }
    }

    // --------------------------------------- regression: existing consumers

    @Test
    fun categorizedTransaction_feedsFinancialAnalysisCategoryTotals() = runBlocking {
        val success = { _: AiRequest -> AiResult.Success("""{"category": "Food"}""", 3) }
        val (categorizer, _) = useCase(assistant = FakeAssistant(success))
        val stored = categorizer.categorize(expense("Groceries", amount = 2_100))
        val now = Instant.parse("2025-06-15T12:00:00Z").toEpochMilli()

        val snapshot = FinancialAnalyzer.analyze(listOf(stored), now, zone)

        val foodTotal = snapshot.categoryExpenseTotals.first { it.category == "Food" }
        assertEquals(2_100, foodTotal.totalPiastres)
        // The categorized transaction must NOT collapse into the legacy General bucket.
        assertTrue(snapshot.categoryExpenseTotals.none { it.category == "General" })
    }

    @Test
    fun categorizedTransactions_feedsTransactionIntelligenceCategoryDeltas() = runBlocking {
        val success = { _: AiRequest -> AiResult.Success("""{"category": "Food"}""", 3) }
        val (categorizer, _) = useCase(assistant = FakeAssistant(success))
        val first = categorizer.categorize(expense("Groceries", amount = 1_000))
        val second = categorizer.categorize(expense("Dinner", amount = 500))
        val now = Instant.parse("2025-06-15T12:00:00Z").toEpochMilli()

        val snapshot = TransactionIntelligenceAnalyzer.analyze(listOf(first, second), now, zone)

        val food = snapshot.categoryDeltas.first { it.category == "Food" }
        assertEquals(1_500, food.currentTotal)
        assertEquals(0L, food.previousTotal)
    }
}