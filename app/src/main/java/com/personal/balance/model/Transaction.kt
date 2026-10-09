package com.personal.balance.model

enum class TransactionType {
    INCOME, EXPENSE
}

data class Transaction(
    val id: String = "",
    val userId: String = "",
    val type: TransactionType = TransactionType.EXPENSE,
    val amount: Long = 0L, // In piastres
    val description: String = "",
    val category: String = "",
    val timestamp: Long = System.currentTimeMillis()
)
