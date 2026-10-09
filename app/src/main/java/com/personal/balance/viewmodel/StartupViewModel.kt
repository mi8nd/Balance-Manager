package com.personal.balance.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.personal.balance.data.FirebaseAuthRepository
import com.personal.balance.data.FirestoreUserRepository
import com.personal.balance.data.UserConfigResult
import com.personal.balance.data.SessionValidationResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed class StartupState {
    object Loading : StartupState()
    object Unauthenticated : StartupState()
    object OnboardingNeeded : StartupState()
    object Ready : StartupState()
}

class StartupViewModel(
    private val authRepo: FirebaseAuthRepository = FirebaseAuthRepository(),
    private val userRepo: FirestoreUserRepository = FirestoreUserRepository()
) : ViewModel() {

    private val _startupState = MutableStateFlow<StartupState>(StartupState.Loading)
    val startupState: StateFlow<StartupState> = _startupState.asStateFlow()

    init {
        determineStartupRoute()
    }

    private fun determineStartupRoute() {
        viewModelScope.launch {
            // Validate session against Firebase servers to detect externally deleted accounts
            val sessionValidation = authRepo.validateSession()

            val userId = when (sessionValidation) {
                is SessionValidationResult.Valid -> sessionValidation.userId
                SessionValidationResult.InvalidAccount -> {
                    _startupState.value = StartupState.Unauthenticated
                    return@launch
                }
                SessionValidationResult.NetworkError -> {
                    // Network is unavailable, but we have a cached currentUser
                    // Try to use it, but don't trust Firestore data alone
                    authRepo.getCurrentUser()?.let { cachedUid ->
                        cachedUid
                    } ?: run {
                        // No cached user either
                        _startupState.value = StartupState.Unauthenticated
                        return@launch
                    }
                }
            }

            // Session is valid (or we're using cached user); check onboarding status
            // and the verified-email gate. An Email/Password account whose address
            // is not verified is re-routed to onboarding so the verify-email gate
            // can be shown (verified users pass straight through).
            if (!authRepo.syncVerificationGateState()) {
                _startupState.value = StartupState.OnboardingNeeded
                return@launch
            }
            val userConfigResult = userRepo.getUserConfig(userId)
            when (userConfigResult) {
                is UserConfigResult.Success -> {
                    if (userConfigResult.config.onboardingCompleted) {
                        _startupState.value = StartupState.Ready
                    } else {
                        _startupState.value = StartupState.OnboardingNeeded
                    }
                }
                is UserConfigResult.Error -> {
                    // If we can't fetch config, treat as unauthenticated
                    _startupState.value = StartupState.Unauthenticated
                }
            }
        }
    }

    fun reset() {
        _startupState.value = StartupState.Loading
        determineStartupRoute()
    }
}
