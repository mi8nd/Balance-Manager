package com.personal.balance.domain.category

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Static, search-based verification of the automatic categorization contract:
 *
 *  - The transaction is PERSISTED FIRST — the dashboard calls the repository
 *    save before it runs the AI categorizer, so adding a transaction always
 *    succeeds independent of AI.
 *  - The AI category is persisted back onto the stored document
 *    (updateTransactionCategory) — the UI reflects the persisted category.
 *  - The manual "Suggest category" affordance and the old
 *    TransactionAssistanceUseCase are gone.
 *  - The manual override path (`categoryOverride`) is gone: an arbitrary string
 *    can never reach the stored category.
 *
 * These tests read this repo's own source, mirroring the existing
 * AppCheckProviderVerificationTest pattern. Runtime behavior (device) is
 * verified in the full-app QA phase.
 */
class SaveFirstAsyncCategorizationVerificationTest {

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
    fun dashboardPersistsBeforeCategorizing() {
        val vm = read("app/src/main/java/com/personal/balance/viewmodel/DashboardViewModel.kt")
        val saveIndex = vm.indexOf("userRepo.addTransaction")
        val categorizeIndex = vm.indexOf("categorizer.categorize")
        assertTrue("DashboardViewModel must call userRepo.addTransaction", saveIndex >= 0)
        assertTrue("DashboardViewModel must call categorizer.categorize", categorizeIndex >= 0)
        assertTrue(
            "The transaction must be persisted BEFORE the AI categorization runs (save-first)",
            saveIndex < categorizeIndex,
        )
    }

    @Test
    fun aiCategoryIsPersistedBackUpdate() {
        val repository = read("app/src/main/java/com/personal/balance/data/FirestoreUserRepository.kt")
        assertTrue(
            "A category-only update API must exist for the async persistence",
            repository.contains("updateTransactionCategory"),
        )
        val vm = read("app/src/main/java/com/personal/balance/viewmodel/DashboardViewModel.kt")
        assertTrue(
            "DashboardViewModel must persist the AI-assigned category",
            vm.contains("userRepo.updateTransactionCategory"),
        )
    }

    @Test
    fun manualSuggestCategoryIsRemoved() {
        val screen = read("app/src/main/java/com/personal/balance/ui/screens/DashboardScreen.kt")
        assertFalse("'Suggest category' button must be gone", screen.contains("Suggest category"))
        assertFalse("the categoryOverride override path must be gone", screen.contains("categoryOverride"))
        val vm = read("app/src/main/java/com/personal/balance/viewmodel/DashboardViewModel.kt")
        assertFalse("requestCategorySuggestion must be gone", vm.contains("requestCategorySuggestion"))
        assertFalse("categorySuggestion state must be gone", vm.contains("categorySuggestion"))
        val assistanceFile = File(repoRoot, "app/src/main/java/com/personal/balance/domain/category/TransactionAssistanceUseCase.kt")
        assertFalse("TransactionAssistanceUseCase must be deleted", assistanceFile.exists())
    }

    @Test
    fun deterministicCategorySeedsThePersistedTransaction() {
        val vm = read("app/src/main/java/com/personal/balance/viewmodel/DashboardViewModel.kt")
        assertTrue(
            "Saved transaction must start from the on-device deterministic category",
            vm.contains("DeterministicCategoryClassifier.classify"),
        )
    }

    @Test
    fun saveNeverBlockedOnAi() {
        val vm = read("app/src/main/java/com/personal/balance/viewmodel/DashboardViewModel.kt")
        // The AI result only ever refines an ALREADY-persisted category: an
        // error path must not roll the transaction back (there is no delete-on-AI
        // failure and the save result is fully handled before the AI step).
        val saveHandled = vm.indexOf("is AddTransactionResult.Saved")
        val aiCall = vm.indexOf("categorizer.categorize")
        assertTrue("Save result must be handled before the async AI step", saveHandled < aiCall)
        assertFalse("No rollback-on-AI-failure delete may exist", vm.contains("deleteTransaction"))
    }

    @Test
    fun noAiChatResidue() {
        assertFalse(
            "Chat data layer must be gone",
            File(repoRoot, "app/src/main/java/com/personal/balance/data/FirestoreChatRepository.kt").exists(),
        )
        val chatDir = File(repoRoot, "app/src/main/java/com/personal/balance/domain/chat")
        assertFalse(
            "Chat domain package must be gone or empty",
            chatDir.isDirectory && (chatDir.listFiles()?.isNotEmpty() == true),
        )
        val rules = read("firestore.rules")
        assertFalse("Firestore rules must not allow conversations", rules.contains("conversations"))
        val navigation = read("app/src/main/java/com/personal/balance/ui/navigation/Navigation.kt")
        assertFalse("Navigation must not reference chat routes", navigation.contains("CHAT"))
    }

    @Test
    fun singleGoalPerUserModel() {
        val config = read("app/src/main/java/com/personal/balance/model/UserConfig.kt")
        // Exactly ONE goal: a single set of target fields on the user config.
        assertTrue(config.contains("targetAmount"))
        assertTrue(config.contains("targetName"))
        assertTrue(config.contains("targetEnabled"))
        val rules = read("firestore.rules")
        assertFalse(
            "No separate goals collection may exist under the user",
            rules.contains("goals"),
        )
        val goalCoach = read("app/src/main/java/com/personal/balance/domain/ai/orchestration/GoalCoachSupport.kt")
        // One goal, not a plural collection.
        assertTrue(goalCoach.contains("GoalCoachState.Active"))
        assertFalse("Goal coach must model a single goal", goalCoach.contains("List<Goal"))
    }
}