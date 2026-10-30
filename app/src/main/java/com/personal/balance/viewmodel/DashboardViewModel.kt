package com.personal.balance.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.personal.balance.data.AddTransactionResult
import com.personal.balance.data.FirestoreUserRepository
import com.personal.balance.data.FirebaseAuthRepository
import com.personal.balance.data.UserConfigResult
import com.personal.balance.data.AuthResult
import com.personal.balance.domain.ai.AiStackFactory
import com.personal.balance.domain.ai.AskAiUseCase
import com.personal.balance.domain.category.CategorizeTransactionUseCase
import com.personal.balance.domain.category.DeterministicCategoryClassifier
import com.personal.balance.domain.category.TransactionCategorySet
import com.personal.balance.model.Transaction
import com.personal.balance.model.TransactionType
import com.personal.balance.model.UserConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed class DashboardUiState {
    object Loading : DashboardUiState()
    data class Success(
        val userConfig: UserConfig,
        val transactions: List<Transaction>,
        val canLinkGoogle: Boolean = false,
        val hasGoogleProvider: Boolean = false
    ) : DashboardUiState()
    data class Error(val message: String) : DashboardUiState()
}

sealed class DashboardNavigationEvent {
    object NavigateToAuth : DashboardNavigationEvent()
}

class DashboardViewModel(
    private val authRepo: FirebaseAuthRepository = FirebaseAuthRepository(),
    private val userRepo: FirestoreUserRepository = FirestoreUserRepository(),
    private val aiStackFactory: AiStackFactory = AiStackFactory(),
    private val lazyCategorizer: (() -> CategorizeTransactionUseCase)? = null,
) : ViewModel() {

    /**
     * One AI stack for the dashboard's AI-assisted action (automatic
     * categorization). Built lazily (the Firebase AI chain is only constructed
     * on first use; injected in tests). Production wiring mirrors
     * FinancialAssistantViewModel: the shared usage budget is respected by
     * construction — [AskAiUseCase] enforces it before contacting the backend.
     */
    private val askAi: AskAiUseCase by lazy {
        aiStackFactory.createAskAiUseCase()
    }

    /**
     * Test seam: allows tests to inject a fully-configured categorizer without
     * needing to also replace the private [askAi]. Production code uses the
     * default lazy initializer that wires the categorizer to this ViewModel's
     * [askAi].
     */
    private val categorizer: CategorizeTransactionUseCase by lazy {
        lazyCategorizer?.invoke() ?: CategorizeTransactionUseCase(askAi = askAi)
    }

    private val _uiState = MutableStateFlow<DashboardUiState>(DashboardUiState.Loading)
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    private val _navigationEvent = MutableStateFlow<DashboardNavigationEvent?>(null)
    val navigationEvent: StateFlow<DashboardNavigationEvent?> = _navigationEvent.asStateFlow()

    private val _userFeedback = MutableStateFlow<String?>(null)
    val userFeedback: StateFlow<String?> = _userFeedback.asStateFlow()

    // Guard against duplicate "Add transaction" submissions while the first
    // save (persistence + categorization) is still in flight.
    private val _transactionSaving = MutableStateFlow(false)
    val transactionSaving: StateFlow<Boolean> = _transactionSaving.asStateFlow()

    // Current user email for display in settings
    val currentUserEmail: String?
        get() = authRepo.getCurrentEmail()

    init {
        loadDashboardData()
        setupAuthStateListener()
    }

    private fun setupAuthStateListener() {
        authRepo.addAuthStateListener { isAuthenticated ->
            if (!isAuthenticated) {
                clearUserState()
                _navigationEvent.value = DashboardNavigationEvent.NavigateToAuth
            }
        }
    }

    /**
     * Loads (or re-loads) the Dashboard data.
     *
     * The large/full-screen [DashboardUiState.Loading] is reserved for the initial
     * load and for any load that has no usable content to show yet. When usable
     * content is already on screen, the Dashboard stays visible and the refresh is
     * applied silently: a normal local mutation (transaction saved, goal set,
     * category updated by AI) never blanks the whole screen behind a spinner.
     *
     * A silent refresh keeps whatever is currently displayed if the re-read fails,
     * and surfaces the problem as user feedback instead of destroying the view; the
     * Error state is still shown when there was nothing usable to begin with.
     */
    fun loadDashboardData() {
        val userId = authRepo.getCurrentUser() ?: return

        viewModelScope.launch {
            val hasUsableContent = _uiState.value is DashboardUiState.Success
            if (!hasUsableContent) {
                _uiState.value = DashboardUiState.Loading
            }
            val configResult = userRepo.getUserConfig(userId)
            val transactions = userRepo.getRecentTransactions(userId)

            if (configResult is UserConfigResult.Success) {
                // Carry the already-resolved Google-link flags through the refresh so
                // the header does not briefly flip to the wrong link/unlink action.
                val previous = _uiState.value as? DashboardUiState.Success
                _uiState.value = DashboardUiState.Success(
                    userConfig = configResult.config,
                    transactions = transactions,
                    canLinkGoogle = previous?.canLinkGoogle ?: false,
                    hasGoogleProvider = previous?.hasGoogleProvider ?: false,
                )
            } else if (!hasUsableContent) {
                _uiState.value = DashboardUiState.Error("Failed to load data")
            } else {
                _userFeedback.value = "Couldn't refresh data. Showing the last saved values."
            }
            // Check Google link eligibility after loading user config
            checkGoogleLinkEligibility()
        }
    }

    /**
     * Check if the current user (signed in with Email/Password) can link Google.
     * The Google provider may only be linked to a VERIFIED email account: linking
     * while unverified would expose the trusted-provider overwrite of the
     * password provider. This should be called after loading dashboard data.
     */
    fun checkGoogleLinkEligibility() {
        viewModelScope.launch {
            val providers = authRepo.getCurrentUserProviders()
            val emailVerified = authRepo.isCurrentUserEmailVerified()
            val hasGoogleProvider = providers.contains("google.com")
            val hasPasswordProvider = providers.contains("password")
            // Can link Google if user has password provider but NOT Google provider,
            // AND the account's email is verified.
            val currentState = _uiState.value
            if (currentState is DashboardUiState.Success) {
                _uiState.value = currentState.copy(
                    canLinkGoogle = hasPasswordProvider && !hasGoogleProvider && emailVerified,
                    hasGoogleProvider = hasGoogleProvider
                )
            }
        }
    }

    /**
     * Link Google account to the currently signed-in user (Email/Password).
     * This is an explicit action - user must be signed in with Email/Password
     * and choose to add Google as a second sign-in method.
     */
    fun linkGoogleAccount(idToken: String) {
        viewModelScope.launch {
            val authResult = authRepo.linkGoogleAccount(idToken)
            when (authResult) {
                is AuthResult.Success -> {
                    val currentState = _uiState.value
                    if (currentState is DashboardUiState.Success) {
                        _uiState.value = currentState.copy(
                            canLinkGoogle = false,
                            hasGoogleProvider = true
                        )
                    }
                    _userFeedback.value = "Google account linked successfully"
                }
                is AuthResult.Error -> {
                    _userFeedback.value = authResult.message
                }
                is AuthResult.EmailCollision -> {
                    _userFeedback.value = authResult.message
                }
                is AuthResult.VerificationRequired -> {
                    _userFeedback.value = authResult.message
                }
            }
        }
    }

    fun clearUserFeedback() {
        _userFeedback.value = null
    }

    fun signOut() {
        viewModelScope.launch {
            authRepo.signOut()
            // Auth state listener will handle navigation
        }
    }

    fun deleteAccount() {
        viewModelScope.launch {
            // Get fresh user reference to avoid stale state issues
            val currentUserId = authRepo.getCurrentUser() ?: return@launch
            // GUARD FIRST (see PROJECT_MAP §28): capture the ID token BEFORE any
            // destructive step. Firebase Auth enforces a recent-login window on
            // account deletion; doing auth deletion FIRST means a failed delete
            // (ERROR_REQUIRES_RECENT_LOGIN) can NEVER have wiped the user's
            // Firestore data — the previous order purged Firestore before auth
            // delete and lost data on that failure. The Firestore purge is only
            // attempted AFTER auth deletion succeeds, using the captured token.
            val capturedIdToken = authRepo.captureIdToken()
            val deleteResult = authRepo.deleteCurrentUser()
            when (deleteResult) {
                is AuthResult.Success -> {
                    if (!capturedIdToken.isNullOrBlank()) {
                        userRepo.purgeUserDataWithCapturedToken(currentUserId, capturedIdToken)
                    }
                    _userFeedback.value = "Account deleted"
                    // Auth state listener will handle navigation
                }
                is AuthResult.Error -> {
                    // Auth delete failed (e.g. recent-login required); the
                    // user's Firestore data is UNTOUCHED — sign out/in and retry.
                    _userFeedback.value = deleteResult.message
                }
                is AuthResult.EmailCollision -> {
                    // Should not happen for delete, but handle exhaustively
                }
                is AuthResult.VerificationRequired -> {
                    // Should not happen for delete, but handle exhaustively
                }
            }
        }
    }

    /**
     * Creates a new transaction with automatic, asynchronous categorization.
     *
     * The transaction is PERSISTED FIRST: it is written to Firestore with the
     * on-device deterministic category (instant, never spends AI budget, blank
     * descriptions become "General") before any AI work starts, so adding a
     * transaction always succeeds independent of AI availability. Immediately
     * after the save a background categorization runs through the shared AI
     * stack (resp. the shared usage budget), and a validated, canonical result
     * is persisted back onto the transaction's `category` field. An AI failure
     * of any kind never rolls the transaction back — the deterministic category
     * already stored is simply kept. Balance/target arithmetic is unchanged.
     *
     * The saving flag is flipped synchronously before the coroutine launches so
     * a double-tap on the form cannot enqueue two identical transactions.
     */
    fun addTransaction(
        type: TransactionType,
        amount: Long,
        description: String,
    ) {
        if (_transactionSaving.value) return
        val userId = authRepo.getCurrentUser() ?: return
        _transactionSaving.value = true

        viewModelScope.launch {
            try {
                val deterministicCategory =
                    if (description.isBlank()) TransactionCategorySet.GENERAL
                    else DeterministicCategoryClassifier.classify(type, description)
                val baseline = Transaction(
                    type = type,
                    amount = amount,
                    description = description,
                    category = deterministicCategory,
                )
                when (val save = userRepo.addTransaction(userId, baseline)) {
                    is AddTransactionResult.Saved -> {
                        loadDashboardData()
                        val categorized = categorizer.categorize(save.transaction)
                        if (categorized.category != baseline.category) {
                            userRepo.updateTransactionCategory(
                                userId,
                                save.transaction.id,
                                categorized.category,
                            )
                            loadDashboardData()
                        }
                    }
                    is AddTransactionResult.Error -> _userFeedback.value = save.message
                }
            } finally {
                _transactionSaving.value = false
            }
        }
    }

    fun setTarget(name: String, amount: Long) {
        val userId = authRepo.getCurrentUser() ?: return
        viewModelScope.launch {
            userRepo.setTarget(userId, name, amount)
            loadDashboardData()
        }
    }

    fun disableTarget() {
        val userId = authRepo.getCurrentUser() ?: return
        viewModelScope.launch {
            userRepo.setTarget(userId, "", 0L, targetEnabled = false)
            loadDashboardData()
        }
    }

    fun acknowledgeTarget() {
        val userId = authRepo.getCurrentUser() ?: return
        viewModelScope.launch {
            userRepo.acknowledgeTarget(userId)
            loadDashboardData()
        }
    }

    fun clearNavigationEvent() {
        _navigationEvent.value = null
    }

    private fun clearUserState() {
        _uiState.value = DashboardUiState.Loading
    }
}
