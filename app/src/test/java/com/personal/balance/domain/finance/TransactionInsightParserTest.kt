package com.personal.balance.domain.finance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransactionInsightParserTest {

    @Test
    fun singleStructuredLine_parsedCorrectly() {
        val result = TransactionInsightParser.parse(
            "INSIGHT|HIGH|Rent concentration|Rent is 75% of your expenses this month.",
        )

        assertEquals(1, result.size)
        assertEquals("Rent concentration", result[0].title)
        assertEquals("Rent is 75% of your expenses this month.", result[0].explanation)
        assertEquals(Importance.HIGH, result[0].importance)
    }

    @Test
    fun multipleLines_allParsed() {
        val result = TransactionInsightParser.parse(
            """
                |INSIGHT|HIGH|Big jump|Food spending increased 50% month-over-month.
                |INSIGHT|LOW|Stable rent|Rent stayed consistent at 1,000.00 EGP.
                |INSIGHT|INFO|Overview|Your spending covered 4 categories.
            """.trimMargin(),
        )

        assertEquals(3, result.size)
        assertEquals("Big jump", result[0].title)
        assertEquals(Importance.HIGH, result[0].importance)
        assertEquals("Stable rent", result[1].title)
        assertEquals(Importance.LOW, result[1].importance)
        assertEquals("Overview", result[2].title)
        assertEquals(Importance.INFO, result[2].importance)
    }

    @Test
    fun whiteSpace_trimmedCorrectly() {
        val result = TransactionInsightParser.parse(
            "  INSIGHT | MEDIUM | Title | Explanation text  ",
        )

        assertEquals(1, result.size)
        assertEquals("Title", result[0].title)
        assertEquals("Explanation text", result[0].explanation)
        assertEquals(Importance.MEDIUM, result[0].importance)
    }

    @Test
    fun preambleIgnored_onlyInsightLinesUsed() {
        val result = TransactionInsightParser.parse(
            """
                |Here is my analysis:
                |Based on your spending patterns:
                |INSIGHT|HIGH|Outlier|Your 500.00 EGP rent transaction is 5x the average.
                |Let me know if you need more details.
            """.trimMargin(),
        )

        assertEquals(1, result.size)
        assertEquals("Outlier", result[0].title)
    }

    @Test
    fun unknownLevel_defaultsToMedium() {
        val result = TransactionInsightParser.parse(
            "INSIGHT|CRITICAL|Urgent|Your spending is high.",
        )

        assertEquals(1, result.size)
        assertEquals(Importance.MEDIUM, result[0].importance)
    }

    @Test
    fun caseInsensitiveLevel() {
        val result = TransactionInsightParser.parse(
            "INSIGHT|high|Test|Explanation.",
        )

        assertEquals(1, result.size)
        assertEquals(Importance.HIGH, result[0].importance)
    }

    @Test
    fun missingExplanation_usesTitleAsExplanation() {
        val result = TransactionInsightParser.parse(
            "INSIGHT|INFO|Just a headline",
        )

        assertEquals(1, result.size)
        assertEquals("Just a headline", result[0].title)
        assertEquals("Just a headline", result[0].explanation)
    }

    @Test
    fun emptyTitle_lineSkipped() {
        val result = TransactionInsightParser.parse(
            "INSIGHT|HIGH||Explanation without a title",
        )

        assertTrue(result.isEmpty())
    }

    @Test
    fun pipesInExplanation_preservedExactly() {
        val result = TransactionInsightParser.parse(
            "INSIGHT|MEDIUM|Category split|Food: 50% | Transport: 30% | Rent: 20%.",
        )

        assertEquals(1, result.size)
        assertEquals("Food: 50% | Transport: 30% | Rent: 20%.", result[0].explanation)
    }

    @Test
    fun blankInput_returnsEmptyList() {
        assertTrue(TransactionInsightParser.parse("").isEmpty())
        assertTrue(TransactionInsightParser.parse("   ").isEmpty())
        assertTrue(TransactionInsightParser.parse("\n\n").isEmpty())
    }

    @Test
    fun noStructuredLines_fallbackGenericInsight() {
        val result = TransactionInsightParser.parse(
            "Your spending increased significantly this month due to higher food costs.",
        )

        assertEquals(1, result.size)
        assertEquals("Financial summary", result[0].title)
        assertEquals(Importance.INFO, result[0].importance)
        assertTrue(result[0].explanation.contains("spending increased"))
    }

    @Test
    fun emptyExplanationAfterPipe_usesTitle() {
        val result = TransactionInsightParser.parse(
            "INSIGHT|MEDIUM|Important pattern|",
        )

        assertEquals(1, result.size)
        assertEquals("Important pattern", result[0].explanation)
    }

    @Test
    fun nonInsightLinesBetweenValidLines_ignored() {
        val result = TransactionInsightParser.parse(
            """
                |Here is a preamble.
                |INSIGHT|HIGH|First|First explanation.
                |Some random text between insights.
                |INSIGHT|LOW|Second|Second explanation.
            """.trimMargin(),
        )

        assertEquals(2, result.size)
        assertEquals("First", result[0].title)
        assertEquals("Second", result[1].title)
    }

    @Test
    fun wrappedExplanationKeepsOnlyItsFirstLine() {
        // The contract is exactly one insight per line; an explanation wrapped
        // onto a second line keeps only its first line (the second line is a
        // non-INSIGHT line and is ignored, never merged).
        val result = TransactionInsightParser.parse(
            "INSIGHT|MEDIUM|Multi line|First sentence.\nSecond sentence.",
        )

        assertEquals(1, result.size)
        assertEquals("Multi line", result[0].title)
        assertEquals("First sentence.", result[0].explanation)
        assertFalse(result[0].explanation.contains("Second"))
    }

    @Test
    fun split_proseAndInsights_separated() {
        val reply = """
            Your spending pattern this month:
            INSIGHT|HIGH|Food spike|Food rose 50%.
            INSIGHT|LOW|Rent steady|Rent held at 1,000.00 EGP.
            Let me know if you want the full breakdown.
        """.trimIndent()

        val split = TransactionInsightParser.split(reply)

        assertEquals(2, split.insights.size)
        assertEquals("Food spike", split.insights[0].title)
        assertEquals("Rent steady", split.insights[1].title)
        assertTrue(split.prose.contains("Your spending pattern this month:"))
        assertTrue(split.prose.contains("Let me know if you want the full breakdown."))
        assertFalse(split.prose.contains("INSIGHT"))
        assertFalse(split.prose.contains("Food rose 50%"))
        assertFalse(split.prose.contains("Rent held"))
    }

    @Test
    fun split_onlyInsights_emptyProse() {
        val reply = """
            INSIGHT|HIGH|A|A explanation.
            INSIGHT|LOW|B|B explanation.
        """.trimIndent()

        val split = TransactionInsightParser.split(reply)

        assertEquals(2, split.insights.size)
        assertTrue(split.prose.isEmpty())
    }

    @Test
    fun split_onlyProse_noInsights() {
        val split = TransactionInsightParser.split("No structured lines here at all.")

        assertTrue(split.insights.isEmpty())
        assertEquals("No structured lines here at all.", split.prose)
    }

    @Test
    fun split_blankReply_emptyBoth() {
        val split = TransactionInsightParser.split("")

        assertTrue(split.insights.isEmpty())
        assertTrue(split.prose.isEmpty())
    }

    @Test
    fun split_preamblePreservedInsightStripped() {
        val split = TransactionInsightParser.split(
            "Here is my analysis.\nINSIGHT|MEDIUM|Outlier|Rent is 5x average.",
        )

        assertEquals(1, split.insights.size)
        assertEquals("Here is my analysis.", split.prose)
    }

    @Test
    fun split_malformedInsightStaysInProse() {
        val split = TransactionInsightParser.split(
            "INSIGHT Badly formatted without pipes.",
        )

        assertTrue(split.insights.isEmpty())
        assertTrue(split.prose.contains("Badly formatted"))
    }
}