package com.personal.balance.data

import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed class AuthenticationState {
    object Loading : AuthenticationState()
    data class Authenticated(val userId: String) : AuthenticationState()
    object Unauthenticated : AuthenticationState()
}

class AuthenticationManager(
    private val firebaseAuth: FirebaseAuth = FirebaseAuth.getInstance()
) {
    private val _authState = MutableStateFlow<AuthenticationState>(AuthenticationState.Loading)
    val authState: StateFlow<AuthenticationState> = _authState.asStateFlow()

    private var authStateListener: FirebaseAuth.AuthStateListener? = null

    fun startMonitoring() {
        if (authStateListener != null) return

        authStateListener = FirebaseAuth.AuthStateListener { auth ->
            val currentUser = auth.currentUser
            _authState.value = if (currentUser != null) {
                AuthenticationState.Authenticated(currentUser.uid)
            } else {
                AuthenticationState.Unauthenticated
            }
        }
        firebaseAuth.addAuthStateListener(authStateListener!!)
    }

    fun stopMonitoring() {
        authStateListener?.let { firebaseAuth.removeAuthStateListener(it) }
        authStateListener = null
    }

    fun getCurrentAuthState(): AuthenticationState = _authState.value

    fun clearState() {
        _authState.value = AuthenticationState.Unauthenticated
    }
}
