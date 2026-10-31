package com.personal.balance.domain.ai.orchestration

import com.personal.balance.domain.ai.AiScope
import java.util.Locale

/**
 * Deterministic scope gate that runs BEFORE any AI call.
 *
 * The AI Financial Assistant only serves the user's own finances. When a
 * message is plainly outside that scope it answers with [AiScope.REPLY]
 * locally: zero AI calls, zero data loads, and the exact same wording every
 * time no matter what the model would have produced.
 *
 * Deliberately high precision, NOT broad coverage:
 *  - a message carrying any financial cue is never intercepted (it belongs to
 *    the planner / financial capabilities);
 *  - only a handful of unmistakable categories are recognized — a pure
 *    arithmetic expression the user typed, a coding request, a translation
 *    request, a creative-writing request, and a short list of trivia cue
 *    phrases.
 *
 * Everything else is left to [AiRequestPlanner] plus the prompt-level scope
 * rule ([com.personal.balance.domain.ai.DefaultAiConfig] /
 * [AiOrchestrator] composer instructions), so an unusual phrasing of an
 * in-scope question is never mistaken for an out-of-scope one.
 */
object OutOfScopeGuard {

    private val ARITHMETIC_SYMBOLS = setOf('+', '-', '*', '/', '×', '÷', '=', '%')

    private val ARITHMETIC_OPERATOR_WORDS = setOf(
        "plus", "minus", "times", "multiplied", "multiply", "divided", "divide",
        "add", "sum", "subtract", "over", "equals", "equal", "x",
        "زائد", "ناقص", "ضرب", "مضروب", "مقسوم", "طرح", "جمع", "يساوي",
    )

    private val NUMBER_WORDS = setOf(
        "zero", "one", "two", "three", "four", "five", "six", "seven", "eight",
        "nine", "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen",
        "sixteen", "seventeen", "eighteen", "nineteen", "twenty", "thirty",
        "forty", "fifty", "sixty", "seventy", "eighty", "ninety", "hundred",
        "thousand", "million", "billion",
    )

    private val ARITHMETIC_FILLER = setOf(
        "what", "whats", "what's", "s", "is", "are", "the", "result", "of",
        "answer", "please", "how", "much", "many", "calculate", "compute",
        "solve", "evaluate", "it", "that", "can", "you", "tell", "me", "do",
        "and", "to", "give", "for", "equals", "equal", "by",
    )

    private val CODE_CUES = listOf(
        "write code", "write a function", "write a program", "write a script",
        "write a query", "fix my code", "debug my", "stack trace", "stacktrace",
        "python", "javascript", "typescript", "kotlin", "java code", "c++",
        "sql", "html", "css", "regex", "compile", "api endpoint", "docker",
        "leetcode", "برمجة", "كود",
    )

    private val TRANSLATION_CUES = listOf("translate", "translation", "ترجم")

    private val CREATIVE_CUES = listOf(
        "tell me a joke", "a joke", "write a poem", "write a story", "write a song",
        "write a rap", "write an essay", "write a cover letter", "pickup line",
        "pick-up line", "write me a", "once upon a time",
    )

    private val TRIVIA_CUES = listOf(
        "capital of", "president of", "population of", "weather in", "weather like",
        "distance between", "how tall is", "how old is", "who won", "what year did",
        "history of", "who discovered", "who invented", "meaning of life",
        "tallest mountain", "boiling point of",
    )

    /**
     * @return [AiScope.REPLY] when [question] is unambiguously outside the
     * assistant's scope, or null when it must keep its normal route.
     */
    fun localReply(question: String): String? {
        if (question.isBlank()) return null
        val text = question.lowercase(Locale.US)
        // Any financial cue means the question may be about the user's money.
        if (ConversationalTriage.hasFinancialCue(text)) return null
        return if (isOutOfScope(text)) AiScope.REPLY else null
    }

    /** True when [question] matches one of the narrow, unmistakable off-topic categories. */
    fun isOutOfScope(question: String): Boolean {
        val text = question.lowercase(Locale.US)
        if (ConversationalTriage.hasFinancialCue(text)) return false
        return looksLikeArithmetic(text) ||
            containsAny(text, CODE_CUES) ||
            containsAny(text, TRANSLATION_CUES) ||
            containsAny(text, CREATIVE_CUES) ||
            containsAny(text, TRIVIA_CUES)
    }

    private fun containsAny(text: String, cues: List<String>): Boolean = cues.any { text.contains(it) }

    /**
     * True for an expression that is nothing but numbers, arithmetic operators
     * and filler words — "5 plus 8", "5+8", "what is 12 times 4", "five plus
     * eight". A single numeric token, or any unknown word, means "not
     * arithmetic" so the message keeps its normal route.
     */
    private fun looksLikeArithmetic(text: String): Boolean {
        val spaced = buildString {
            text.forEach { char ->
                if (char in ARITHMETIC_SYMBOLS) {
                    append(' ')
                    append(char)
                    append(' ')
                } else {
                    append(char)
                }
            }
        }
        val tokens = spaced
            .split(' ', '\n', '\t', '\r')
            .map { it.trim(',', '.', '!', '?', ':', ';', '"', '\'', '(', ')') }
            .filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return false

        var numbers = 0
        var operators = 0
        for (token in tokens) {
            when {
                isNumberToken(token) -> numbers++
                token in ARITHMETIC_OPERATOR_WORDS -> operators++
                isOperatorSymbol(token) -> operators++
                token in ARITHMETIC_FILLER -> Unit
                else -> return false
            }
        }
        return numbers >= 2 && operators >= 1
    }

    private fun isNumberToken(token: String): Boolean =
        (token.any { it.isDigit() } && token.all { it.isDigit() || it == ',' || it == '.' }) ||
            token in NUMBER_WORDS

    private fun isOperatorSymbol(token: String): Boolean =
        token.length == 1 && token[0] in ARITHMETIC_SYMBOLS
}
