package com.personal.balance.domain.category

import com.personal.balance.domain.EGPFormatter
import com.personal.balance.domain.ai.AiRequest
import com.personal.balance.domain.ai.AiResult
import com.personal.balance.domain.ai.AskAiUseCase
import com.personal.balance.model.Transaction
import com.personal.balance.model.TransactionType
import kotlinx.coroutines.CancellationException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Automatically determines the authoritative category for a user-entered
 * transaction. The returned [Transaction] is an exact copy of the input with
 * `category` replaced by a validated, canonical category — whatever lands on
 * the stored [com.personal.balance.model.Transaction.category] field is always
 * a legitimate string from [TransactionCategorySet].
 *
 * Hierarchy (one validation point for all future category writers):
 *  1. **AI semantic classification** — the model is given the description as a
 *     natural-language signal (primary), plus amount and date as supporting
 *     context, and must return exactly one canonical category. The request
 *     traverses the existing [AskAiUseCase] gate (authenticated user + shared
 *     rolling [com.personal.balance.data.ai.SharedAiUsageController] budget);
 *     the caller is never charged a separate budget.
 *  2. **Deterministic fallback** — normalised keyword substring matching on the
 *     description, type-aware so the same word can diverge by income/expense.
 *  3. **General / Other bucket** — blank descriptions yield "General" (matching
 *     the existing `FinancialAnalyzer.displayCategory` convention); unmatched
 *     non-blank descriptions yield "Other Expense" / "Other Income".
 *
 * Privacy: the AI request carries only the transaction's own fields (type,
 * description, amount, date) — never any UID, document ID, authentication
 * token, or other transactions.
 */
class CategorizeTransactionUseCase(
    private val askAi: AskAiUseCase,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {

    suspend fun categorize(transaction: Transaction): Transaction {
        if (transaction.description.isBlank()) {
            return transaction.copy(category = TransactionCategorySet.GENERAL)
        }
        val category = requestAiCategory(transaction)
            ?: DeterministicCategoryClassifier.classify(transaction.type, transaction.description)
        return transaction.copy(category = category)
    }

    private suspend fun requestAiCategory(tx: Transaction): String? = try {
        val result = askAi(buildRequest(tx))
        if (result is AiResult.Success) AiCategoryParser.parse(result.text, tx.type) else null
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    private fun buildRequest(tx: Transaction): AiRequest {
        val allowed = TransactionCategorySet.allowedFor(tx.type)
        val dateStr = Instant.ofEpochMilli(tx.timestamp)
            .atZone(zone)
            .toLocalDate()
            .format(DateTimeFormatter.ISO_LOCAL_DATE)
        return AiRequest(
            question = "Categorize this single transaction.",
            structuredFacts = mapOf(
                "transaction_type" to tx.type.name,
                "transaction_description" to tx.description,
                "transaction_amount_piastres" to tx.amount.toString(),
                "transaction_amount" to EGPFormatter.formatAmount(tx.amount),
                "transaction_date" to dateStr,
            ),
            additionalInstructions = buildInstructions(tx.type, allowed),
        )
    }

    private fun buildInstructions(type: TransactionType, allowed: List<String>): String = buildString {
        append("Categorize the transaction described by the facts into exactly one category ")
        append("from this allowed list: ")
        append(allowed.joinToString(", "))
        appendLine(".")
        append("The description is the primary semantic clue; the type and amount provide supporting context. ")
        append("Reply with exactly one JSON object and nothing else: {\"category\": \"<one allowed category>\"}. ")
        append("Use the exact spelling from the list. If the real-world category is not listed ")
        append("(e.g. \"Restaurants & Dining\"), map it to the closest allowed category (e.g. \"Food\"). ")
        append("\"Other Expense\"/\"Other Income\" is permitted only when no other allowed category genuinely fits.")
    }
}