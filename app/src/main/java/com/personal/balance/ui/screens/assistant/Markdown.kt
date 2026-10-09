package com.personal.balance.ui.screens.assistant

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp

/**
 * Lightweight Markdown rendering for assistant replies.
 *
 * Pure Kotlin, no dependency: the assistant's answers are intentionally concise
 * (bounded by the system prompt) and only use simple Markdown. Two pure stages,
 * each unit-testable without Compose:
 *
 *  - [parseMarkdown]: the line-based source is split into [MdBlock]s (headings,
 *    paragraphs, list items, fenced code blocks, horizontal rules, and
 *    `|`-delimited tables). Incomplete syntax (a `#` with no space, an unclosed
 *    fence, a not-yet-finished table delimiter) stays literal, so
 *    character-by-character reveal never shows broken mid-parse output for long.
 *  - [tokenizeInline]: a block's text is split into [InlineToken]s (`**bold**`,
 *    `*italic*`, `` `code` ``, `[text](url)`).
 *
 * [MarkdownBody] composes the [MdBlock]s into styled text. Tables render as a
 * native weighted-column layout (semi-bold header, M3 hairline divider, per-column
 * alignment) that wraps instead of scrolling horizontally.
 */

sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Paragraph(val text: String) : MdBlock
    data class ListItem(val bullet: String, val text: String) : MdBlock
    data class CodeBlock(val code: String) : MdBlock
    data object HorizontalRule : MdBlock
    data class Table(
        val header: List<String>,
        val rows: List<List<String>>,
        val alignments: List<TableColumnAlignment>,
    ) : MdBlock
}

/** Per-column alignment taken from a table delimiter row (e.g. `:---:` = center). */
enum class TableColumnAlignment { LEFT, CENTER, RIGHT }

sealed interface InlineToken {
    data class Text(val value: String) : InlineToken
    data class Bold(val content: List<InlineToken>) : InlineToken
    data class Italic(val content: List<InlineToken>) : InlineToken
    data class Code(val value: String) : InlineToken
    data class Link(val text: String, val url: String) : InlineToken
}

private val CODE_FENCE_OPEN = Regex("^\\s*```.*")
private val CODE_FENCE_CLOSE = Regex("^\\s*```\\s*$")
private val HORIZONTAL_RULE = Regex("^\\s*-{3,}\\s*$")
// A horizontal rule still being revealed: one or two dashes at a block boundary.
// Hidden in partial mode so the user never sees literal `-`/`--` before the `---`
// arrives and becomes a divider.
private val PENDING_HORIZONTAL_RULE = Regex("^\\s*-{1,2}\\s*$")

private fun isFenceOpen(line: String): Boolean = CODE_FENCE_OPEN.matches(line)

private fun isFenceClose(line: String): Boolean = CODE_FENCE_CLOSE.matches(line)

private fun headingLevel(line: String): Int? {
    val level = line.takeWhile { it == '#' }.length
    if (level == 0) return null
    if (level > 6) return null
    // A space (or end of line) must follow the hashes: `#` alone stays literal.
    if (line.length == level) return level
    return if (line[level] == ' ' || line[level] == '\t') level else null
}

/**
 * Splits a Markdown source string into blocks. Blank lines separate blocks;
 * consecutive non-blank, non-special lines merge into one paragraph. List items
 * are kept as separate blocks. A `|`-delimited header followed by a delimiter
 * row (`|---` / `|:---:|`) becomes a [MdBlock.Table]; following `|` rows stay
 * part of it until a blank or non-table line.
 */
fun parseMarkdown(source: String, partial: Boolean = false): List<MdBlock> {
    val blocks = mutableListOf<MdBlock>()
    val paragraphLines = mutableListOf<String>()
    val lines = source.lines()
    var fenceRemaining: MutableList<String>? = null
    var i = 0

    fun flushParagraph() {
        if (paragraphLines.isNotEmpty()) {
            blocks += MdBlock.Paragraph(paragraphLines.joinToString(" ").trim())
            paragraphLines.clear()
        }
    }

    while (i < lines.size) {
        val rawLine = lines[i]
        val line = rawLine.trimEnd()

        if (fenceRemaining != null) {
            if (isFenceClose(rawLine)) {
                blocks += MdBlock.CodeBlock(fenceRemaining.joinToString("\n"))
                fenceRemaining = null
            } else {
                fenceRemaining += rawLine
            }
            i++
            continue
        }

        if (isFenceOpen(rawLine)) {
            flushParagraph()
            fenceRemaining = mutableListOf()
            i++
            continue
        }

        if (line.isBlank()) {
            flushParagraph()
            i++
            continue
        }

        val level = headingLevel(line)
        if (level != null) {
            flushParagraph()
            blocks += MdBlock.Heading(level, line.substring(level).trim())
            i++
            continue
        }

        // Table: a `|`-delimited header line whose next non-blank line is a
        // delimiter row (only `-` and `:` between pipes).
        val nextRaw = lines.getOrNull(i + 1)
        if (line.contains('|') && nextRaw != null && nextRaw.trimEnd().isNotBlank() &&
            isTableDelimiter(nextRaw.trimEnd())
        ) {
            flushParagraph()
            val header = splitTableCells(line)
            val alignments = splitTableCells(nextRaw.trimEnd()).map { tableColumnAlignment(it) }
            val rows = mutableListOf<List<String>>()
            var j = i + 2
            while (j < lines.size) {
                val rowLine = lines[j].trimEnd()
                if (rowLine.isBlank()) break
                if (!rowLine.contains('|')) break
                if (isTableDelimiter(rowLine)) break
                rows += splitTableCells(rowLine)
                j++
            }
            blocks += MdBlock.Table(header, rows, alignments)
            i = j
            continue
        }

        if (line.matches(HORIZONTAL_RULE)) {
            flushParagraph()
            blocks += MdBlock.HorizontalRule
            i++
            continue
        }

        // In partial (reveal-in-progress) mode, an unfinished `-`/`--` at a block
        // boundary is an in-progress horizontal rule: hide it until the rule is
        // complete so literal dashes never flash during the typewriter reveal.
        if (partial && paragraphLines.isEmpty() && line.matches(PENDING_HORIZONTAL_RULE)) {
            i++
            continue
        }

        val bulletMatch = Regex("^[-*+]\\s+").find(line)
        if (bulletMatch != null) {
            flushParagraph()
            blocks += MdBlock.ListItem("•", line.drop(bulletMatch.value.length).trim())
            i++
            continue
        }

        val orderedMatch = Regex("^\\d{1,3}[.)]\\s+").find(line)
        if (orderedMatch != null) {
            flushParagraph()
            val number = orderedMatch.value.trim()
            val bullet = "${number.trimEnd('.').trimEnd(')')}."
            blocks += MdBlock.ListItem(bullet, line.drop(orderedMatch.value.length).trim())
            i++
            continue
        }

        paragraphLines += line
        i++
    }

    if (fenceRemaining != null) {
        // Unterminated fence: keep the captured lines literal.
        blocks += MdBlock.CodeBlock(fenceRemaining.joinToString("\n"))
    }
    flushParagraph()
    return blocks
}

/**
 * True when [line] looks like a table delimiter row: once a surrounding `|` and
 * surrounding whitespace are stripped, every `|`-separated segment contains only
 * dashes and optional alignment colons, with at least one dash.
 */
internal fun isTableDelimiter(line: String): Boolean {
    val t = line.trim()
    val core = t.removePrefix("|").removeSuffix("|").trim()
    if (core.isEmpty()) return false
    val segments = core.split("|").map { it.trim() }
    if (segments.isEmpty() || segments.any { it.isEmpty() }) return false
    return segments.all { segment ->
        segment.all { it == '-' || it == ':' } && segment.any { it == '-' }
    }
}

/** Splits a `|`-delimited row into trimmed cell values (leading/trailing `|` optional). */
internal fun splitTableCells(line: String): List<String> {
    val core = line.trim().removePrefix("|").removeSuffix("|")
    return core.split("|").map { it.trim() }
}

/** Maps one delimiter column (`:---`, `---:`, `:---:`, `---`) to a column alignment. */
internal fun tableColumnAlignment(segment: String): TableColumnAlignment {
    val s = segment.trim()
    val left = s.startsWith(':')
    val right = s.endsWith(':')
    return when {
        left && right -> TableColumnAlignment.CENTER
        right -> TableColumnAlignment.RIGHT
        left -> TableColumnAlignment.LEFT
        else -> TableColumnAlignment.LEFT
    }
}

/**
 * Splits inline text into styled tokens. Simple, non-recursive-by-marker scan
 * that handles `**bold**`, `*italic*`, `` `code` `` and `[text](url)`. Markers
 * that never close are emitted as plain text.
 *
 * When [partial] is true (used while a reply is still being revealed character
 * by character), incomplete markers are hidden instead of printed literally:
 * an unclosed `**`/`*`/`` ` `` styles the remaining text, unfinished links drop
 * their `[`, `]`, `(` and not-yet-written url, and a `*`/`**` surrounded by
 * whitespace (e.g. `2 * 3`) stays literal. For a well-formed input the two
 * modes produce identical output.
 */
fun tokenizeInline(source: String, partial: Boolean = false): List<InlineToken> {
    val tokens = mutableListOf<InlineToken>()
    var i = 0
    val plain = StringBuilder()

    fun flushPlain() {
        if (plain.isNotEmpty()) {
            tokens += InlineToken.Text(plain.toString())
            plain.clear()
        }
    }

    while (i < source.length) {
        val c = source[i]

        if (c == '`') {
            val end = source.indexOf('`', i + 1)
            if (end != -1) {
                flushPlain()
                tokens += InlineToken.Code(source.substring(i + 1, end))
                i = end + 1
                continue
            }
            if (partial) {
                // Unclosed backtick: keep the span styled, drop the marker.
                flushPlain()
                tokens += InlineToken.Code(source.substring(i + 1))
                i = source.length
                continue
            }
        }

        if (source.startsWith("**", i)) {
            if (partial && !starOpenAllowed(source, i, 2)) {
                plain.append("**")
                i += 2
                continue
            }
            val end = source.indexOf("**", i + 2)
            if (end != -1) {
                flushPlain()
                tokens += InlineToken.Bold(tokenizeInline(source.substring(i + 2, end), partial))
                i = end + 2
                continue
            }
            if (partial) {
                // Unclosed bold: style the rest, drop the markers.
                flushPlain()
                tokens += InlineToken.Bold(tokenizeInline(source.substring(i + 2), partial = true))
                i = source.length
                continue
            }
            // Unclosed bold: treat as literal text so the two stars never turn
            // into a stray italic opener.
            plain.append("**")
            i += 2
            continue
        }

        if (c == '*') {
            if (partial && !starOpenAllowed(source, i, 1)) {
                plain.append('*')
                i += 1
                continue
            }
            val end = source.indexOf('*', i + 1)
            if (end != -1) {
                flushPlain()
                tokens += InlineToken.Italic(tokenizeInline(source.substring(i + 1, end), partial))
                i = end + 1
                continue
            }
            if (partial) {
                // Unclosed italic: style the rest, drop the marker.
                flushPlain()
                tokens += InlineToken.Italic(tokenizeInline(source.substring(i + 1), partial = true))
                i = source.length
                continue
            }
        }

        if (c == '[') {
            val closeBracket = source.indexOf(']', i + 1)
            if (closeBracket != -1 && source.getOrNull(closeBracket + 1) == '(') {
                val endParen = source.indexOf(')', closeBracket + 2)
                if (endParen != -1) {
                    val text = source.substring(i + 1, closeBracket)
                    val url = source.substring(closeBracket + 2, endParen)
                    if (text.isNotBlank() && url.isNotBlank()) {
                        flushPlain()
                        tokens += InlineToken.Link(text, url)
                        i = endParen + 1
                        continue
                    }
                    if (partial) {
                        flushPlain()
                        tokens += InlineToken.Text(text)
                        i = endParen + 1
                        continue
                    }
                } else if (partial) {
                    // '(' reached but ')' not yet: hide brackets and the pending url.
                    flushPlain()
                    tokens += InlineToken.Text(source.substring(i + 1, closeBracket))
                    i = source.length
                    continue
                }
            } else if (partial) {
                if (closeBracket != -1) {
                    // ']' without '(' — hide both brackets, show the text.
                    flushPlain()
                    tokens += InlineToken.Text(source.substring(i + 1, closeBracket))
                    i = closeBracket + 1
                    continue
                }
                // No ']' yet: hide the '[' and keep scanning; the text shows plainly.
                i += 1
                continue
            }
        }

        plain.append(c)
        i++
    }
    flushPlain()
    return tokens
}

/** A `*`/`**` opener needs a blank/whitespace predecessor and a non-whitespace follower. */
private fun starOpenAllowed(source: String, i: Int, starCount: Int): Boolean {
    if (i > 0 && !source[i - 1].isWhitespace()) return false
    val next = source.getOrNull(i + starCount)
    return next != null && !next.isWhitespace()
}

/** Recursively appends a token list to [builder], applying [style] to each token. */
private fun appendInline(
    tokens: List<InlineToken>,
    builder: AnnotatedString.Builder,
    style: TextStyle,
    codeSpan: SpanStyle,
    linkSpan: SpanStyle,
) {
    for (token in tokens) {
        when (token) {
            is InlineToken.Text -> builder.append(token.value)

            is InlineToken.Code -> {
                val start = builder.length
                builder.append(token.value)
                builder.addStyle(codeSpan, start, builder.length)
            }

            is InlineToken.Bold -> {
                val start = builder.length
                appendInline(token.content, builder, style.merge(
                    TextStyle(fontWeight = FontWeight.Bold),
                ), codeSpan, linkSpan)
                builder.addStyle(SpanStyle(fontWeight = FontWeight.Bold), start, builder.length)
            }

            is InlineToken.Italic -> {
                val start = builder.length
                appendInline(token.content, builder, style.merge(
                    TextStyle(fontStyle = FontStyle.Italic),
                ), codeSpan, linkSpan)
                builder.addStyle(SpanStyle(fontStyle = FontStyle.Italic), start, builder.length)
            }

            is InlineToken.Link -> {
                val start = builder.length
                builder.append(token.text)
                builder.addStyle(linkSpan, start, builder.length)
            }
        }
    }
}

internal fun inlineAnnotated(
    source: String,
    style: TextStyle,
    codeSpan: SpanStyle,
    linkSpan: SpanStyle,
    partial: Boolean = false,
): AnnotatedString = buildAnnotatedString {
    appendInline(tokenizeInline(source, partial), this, style, codeSpan, linkSpan)
}

@Composable
fun MarkdownBody(
    markdown: String,
    modifier: Modifier = Modifier,
    baseStyle: TextStyle = MaterialTheme.typography.bodyMedium,
    partial: Boolean = false,
) {
    val codeBackground = MaterialTheme.colorScheme.surfaceVariant
    val codeForeground = MaterialTheme.colorScheme.onSurfaceVariant
    val linkColor = MaterialTheme.colorScheme.primary
    val codeSpan = SpanStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = baseStyle.fontSize * 0.92f,
        background = codeBackground,
        color = codeForeground,
    )
    val linkSpan = SpanStyle(
        color = linkColor,
        textDecoration = TextDecoration.Underline,
    )
    val headingStyles = mapOf(
        1 to MaterialTheme.typography.titleLarge,
        2 to MaterialTheme.typography.titleMedium,
        3 to MaterialTheme.typography.titleSmall,
    )

    // Parsing is deterministic and expensive enough to caches: a completed message
    // parses exactly once for as long as (markdown, partial) is unchanged, instead
    // of every time the item (or the whole screen) recomposes.
    val parsed = remember(markdown, partial) { parseMarkdown(markdown, partial) }
    androidx.compose.foundation.layout.Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (block in parsed) {
            when (block) {
                is MdBlock.Heading -> {
                    val style = headingStyles[block.level]
                        ?: MaterialTheme.typography.labelLarge
                    Text(
                        text = rememberInline(block.text, style, codeSpan, linkSpan, partial),
                        style = style,
                    )
                }

                is MdBlock.Paragraph -> {
                    Text(
                        text = rememberInline(block.text, baseStyle, codeSpan, linkSpan, partial),
                        style = baseStyle,
                    )
                }

                is MdBlock.ListItem -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = block.bullet,
                            style = baseStyle,
                            modifier = Modifier.padding(start = 2.dp),
                        )
                        Text(
                            text = rememberInline(block.text, baseStyle, codeSpan, linkSpan, partial),
                            style = baseStyle,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                is MdBlock.CodeBlock -> {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = codeBackground,
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Text(
                            text = block.code,
                            style = baseStyle.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = baseStyle.fontSize * 0.92f,
                            ),
                            color = codeForeground,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                }

                is MdBlock.HorizontalRule -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp)
                            .height(1.dp)
                            .background(MaterialTheme.colorScheme.outlineVariant),
                    )
                }

                is MdBlock.Table -> {
                    MarkdownTable(
                        block = block,
                        baseStyle = baseStyle,
                        codeSpan = codeSpan,
                        linkSpan = linkSpan,
                        partial = partial,
                    )
                }
            }
        }
    }
}

/**
 * Memoizes the inline-token → [AnnotatedString] conversion for one source string.
 * Keying on the rendered style/code/link spans keeps the theme changeable while
 * making repeated recomposition of a completed message nearly free (tokens are
 * computed once per distinct source).
 */
@Composable
private fun rememberInline(
    source: String,
    style: TextStyle,
    codeSpan: SpanStyle,
    linkSpan: SpanStyle,
    partial: Boolean,
): AnnotatedString = remember(source, style, codeSpan, linkSpan, partial) {
    inlineAnnotated(source, style, codeSpan, linkSpan, partial)
}

/**
 * Renders a [MdBlock.Table] as a lightweight native Compose table: one weighted
 * column per header cell, a semi-bold header row, a thin M3 divider beneath the
 * header, then body rows with per-column [TableColumnAlignment]. Cells wrap
 * instead of scrolling horizontally, so ordinary financial tables fit any phone
 * width; rows shorter than the header are padded and longer ones trimmed.
 */
@Composable
private fun MarkdownTable(
    block: MdBlock.Table,
    baseStyle: TextStyle,
    codeSpan: SpanStyle,
    linkSpan: SpanStyle,
    partial: Boolean,
) {
    val columnCount = block.header.size.coerceAtLeast(1)
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 2.dp),
        ) {
            for (column in 0 until columnCount) {
                val cell = block.header.getOrNull(column).orEmpty()
                val textAlign = block.alignments.getOrNull(column)?.toTextAlign() ?: TextAlign.Start
                Text(
                    text = rememberInline(cell, baseStyle, codeSpan, linkSpan, partial),
                    style = baseStyle,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = textAlign,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                )
            }
        }
        HorizontalDivider(
            modifier = Modifier.fillMaxWidth(),
            thickness = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant,
        )
        for (row in block.rows) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp),
            ) {
                for (column in 0 until columnCount) {
                    val cell = row.getOrNull(column).orEmpty()
                    val textAlign = block.alignments.getOrNull(column)?.toTextAlign() ?: TextAlign.Start
                    Text(
                        text = rememberInline(cell, baseStyle, codeSpan, linkSpan, partial),
                        style = baseStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = textAlign,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                    )
                }
            }
        }
    }
}

private fun TableColumnAlignment.toTextAlign(): TextAlign = when (this) {
    TableColumnAlignment.LEFT -> TextAlign.Start
    TableColumnAlignment.CENTER -> TextAlign.Center
    TableColumnAlignment.RIGHT -> TextAlign.End
}