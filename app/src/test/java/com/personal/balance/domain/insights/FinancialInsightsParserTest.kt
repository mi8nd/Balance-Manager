package com.personal.balance.domain.insights

import com.personal.balance.domain.finance.Importance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FinancialInsightsParserTest {

    @Test
    fun parsesInsightAndRecommendationLines() {
        val reply = """
            INSIGHT|HIGH|Spending spike|Transport jumped 40% vs last month.
            INSIGHT|INFO|Income steady|Income is flat.
            RECOMMENDATION|Review subscriptions|You could consider trimming recurring subscriptions.
        """.trimIndent()

        val parsed = FinancialInsightsParser.parse(reply)

        assertEquals(2, parsed.insights.size)
        assertEquals("Spending spike", parsed.insights[0].title)
        assertEquals(Importance.HIGH, parsed.insights[0].importance)
        assertEquals("Transport jumped 40% vs last month.", parsed.insights[0].explanation)
        assertEquals(Importance.INFO, parsed.insights[1].importance)
        val recommendation = parsed.recommendations.single()
        assertEquals("Review subscriptions", recommendation.title)
        assertEquals("You could consider trimming recurring subscriptions.", recommendation.explanation)
    }

    @Test
    fun anyCaseLevelMatchesImportance() {
        val reply = "INSIGHT|medium|Moderate|Something changed."

        val insight = FinancialInsightsParser.parse(reply).insights.single()

        assertEquals(Importance.MEDIUM, insight.importance)
    }

    @Test
    fun titleStopsAtFirstPipeEverythingElseIsExplanation() {
        val reply = "INSIGHT|HIGH|A | B | C|Explanation"

        val insight = FinancialInsightsParser.parse(reply).insights.single()

        assertEquals("A", insight.title)
        assertEquals("B | C|Explanation", insight.explanation)
    }

    @Test
    fun emptyTitleLinesAreIgnored() {
        val reply = "INSIGHT|HIGH||Explanation\nRECOMMENDATION|"

        val parsed = FinancialInsightsParser.parse(reply)

        assertTrue(parsed.insights.isEmpty())
        assertTrue(parsed.recommendations.isEmpty())
    }

    @Test
    fun proseAndUnknownLinesAreIgnored() {
        val reply = """
            Here is my overall summary of your last two months. You have been spending
            a bit more than before on transport overall, which matters because it is
            your largest category right now.
            INSIGHT|LOW|Something small|Trivial detail.
        """.trimIndent()

        val parsed = FinancialInsightsParser.parse(reply)

        // Unlike Transaction Intelligence, prose never becomes a generic "Financial
        // summary" card — only exact INSIGHT/RECOMMENDATION lines are consumed.
        assertEquals(listOf("Something small"), parsed.insights.map { it.title })
    }

    @Test
    fun emptyReplyYieldsEmptyStructures() {
        val parsed = FinancialInsightsParser.parse("")

        assertTrue(parsed.insights.isEmpty())
        assertTrue(parsed.recommendations.isEmpty())
    }

    @Test
    fun missingExplanationFallsBackToTitle() {
        val insight = FinancialInsightsParser.parse("INSIGHT|HIGH|Just a title").insights.single()

        assertEquals("Just a title", insight.explanation)
    }
}