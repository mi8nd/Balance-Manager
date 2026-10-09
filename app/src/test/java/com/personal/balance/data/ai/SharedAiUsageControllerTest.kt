package com.personal.balance.data.ai

import com.personal.balance.domain.ai.AiUsageController
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guarantees the app-wide AI usage budget stays a single shared controller: the
 * Financial Assistant, Transaction Intelligence, and the debug AI Smoke Test must
 * never create a second budget. One controller means one rolling window of
 * allowed calls per hour across every AI feature.
 */
class SharedAiUsageControllerTest {

    @Test
    fun instance_isNeverRecreated() {
        val first = SharedAiUsageController.instance
        val second = SharedAiUsageController.instance

        assertSame(first, second)
    }

    @Test
    fun instance_isTheSharedLocalUsageController() {
        assertTrue(SharedAiUsageController.instance is AiUsageController)
    }

    @Test
    fun instance_isFunctionalAtStartup() {
        runBlocking {
            // A fresh process has not recorded any calls, so the shared controller
            // starts permissive (the window only fills as AI features run).
            assertTrue(SharedAiUsageController.instance.isCallAllowed())
        }
    }
}