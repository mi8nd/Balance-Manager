package com.personal.balance.domain.ai.orchestration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationalTriageTest {

    private fun reply(question: String): String? = ConversationalTriage.localReply(question)

    @Test
    fun blankInput_returnsNull() {
        assertNull(reply(""))
        assertNull(reply("   "))
        assertNull(reply("\n\n"))
    }

    @Test
    fun greeting_returnsLocalReply() {
        assertNotNull(reply("Hello!"))
        assertNotNull(reply("Hi"))
        assertNotNull(reply("hey"))
        assertNotNull(reply("Good morning"))
        assertNotNull(reply("Good afternoon"))
        assertNotNull(reply("Good evening"))
    }

    @Test
    fun shortTailGreeting_returnsLocalReply() {
        assertNotNull(reply("Hi there"))
        assertNotNull(reply("Hello, how are you?"))
    }

    @Test
    fun longTailGreeting_routedToAI() {
        assertNull(reply("Hello, how much did I spend this month?"))
    }

    @Test
    fun thanks_returnsLocalReply() {
        assertNotNull(reply("Thanks"))
        assertNotNull(reply("Thank you"))
        assertNotNull(reply("Thank you very much!"))
        assertNotNull(reply("Thanks a lot"))
    }

    @Test
    fun farewell_returnsLocalReply() {
        assertNotNull(reply("Bye"))
        assertNotNull(reply("Goodbye"))
        assertNotNull(reply("See you later"))
        assertNotNull(reply("Good night"))
    }

    @Test
    fun identity_returnsLocalReply() {
        assertNotNull(reply("Who are you?"))
        assertNotNull(reply("What are you"))
        assertNotNull(reply("What is your name"))
    }

    @Test
    fun help_returnsLocalReply() {
        assertNotNull(reply("Help"))
        assertNotNull(reply("What can you do?"))
        assertNotNull(reply("How do I use this app?"))
        assertNotNull(reply("What should I ask?"))
    }

    @Test
    fun financialCue_returnsNullEvenWhenGreeting() {
        assertNull(reply("Hello, how much did I spend this month?"))
        assertNull(reply("Hi, what about rent?"))
    }

    @Test
    fun financialCue_returnsNullEvenWhenHelp() {
        assertNull(reply("Help me with my spending"))
        assertNull(reply("Help me with rent"))
    }

    @Test
    fun financialQuestion_returnsNull() {
        assertNull(reply("How much did I spend?"))
        assertNull(reply("What is my current balance?"))
        assertNull(reply("Which category did I spend the most on?"))
        assertNull(reply("How does this month compare with last month?"))
    }

    @Test
    fun generalNonFinancial_returnsNull() {
        assertNull(reply("Explain compound interest."))
        assertNull(reply("What is the best way to save money?"))
        assertNull(reply("How do I create a budget?"))
    }

    @Test
    fun financialCueInArabic_returnsNull() {
        assertNull(reply("رصيدي كام"))
    }

    @Test
    fun greetingReply_nonEmpty() {
        val r = reply("Hi")
        assertNotNull(r)
        assertTrue(r!!.length > 10)
    }
}