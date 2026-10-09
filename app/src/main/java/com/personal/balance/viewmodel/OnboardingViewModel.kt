package com.personal.balance.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.personal.balance.data.FirebaseAuthRepository
import com.personal.balance.data.FirestoreUserRepository
import com.personal.balance.data.UserConfigResult
import com.personal.balance.domain.EGPFormatter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class OnboardingUiState(
    val isLoading: Boolean = false,
    val balanceInput: String = "",
    val balanceError: String? = null,
    val generalError: String? = null,
    val isOnboardingCompleted: Boolean = false,
    val currentBalance: Long = 0L,
    val userConfig: com.personal.balance.model.UserConfig? = null,
    val verificationRequired: Boolean = false,
    val verificationEmail: String? = null,
    val verificationMessage: String? = null
)

sealed class OnboardingNavigationEvent {
    object NavigateToDashboard : OnboardingNavigationEvent()
}

class OnboardingViewModel(
    private val authRepo: FirebaseAuthRepository = FirebaseAuthRepository(),
    private val userRepo: FirestoreUserRepository = FirestoreUserRepository()
) : ViewModel() {

    private val _uiState = MutableStateFlow(OnboardingUiState())
    val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()

    private val _navigationEvent = MutableStateFlow<OnboardingNavigationEvent?>(null)
    val navigationEvent: StateFlow<OnboardingNavigationEvent?> = _navigationEvent.asStateFlow()

    fun loadOnboardingState() {
        val userId = authRepo.getCurrentUser()
        if (userId.isNullOrEmpty()) return

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)

            // VERIFICATION GATE (authenticated path): the authoritative
            // `emailVerified` state of the signed-in user decides whether this
            // account may proceed. Email/Password accounts that have not verified
            // their address are held on the Verify Email screen (with a resend
            // option) BEFORE the balance onboarding. Google-only accounts are
            // always verified, so they pass straight through.
            val emailVerified = authRepo.syncVerificationGateState()
            val email = authRepo.getCurrentEmail()
            if (!emailVerified) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    verificationRequired = true,
                    verificationEmail = email,
                    verificationMessage = "A verification email was sent to $email. " +
                        "Check your inbox and click the link before continuing so your " +
                        "email/password sign-in stays safe."
                )
                return@launch
            }

            val result = userRepo.getUserConfig(userId)
            when (result) {
                is UserConfigResult.Success -> {
                    val config = result.config
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        isOnboardingCompleted = config.onboardingCompleted,
                        currentBalance = config.currentBalance,
                        userConfig = config
                    )
                }
                is UserConfigResult.Error -> {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        generalError = result.message
                    )
                }
            }
        }
    }

    fun updateBalanceInput(input: String) {
        _uiState.value = _uiState.value.copy(balanceInput = input, balanceError = null)
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(generalError = null)
    }

    /**
     * Re-sends the email-address verification email to the signed-in user.
     * Non-destructive: it only sends an email, it never touches providers.
     */
    fun resendVerificationEmail() {
        viewModelScope.launch {
            val email = authRepo.getCurrentEmail()
            val sent = authRepo.sendVerificationEmail()
            _uiState.value = _uiState.value.copy(
                verificationMessage = if (sent) {
                    "Verification email re-sent to $email. Check your inbox and click the link."
                } else {
                    "The verification email could not be sent right now. Please try again in a moment."
                }
            )
        }
    }

    /**
     * Re-checks the server-side `emailVerified` state (reload). If the address is
     * now verified, the gate lifts and the normal onboarding continues.
     */
    fun refreshVerificationState() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            val emailVerified = authRepo.syncVerificationGateState()
            if (emailVerified) {
                // Gate lifted: clear the gate state and continue onboarding.
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    verificationRequired = false,
                    verificationEmail = null,
                    verificationMessage = null
                )
                loadOnboardingState()
            } else {
                val email = authRepo.getCurrentEmail()
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    verificationRequired = true,
                    verificationEmail = email,
                    verificationMessage = "Verification not confirmed yet. Check your inbox and click the link, then try again."
                )
            }
        }
    }

    fun completeOnboarding() {
        // If onboarding is already completed, just navigate to Dashboard
        if (_uiState.value.isOnboardingCompleted) {
            _navigationEvent.value = OnboardingNavigationEvent.NavigateToDashboard
            return
        }

        val balanceInput = _uiState.value.balanceInput.trim()

        // Validate balance
        if (balanceInput.isEmpty()) {
            _uiState.value = _uiState.value.copy(balanceError = "Please enter your initial balance")
            return
        }

        val balanceInPiastres = EGPFormatter.parseInputToPiastres(balanceInput)
        if (balanceInPiastres == null) {
            _uiState.value = _uiState.value.copy(balanceError = "Please enter a valid amount")
            return
        }

        val userId = authRepo.getCurrentUser()
        if (userId.isNullOrEmpty()) {
            _uiState.value = _uiState.value.copy(generalError = "User not authenticated")
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, generalError = null)

            val result = userRepo.completeOnboarding(userId, balanceInPiastres)
            when (result) {
                is UserConfigResult.Success -> {
                    _navigationEvent.value = OnboardingNavigationEvent.NavigateToDashboard
                }
                is UserConfigResult.Error -> {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        generalError = result.message
                    )
                }
            }
        }
    }

    fun clearNavigationEvent() {
        _navigationEvent.value = null
    }
}
