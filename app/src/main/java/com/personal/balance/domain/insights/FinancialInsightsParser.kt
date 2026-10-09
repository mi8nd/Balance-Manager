package com.personal.balance.domain.insights

import com.personal.balance.domain.finance.Importance
import com.personal.balance.domain.finance.TransactionInsight

/**
 * Parses the Financial Insights model reply into structured insights and
 * recommendations.
 *
 * The model is instructed to emit:
 *  - `INSIGHT|<LEVEL>|Title|Explanation` lines (reusing Transaction
 *    Intelligence's insight format and [TransactionInsight] type)
 *  - `RECOMMENDATION|Title|Explanation` lines
 *
 * Only exact lines are consumed; everything else is ignored, so a preamble or a
 * partial (typewriter) reveal never corrupts the cards. Unlike Transaction
 * Intelligence's parser, the whole reply is NEVER wrapped into a generic
 * "Financial summary" insight here — prose belongs to neither card type, and
 * the screen shows an honest "nothing parsed" message instead of a fabricated
 * structure. A missing section is simply empty.
 */
object FinancialInsightsParser {

    private val INSIGHT_LINE = Regex("^INSIGHT\\s*\\|\\s*([A-Za-z]+)\\s*\\|\\s*(.*)$")
    private val RECOMMENDATION_LINE = Regex("^RECOMMENDATION\\s*\\|\\s*(.*)$")

    data class Reply(
        val insights: List<TransactionInsight> = emptyList(),
        val recommendations: List<FinancialRecommendation> = emptyList(),
    )

    fun parse(reply: String): Reply {
        val insights = mutableListOf<TransactionInsight>()
        val recommendations = mutableListOf<FinancialRecommendation>()
        for (raw in reply.lines()) {
            val line = raw.trim()
            parseInsightLine(line)?.let { insights += it }
            parseRecommendationLine(line)?.let { recommendations += it }
        }
        return Reply(insights, recommendations)
    }

    private fun parseInsightLine(line: String): TransactionInsight? {
        val match = INSIGHT_LINE.matchEntire(line) ?: return null
        val importance = Importance.from(match.groupValues[1])
        val body = match.groupValues[2]
        val parts = body.split("|", limit = 2)
        val title = parts.getOrElse(0) { "" }.trim()
        if (title.isEmpty()) return null
        val explanation = parts.getOrElse(1) { "" }.trim().ifEmpty { title }
        return TransactionInsight(title = title, explanation = explanation, importance = importance)
    }

    private fun parseRecommendationLine(line: String): FinancialRecommendation? {
        val match = RECOMMENDATION_LINE.matchEntire(line) ?: return null
        val body = match.groupValues[1]
        val parts = body.split("|", limit = 2)
        val title = parts.getOrElse(0) { "" }.trim()
        if (title.isEmpty()) return null
        val explanation = parts.getOrElse(1) { "" }.trim().ifEmpty { title }
        return FinancialRecommendation(title = title, explanation = explanation)
    }
}