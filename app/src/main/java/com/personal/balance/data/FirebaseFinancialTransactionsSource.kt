package com.personal.balance.data

import com.personal.balance.domain.finance.FinancialTransactionsResult
import com.personal.balance.domain.finance.FinancialTransactionsSource
import com.personal.balance.model.Transaction

/**
 * Adapter from the existing [FirestoreUserRepository] to the domain
 * [FinancialTransactionsSource]. The AI stack never talks to Firestore itself;
 * it goes through this bounded, uid-scoped query.
 */
class FirebaseFinancialTransactionsSource(
    private val repository: FirestoreUserRepository = FirestoreUserRepository(),
) : FinancialTransactionsSource {

    override suspend fun loadTransactions(
        uid: String,
        startEpochMillis: Long,
        endEpochMillis: Long,
    ): FinancialTransactionsResult {
        return when (val result = repository.getTransactionsBetween(uid, startEpochMillis, endEpochMillis)) {
            is TransactionQueryResult.Success -> FinancialTransactionsResult.Success(result.transactions)
            is TransactionQueryResult.Error -> FinancialTransactionsResult.Error(result.diagnostic)
        }
    }
}