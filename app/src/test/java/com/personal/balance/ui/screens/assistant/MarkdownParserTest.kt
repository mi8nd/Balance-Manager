package com.personal.balance.ui.screens.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownParserTest {

    // ─────────────────  Block parsing  ─────────────────

    @Test
    fun plainText_becomesSingleParagraph() {
        val blocks = parseMarkdown("hello\nworld")

        assertEquals(listOf(MdBlock.Paragraph("hello world")), blocks)
    }

    @Test
    fun blankLinesSeparateParagraphs() {
        val blocks = parseMarkdown("first paragraph\n\nsecond paragraph")

        assertEquals(
            listOf(MdBlock.Paragraph("first paragraph"), MdBlock.Paragraph("second paragraph")),
            blocks,
        )
    }

    @Test
    fun headings_requireSpaceAfterHashes() {
        val blocks = parseMarkdown("# Title\n## Section\n### Deep")

        assertEquals(
            listOf(
                MdBlock.Heading(1, "Title"),
                MdBlock.Heading(2, "Section"),
                MdBlock.Heading(3, "Deep"),
            ),
            blocks,
        )
    }

    @Test
    fun hashWithoutSpace_staysLiteralParagraph() {
        val blocks = parseMarkdown("#not-a-heading")

        assertEquals(listOf(MdBlock.Paragraph("#not-a-heading")), blocks)
    }

    @Test
    fun moreThanSixHashes_notAHeading() {
        val blocks = parseMarkdown("####### too many")

        assertEquals(listOf(MdBlock.Paragraph("####### too many")), blocks)
    }

    @Test
    fun bulletLists_areSeparateItems() {
        val blocks = parseMarkdown("- apple\n- banana\n* another")

        assertEquals(
            listOf(
                MdBlock.ListItem("•", "apple"),
                MdBlock.ListItem("•", "banana"),
                MdBlock.ListItem("•", "another"),
            ),
            blocks,
        )
    }

    @Test
    fun orderedLists_numberTheBubble() {
        val blocks = parseMarkdown("1. first\n2. second")

        assertEquals(
            listOf(
                MdBlock.ListItem("1.", "first"),
                MdBlock.ListItem("2.", "second"),
            ),
            blocks,
        )
    }

    @Test
    fun fencedCodeBlock_capturedLiteral() {
        val blocks = parseMarkdown("intro\n\n```kotlin\nval x = 1\n#!/not-markdown\n```\n\noutro")

        assertEquals(
            listOf(
                MdBlock.Paragraph("intro"),
                MdBlock.CodeBlock("val x = 1\n#!/not-markdown"),
                MdBlock.Paragraph("outro"),
            ),
            blocks,
        )
    }

    @Test
    fun unterminatedFence_keepsLiteralContent() {
        val blocks = parseMarkdown("```\nstill open")

        assertEquals(listOf(MdBlock.CodeBlock("still open")), blocks)
    }

    @Test
    fun horizontalRule_threeDashes() {
        val blocks = parseMarkdown("---")

        assertEquals(listOf(MdBlock.HorizontalRule), blocks)
    }

    @Test
    fun horizontalRule_withSurroundingText() {
        val blocks = parseMarkdown("intro\n\n---\n\noutro")

        assertEquals(
            listOf(
                MdBlock.Paragraph("intro"),
                MdBlock.HorizontalRule,
                MdBlock.Paragraph("outro"),
            ),
            blocks,
        )
    }

    @Test
    fun horizontalRule_userReportedCase_parsesAsRuleNotLiteralParagraph() {
        // The exact case reported: a standalone `---` between two paragraphs must
        // become a HorizontalRule, never a paragraph containing "---".
        val blocks = parseMarkdown("First paragraph.\n\n---\n\nSecond paragraph.")

        assertEquals(
            listOf(
                MdBlock.Paragraph("First paragraph."),
                MdBlock.HorizontalRule,
                MdBlock.Paragraph("Second paragraph."),
            ),
            blocks,
        )
        assertTrue(blocks.none { it is MdBlock.Paragraph && it.text.contains("---") })
    }

    @Test
    fun horizontalRule_completedMessage_equalsPartialEndState() {
        // Typewriter finalization: when the reveal finishes, partial=false and the
        // full string must parse to the same structure that the trailing partial
        // reveals already converged on (paragraph + rule + paragraph).
        val source = "First paragraph.\n\n---\n\nSecond paragraph."

        assertEquals(parseMarkdown(source), parseMarkdown(source, partial = true))
        assertEquals(listOf(MdBlock.HorizontalRule), parseMarkdown("---", partial = true))
    }

    @Test
    fun horizontalRule_pendingDashesHiddenDuringPartialReveal() {
        // While being revealed, a horizontal rule passes through `-` then `--`:
        // at a block boundary those are hidden (the text before them stays), so
        // literal dashes never flash during the typewriter reveal.
        assertEquals(
            listOf(MdBlock.Paragraph("First paragraph.")),
            parseMarkdown("First paragraph.\n\n-", partial = true),
        )
        assertEquals(
            listOf(MdBlock.Paragraph("First paragraph.")),
            parseMarkdown("First paragraph.\n\n--", partial = true),
        )
    }

    @Test
    fun horizontalRule_pendingDashe_keptWhenMergedIntoOpenParagraph() {
        // "-" directly attached to paragraph text is ambiguous; it stays literal
        // (matches the fully-revealed parse), only block-boundary dashes are hidden.
        assertEquals(
            listOf(MdBlock.Paragraph("hello -")),
            parseMarkdown("hello\n-", partial = true),
        )
    }

    @Test
    fun horizontalRule_withLeadingIndentation() {
        // Indented separators are common in model output; a leading-whitespace
        // `---` must still be a rule (not a literal paragraph).
        assertEquals(listOf(MdBlock.HorizontalRule), parseMarkdown("  ---"))
        assertEquals(listOf(MdBlock.HorizontalRule), parseMarkdown("\t---"))
    }

    @Test
    fun horizontalRule_singleAndDoubleDashes_stayLiteralWhenComplete() {
        assertEquals(listOf(MdBlock.Paragraph("-")), parseMarkdown("-", partial = false))
        assertEquals(listOf(MdBlock.Paragraph("--")), parseMarkdown("--", partial = false))
    }

    @Test
    fun horizontalRule_crlfLineEndings() {
        val blocks = parseMarkdown("intro\r\n\r\n---\r\n\r\noutro")

        assertEquals(
            listOf(
                MdBlock.Paragraph("intro"),
                MdBlock.HorizontalRule,
                MdBlock.Paragraph("outro"),
            ),
            blocks,
        )
    }

    @Test
    fun horizontalRule_withoutBlankLines_aroundSeparator() {
        val blocks = parseMarkdown("intro\n---\noutro")

        assertEquals(
            listOf(
                MdBlock.Paragraph("intro"),
                MdBlock.HorizontalRule,
                MdBlock.Paragraph("outro"),
            ),
            blocks,
        )
    }

    // ─────────────────  Inline token parsing  ─────────────────

    @Test
    fun inlineBold_andItalic() {
        val tokens = tokenizeInline("say **loudly** now *softly* end")

        assertEquals(
            listOf(
                InlineToken.Text("say "),
                InlineToken.Bold(listOf(InlineToken.Text("loudly"))),
                InlineToken.Text(" now "),
                InlineToken.Italic(listOf(InlineToken.Text("softly"))),
                InlineToken.Text(" end"),
            ),
            tokens,
        )
    }

    @Test
    fun inlineCode() {
        val tokens = tokenizeInline("use `value` here")

        assertEquals(
            listOf(InlineToken.Text("use "), InlineToken.Code("value"), InlineToken.Text(" here")),
            tokens,
        )
    }

    @Test
    fun linkWithTextAndUrl() {
        val tokens = tokenizeInline("see [docs](https://example.com) now")

        assertEquals(
            listOf(
                InlineToken.Text("see "),
                InlineToken.Link("docs", "https://example.com"),
                InlineToken.Text(" now"),
            ),
            tokens,
        )
    }

    @Test
    fun unclosedMarkers_stayLiteral() {
        val tokens = tokenizeInline("**never closed *no end `none")

        assertTrue(tokens.all { it is InlineToken.Text })
        assertEquals("**never closed *no end `none", tokens.joinToString("") { (it as InlineToken.Text).value })
    }

    @Test
    fun boldContainingItalic_isNested() {
        val tokens = tokenizeInline("**bold *and italic* done**")

        assertEquals(
            listOf(InlineToken.Bold(listOf(
                InlineToken.Text("bold "),
                InlineToken.Italic(listOf(InlineToken.Text("and italic"))),
                InlineToken.Text(" done"),
            ))),
            tokens,
        )
    }

    // ─────────────────  Typewriter pacing (pure logic)  ─────────────────

    @Test
    fun revealStep_isPositiveAndBounded() {
        assertTrue(revealStep(1) == 1)
        assertTrue(revealStep(100_000) <= 100_000)
        assertTrue(revealStep(90) in 1..90)
    }

    @Test
    fun revealsCompleteInAboutFortyFiveBoundedTicks() {
        // Character counts that require exactly ~45 ticks, regardless of length.
        fun expectedTicks(length: Int): Long {
            val step = revealStep(length)
            return (length.toLong() + step - 1) / step
        }
        assertEquals(10L, expectedTicks(10))
        assertEquals(45L, expectedTicks(90))
        assertTrue(expectedTicks(10_000) in 45L..47L)
    }

    @Test
    fun longAnswersTickFasterThanShortOnes() {
        assertTrue(revealTickMillis(10_000) <= revealTickMillis(50))
    }

    // ─────────────────  Partial (reveal-in-progress) rendering  ─────────────────

    @Test
    fun partial_unclosedBold_hidesMarkersAndStylesContent() {
        val tokens = tokenizeInline("**bold still going", partial = true)

        assertEquals(
            listOf(InlineToken.Bold(listOf(InlineToken.Text("bold still going")))),
            tokens,
        )
    }

    @Test
    fun partial_unclosedItalic_hidesMarker() {
        val tokens = tokenizeInline("apply *now", partial = true)

        assertEquals(
            listOf(
                InlineToken.Text("apply "),
                InlineToken.Italic(listOf(InlineToken.Text("now"))),
            ),
            tokens,
        )
    }

    @Test
    fun partial_unclosedCode_hidesBacktickAndStyles() {
        val tokens = tokenizeInline("use `value unfin", partial = true)

        assertEquals(
            listOf(InlineToken.Text("use "), InlineToken.Code("value unfin")),
            tokens,
        )
    }

    @Test
    fun partial_incompleteLink_hidesBracketsAndPendingUrl() {
        val tokens = tokenizeInline("see [docs](https://ex", partial = true)

        assertEquals(listOf(InlineToken.Text("see "), InlineToken.Text("docs")), tokens)
    }

    @Test
    fun partial_loneOpeningBracket_isHidden() {
        val tokens = tokenizeInline("see [docs", partial = true)

        assertEquals(listOf(InlineToken.Text("see docs")), tokens)
    }

    @Test
    fun partial_bracketWithoutParen_hidesBothBrackets() {
        val tokens = tokenizeInline("see [docs]", partial = true)

        assertEquals(listOf(InlineToken.Text("see "), InlineToken.Text("docs")), tokens)
    }

    @Test
    fun partialMode_keepsMathAsterisksLiteral() {
        val tokens = tokenizeInline("2 * 3 and 4 ** 5", partial = true)

        assertEquals(listOf(InlineToken.Text("2 * 3 and 4 ** 5")), tokens)
    }

    @Test
    fun partialMode_equalsComplete_forWellFormedInput() {
        val source = "**bold** and *italic* and `code` and [link](https://example.com)"

        assertEquals(tokenizeInline(source), tokenizeInline(source, partial = true))
    }

    // ─────────────────  Markdown tables  ─────────────────

    @Test
    fun validTable_parsesIntoTableBlock() {
        val blocks = parseMarkdown(
            "| Category | Amount |\n" +
                "|---|---|\n" +
                "| Housing | 4,000 EGP |\n" +
                "| Food | 2,100 EGP |",
        )

        assertEquals(
            listOf(
                MdBlock.Table(
                    header = listOf("Category", "Amount"),
                    rows = listOf(
                        listOf("Housing", "4,000 EGP"),
                        listOf("Food", "2,100 EGP"),
                    ),
                    alignments = listOf(TableColumnAlignment.LEFT, TableColumnAlignment.LEFT),
                ),
            ),
            blocks,
        )
    }

    @Test
    fun table_withAlignmentMarkers_parsesColumnAlignments() {
        val blocks = parseMarkdown(
            "| Left | Right | Center |\n" +
                "|:---|---:|:---:|\n" +
                "| a | b | c |",
        )

        val table = blocks.single() as MdBlock.Table
        assertEquals(
            listOf(
                TableColumnAlignment.LEFT,
                TableColumnAlignment.RIGHT,
                TableColumnAlignment.CENTER,
            ),
            table.alignments,
        )
        assertEquals(listOf(listOf("a", "b", "c")), table.rows)
    }

    @Test
    fun table_withMissingExtraWhitespace_isTolerated() {
        // No padding spaces, no leading/trailing pipe, extra internal whitespace.
        val blocks = parseMarkdown(
            "Category| Amount\n" +
                "--- | ---\n" +
                "Housing|4,000 EGP",
        )

        val table = blocks.single() as MdBlock.Table
        assertEquals(listOf("Category", "Amount"), table.header)
        assertEquals(listOf(listOf("Housing", "4,000 EGP")), table.rows)
    }

    @Test
    fun table_withCurrencyValues_keepsValuesIntact() {
        val blocks = parseMarkdown(
            "|  Category  |  Amount  |\n" +
                "|---|---:|\n" +
                "| Housing | 4,000 EGP |\n" +
                "| Shopping | 2,100 EGP |",
        )

        val table = blocks.single() as MdBlock.Table
        assertEquals(listOf("Housing", "4,000 EGP"), table.rows[0])
        assertEquals(listOf("Shopping", "2,100 EGP"), table.rows[1])
        assertEquals(TableColumnAlignment.RIGHT, table.alignments[1])
    }

    @Test
    fun textWithPipes_withoutDelimiter_staysParagraph() {
        val blocks = parseMarkdown(
            "| not a | table\n" +
                "because there is no delimiter row",
        )

        assertEquals(listOf(MdBlock.Paragraph("| not a | table because there is no delimiter row")), blocks)
    }

    @Test
    fun delimitedText_withoutFollowingRow_staysParagraph() {
        // A `|`-line whose NEXT line is ordinary text is not a table.
        val blocks = parseMarkdown(
            "| A | B |\n" +
                "Some ordinary sentence.",
        )

        assertEquals(1, blocks.size)
        assertTrue(blocks.single() is MdBlock.Paragraph)
    }

    @Test
    fun table_mixedWithParagraphs_parsesInOrder() {
        val blocks = parseMarkdown(
            "Here is a summary.\n" +
                "\n" +
                "| Category | Amount |\n" +
                "|---|---:|\n" +
                "| Housing | 4,000 EGP |\n" +
                "\n" +
                "Spending is concentrated.",
        )

        assertEquals(3, blocks.size)
        assertEquals(MdBlock.Paragraph("Here is a summary."), blocks[0])
        assertEquals(
            MdBlock.Table(listOf("Category", "Amount"), listOf(listOf("Housing", "4,000 EGP")), listOf(TableColumnAlignment.LEFT, TableColumnAlignment.RIGHT)),
            blocks[1],
        )
        assertEquals(MdBlock.Paragraph("Spending is concentrated."), blocks[2])
    }

    @Test
    fun table_delimiterWithoutHeaderLine_isNotATable() {
        // A delimiter alone (no `|`-header above) must not become a table; it
        // stays ordinary paragraph text.
        val blocks = parseMarkdown("---|---")

        assertEquals(1, blocks.size)
        assertTrue(blocks.single() is MdBlock.Paragraph)
    }

    @Test
    fun table_completedEqualsPartialEndState() {
        val source =
            "| Category | Amount |\n" +
                "|---|---:|\n" +
                "| Housing | 4,000 EGP |"

        assertEquals(parseMarkdown(source), parseMarkdown(source, partial = true))
    }

    @Test
    fun table_partialDelimiter_justAPipe_staysParagraph() {
        // Mid-typewriter the delimiter is only a bare `|` (not yet a valid
        // delimiter row): keep everything as ordinary text rather than flashing
        // a broken table. The pipe is harmless during a fast reveal.
        val blocks = parseMarkdown("| Category | Amount |\n|", partial = true)

        assertTrue(blocks.all { it is MdBlock.Paragraph })
    }

    @Test
    fun table_partialDelimiter_formsTableOnceValid() {
        // As the delimiter `|---|---|` is being written, prefixes like `|---` are
        // already a valid single-column delimiter: the block consistently parses
        // as a Table (fewer delimiter columns than header cells is tolerated).
        val blocks = parseMarkdown("| Category | Amount |\n|---", partial = true)

        val table = blocks.single() as MdBlock.Table
        assertEquals(listOf("Category", "Amount"), table.header)
        assertEquals(1, table.alignments.size)
        assertTrue(table.rows.isEmpty())
    }

    @Test
    fun table_partialRow_buildsTableWithRowsWrittenSoFar() {
        val blocks = parseMarkdown(
            "| Category | Amount |\n" +
                "|---|---|\n" +
                "| Housing |",
            partial = true,
        )

        val table = blocks.single() as MdBlock.Table
        assertEquals(listOf("Category", "Amount"), table.header)
        assertEquals(listOf(listOf("Housing")), table.rows)
    }

    @Test
    fun columnAlignment_mapsDelimiterSegments() {
        assertEquals(TableColumnAlignment.LEFT, tableColumnAlignment(":---"))
        assertEquals(TableColumnAlignment.RIGHT, tableColumnAlignment("---:"))
        assertEquals(TableColumnAlignment.CENTER, tableColumnAlignment(":---:"))
        assertEquals(TableColumnAlignment.LEFT, tableColumnAlignment("----"))
    }

    @Test
    fun table_withPercentageValues_keepsValuesIntact() {
        val blocks = parseMarkdown(
            "| Category | Amount | Share |\n" +
                "|---|---:|---:|\n" +
                "| Housing | 4,000 EGP | 41% |\n" +
                "| Food | 2,100 EGP | 22% |\n" +
                "| Transport | 900 EGP | 9% |",
        )

        val table = blocks.single() as MdBlock.Table
        assertEquals(listOf("Category", "Amount", "Share"), table.header)
        assertEquals(listOf("Housing", "4,000 EGP", "41%"), table.rows[0])
        assertEquals(listOf("Food", "2,100 EGP", "22%"), table.rows[1])
        assertEquals(listOf("Transport", "900 EGP", "9%"), table.rows[2])
        assertEquals(TableColumnAlignment.RIGHT, table.alignments[2])
    }

    @Test
    fun table_crlfLineEndings_parsesIntoTable() {
        val blocks = parseMarkdown(
            "| Category | Amount |\r\n" +
                "|---|---|\r\n" +
                "| Housing | 4,000 EGP |\r\n" +
                "| Food | 2,100 EGP |",
        )

        val table = blocks.single() as MdBlock.Table
        assertEquals(listOf("Category", "Amount"), table.header)
        assertEquals(
            listOf(listOf("Housing", "4,000 EGP"), listOf("Food", "2,100 EGP")),
            table.rows,
        )
    }

    @Test
    fun table_multipleTables_parseInOrder() {
        val blocks = parseMarkdown(
            "| Category | Amount |\n" +
                "|---|---:|\n" +
                "| Housing | 4,000 EGP |\n" +
                "\n" +
                "Income and expenses:\n" +
                "\n" +
                "| Group | Amount |\n" +
                "|---|---:|\n" +
                "| Income | 12,000 EGP |\n" +
                "| Expenses | 8,400 EGP |",
        )

        assertEquals(3, blocks.size)
        assertEquals(
            MdBlock.Table(
                listOf("Category", "Amount"),
                listOf(listOf("Housing", "4,000 EGP")),
                listOf(TableColumnAlignment.LEFT, TableColumnAlignment.RIGHT),
            ),
            blocks[0],
        )
        assertEquals(MdBlock.Paragraph("Income and expenses:"), blocks[1])
        assertEquals(
            MdBlock.Table(
                listOf("Group", "Amount"),
                listOf(listOf("Income", "12,000 EGP"), listOf("Expenses", "8,400 EGP")),
                listOf(TableColumnAlignment.LEFT, TableColumnAlignment.RIGHT),
            ),
            blocks[2],
        )
    }

    @Test
    fun tableFollowedByInsightCard_proseAndCardsStaySeparateElements() {
        val raw =
            "| Category | Amount |\n" +
                "|---|---:|\n" +
                "| Housing | 4,000 EGP |\n" +
                "\n" +
                "INSIGHT|INFO|Top category|Housing led spending."

        val split = com.personal.balance.domain.finance.TransactionInsightParser.split(raw)

        // Prose keeps the table (rendered inside the assistant bubble)…
        val proseBlocks = parseMarkdown(split.prose)
        assertEquals(1, proseBlocks.size)
        assertTrue(proseBlocks.single() is MdBlock.Table)
        // …and the INSIGHT line is materialised as a separate card element.
        assertEquals(1, split.insights.size)
        assertEquals("Top category", split.insights[0].title)
    }
}