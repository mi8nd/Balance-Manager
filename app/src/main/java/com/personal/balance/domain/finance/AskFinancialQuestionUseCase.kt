package com.personal.balance.domain.finance

import com.personal.balance.domain.ai.AiResult
import com.personal.balance.domain.ai.AskAiUseCase
import java.time.ZoneId

/**
 * Answers a natural-language question about the user's own transactions:
 * read one bounded two-calendar-month window through [FinancialTransactionsSource],
 * compute a deterministic [FinancialSnapshot], build a compact [AiRequest] with
 * [AiFinancialContextBuilder], then delegate to the existing [AskAiUseCase] gate
 * (auth, local usage budget, AI backend). Never touches Firestore directly.
 */
class AskFinancialQuestionUseCase(
    private val currentUserId: () -> String?,
    private val transactionsSource: FinancialTransactionsSource,
    private val analyzer: FinancialAnalyzer = FinancialAnalyzer,
    private val contextBuilder: AiFinancialContextBuilder = AiFinancialContextBuilder,
    private val askAi: AskAiUseCase,
    private val nowEpochMillis: () -> Long = { System.currentTimeMillis() },
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val balanceSource: suspend (uid: String) -> Long? = { null },
) {

    suspend operator fun invoke(question: String): AiResult {
        val uid = currentUserId()
        if (uid == null) {
            return AiResult.Error.Unauthenticated(
                userSafeMessage = "You must be signed in to use the AI assistant.",
            )
        }

        val now = nowEpochMillis()
        val window = FinancialWindows.windowAt(now, zone)
        val loadResult = transactionsSource.loadTransactions(
            uid = uid,
            startEpochMillis = window.previousMonthStartEpochMillis,
            endEpochMillis = window.currentMonthEndEpochMillis,
        )

        val transactions = when (loadResult) {
            is FinancialTransactionsResult.Success -> loadResult.transactions
            is FinancialTransactionsResult.Error -> {
                return AiResult.Error.NetworkFailure(
                    userSafeMessage = "Couldn't load your recent transactions. Please try again.",
                    diagnostic = "transactions.read_failed",
                )
            }
        }

        val snapshot = analyzer.analyze(transactions, now, zone)
        val balance = balanceSource(uid)
        val snapshotWithBalance = if (balance != null) {
            snapshot.copy(currentBalance = balance)
        } else {
            snapshot
        }
        val request = contextBuilder.build(question, snapshotWithBalance, zone)
        return askAi(request)
    }
}