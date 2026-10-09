package com.personal.balance.domain.insights

import com.personal.balance.domain.ai.AiResult
import com.personal.balance.domain.ai.AskAiUseCase
import com.personal.balance.domain.ai.orchestration.GoalCoachSource
import com.personal.balance.domain.finance.FinancialTransactionsResult
import com.personal.balance.domain.finance.FinancialTransactionsSource
import com.personal.balance.domain.finance.FinancialWindows
import com.personal.balance.domain.finance.TransactionInsight
import kotlinx.coroutines.CancellationException
import java.time.ZoneId

/**
 * Outcome of a Financial Insights run.
 *
 * [NoData] and [InsufficientData] return WITHOUT calling the model at all: the
 * deterministic analysis already decides there is nothing useful to interpret,
 * so the device-local AI budget is never spent on empty or near-empty histories.
 */
sealed interface FinancialInsightsResult {
    data class Success(
        val snapshot: FinancialInsightsSnapshot,
        val insights: List<TransactionInsight>,
        val recommendations: List<FinancialRecommendation>,
    ) : FinancialInsightsResult

    /** The bounded window contains no transactions at all. */
    data object NoData : FinancialInsightsResult

    /** Too few transactions to ground any pattern claim. */
    data object InsufficientData : FinancialInsightsResult

    data class Error(val error: AiResult.Error) : FinancialInsightsResult
}

/**
 * Proactively analyzes the user's own bounded financial picture: reads one
 * two-calendar-month window through [FinancialTransactionsSource], loads the
 * authoritative savings goal and balance through [GoalCoachSource]/balance
 * reads (always keyed to the CURRENT authenticated user), computes a
 * deterministic [FinancialInsightsSnapshot], builds a compact [AiRequest]
 * (structured facts only — no raw descriptions), then delegates to the existing
 * [AskAiUseCase] gate (auth, shared local usage budget, AI backend). Never
 * touches Firestore directly and never accepts a uid from the model/UI.
 */
class FinancialInsightsUseCase(
    private val currentUserId: () -> String?,
    private val transactionsSource: FinancialTransactionsSource,
    private val goalCoachSource: GoalCoachSource,
    private val balanceSource: suspend (uid: String) -> Long?,
    private val analyzer: FinancialInsightsAnalyzer = FinancialInsightsAnalyzer,
    private val contextBuilder: FinancialInsightsContextBuilder = FinancialInsightsContextBuilder,
    private val askAi: AskAiUseCase,
    private val nowEpochMillis: () -> Long = { System.currentTimeMillis() },
    private val zone: ZoneId = ZoneId.systemDefault(),
) {

    /**
     * Pre-flight step: authenticates, loads the bounded window + goal + balance,
     * and computes the deterministic snapshot WITHOUT touching the model. The
     * caller can gate on data sufficiency or call [invoke] to complete the
     * request (single AI call).
     */
    sealed interface Preparation {
        data class Ready(val snapshot: FinancialInsightsSnapshot) : Preparation
        data class Rejected(val result: FinancialInsightsResult) : Preparation
    }

    suspend fun prepare(): Preparation {
        val uid = currentUserId()
        if (uid == null) {
            return Preparation.Rejected(
                FinancialInsightsResult.Error(
                    AiResult.Error.Unauthenticated(
                        userSafeMessage = "You must be signed in to see your financial insights.",
                    ),
                ),
            )
        }
        return try {
            val now = nowEpochMillis()
            val window = FinancialWindows.windowAt(now, zone)
            val loadResult = transactionsSource.loadTransactions(
                uid = uid,
                startEpochMillis = window.previousMonthStartEpochMillis,
                endEpochMillis = window.currentMonthEndEpochMillis,
            )
            when (loadResult) {
                is FinancialTransactionsResult.Success -> {
                    val goal = goalCoachSource.load(uid)
                    val balance = balanceSource(uid)
                    Preparation.Ready(
                        analyzer.analyze(loadResult.transactions, now, zone, currentBalance = balance, goal = goal),
                    )
                }

                is FinancialTransactionsResult.Error -> Preparation.Rejected(
                    FinancialInsightsResult.Error(
                        AiResult.Error.NetworkFailure(
                            userSafeMessage = "Couldn't load your transactions. Please try again.",
                            diagnostic = "insights.transactions.read_failed",
                        ),
                    ),
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Preparation.Rejected(
                FinancialInsightsResult.Error(
                    AiResult.Error.NetworkFailure(
                        userSafeMessage = "Couldn't load your financial insights. Please try again.",
                        diagnostic = "insights.prepare_failed",
                    ),
                ),
            )
        }
    }

    suspend operator fun invoke(): FinancialInsightsResult {
        return when (val prepared = prepare()) {
            is Preparation.Rejected -> prepared.result

            is Preparation.Ready -> {
                val snapshot = prepared.snapshot
                if (snapshot.intelligence.windowTransactionCount == 0) return FinancialInsightsResult.NoData
                if (!snapshot.hasEnoughData) return FinancialInsightsResult.InsufficientData

                val request = contextBuilder.build(snapshot, zone)
                return when (val aiResult = askAi(request)) {
                    is AiResult.Success -> {
                        val parsed = FinancialInsightsParser.parse(aiResult.text)
                        FinancialInsightsResult.Success(snapshot, parsed.insights, parsed.recommendations)
                    }

                    is AiResult.Error -> FinancialInsightsResult.Error(aiResult)
                }
            }
        }
    }
}