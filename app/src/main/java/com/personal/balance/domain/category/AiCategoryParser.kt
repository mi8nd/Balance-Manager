package com.personal.balance.domain.category

import com.personal.balance.model.TransactionType

/**
 * Parses the AI's categorization reply (produced over the existing text/plain
 * Firebase AI Logic chain) and validates it against the authoritative
 * [TransactionCategorySet].
 *
 * Accepted reply shapes, in order of preference:
 *  1. A ```json ... ``` fenced JSON object.
 *  2. A bare JSON object containing a `"category"` member
 *     (`{"category": "Food"}` — quoted or plain value).
 *  3. A plain single-line category name (`Food`).
 *
 * Everything is handled defensively: surrounding prose is ignored, the
 * candidate is normalized (whitespace/case/decoration) and MUST match one of
 * the allowed categories for the transaction type. A reply that is unknown,
 * malformed, or empty yields `null` — the caller falls back deterministically
 * rather than storing an arbitrary category name.
 */
object AiCategoryParser {

    private val FENCE = Regex("```(?:json)?\\s*([\\s\\S]*?)```")
    private val CATEGORY_KEY = "\"category\""

    fun parse(reply: String, type: TransactionType): String? {
        if (reply.isBlank()) return null

        // 1 & 2: JSON object (fenced or bare), then the "category" member.
        extractJsonObject(reply)?.let { json ->
            extractCategoryValue(json)?.let { candidate ->
                TransactionCategorySet.canonical(candidate, type)?.let { return it }
            }
        }

        // 3: the whole trimmed reply might itself be `Food` (possibly with space
        // padding). Only a clean single-line value is accepted — anything that
        // looks like a (malformed) JSON object is rejected so `{"Food"}` or a
        // broken fenced block can never leak through after a failed JSON parse.
        val whole = reply.trim()
        if (!whole.contains('\n') && whole.length <= 48 && !whole.contains('{') && !whole.contains('}')) {
            TransactionCategorySet.canonical(whole, type)?.let { return it }
        }
        return null
    }

    private fun extractJsonObject(raw: String): String? {
        var text = raw.trim()
        FENCE.find(text)?.let { text = it.groupValues[1].trim() }
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start == -1 || end == -1 || end <= start) return null
        return text.substring(start, end + 1)
    }

    private fun extractCategoryValue(json: String): String? {
        val idx = json.indexOf(CATEGORY_KEY)
        if (idx == -1) return null
        val colon = json.indexOf(':', idx)
        if (colon == -1) return null
        var i = colon + 1
        while (i < json.length && json[i].isWhitespace()) i++
        if (i >= json.length) return null
        if (json[i] == '"') {
            i++
            val value = StringBuilder()
            while (i < json.length && json[i] != '"') {
                value.append(json[i])
                i++
            }
            val text = value.toString()
            return text.ifBlank { null }
        }
        var j = i
        while (j < json.length && json[j] != ',' && json[j] != '}' && json[j] != '\n') j++
        val text = json.substring(i, j).trim()
        return text.ifBlank { null }
    }
}