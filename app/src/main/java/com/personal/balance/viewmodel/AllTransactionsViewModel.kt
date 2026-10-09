package com.personal.balance.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.personal.balance.data.FirestoreUserRepository
import com.personal.balance.model.Transaction
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed class AllTransactionsUiState {
    object Loading : AllTransactionsUiState()
    data class Ready(val transactions: List<Transaction>) : AllTransactionsUiState()
    object Empty : AllTransactionsUiState()
    data class Error(val message: String) : AllTransactionsUiState()
}

class AllTransactionsViewModel(
    private val userRepo: FirestoreUserRepository = FirestoreUserRepository(),
) : ViewModel() {

    private val _uiState = MutableStateFlow<AllTransactionsUiState>(AllTransactionsUiState.Loading)
    val uiState: StateFlow<AllTransactionsUiState> = _uiState.asStateFlow()

    init {
        loadAll()
    }

    private fun loadAll() {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        viewModelScope.launch {
            _uiState.value = AllTransactionsUiState.Loading
            val result = userRepo.getAllTransactions(uid)
            _uiState.value = when {
                result.isFailure -> AllTransactionsUiState.Error("Failed to load transactions.")
                result.getOrNull().isNullOrEmpty() -> AllTransactionsUiState.Empty
                else -> AllTransactionsUiState.Ready(result.getOrThrow())
            }
        }
    }
}