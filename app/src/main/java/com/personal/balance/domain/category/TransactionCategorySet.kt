package com.personal.balance.domain.category

import com.personal.balance.model.TransactionType

/**
 * The ONE authoritative transaction category system.
 *
 * A transaction's category is stored as a plain string on [com.personal.balance.model.Transaction]
 * (`Transaction.category`). This object is the single source of truth for which
 * strings are legitimate, so whatever produced the value (automatic AI
 * categorization, the deterministic fallback, or future editing) always lands
 * on the same canonical spelling. Financial Analysis and Transaction
 * Intelligence already read `Transaction.category` verbatim (blank -> "General"),
 * so no consumer needs any new category logic.
 *
 * Expense and Income intentionally use DIFFERENT category namespaces: a salary is
 * never a shopping category, and grouping (FinancialAnalyzer / Transaction
 * Intelligence) already splits income and expense totals per type.
 *
 * "General" and the "Other …" buckets are produced only locally (blank or
 * genuinely unknown descriptions). They are NOT offered to the AI; the model
 * must always return a specific allowed category.
 */
object TransactionCategorySet {

    const val GENERAL = "General"

    /** Specific expense categories the AI may return. */
    val expenseCategories: List<String> = listOf(
        "Housing",
        "Food",
        "Transport",
        "Shopping",
        "Entertainment",
        "Health",
        "Utilities",
        "Other Expense",
    )

    /** Specific income categories the AI may return. */
    val incomeCategories: List<String> = listOf(
        "Salary",
        "Freelance",
        "Bonus",
        "Gifts",
        "Other Income",
    )

    /** The exact categories allowed for [type], in canonical spelling. */
    fun allowedFor(type: TransactionType): List<String> =
        when (type) {
            TransactionType.INCOME -> incomeCategories
            TransactionType.EXPENSE -> expenseCategories
        }

    /**
     * Returns the canonical category whose name equals [candidate] ignoring
     * surrounding whitespace, quote/punctuation characters and letter case.
     * `null` when [candidate] is not one of the allowed categories for [type] —
     * callers then fall back safely (an invented name like "Restaurants & Dining"
     * is never stored).
     */
    fun canonical(candidate: String, type: TransactionType): String? {
        val normalized = normalize(candidate)
        if (normalized.isEmpty()) return null
        return allowedFor(type).firstOrNull { it.equals(normalized, ignoreCase = true) }
    }

    /**
     * Lowers, trims, strips surrounding quote/backtick/asterisk decorations and
     * collapses internal whitespace so `"  food  "`, `` `Food` `` and ` Food ` all
     * match "Food".
     */
    private fun normalize(value: String): String =
        value.trim()
            .trim('"', '\'', '`', '*', '[', ']', '{', '}', ',', '.', ';', ':')
            .trim()
            .replace(Regex("\\s+"), " ")
}