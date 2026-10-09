package com.personal.balance.domain.finance

import com.personal.balance.model.Transaction

sealed interface FinancialTransactionsResult {
    data class Success(val transactions: List<Transaction>) : FinancialTransactionsResult
    data class Error(val diagnostic: String) : FinancialTransactionsResult
}

/**
 * Source of the user's own bounded transaction history for the AI Financial
 * Assistant. Implementations must scope every query to a single user id and
 * must never exceed the requested window.
 */
fun interface FinancialTransactionsSource {
    suspend fun loadTransactions(
        uid: String,
        startEpochMillis: Long,
        endEpochMillis: Long,
    ): FinancialTransactionsResult
}