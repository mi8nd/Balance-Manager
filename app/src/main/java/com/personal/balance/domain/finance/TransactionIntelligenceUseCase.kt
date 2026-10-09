package com.personal.balance.domain.finance

import com.personal.balance.domain.ai.AiResult
import com.personal.balance.domain.ai.AskAiUseCase
import java.time.ZoneId

/**
 * Outcome of a Transaction Intelligence run.
 *
 * [NoData] and [InsufficientData] are returned WITHOUT calling the model at all —
 * the deterministic analysis already decides there is nothing useful to interpret,
 * so the local usage budget is never spent on empty or near-empty histories.
 */
sealed interface TransactionIntelligenceResult {
    data class Success(val insights: List<TransactionInsight>) : TransactionIntelligenceResult

    /** The bounded window contains no transactions at all. */
    data object NoData : TransactionIntelligenceResult

    /** Too few transactions to ground any pattern claim (see [TransactionIntelligenceSnapshot.hasEnoughData]). */
    data object InsufficientData : TransactionIntelligenceResult

    data class Error(val error: AiResult.Error) : TransactionIntelligenceResult
}

/**
 * Proactively analyzes the user's own bounded transaction history: reads one
 * two-calendar-month window through [FinancialTransactionsSource], computes a
 * deterministic [TransactionIntelligenceSnapshot], builds a compact [AiRequest]
 * (structured facts only — no raw descriptions), then delegates to the existing
 * [AskAiUseCase] gate (auth, shared local usage budget, AI backend). Never touches
 * Firestore directly.
 */
class TransactionIntelligenceUseCase(
    private val currentUserId: () -> String?,
    private val transactionsSource: FinancialTransactionsSource,
    private val analyzer: TransactionIntelligenceAnalyzer = TransactionIntelligenceAnalyzer,
    private val contextBuilder: TransactionIntelligenceContextBuilder = TransactionIntelligenceContextBuilder,
    private val askAi: AskAiUseCase,
    private val nowEpochMillis: () -> Long = { System.currentTimeMillis() },
    private val zone: ZoneId = ZoneId.systemDefault(),
) {

    /**
     * Pre-flight step (reused by the orchestrator): authenticates, loads the
     * bounded window, and computes the deterministic snapshot without touching
     * the model. The caller can then gate on [TransactionIntelligenceSnapshot]
     * data sufficiency or call [invoke] to complete the request.
     */
    sealed interface Preparation {
        data class Ready(val snapshot: TransactionIntelligenceSnapshot) : Preparation
        data class Rejected(val result: TransactionIntelligenceResult) : Preparation
    }

    suspend fun prepare(): Preparation {
        val uid = currentUserId()
        if (uid == null) {
            return Preparation.Rejected(
                TransactionIntelligenceResult.Error(
                    AiResult.Error.Unauthenticated(
                        userSafeMessage = "You must be signed in to use Transaction Intelligence.",
                    ),
                ),
            )
        }
        val now = nowEpochMillis()
        val window = FinancialWindows.windowAt(now, zone)
        val loadResult = transactionsSource.loadTransactions(
            uid = uid,
            startEpochMillis = window.previousMonthStartEpochMillis,
            endEpochMillis = window.currentMonthEndEpochMillis,
        )
        return when (loadResult) {
            is FinancialTransactionsResult.Success -> Preparation.Ready(
                analyzer.analyze(loadResult.transactions, now, zone),
            )

            is FinancialTransactionsResult.Error -> Preparation.Rejected(
                TransactionIntelligenceResult.Error(
                    AiResult.Error.NetworkFailure(
                        userSafeMessage = "Couldn't load your transactions. Please try again.",
                        diagnostic = "transactions.read_failed",
                    ),
                ),
            )
        }
    }

    suspend operator fun invoke(): TransactionIntelligenceResult {
        return when (val prepared = prepare()) {
            is Preparation.Rejected -> prepared.result

            is Preparation.Ready -> {
                val snapshot = prepared.snapshot
                if (snapshot.windowTransactionCount == 0) return TransactionIntelligenceResult.NoData
                if (!snapshot.hasEnoughData) return TransactionIntelligenceResult.InsufficientData

                val request = contextBuilder.build(snapshot, zone)
                return when (val aiResult = askAi(request)) {
                    is AiResult.Success -> TransactionIntelligenceResult.Success(
                        TransactionInsightParser.parse(aiResult.text),
                    )

                    is AiResult.Error -> TransactionIntelligenceResult.Error(aiResult)
                }
            }
        }
    }
}