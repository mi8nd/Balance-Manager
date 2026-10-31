package com.personal.balance.domain.ai.orchestration

import com.personal.balance.domain.ai.AiScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The deterministic scope gate: plainly off-topic messages (the "5 plus 8"
 * complaint) are declined locally with the app's single refusal sentence,
 * while every financially meaningful message keeps its normal route.
 */
class OutOfScopeGuardTest {

    private fun reply(question: String): String? = OutOfScopeGuard.localReply(question)

    @Test
    fun blankInput_returnsNull() {
        assertNull(reply(""))
        assertNull(reply("   "))
    }

    @Test
    fun bareArithmetic_isDeclinedLocally() {
        assertEquals(AiScope.REPLY, reply("5 plus 8"))
        assertEquals(AiScope.REPLY, reply("5+8"))
        assertEquals(AiScope.REPLY, reply("What is 5 + 8?"))
        assertEquals(AiScope.REPLY, reply("what is 12 times 4"))
        assertEquals(AiScope.REPLY, reply("100 divided by 7"))
        assertEquals(AiScope.REPLY, reply("10 - 3 = ?"))
        assertEquals(AiScope.REPLY, reply("five plus eight"))
        assertEquals(AiScope.REPLY, reply("2 * 3 * 4"))
    }

    @Test
    fun arithmeticWithArabicIndicDigits_isDeclined() {
        assertEquals(AiScope.REPLY, reply("٥ زائد ٨"))
    }

    @Test
    fun codingTranslationAndTrivia_areDeclinedLocally() {
        assertEquals(AiScope.REPLY, reply("Write a Python function that reverses a list"))
        assertEquals(AiScope.REPLY, reply("Translate good morning to French"))
        assertEquals(AiScope.REPLY, reply("Tell me a joke"))
        assertEquals(AiScope.REPLY, reply("What is the capital of France?"))
        assertEquals(AiScope.REPLY, reply("How tall is the Eiffel Tower?"))
    }

    @Test
    fun financialQuestions_areNeverDeclined() {
        assertNull(reply("How much did I spend this month?"))
        assertNull(reply("What is my current balance?"))
        assertNull(reply("Which category did I spend the most on?"))
        assertNull(reply("How does this month compare with last month?"))
        assertNull(reply("What patterns stand out in my transactions?"))
        assertNull(reply("How is my savings goal going?"))
        assertNull(reply("How do I create a budget?"))
        assertNull(reply("Explain compound interest."))
        assertNull(reply("Add 50 to my food budget"))
    }

    @Test
    fun conversationalIntent_isLeftToTriage() {
        assertNull(reply("Hello!"))
        assertNull(reply("Thanks"))
        assertNull(reply("Who are you?"))
    }

    @Test
    fun singleNumberOrUnknownWords_areNotArithmetic() {
        assertNull(reply("5"))
        assertNull(reply("I have 3 cats"))
        assertNull(reply("Is 5 a lot?"))
        assertFalse(OutOfScopeGuard.isOutOfScope("Is 5 a lot?"))
    }

    @Test
    fun outOfScopeReply_saysWhatItIsNotDesignedFor() {
        assertTrue(AiScope.REPLY.startsWith("I'm not designed for that."))
        assertTrue(AiScope.REPLY.contains("Balance financial assistant"))
        assertTrue(AiScope.REPLY.contains("only help with your own money"))
    }
}
