package com.personal.balance.domain.category

import com.personal.balance.model.TransactionType

/**
 * Deterministic, device-local fallback categorizer. Used ONLY when the AI path
 * is unavailable (offline, budget exhausted, malformed/unvalidated reply) — it
 * is deliberately NOT the primary semantic classifier.
 *
 * Matching is simple normalized substring matching:
 *  - the description is lower-cased, trimmed and whitespace-collapsed;
 *  - for the transaction's type, each category's keyword list is checked in
 *    priority order; the first category holding any keyword as a substring wins;
 *  - a blank description yields "General" (the historical blank→General rule
 *    shared with Financial Analysis);
 *  - a non-blank but unmatched description yields the type's "Other" bucket
 *    ("Other Expense" / "Other Income") — never a silent wrong category.
 *
 * Expense and Income use separate keyword tables so the same word diverges by
 * type where needed ("Gift": expense → Shopping, income → Gifts).
 */
object DeterministicCategoryClassifier {

    fun classify(type: TransactionType, description: String): String {
        val normalized = description.lowercase().trim().replace(Regex("\\s+"), " ")
        if (normalized.isEmpty()) return TransactionCategorySet.GENERAL

        val rules = if (type == TransactionType.EXPENSE) expenseRules else incomeRules
        for ((category, keywords) in rules) {
            for (keyword in keywords) {
                if (normalized.contains(keyword)) return category
            }
        }
        return if (type == TransactionType.EXPENSE) "Other Expense" else "Other Income"
    }

    private val expenseRules: List<Pair<String, List<String>>> = listOf(
        "Health" to listOf(
            "medicine", "pharmacy", "pharmacist", "medication", "doctor", "hospital",
            "clinic", "dentist", "dental", "medical", "health", "therapy",
            "physiotherapy", "gym", "healthcare",
        ),
        "Utilities" to listOf(
            "electricity", "electric bill", "electrical", "water", "internet", "wifi",
            "broadband", "phone bill", "mobile bill", "utility", "utilities",
            "heating", "gas bill",
        ),
        "Transport" to listOf(
            "uber", "taxi", "cab", "bus", "metro", "subway", "train", "transport",
            "transportation", "fuel", "petrol", "gasoline", "parking", "commute",
            "fare", "flight", "airfare", "car rental",
        ),
        "Entertainment" to listOf(
            "game", "games", "gaming", "cinema", "movie", "movies", "film",
            "concert", "streaming", "netflix", "music", "playstation", "xbox",
            "entertainment", "amusement", "show",
        ),
        "Shopping" to listOf(
            "clothes", "clothing", "shoes", "shoe", "jeans", "shirt", "dress",
            "jacket", "bag", "store", "shop", "shopping", "mall", "electronics",
            "phone", "laptop", "gadget", "gadgets", "headphones", "accessory",
            "gift", "present", "retail", "purchases", "purchase",
        ),
        "Food" to listOf(
            "food", "groceries", "grocery", "supermarket", "market", "restaurant",
            "cafe", "café", "coffee", "lunch", "dinner", "breakfast", "meal",
            "dining", "takeaway", "takeout", "snack", "snacks", "pizza", "burger",
            "kebab", "sandwich", "eat", "eating", "drank", "bakery", "baker",
        ),
        "Housing" to listOf(
            "rent", "apartment", "flat", "landlord", "mortgage", "housing",
            "accommodation", "property", "lease",
        ),
    )

    private val incomeRules: List<Pair<String, List<String>>> = listOf(
        "Salary" to listOf(
            "salary", "paycheck", "payroll", "wage", "wages",
            "stipend",
        ),
        "Bonus" to listOf(
            "bonus", "incentive", "commission", "profit share", "gratuity",
            "reward",
        ),
        "Gifts" to listOf(
            "gift", "gifts", "present", "donation", "donations", "grant",
        ),
        "Freelance" to listOf(
            "freelance", "freelancing", "freelancer", "gig", "gigs", "contract",
            "contracting", "consulting", "consultancy", "consultation",
        ),
    )
}