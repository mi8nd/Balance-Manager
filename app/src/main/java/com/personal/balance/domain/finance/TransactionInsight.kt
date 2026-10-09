package com.personal.balance.domain.finance

/**
 * One structured insight produced by Transaction Intelligence.
 *
 * The model summarizes the deterministic facts into a list of these. [Importance]
 * lets the UI weight visual emphasis; [title] is a short headline and [explanation]
 * carries the supporting facts. Never carries raw transaction descriptions.
 */
data class TransactionInsight(
    val title: String,
    val explanation: String,
    val importance: Importance = Importance.MEDIUM,
)

enum class Importance {
    HIGH,
    MEDIUM,
    LOW,
    INFO,
    ;

    companion object {
        fun from(label: String): Importance = entries.firstOrNull {
            it.name.equals(label.trim(), ignoreCase = true)
        } ?: MEDIUM
    }
}

/**
 * Parses the model's reply into [TransactionInsight]s.
 *
 * The model is instructed to emit one line per insight, each formatted as:
 * `INSIGHT|<LEVEL>|Title|Explanation` where LEVEL is HIGH/MEDIUM/LOW/INFO.
 * Lines that don't follow the format are ignored, so a partial (typewriter)
 * reveal or a brief preamble never breaks parsing. If no structured line is
 * recognized, the whole reply is wrapped in one generic insight so the user
 * always sees the model's output.
 */
object TransactionInsightParser {

    // Spaces around the separators are tolerated (models often add them for
    // readability): `INSIGHT | HIGH | Title | Explanation`. The format itself
    // is one insight per line, so a wrapped explanation keeps only its first
    // line; the second line is simply not an insight line.
    private val INSIGHT_LINE = Regex("^INSIGHT\\s*\\|\\s*([A-Za-z]+)\\s*\\|\\s*(.*)$")

    fun parse(reply: String): List<TransactionInsight> {
        val insights = reply.lineSequence()
            .map { it.trim() }
            .mapNotNull { parseLine(it) }
            .toList()
        if (insights.isNotEmpty()) return insights

        // The model attempted the structured format but none of it parsed
        // cleanly: return nothing rather than dumping raw markup into a
        // generic insight.
        val attemptedStructured = reply.lineSequence().any { it.trim().startsWith("INSIGHT") }
        if (attemptedStructured) return emptyList()

        val cleaned = reply.trim()
        if (cleaned.isBlank()) return emptyList()
        return listOf(
            TransactionInsight(
                title = "Financial summary",
                explanation = cleaned,
                importance = Importance.INFO,
            ),
        )
    }

    private fun parseLine(line: String): TransactionInsight? {
        val match = INSIGHT_LINE.matchEntire(line) ?: return null
        val importance = Importance.from(match.groupValues[1])
        val body = match.groupValues[2]
        val parts = body.split("|", limit = 2)
        val title = parts.getOrElse(0) { "" }.trim()
        if (title.isEmpty()) return null
        val explanation = parts.getOrElse(1) { "" }.trim().ifEmpty { title }
        return TransactionInsight(
            title = title,
            explanation = explanation,
            importance = importance,
        )
    }

    data class SplitReply(val prose: String, val insights: List<TransactionInsight>)

    /**
     * Splits the model's unified reply into the prose shown in the assistant
     * bubble and the structured [TransactionInsight] cards rendered alongside it.
     *
     * Valid INSIGHT lines are stripped from the prose; everything else is
     * preserved verbatim (including empty lines) and then trimmed. When the reply
     * contains ONLY insight lines the prose is empty (the caller can add a brief
     * fallback). The word "INSIGHT" appears nowhere in the returned prose.
     */
    fun split(reply: String): SplitReply {
        val insights = mutableListOf<TransactionInsight>()
        val proseLines = mutableListOf<String>()
        for (raw in reply.lines()) {
            val parsed = parseLine(raw.trim())
            if (parsed != null) insights.add(parsed) else proseLines.add(raw)
        }
        return SplitReply(
            prose = proseLines.joinToString("\n").trim(),
            insights = insights,
        )
    }
}