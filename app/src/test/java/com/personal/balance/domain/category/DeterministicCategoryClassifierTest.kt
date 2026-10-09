package com.personal.balance.domain.category

import com.personal.balance.model.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Test

class DeterministicCategoryClassifierTest {

    @Test
    fun expense_examplesFromSpec() {
        assertEquals("Housing", classifyExpense("Rent"))
        assertEquals("Food", classifyExpense("Groceries"))
        assertEquals("Food", classifyExpense("Restaurant dinner"))
        assertEquals("Transport", classifyExpense("Uber"))
        assertEquals("Transport", classifyExpense("Taxi"))
        assertEquals("Shopping", classifyExpense("Clothes"))
        assertEquals("Shopping", classifyExpense("Electronics"))
        assertEquals("Entertainment", classifyExpense("Games"))
        assertEquals("Health", classifyExpense("Medicine"))
    }

    @Test
    fun income_examplesFromSpec() {
        assertEquals("Salary", classifyIncome("Salary"))
        assertEquals("Freelance", classifyIncome("Freelance payment"))
        assertEquals("Bonus", classifyIncome("Bonus"))
        assertEquals("Gifts", classifyIncome("Gift"))
    }

    @Test
    fun semanticDescriptions_fromSpec() {
        assertEquals("Food", classifyExpense("Bought dinner with friends"))
        assertEquals("Housing", classifyExpense("Paid my apartment"))
        assertEquals("Transport", classifyExpense("Took an Uber home"))
        assertEquals("Shopping", classifyExpense("Bought a new pair of jeans"))
    }

    @Test
    fun blankDescription_mapsToGeneral() {
        assertEquals("General", classifyExpense(""))
        assertEquals("General", classifyExpense("   "))
        assertEquals("General", classifyIncome(""))
    }

    @Test
    fun unknownDescription_mapsToOtherBucket() {
        assertEquals("Other Expense", classifyExpense("Something indescribable happened"))
        assertEquals("Other Income", classifyIncome("Mysterious envelope of cash"))
    }

    @Test
    fun classification_isCaseInsensitiveAndWhitespaceTolerant() {
        assertEquals("Food", classifyExpense("  GROCERIES  "))
        assertEquals("Transport", classifyExpense("took   an   uber   home"))
    }

    @Test
    fun sameWord_isTypeAware() {
        // "Gift" means a purchase for an expense but income for income.
        assertEquals("Shopping", classifyExpense("Gift for a friend"))
        assertEquals("Gifts", classifyIncome("Gift from grandma"))
    }

    @Test
    fun incomeKeywords_neverCollapseIntoExpenseCategories() {
        assertEquals("Salary", classifyIncome("Monthly salary deposit"))
        assertEquals("Freelance", classifyIncome("Freelance contract payment"))
        assertEquals("Bonus", classifyIncome("Performance bonus"))
    }

    private fun classifyExpense(description: String): String =
        DeterministicCategoryClassifier.classify(TransactionType.EXPENSE, description)

    private fun classifyIncome(description: String): String =
        DeterministicCategoryClassifier.classify(TransactionType.INCOME, description)
}