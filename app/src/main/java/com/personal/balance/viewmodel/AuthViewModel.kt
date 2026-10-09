package com.personal.balance.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.personal.balance.data.FirebaseAuthRepository
import com.personal.balance.data.FirestoreUserRepository
import com.personal.balance.data.AuthResult
import com.personal.balance.data.UserConfigResult
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class AuthUiState(
    val isLoading: Boolean = false,
    val email: String = "",
    val password: String = "",
    val confirmPassword: String = "",
    val emailError: String? = null,
    val passwordError: String? = null,
    val confirmPasswordError: String? = null,
    val generalError: String? = null,
    val isPasswordVisible: Boolean = false,
    val isConfirmPasswordVisible: Boolean = false,
    val canLinkGoogle: Boolean = false,
    val hasGoogleProvider: Boolean = false,
    val verificationRequiredEmail: String? = null
)

sealed class AuthNavigationEvent {
    object NavigateToOnboarding : AuthNavigationEvent()
    object NavigateToDashboard : AuthNavigationEvent()
    object NavigateToLogin : AuthNavigationEvent()
    object NavigateToSignUp : AuthNavigationEvent()
}

class SignUpViewModel(
    private val authRepo: FirebaseAuthRepository = FirebaseAuthRepository(),
    private val userRepo: FirestoreUserRepository = FirestoreUserRepository()
) : ViewModel() {

    private val _uiState = MutableStateFlow(AuthUiState())
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    private val _navigationEvent = MutableStateFlow<AuthNavigationEvent?>(null)
    val navigationEvent: StateFlow<AuthNavigationEvent?> = _navigationEvent.asStateFlow()

    private val _emailCollisionEvent = MutableSharedFlow<String>()
    val emailCollisionEvent: SharedFlow<String> = _emailCollisionEvent.asSharedFlow()

    fun updateEmail(email: String) {
        _uiState.value = _uiState.value.copy(email = email, emailError = null)
    }

    fun updatePassword(password: String) {
        _uiState.value = _uiState.value.copy(password = password, passwordError = null)
    }

    fun updateConfirmPassword(confirmPassword: String) {
        _uiState.value = _uiState.value.copy(confirmPassword = confirmPassword, confirmPasswordError = null)
    }

    fun togglePasswordVisibility() {
        _uiState.value = _uiState.value.copy(isPasswordVisible = !_uiState.value.isPasswordVisible)
    }

    fun toggleConfirmPasswordVisibility() {
        _uiState.value = _uiState.value.copy(isConfirmPasswordVisible = !_uiState.value.isConfirmPasswordVisible)
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(generalError = null)
    }

    /**
     * Check if the current user (signed in with Email/Password) can link Google.
     * The Google provider may only be linked to a VERIFIED email account: linking
     * while unverified would expose the trusted-provider overwrite of the
     * password provider. This should be called after successful email/password
     * sign in.
     */
    fun checkGoogleLinkEligibility() {
        viewModelScope.launch {
            val providers = authRepo.getCurrentUserProviders()
            val emailVerified = authRepo.isCurrentUserEmailVerified()
            val hasGoogleProvider = providers.contains("google.com")
            val hasPasswordProvider = providers.contains("password")
            // Can link Google if user has password provider but NOT Google provider,
            // AND the account's email is verified.
            _uiState.value = _uiState.value.copy(
                canLinkGoogle = hasPasswordProvider && !hasGoogleProvider && emailVerified,
                hasGoogleProvider = hasGoogleProvider
            )
        }
    }

    fun signUp() {
        val email = _uiState.value.email.trim()
        val password = _uiState.value.password
        val confirmPassword = _uiState.value.confirmPassword

        // Validation
        var hasError = false
        var emailError: String? = null
        var passwordError: String? = null
        var confirmPasswordError: String? = null

        if (email.isEmpty()) {
            emailError = "Email is required"
            hasError = true
        } else if (!isValidEmail(email)) {
            emailError = "Invalid email address"
            hasError = true
        }

        if (password.isEmpty()) {
            passwordError = "Password is required"
            hasError = true
        } else if (password.length < 6) {
            passwordError = "Password must be at least 6 characters"
            hasError = true
        }

        if (confirmPassword.isEmpty()) {
            confirmPasswordError = "Please confirm your password"
            hasError = true
        } else if (password != confirmPassword) {
            confirmPasswordError = "Passwords do not match"
            hasError = true
        }

        if (hasError) {
            _uiState.value = _uiState.value.copy(
                emailError = emailError,
                passwordError = passwordError,
                confirmPasswordError = confirmPasswordError
            )
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, generalError = null)

            val authResult = authRepo.signUp(email, password)
            when (authResult) {
                is AuthResult.Success -> {
                    val userConfigResult = userRepo.createUserConfig(authResult.userId)
                    when (userConfigResult) {
                        is UserConfigResult.Success -> {
                            // Check Google link eligibility after successful sign up
                            checkGoogleLinkEligibility()
                            _navigationEvent.value = AuthNavigationEvent.NavigateToOnboarding
                        }
                        is UserConfigResult.Error -> {
                            _uiState.value = _uiState.value.copy(
                                isLoading = false,
                                generalError = userConfigResult.message
                            )
                        }
                    }
                }
                is AuthResult.Error -> {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        generalError = authResult.message
                    )
                }
                is AuthResult.EmailCollision -> {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        generalError = authResult.message
                    )
                }
                is AuthResult.VerificationRequired -> {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        generalError = authResult.message
                    )
                }
            }
        }
    }

    fun signInWithGoogle(idToken: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, generalError = null)

            val authResult = authRepo.signInWithGoogleForSignUp(idToken)
            when (authResult) {
                is AuthResult.Success -> {
                    // NEW Google user: create Firestore document AFTER Firebase auth
                    val createResult = userRepo.createUserConfig(authResult.userId)
                    when (createResult) {
                        is UserConfigResult.Success -> {
                            _navigationEvent.value = AuthNavigationEvent.NavigateToOnboarding
                        }
                        is UserConfigResult.Error -> {
                            _uiState.value = _uiState.value.copy(
                                isLoading = false,
                                generalError = "Failed to initialize profile: ${createResult.message}"
                            )
                        }
                    }
                }
                is AuthResult.Error -> {
                    when (authResult.message) {
                        "NO_ACCOUNT" -> {
                            // This shouldn't happen in sign-up flow, but handle gracefully
                            _uiState.value = _uiState.value.copy(
                                isLoading = false,
                                generalError = "No account found. Please sign in instead."
                            )
                        }
                        "ACCOUNT_EXISTS" -> {
                            // Google account already exists. Show the actionable
                            // "already exists" dialog so the user can switch to the
                            // Login screen and use Google Login instead of being
                            // left at a dead end.
                            _uiState.value = _uiState.value.copy(isLoading = false)
                            _emailCollisionEvent.emit(
                                "An account with this email already exists. Please use Google Login instead."
                            )
                        }
                        else -> {
                            _uiState.value = _uiState.value.copy(
                                isLoading = false,
                                generalError = authResult.message
                            )
                        }
                    }
                }
                is AuthResult.EmailCollision -> {
                    _uiState.value = _uiState.value.copy(isLoading = false)
                    _emailCollisionEvent.emit(authResult.message)
                }
                is AuthResult.VerificationRequired -> {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        generalError = authResult.message
                    )
                }
            }
        }
    }

    fun navigateToLogin() {
        _navigationEvent.value = AuthNavigationEvent.NavigateToLogin
    }

    fun clearNavigationEvent() {
        _navigationEvent.value = null
    }

    private fun isValidEmail(email: String): Boolean {
        return email.matches(Regex("^[A-Za-z0-9+_.-]+@([A-Za-z0-9.-]+\\.[A-Za-z]{2,})$"))
    }

    /**
     * Link Google account to the currently signed-in user (Email/Password).
     * This is an explicit action - user must be signed in with Email/Password
     * and choose to add Google as a second sign-in method.
     */
    fun linkGoogleAccount(idToken: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, generalError = null)

            val authResult = authRepo.linkGoogleAccount(idToken)
            when (authResult) {
                is AuthResult.Success -> {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        canLinkGoogle = false,
                        hasGoogleProvider = true
                    )
                    // Optionally show success message or navigate
                }
                is AuthResult.Error -> {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        generalError = authResult.message
                    )
                }
                is AuthResult.EmailCollision -> {
                    _uiState.value = _uiState.value.copy(isLoading = false)
                    _emailCollisionEvent.emit(authResult.message)
                }
                is AuthResult.VerificationRequired -> {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        generalError = authResult.message
                    )
                }
            }
        }
    }
}

class LoginViewModel(
    private val authRepo: FirebaseAuthRepository = FirebaseAuthRepository(),
    private val userRepo: FirestoreUserRepository = FirestoreUserRepository()
) : ViewModel() {

    private val _uiState = MutableStateFlow(AuthUiState())
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    private val _navigationEvent = MutableStateFlow<AuthNavigationEvent?>(null)
    val navigationEvent: StateFlow<AuthNavigationEvent?> = _navigationEvent.asStateFlow()

    private val _emailCollisionEvent = MutableSharedFlow<String>()
    val emailCollisionEvent: SharedFlow<String> = _emailCollisionEvent.asSharedFlow()

    fun updateEmail(email: String) {
        _uiState.value = _uiState.value.copy(email = email, emailError = null, verificationRequiredEmail = null)
    }

    fun updatePassword(password: String) {
        _uiState.value = _uiState.value.copy(password = password, passwordError = null, verificationRequiredEmail = null)
    }

    fun togglePasswordVisibility() {
        _uiState.value = _uiState.value.copy(isPasswordVisible = !_uiState.value.isPasswordVisible)
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(generalError = null, verificationRequiredEmail = null)
    }

    /**
     * Check if the current user (signed in with Email/Password) can link Google.
     * The Google provider may only be linked to a VERIFIED email account: linking
     * while unverified would expose the trusted-provider overwrite of the
     * password provider. This should be called after successful email/password
     * sign in.
     */
    fun checkGoogleLinkEligibility() {
        viewModelScope.launch {
            val providers = authRepo.getCurrentUserProviders()
            val emailVerified = authRepo.isCurrentUserEmailVerified()
            val hasGoogleProvider = providers.contains("google.com")
            val hasPasswordProvider = providers.contains("password")
            // Can link Google if user has password provider but NOT Google provider,
            // AND the account's email is verified.
            _uiState.value = _uiState.value.copy(
                canLinkGoogle = hasPasswordProvider && !hasGoogleProvider && emailVerified,
                hasGoogleProvider = hasGoogleProvider
            )
        }
    }

    fun signIn() {
        val email = _uiState.value.email.trim()
        val password = _uiState.value.password

        var hasError = false
        var emailError: String? = null
        var passwordError: String? = null

        if (email.isEmpty()) {
            emailError = "Email is required"
            hasError = true
        }
        if (password.isEmpty()) {
            passwordError = "Password is required"
            hasError = true
        }

        if (hasError) {
            _uiState.value = _uiState.value.copy(
                emailError = emailError,
                passwordError = passwordError
            )
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, generalError = null, verificationRequiredEmail = null)

            val authResult = authRepo.signIn(email, password)
            when (authResult) {
                is AuthResult.Success -> {
                    // Reconcile the on-device verification-gate record with the
                    // server-side emailVerified state, then route unverified
                    // accounts to the Onboarding verify-email gate (which lets the
                    // user resend the confirmation email) instead of the Dashboard.
                    val emailVerified = authRepo.syncVerificationGateState()
                    val userConfigResult = userRepo.getUserConfig(authResult.userId)
                    when (userConfigResult) {
                        is UserConfigResult.Success -> {
                            // Check Google link eligibility after successful sign in
                            checkGoogleLinkEligibility()
                            if (userConfigResult.config.onboardingCompleted && emailVerified) {
                                _navigationEvent.value = AuthNavigationEvent.NavigateToDashboard
                            } else {
                                _navigationEvent.value = AuthNavigationEvent.NavigateToOnboarding
                            }
                        }
                        is UserConfigResult.Error -> {
                            // User exists in Firebase Auth but not in Firestore yet
                            // Create the user config and navigate to Onboarding
                            val createResult = userRepo.createUserConfig(authResult.userId)
                            when (createResult) {
                                is UserConfigResult.Success -> {
                                    // Check Google link eligibility after creating config
                                    checkGoogleLinkEligibility()
                                    _navigationEvent.value = AuthNavigationEvent.NavigateToOnboarding
                                }
                                is UserConfigResult.Error -> {
                                    _uiState.value = _uiState.value.copy(
                                        isLoading = false,
                                        generalError = "Failed to initialize profile: ${createResult.message}"
                                    )
                                }
                            }
                        }
                    }
                }
                is AuthResult.Error -> {
                    // The new authentication flow handles provider collision detection
                    // at the Firebase level, so we can show the error directly
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        generalError = authResult.message
                    )
                }
                is AuthResult.EmailCollision -> {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        generalError = authResult.message
                    )
                }
                is AuthResult.VerificationRequired -> {
                    // Not expected from a password sign-in, but required by exhaustive when
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        generalError = authResult.message
                    )
                }
            }
        }
    }

    fun signInWithGoogleForLogin(idToken: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, generalError = null, verificationRequiredEmail = null)

            val authResult = authRepo.signInWithGoogleForLogin(idToken)
            when (authResult) {
                is AuthResult.Success -> {
                    // Existing Google user: ALWAYS go to Onboarding first (Balance page)
                    // to match email/password flow. OnboardingViewModel handles
                    // already-completed onboarding by showing current balance and Continue button.
                    _navigationEvent.value = AuthNavigationEvent.NavigateToOnboarding
                }
                is AuthResult.Error -> {
                    when (authResult.message) {
                        "NO_ACCOUNT" -> {
                            // No account exists - Login flow should NOT create one
                            // Show error and offer Sign Up
                            _uiState.value = _uiState.value.copy(
                                isLoading = false,
                                generalError = "No account was found for this Google account. Would you like to sign up?"
                            )
                        }
                        "GOOGLE_NOT_LINKED" -> {
                            // Blocked BEFORE signInWithCredential ran (Pass 8): the
                            // Google provider was never attached by this login, and
                            // the email/password provider remains intact. Guidance
                            // without exposing Firebase provider IDs.
                            _uiState.value = _uiState.value.copy(
                                isLoading = false,
                                generalError = "Google sign-in is not enabled for this account. Please sign in with your email and password first, then add Google sign-in from the Settings screen."
                            )
                        }
                        else -> {
                            _uiState.value = _uiState.value.copy(
                                isLoading = false,
                                generalError = authResult.message
                            )
                        }
                    }
                }
                is AuthResult.EmailCollision -> {
                    _uiState.value = _uiState.value.copy(isLoading = false)
                    _emailCollisionEvent.emit(authResult.message)
                }
                is AuthResult.VerificationRequired -> {
                    // The Google sign-in was blocked BEFORE signInWithCredential ran,
                    // so the email/password provider is intact. Show the guidance.
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        verificationRequiredEmail = authResult.email
                    )
                }
            }
        }
    }

    fun navigateToSignUp() {
        _navigationEvent.value = AuthNavigationEvent.NavigateToSignUp
    }

    fun clearNavigationEvent() {
        _navigationEvent.value = null
    }

    /**
     * Link Google account to the currently signed-in user (Email/Password).
     * This is an explicit action - user must be signed in with Email/Password
     * and choose to add Google as a second sign-in method.
     */
    fun linkGoogleAccount(idToken: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, generalError = null)

            val authResult = authRepo.linkGoogleAccount(idToken)
            when (authResult) {
                is AuthResult.Success -> {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        canLinkGoogle = false,
                        hasGoogleProvider = true
                    )
                    // Optionally show success message or navigate
                }
                is AuthResult.Error -> {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        generalError = authResult.message
                    )
                }
                is AuthResult.EmailCollision -> {
                    _uiState.value = _uiState.value.copy(isLoading = false)
                    _emailCollisionEvent.emit(authResult.message)
                }
                is AuthResult.VerificationRequired -> {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        generalError = authResult.message
                    )
                }
            }
        }
    }
}
