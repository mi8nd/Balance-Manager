package com.personal.balance.domain.insights

import com.personal.balance.domain.EGPFormatter
import com.personal.balance.domain.ai.AiRequest
import com.personal.balance.domain.finance.TransactionIntelligenceContextBuilder
import java.time.ZoneId

/**
 * Builds the [AiRequest] for Financial Insights from a deterministic
 * [FinancialInsightsSnapshot].
 *
 * AI-INPUT BOUNDARY: mirrors Transaction Intelligence — transactions are sent
 * only as pre-computed integer facts (no raw descriptions, no uid/doc ids).
 * The base facts come from [TransactionIntelligenceContextBuilder]; this adds
 * account-context facts (balance, net change, uncategorized count, goal) that
 * are exact values computed by the app. The balance is named `account_balance`
 * and the instructions say it is separate from any monthly totals/net change,
 * so the model can never conflate it with spending.
 */
object FinancialInsightsContextBuilder {

    private val INSTRUCTIONS = buildString {
        append("The \"Authoritative facts\" above are the only financial data you were given and are exact values computed by the app. ")
        append("Base your insights and recommendations on them only. Never invent transactions, amounts, categories, dates, values, or patterns. ")
        append("A fact that is absent means there is not enough evidence — do not claim a trend or anomaly it would require. ")
        append("account_balance is the account's current balance and is a SEPARATE fact from any monthly totals or net change; never treat it as spending or income. ")
        append("Use the supplied EGP amounts exactly as given. ")
        append("Grounded facts about savings progress may reference goal_name, goal_target, goal_current, goal_remaining, goal_progress_percent, and goal_completed; ")
        append("when the goal facts say no savings goal is tracked, never invent one or claim progress. ")
        append("Insights must be observations grounded in the exact numbers. ")
        append("Recommendations must be phrased as suggestions (e.g. \"You could consider…\"), must follow directly from the user's own numbers, must not promise outcomes, ")
        append("and must not present you as a licensed financial professional or a promise of future financial results. ")
        append("Be concise and practical; explain why an insight matters when that is not obvious. ")
        append("Do not add boilerplate disclaimers; only state a limitation when it genuinely matters.")
    }

    private const val QUESTION =
        "Analyze these authoritative facts from my last two months of transactions and my savings progress. " +
            "Identify genuinely notable patterns, changes, anomalies, or behaviors (for example large spending " +
            "shifts, an unusually large transaction, heavy category concentration, a recurring-looking category, " +
            "a positive or negative income-vs-expense gap, or savings-goal progress). " +
            "Reply with up to 3 insight lines, each formatted exactly as: INSIGHT|LEVEL|Title|Explanation, " +
            "where LEVEL is HIGH, MEDIUM, LOW, or INFO. " +
            "Then reply with up to 3 recommendation lines, each formatted exactly as: RECOMMENDATION|Title|Explanation. " +
            "Every insight and every recommendation must be supported only by the facts above; if the facts cannot " +
            "support a claim, do not make it. Use 1-2 sentences per explanation."

    fun build(
        snapshot: FinancialInsightsSnapshot,
        zone: ZoneId = ZoneId.systemDefault(),
    ): AiRequest {
        val base = TransactionIntelligenceContextBuilder.build(snapshot.intelligence, zone)
        val extra = linkedMapOf<String, String>()

        extra["account_balance"] = snapshot.currentBalance?.let { money(it) } ?: "not available"
        extra["net_change_current"] = snapshot.intelligence.currentLabel.let {
            "$it net change (income − expenses): ${signedMoney(snapshot.netChangeCurrent)}"
        }
        extra["net_change_previous"] = snapshot.intelligence.previousLabel.let {
            "$it net change (income − expenses): ${signedMoney(snapshot.netChangePrevious)}"
        }
        extra["uncategorized_current_transactions"] =
            if (snapshot.uncategorizedCurrentCount > 0) {
                "${snapshot.uncategorizedCurrentCount} transaction(s) in the current period have no category yet"
            } else {
                "every transaction in the current period already has a category"
            }

        val goal = snapshot.goal
        if (goal != null) {
            val remaining = (goal.targetPiastres - goal.currentPiastres).coerceAtLeast(0L)
            val progressPercent = if (goal.targetPiastres > 0L) {
                ((goal.currentPiastres * 100) / goal.targetPiastres).toInt().coerceIn(0, 100)
            } else {
                0
            }
            extra["goal_name"] = goal.name
            extra["goal_target"] = money(goal.targetPiastres)
            extra["goal_current"] = money(goal.currentPiastres)
            extra["goal_remaining"] = money(remaining)
            extra["goal_progress_percent"] = "$progressPercent%"
            extra["goal_completed"] = goal.completed.toString()
        } else {
            extra["goal_status"] = "No savings goal is currently tracked in the app."
        }

        return base.copy(
            structuredFacts = base.structuredFacts + extra,
            question = QUESTION,
            additionalInstructions = INSTRUCTIONS,
        )
    }

    private fun money(piastres: Long): String = EGPFormatter.formatAmount(piastres)

    private fun signedMoney(piastres: Long): String {
        val sign = if (piastres > 0) "+" else ""
        return "$sign${money(piastres)}"
    }
}