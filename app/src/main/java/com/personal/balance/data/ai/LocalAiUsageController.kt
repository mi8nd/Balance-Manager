package com.personal.balance.data.ai

import com.personal.balance.domain.ai.AiUsageController
import com.personal.balance.domain.ai.DefaultAiConfig
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * In-memory rolling-window budget that keeps AI calls deliberate and bounded.
 * This is a device-local guard; it is NOT Google's server-side quota and is
 * reset when the process restarts. [now] is injectable for deterministic tests.
 */
class LocalAiUsageController(
    private val maxCallsPerWindow: Int = DefaultAiConfig.instance.maxCallsPerWindow,
    private val windowMillis: Long = DefaultAiConfig.instance.windowMillis,
    private val now: () -> Long = { System.currentTimeMillis() },
) : AiUsageController {

    private val mutex = Mutex()
    private val callTimestamps = ArrayDeque<Long>()

    override suspend fun isCallAllowed(): Boolean = mutex.withLock {
        val cutoff = now() - windowMillis
        while (callTimestamps.firstOrNull()?.let { it < cutoff } == true) {
            callTimestamps.removeFirst()
        }
        callTimestamps.size < maxCallsPerWindow
    }

    override suspend fun recordCall() {
        mutex.withLock {
            callTimestamps.addLast(now())
        }
    }
}