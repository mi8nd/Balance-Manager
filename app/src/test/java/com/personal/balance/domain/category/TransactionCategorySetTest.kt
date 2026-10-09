package com.personal.balance.domain.category

import com.personal.balance.model.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TransactionCategorySetTest {

    @Test
    fun expenseAllowedSet_isExhaustiveAndSpecific() {
        assertEquals(
            listOf(
                "Housing", "Food", "Transport", "Shopping",
                "Entertainment", "Health", "Utilities", "Other Expense",
            ),
            TransactionCategorySet.allowedFor(TransactionType.EXPENSE),
        )
    }

    @Test
    fun incomeAllowedSet_isExhaustiveAndSpecific() {
        assertEquals(
            listOf("Salary", "Freelance", "Bonus", "Gifts", "Other Income"),
            TransactionCategorySet.allowedFor(TransactionType.INCOME),
        )
    }

    @Test
    fun incomeAndExpenseUseSeparateNamespaces() {
        assertFalse(TransactionCategorySet.allowedFor(TransactionType.EXPENSE).contains("Salary"))
        assertFalse(TransactionCategorySet.allowedFor(TransactionType.INCOME).contains("Food"))
    }

    @Test
    fun canonical_matchesExactSpelling() {
        assertEquals("Food", TransactionCategorySet.canonical("Food", TransactionType.EXPENSE))
        assertEquals("Salary", TransactionCategorySet.canonical("Salary", TransactionType.INCOME))
    }

    @Test
    fun canonical_isCaseInsensitive() {
        assertEquals("Food", TransactionCategorySet.canonical("FOOD", TransactionType.EXPENSE))
        assertEquals("Salary", TransactionCategorySet.canonical("salARY", TransactionType.INCOME))
    }

    @Test
    fun canonical_ignoresSurroundingWhitespaceAndDecoration() {
        assertEquals("Food", TransactionCategorySet.canonical("  Food  ", TransactionType.EXPENSE))
        assertEquals("Food", TransactionCategorySet.canonical("`Food`", TransactionType.EXPENSE))
        assertEquals("Food", TransactionCategorySet.canonical("\"Food\"", TransactionType.EXPENSE))
        assertEquals("Food", TransactionCategorySet.canonical("Food.", TransactionType.EXPENSE))
        assertEquals("Food", TransactionCategorySet.canonical("  \" Food \" ", TransactionType.EXPENSE))
    }

    @Test
    fun canonical_rejectsUnknownNames() {
        assertNull(TransactionCategorySet.canonical("Restaurants & Dining", TransactionType.EXPENSE))
        assertNull(TransactionCategorySet.canonical("Airplane Food", TransactionType.EXPENSE))
        assertNull(TransactionCategorySet.canonical("General", TransactionType.EXPENSE)) // "General" is a local bucket, never AI-allowed
        assertNull(TransactionCategorySet.canonical("", TransactionType.EXPENSE))
        assertNull(TransactionCategorySet.canonical("   ", TransactionType.EXPENSE))
    }

    @Test
    fun canonical_isTypeAware() {
        // A valid income category is invalid for an expense transaction and vice versa.
        assertNull(TransactionCategorySet.canonical("Gifts", TransactionType.EXPENSE))
        assertNull(TransactionCategorySet.canonical("Food", TransactionType.INCOME))
    }
}