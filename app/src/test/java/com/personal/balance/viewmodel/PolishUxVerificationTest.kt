package com.personal.balance.viewmodel

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Static verification of the two UI polish invariants:
 *
 *  1. The AI typing indicator is driven by ONE shared animation with a STAGGERED
 *     phase per dot (a travelling wave), not three independent animations and not
 *     the previous folded-triangle structure that read as a fast flicker. Its
 *     visibility is bound to request state and exits smoothly.
 *
 *  2. The full-screen Dashboard loading state is reserved for a load with no usable
 *     content. A refresh over already-loaded content (normal mutations: transaction
 *     save, goal set/disable/acknowledge) must NOT re-enter the full-screen
 *     Loading state, so the Dashboard stays visible and only the affected data is
 *     updated.
 *
 * These tests read the repo's own source, mirroring the existing
 * AppCheckProviderVerificationTest / SaveFirstAsyncCategorizationVerificationTest
 * pattern. Runtime behavior is verified on-device in the polish QA phase.
 */
class PolishUxVerificationTest {

    private val repoRoot: File by lazy { locateRepoRoot() }

    private fun locateRepoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir")!!).absoluteFile
        while (dir != null) {
            if (File(dir, "app").isDirectory && File(dir, "app/build.gradle.kts").isFile) return dir
            if (File(dir, "build.gradle.kts").isFile && File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile
        }
        throw IllegalStateException("Cannot locate repo root from ${System.getProperty("user.dir")}")
    }

    private fun read(relativePath: String): String {
        val file = File(repoRoot, relativePath)
        assertTrue("missing expected file: $relativePath", file.isFile)
        return file.readText()
    }

    private val indicatorSource: String
        get() = read("app/src/main/java/com/personal/balance/ui/screens/assistant/AssistantMessage.kt")

    private val assistantScreenSource: String
        get() = read("app/src/main/java/com/personal/balance/ui/screens/FinancialAssistantScreen.kt")

    private val dashboardVmSource: String
        get() = read("app/src/main/java/com/personal/balance/viewmodel/DashboardViewModel.kt")

    // ---------------------------------------------------------------------
    // Task 1 - AI typing indicator
    // ---------------------------------------------------------------------

    @Test
    fun typingIndicatorUsesOneSharedAnimation() {
        val src = indicatorSource
        assertTrue(
            "The typing indicator must use a shared infinite transition",
            src.contains("rememberInfiniteTransition"),
        )
        // Exactly ONE animateFloat drives the ramp; the three dots read it at
        // staggered phases rather than each owning an animation.
        val animateFloatCount = Regex("transition\\.animateFloat").findAll(src).count()
        assertTrue(
            "Expected exactly one shared animateFloat ramp, found $animateFloatCount",
            animateFloatCount == 1,
        )
        assertFalse(
            "The indicator must not declare a separate animation per dot",
            src.contains("repeat(3) { index ->\n            val transition"),
        )
    }

    @Test
    fun typingIndicatorWaveIsStaggeredNotIndependent() {
        val src = indicatorSource
        assertTrue(
            "Each dot must read the shared ramp at a staggered phase offset",
            src.contains("TYPING_DOT_STAGGER_FRACTION"),
        )
        assertTrue(
            "The per-dot phase must be derived from the shared ramp at a staggered offset",
            src.contains("ramp - index * TYPING_DOT_STAGGER_FRACTION"),
        )
        // The staggered delay must be a real, non-trivial pause between dots.
        val declared = Regex("TYPING_DOT_STAGGER_MS\\s*=\\s*(\\d+)").find(src)
        assertTrue("A stagger constant must be declared", declared != null)
        val staggerMs = declared!!.groupValues[1].toInt()
        assertTrue(
            "Stagger must be a perceptible pause (>=150ms), was ${staggerMs}ms",
            staggerMs >= 150,
        )
        // Target feel: 660ms bounce (330 up + 330 down) with a 330ms stagger, i.e. the
        // stagger is exactly half the bounce so the dots hand off one at a time.
        val bounce = Regex("TYPING_DOT_BOUNCE_MS\\s*=\\s*(\\d+)").find(src)
        assertTrue("A bounce-duration constant must be declared", bounce != null)
        val bounceMs = bounce!!.groupValues[1].toInt()
        assertTrue(
            "Bounce must be a calm 660ms (330ms up + 330ms down), was ${bounceMs}ms",
            bounceMs == 660,
        )
        assertTrue(
            "Stagger must be exactly half the bounce (330ms) for a travelling wave, " +
                "stagger=${staggerMs}ms bounce=${bounceMs}ms",
            staggerMs * 2 == bounceMs,
        )
    }

    @Test
    fun typingIndicatorUsesSmoothContinuousEnvelope() {
        val src = indicatorSource
        assertTrue(
            "The dot envelope must use a smooth cosine curve (no triangle kink)",
            src.contains("cos("),
        )
        assertFalse(
            "The previous folded-triangle highlight must be gone",
            src.contains("1f - abs(2f *"),
        )
        assertFalse(
            "The per-dot abs() proximity switch must be gone",
            src.contains("abs(highlight"),
        )
        assertTrue(
            "The dot must move vertically (gentle rise), not only change alpha",
            src.contains("translationY"),
        )
    }

    @Test
    fun typingIndicatorIsSlowerThanTheOldFlicker() {
        val src = indicatorSource
        val declared = Regex("TYPING_WAVE_DURATION_MS\\s*=\\s*(\\d+)").find(src)
        assertTrue("A wave-duration constant must be declared", declared != null)
        val durationMs = declared!!.groupValues[1].toInt()
        assertTrue(
            "The wave must be noticeably slower than the previous 1200ms flicker, was ${durationMs}ms",
            durationMs > 1200,
        )
        assertFalse(
            "The old 1200ms tween must no longer be inline",
            src.contains("durationMillis = 1200"),
        )
    }

    @Test
    fun typingIndicatorVisibilityIsRequestStateDrivenWithSmoothExit() {
        val src = assistantScreenSource
        assertTrue(
            "Indicator visibility must be bound to the request running state",
            src.contains("visible = uiState.running"),
        )
        assertTrue(
            "The typing row must exit smoothly via AnimatedVisibility",
            src.contains("AnimatedVisibility"),
        )
        assertTrue("Exit must be a transition", src.contains("typingIndicatorExit"))
        assertFalse(
            "An arbitrary timeout must not gate indicator visibility",
            src.contains("delay(") && src.contains("running"),
        )
        // The old abrupt removal must be gone.
        assertFalse(
            "The typing item must no longer be removed by a bare `if`",
            src.contains("if (uiState.running) {\n                        item(key = \"typing\")"),
        )
    }

    @Test
    fun typingIndicatorPreservesThreeDotsAndBubbleStyle() {
        val src = indicatorSource
        assertTrue("Exactly three dots must be rendered", src.contains("repeat(3)"))
        assertTrue("Dot size must stay 7.dp", src.contains(".size(7.dp)"))
        assertTrue("Dot spacing must stay 5.dp", src.contains("spacedBy(5.dp)"))
        assertTrue(
            "Dot horizontal inset must stay 16.dp",
            src.contains("padding(horizontal = 16.dp, vertical = 6.dp)"),
        )
        assertTrue(
            "Dots must stay circles", src.contains("RoundedCornerShape(50)"),
        )
    }

    // ---------------------------------------------------------------------
    // Task 2 - Dashboard loading state
    // ---------------------------------------------------------------------

    @Test
    fun dashboardLoadingIsReservedForNoUsableContent() {
        val src = dashboardVmSource
        assertTrue(
            "loadDashboardData must detect whether usable content is already shown",
            src.contains("hasUsableContent"),
        )
        assertTrue(
            "Full-screen Loading must be gated on the absence of usable content",
            src.contains("if (!hasUsableContent)"),
        )
    }

    @Test
    fun dashboardNeverFlashesLoadingOnSilentRefresh() {
        val src = dashboardVmSource
        val loadIndex = src.indexOf("fun loadDashboardData")
        assertTrue("loadDashboardData must exist", loadIndex >= 0)
        val body = src.substring(loadIndex, src.indexOf("\n    }", loadIndex))
        // The loading assignment must be conditional, not unconditional.
        assertFalse(
            "Loading must not be assigned unconditionally in loadDashboardData",
            Regex("_uiState\\.value = DashboardUiState\\.Loading\\s*\\n\\s*val configResult").containsMatchIn(body),
        )
        assertTrue(
            "A refresh over loaded content must fall back to feedback, not a full Error screen",
            body.contains("Couldn't refresh data"),
        )
    }

    @Test
    fun initialLoadStillShowsFullScreenLoading() {
        val src = dashboardVmSource
        // The Loading state must still exist and still be reachable for a true
        // initial load (the state itself is part of the public contract).
        assertTrue("DashboardUiState.Loading must still exist", src.contains("object Loading : DashboardUiState()"))
        assertTrue(
            "A sign-out must still reset to the Loading state",
            src.contains("private fun clearUserState()"),
        )
    }

    @Test
    fun mutationsReuseTheSameNonBlockingRefresh() {
        val src = dashboardVmSource
        // Every normal mutation must go through loadDashboardData (now a silent
        // refresh) rather than assigning Loading itself.
        val mutationFns = listOf("fun addTransaction", "fun setTarget", "fun disableTarget", "fun acknowledgeTarget")
        for (fn in mutationFns) {
            val idx = src.indexOf(fn)
            assertTrue("$fn must exist", idx >= 0)
            val nextFn = src.indexOf("\n    fun ", idx + fn.length)
            val body = src.substring(idx, if (nextFn > 0) nextFn else src.length)
            assertFalse(
                "$fn must not force the full-screen Loading state",
                body.contains("DashboardUiState.Loading"),
            )
        }
    }

    @Test
    fun refreshPreservesGoogleLinkFlags() {
        val src = dashboardVmSource
        assertTrue(
            "A silent refresh must carry the resolved Google-link flags forward",
            src.contains("canLinkGoogle = previous?.canLinkGoogle"),
        )
        assertTrue(
            "A silent refresh must carry the provider flag forward",
            src.contains("hasGoogleProvider = previous?.hasGoogleProvider"),
        )
    }

    @Test
    fun aiCategorizationStaysAsyncAndNonBlocking() {
        val src = dashboardVmSource
        // The save still precedes AI, and the save flag is released in `finally`
        // so a slow/failed AI call can never wedge the create button.
        assertTrue(
            "Save must precede categorization",
            src.indexOf("userRepo.addTransaction") < src.indexOf("categorizer.categorize"),
        )
        assertTrue(
            "The transaction-saving flag must be released in a finally block",
            src.contains("finally {") && src.contains("_transactionSaving.value = false"),
        )
    }

    @Test
    fun singleSourceOfTruthIsPreserved() {
        val src = dashboardVmSource
        // Success continues to be built from the repository read only - no second
        // local financial store was introduced for optimistic updates.
        assertTrue(
            "Dashboard content must still come from getRecentTransactions",
            src.contains("userRepo.getRecentTransactions"),
        )
        assertTrue(
            "Dashboard content must still come from getUserConfig",
            src.contains("userRepo.getUserConfig"),
        )
        assertFalse(
            "No second in-memory transaction store may be introduced",
            src.contains("private val _localTransactions"),
        )
    }
}