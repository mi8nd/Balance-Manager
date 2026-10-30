package com.personal.balance.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.personal.balance.data.FirebaseFinancialTransactionsSource
import com.personal.balance.data.FirestoreGoalCoachSource
import com.personal.balance.data.FirestoreUserRepository
import com.personal.balance.data.UserConfigResult
import com.personal.balance.domain.ai.AiStackFactory
import com.personal.balance.domain.finance.TransactionInsight
import com.personal.balance.domain.insights.FinancialInsightsResult
import com.personal.balance.domain.insights.FinancialInsightsSnapshot
import com.personal.balance.domain.insights.FinancialInsightsUseCase
import com.personal.balance.domain.insights.FinancialRecommendation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Dashboard AI Insights section state.
 *
 * [Ready]/[Error] carry a [stale] flag: when the dashboard reloads user data
 * (balance, transactions, target), the SIGNATURE changes and the previously
 * generated insight content is flagged stale instead of being silently shown as
 * current. The user can refresh (explicit budget spend) or ignore it.
 */
sealed interface DashboardInsightUiState {
    data object Idle : DashboardInsightUiState

    data object Loading : DashboardInsightUiState

    data class Ready(
        val snapshot: FinancialInsightsSnapshot,
        val insights: List<TransactionInsight>,
        val recommendations: List<FinancialRecommendation>,
        val stale: Boolean = false,
    ) : DashboardInsightUiState

    data object NoData : DashboardInsightUiState

    data object InsufficientData : DashboardInsightUiState

    data class Error(
        val message: String,
        val stale: Boolean = false,
    ) : DashboardInsightUiState
}

/**
 * Backs the AI Insights section embedded on the dashboard. Loads once on first
 * sight ([ensureLoaded]) and refreshes on explicit user action. The underlying
 * use case never spends the shared AI budget on empty or near-empty histories.
 */
class DashboardInsightViewModel(
    private val useCase: FinancialInsightsUseCase = defaultDashboardInsightsUseCase(),
) : ViewModel() {

    private val _uiState = MutableStateFlow<DashboardInsightUiState>(DashboardInsightUiState.Idle)
    val uiState: StateFlow<DashboardInsightUiState> = _uiState.asStateFlow()

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    /** The data signature (balance/transactions/target) the current content was produced under. */
    private var lastSignature: String? = null

    fun ensureLoaded() {
        if (_uiState.value !is DashboardInsightUiState.Idle) return
        refresh()
    }

    fun refresh() {
        if (_refreshing.value) return
        _refreshing.value = true
        viewModelScope.launch {
            _uiState.value = when (val result = useCase()) {
                is FinancialInsightsResult.Success -> DashboardInsightUiState.Ready(
                    snapshot = result.snapshot,
                    insights = result.insights,
                    recommendations = result.recommendations,
                )

                FinancialInsightsResult.NoData -> DashboardInsightUiState.NoData
                FinancialInsightsResult.InsufficientData -> DashboardInsightUiState.InsufficientData
                is FinancialInsightsResult.Error -> DashboardInsightUiState.Error(result.error.userSafeMessage)
            }
            _refreshing.value = false
        }
    }

    /**
     * Called whenever the dashboard completes a data reload. When the incoming
     * signature differs from the one the current content was produced under,
     * the section is flagged [DashboardInsightUiState.Ready.stale]
     * ([DashboardInsightUiState.Error.stale]) so the user is invited to refresh
     * rather than trusting outdated analysis.
     */
    fun markStaleIfChanged(signature: String) {
        if (lastSignature == signature) return
        lastSignature = signature
        val current = _uiState.value
        if (current is DashboardInsightUiState.Ready && !current.stale) {
            _uiState.value = current.copy(stale = true)
        } else if (current is DashboardInsightUiState.Error && !current.stale) {
            _uiState.value = current.copy(stale = true)
        }
    }
}

/** Production wiring: one AskAiUseCase over the shared budget + the app's sources. */
private fun defaultDashboardInsightsUseCase(): FinancialInsightsUseCase {
    val askAi = AiStackFactory().createAskAiUseCase()
    return FinancialInsightsUseCase(
        currentUserId = { FirebaseAuth.getInstance().currentUser?.uid },
        transactionsSource = FirebaseFinancialTransactionsSource(),
        goalCoachSource = FirestoreGoalCoachSource(),
        balanceSource = { uid ->
            val result = FirestoreUserRepository().getUserConfig(uid)
            if (result is UserConfigResult.Success) result.config.currentBalance else null
        },
        askAi = askAi,
    )
}