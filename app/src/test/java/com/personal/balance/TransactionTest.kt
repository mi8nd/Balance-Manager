package com.personal.balance

import com.personal.balance.model.Transaction
import com.personal.balance.model.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Test

class TransactionTest {

    @Test
    fun transaction_amountValidation() {
        // Just verifying model constraints or simple parsing test conceptually
        val income = Transaction(type = TransactionType.INCOME, amount = 1000L)
        val expense = Transaction(type = TransactionType.EXPENSE, amount = 1000L)

        assertEquals(TransactionType.INCOME, income.type)
        assertEquals(TransactionType.EXPENSE, expense.type)
        assertEquals(1000L, income.amount)
    }
}
