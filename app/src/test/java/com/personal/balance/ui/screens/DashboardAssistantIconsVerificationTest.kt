package com.personal.balance.ui.screens

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Static, search-based verification of the dashboard's top-bar AI shortcuts:
 *
 *  - The AI Financial Assistant has its OWN icon button beside the settings
 *    gear (sparkle icon), not only a settings-menu entry.
 *  - The debug-only AI Smoke Test also has its own icon button beside the
 *    settings gear, gated on BuildConfig.DEBUG so release builds never show it.
 *  - The old settings-dropdown entries (SmartToy / Build) are gone: the same
 *    targets must not be reachable twice.
 *
 * These tests read this repo's own source, mirroring the existing
 * SaveFirstAsyncCategorizationVerificationTest pattern. Runtime behavior
 * (device) is verified in the full-app QA phase.
 */
class DashboardAssistantIconsVerificationTest {

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

    @Test
    fun assistantHasItsOwnTopBarIconButton() {
        val screen = read("app/src/main/java/com/personal/balance/ui/screens/DashboardScreen.kt")
        assertTrue(
            "The dashboard must show the AI Financial Assistant as its own sparkle icon button (AutoAwesome)",
            screen.contains("Icons.Filled.AutoAwesome"),
        )
        assertTrue(
            "The assistant icon button must sit in the TopAppBar actions block",
            screen.contains("onOpenFinancialAssistant"),
        )
    }

    @Test
    fun smokeTestHasItsOwnDebugOnlyTopBarIconButton() {
        val screen = read("app/src/main/java/com/personal/balance/ui/screens/DashboardScreen.kt")
        assertTrue(
            "The dashboard must show the AI Smoke Test as its own beaker icon button (Science)",
            screen.contains("Icons.Filled.Science"),
        )
        assertTrue(
            "The smoke-test icon must be gated on BuildConfig.DEBUG so release builds never show it",
            screen.contains("if (BuildConfig.DEBUG && onOpenAiSmokeTest != null)"),
        )
    }

    @Test
    fun oldDropdownDuplicatesAreGone() {
        val screen = read("app/src/main/java/com/personal/balance/ui/screens/DashboardScreen.kt")
        assertFalse(
            "The old SmartToy dropdown entry must be gone (assistant has its own icon now)",
            screen.contains("SmartToy"),
        )
        assertFalse(
            "The old Build dropdown entry must be gone (smoke test has its own icon now)",
            screen.contains("icons.filled.Build"),
        )
        assertFalse(
            "The assistant must no longer be a settings-menu DropdownMenuItem",
            screen.contains("Text(\"AI Financial Assistant\""),
        )
        assertFalse(
            "The smoke test must no longer be a settings-menu DropdownMenuItem",
            screen.contains("Text(\"AI Smoke Test\""),
        )
    }

    @Test
    fun navigationRoutesAreIntact() {
        val navigation = read("app/src/main/java/com/personal/balance/ui/navigation/Navigation.kt")
        assertTrue(
            "The assistant route must still navigate to FinancialAssistantScreen",
            navigation.contains("Routes.FINANCIAL_ASSISTANT"),
        )
        assertTrue(
            "The debug AI smoke-test route must still exist",
            navigation.contains("Routes.DEBUG_AI"),
        )
    }
}
