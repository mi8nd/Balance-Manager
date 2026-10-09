package com.personal.balance.domain.category

import com.personal.balance.model.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AiCategoryParserTest {

    @Test
    fun plainJsonObject_isParsed() {
        assertEquals("Food", AiCategoryParser.parse("""{"category": "Food"}""", TransactionType.EXPENSE))
    }

    @Test
    fun jsonWithProseAround_itIsIgnored() {
        assertEquals("Food", AiCategoryParser.parse(
            """Sure! Based on the description, I'd say: {"category": "Food"}""",
            TransactionType.EXPENSE,
        ))
    }

    @Test
    fun fencedJsonBlock_isParsed() {
        assertEquals("Transport", AiCategoryParser.parse(
            """```json
               {"category": "Transport"}
               ```""",
            TransactionType.EXPENSE,
        ))
    }

    @Test
    fun plainSingleLineCategoryName_isParsed() {
        assertEquals("Food", AiCategoryParser.parse("Food", TransactionType.EXPENSE))
        assertEquals("Housing", AiCategoryParser.parse("  Housing  ", TransactionType.EXPENSE))
    }

    @Test
    fun valueIsNormalized_mixedCaseWhitespaceAndQuotes() {
        assertEquals("Food", AiCategoryParser.parse("""{"category": "  FOOD  "}""", TransactionType.EXPENSE))
        assertEquals("Food", AiCategoryParser.parse("""{"category" : "food"}""", TransactionType.EXPENSE))
        assertEquals("Food", AiCategoryParser.parse("""{"category": "Food"}""", TransactionType.EXPENSE))
    }

    @Test
    fun unknownCategory_rejectsAndFallsBackToNull() {
        // Not in the allowed list -> must never be silently accepted.
        assertNull(AiCategoryParser.parse("""{"category": "Restaurants & Dining"}""", TransactionType.EXPENSE))
        assertNull(AiCategoryParser.parse("""{"category": "Airplane Food"}""", TransactionType.EXPENSE))
        assertNull(AiCategoryParser.parse("""{"category": "Gifts"}""", TransactionType.EXPENSE)) // wrong namespace for expense
        assertNull(AiCategoryParser.parse("""{"category": "Food"}""", TransactionType.INCOME)) // wrong namespace for income
    }

    @Test
    fun malformedJson_isRejected() {
        assertNull(AiCategoryParser.parse("""{"category": "Food"""", TransactionType.EXPENSE))
        assertNull(AiCategoryParser.parse("""category: Food""", TransactionType.EXPENSE))
        assertNull(AiCategoryParser.parse("""{answer: Food}""", TransactionType.EXPENSE))
        assertNull(AiCategoryParser.parse("""{"Food"}""", TransactionType.EXPENSE))
    }

    @Test
    fun emptyAndBlankReplies_areRejected() {
        assertNull(AiCategoryParser.parse("", TransactionType.EXPENSE))
        assertNull(AiCategoryParser.parse("   ", TransactionType.EXPENSE))
        assertNull(AiCategoryParser.parse("\n\n", TransactionType.EXPENSE))
    }

    @Test
    fun incomeCategories_parseForIncomeType() {
        assertEquals("Salary", AiCategoryParser.parse("""{"category": "Salary"}""", TransactionType.INCOME))
        assertEquals("Freelance", AiCategoryParser.parse("""{"category": "Freelance"}""", TransactionType.INCOME))
        assertEquals("Gifts", AiCategoryParser.parse("""{"category": "gifts"}""", TransactionType.INCOME))
    }

    @Test
    fun multiMemberJson_usesCategoryKey() {
        assertEquals("Entertainment", AiCategoryParser.parse(
            """{"category": "Entertainment", "confidence": 0.87}""",
            TransactionType.EXPENSE,
        ))
    }
}