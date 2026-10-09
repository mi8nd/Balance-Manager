package com.personal.balance.domain.ai.orchestration

/**
 * Tolerant parser for the planner's structured output. Pure Kotlin, no JSON
 * library, so it runs anywhere (including JVM unit tests).
 *
 * Accepted shapes (in order of preference):
 *  1. A ```json ... ``` fenced block.
 *  2. A bare JSON object between `{` and `}`.
 *  3. Line-based fallback: `intent: name`, `financial_relevance: true`,
 *     `data_needs: a, b`, `capabilities: ...`, `confidence: high` (also with
 *     `=` as separator).
 *
 * Handling is deliberately defensive: keys are matched by name with tolerant
 * spellings (`data_needs`/`dataNeeds`, `financial_relevance`/`financialRelevance`,
 * `needs_conversation_context`/`needsConversationContext`), values are
 * case-insensitive, unknown intent/confidence/value names are ignored and
 * unknown capabilities/data needs are dropped, duplicates collapse, and a
 * completely unrecognizable reply yields `null`. `null` is NOT a failure: the
 * planner turns it into an empty draft and [PlanSanitizer] fills it from
 * deterministic question cues (so a clearly financial question whose model
 * output is garbage still lands on the financial capabilities).
 */
object AiRequestPlanParser {

    private val INTENT_KEYS = setOf("intent")
    private val RELEVANCE_KEYS = setOf("financial_relevance", "financialRelevance")
    private val NEEDS_CONTEXT_KEYS = setOf("needs_conversation_context", "needsConversationContext")
    private val DATA_NEED_KEYS = setOf("data_needs", "dataNeeds")
    private val CAPABILITY_KEYS = setOf("capabilities")
    private val CONFIDENCE_KEYS = setOf("confidence")

    private val FENCE = Regex("```(?:json)?\\s*([\\s\\S]*?)```")

    fun parse(raw: String): AiRequestPlanDraft? {
        if (raw.isBlank()) return null
        val source = extractJsonObject(raw) ?: raw

        val intent = extractString(source, INTENT_KEYS)?.let { AiIntent.from(it) }
            ?: extractStringFromLines(raw, INTENT_KEYS)?.let { AiIntent.from(it) }

        val relevance = extractBool(source, RELEVANCE_KEYS)
            ?: extractBoolFromLines(raw, RELEVANCE_KEYS)

        val needsContext = extractBool(source, NEEDS_CONTEXT_KEYS)
            ?: extractBoolFromLines(raw, NEEDS_CONTEXT_KEYS)

        val confidence = extractString(source, CONFIDENCE_KEYS)?.let { AiRoutingConfidence.from(it) }
            ?: extractStringFromLines(raw, CONFIDENCE_KEYS)?.let { AiRoutingConfidence.from(it) }

        val capabilities = (
            extractList(source, CAPABILITY_KEYS) + extractListFromLines(raw, CAPABILITY_KEYS)
            ).mapNotNull { AiCapability.from(it) }.toSet()

        val dataNeeds = (
            extractList(source, DATA_NEED_KEYS) + extractListFromLines(raw, DATA_NEED_KEYS)
            ).mapNotNull { AiDataNeed.from(it) }.toSet()

        // Nothing recognizable at all -> the caller runs the deterministic fallback.
        if (intent == null && relevance == null && needsContext == null &&
            confidence == null && capabilities.isEmpty() && dataNeeds.isEmpty()
        ) {
            return null
        }

        return AiRequestPlanDraft(
            intent = intent,
            financialRelevance = relevance,
            dataNeeds = dataNeeds,
            capabilities = capabilities,
            needsConversationContext = needsContext ?: false,
            confidence = confidence,
        )
    }

    private fun extractJsonObject(raw: String): String? {
        var text = raw.trim()
        FENCE.find(text)?.let { text = it.groupValues[1].trim() }
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start == -1 || end == -1 || end <= start) return null
        return text.substring(start, end + 1)
    }

    /** Index of `"key"` followed (after whitespace) by `:`. */
    private fun indexOfKey(json: String, key: String): Int? {
        val target = "\"$key\""
        var from = 0
        while (from < json.length) {
            val idx = json.indexOf(target, from)
            if (idx == -1) return null
            var i = idx + target.length
            while (i < json.length && json[i].isWhitespace()) i++
            if (i < json.length && json[i] == ':') return idx
            from = idx + 1
        }
        return null
    }

    private fun extractList(json: String, keys: Set<String>): List<String> {
        val out = mutableListOf<String>()
        for (key in keys) {
            val idx = indexOfKey(json, key) ?: continue
            val colon = json.indexOf(':', idx)
            if (colon == -1) continue
            val open = json.indexOf('[', colon)
            if (open == -1 || open - colon > 64) continue
            val close = json.indexOf(']', open)
            if (close == -1) continue
            out += extractStringTokens(json.substring(open + 1, close))
        }
        return out
    }

    private fun extractStringTokens(segment: String): List<String> {
        val tokens = mutableListOf<String>()
        val quoted = Regex("\"((?:[^\"\\\\]|\\\\.)*)\"")
        for (match in quoted.findAll(segment)) tokens.add(match.groupValues[1])
        if (tokens.isEmpty()) {
            segment.split(',', '|')
                .map { it.trim().trim('"').trim() }
                .filter { it.isNotBlank() }
                .forEach { tokens.add(it) }
        }
        return tokens
    }

    private fun extractString(json: String, keys: Set<String>): String? {
        for (key in keys) {
            val idx = indexOfKey(json, key) ?: continue
            val colon = json.indexOf(':', idx)
            if (colon == -1) continue
            var i = colon + 1
            while (i < json.length && json[i].isWhitespace()) i++
            if (i >= json.length) continue
            if (json[i] == '"') {
                val value = StringBuilder()
                i++
                while (i < json.length) {
                    when (val c = json[i]) {
                        '\\' -> {
                            if (i + 1 < json.length) value.append(json[i + 1])
                            i += 2
                        }
                        '"' -> break
                        else -> { value.append(c); i++ }
                    }
                }
                val text = value.toString()
                if (text.isNotBlank()) return text
            } else {
                var j = i
                while (j < json.length && json[j] !in ",}\"'") j++
                val text = json.substring(i, j).trim()
                if (text.isNotBlank()) return text
            }
        }
        return null
    }

    /**
     * Boolean values may appear unquoted (`true`), quoted (`"true"`), or as 1/0.
     * A key that does not hold a recognizable boolean is skipped.
     */
    private fun extractBool(json: String, keys: Set<String>): Boolean? {
        for (key in keys) {
            val idx = indexOfKey(json, key) ?: continue
            val colon = json.indexOf(':', idx)
            if (colon == -1) continue
            var i = colon + 1
            while (i < json.length && json[i].isWhitespace()) i++
            if (i >= json.length) continue
            val raw = if (json[i] == '"') {
                i++
                val sb = StringBuilder()
                while (i < json.length && json[i] != '"') { sb.append(json[i]); i++ }
                sb.toString()
            } else {
                var j = i
                while (j < json.length && json[j] !in ",}'" && json[j] != '\n') j++
                json.substring(i, j)
            }
            // Unrecognizable token -> keep scanning for another key.
            parseBool(raw)?.let { return it }
        }
        return null
    }

    private fun parseBool(value: String): Boolean? = when (value.trim().lowercase()) {
        "true", "1", "yes" -> true
        "false", "0", "no" -> false
        else -> null
    }

    private fun extractListFromLines(raw: String, keys: Set<String>): List<String> {
        val out = mutableListOf<String>()
        for (line in raw.lineSequence()) {
            for (key in keys) {
                listOf("$key:", "$key=").forEach { marker ->
                    val at = line.indexOf(marker)
                    if (at != -1) {
                        line.substring(at + marker.length).split(',', '|')
                            .map { it.trim().trim('"').trimEnd(']', '}', ']').trim() }
                            .filter { it.isNotBlank() }
                            .forEach { out.add(it) }
                    }
                }
            }
        }
        return out
    }

    private fun extractStringFromLines(raw: String, keys: Set<String>): String? {
        for (line in raw.lineSequence()) {
            for (key in keys) {
                listOf("$key:", "$key=").forEach { marker ->
                    val at = line.indexOf(marker)
                    if (at != -1) {
                        val value = line.substring(at + marker.length).trim().trim('"', '}').trim()
                        if (value.isNotBlank()) return value
                    }
                }
            }
        }
        return null
    }

    private fun extractBoolFromLines(raw: String, keys: Set<String>): Boolean? {
        for (line in raw.lineSequence()) {
            for (key in keys) {
                listOf("$key:", "$key=").forEach { marker ->
                    val at = line.indexOf(marker)
                    if (at != -1) {
                        val value = line.substring(at + marker.length).trim().trim('"', '}').trim()
                        parseBool(value)?.let { return it }
                    }
                }
            }
        }
        return null
    }
}