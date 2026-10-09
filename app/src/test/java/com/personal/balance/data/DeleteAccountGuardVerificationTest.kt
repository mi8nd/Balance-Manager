package com.personal.balance.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Static, search-based verification of the account-delete guard (PROJECT_MAP
 * §28). The confirmed production defect deleted the user's Firestore data
 * BEFORE attempting the Firebase Auth account delete; when Auth rejected it
 * (ERROR_REQUIRES_RECENT_LOGIN) the user had already lost all data.
 *
 * The fix MUST:
 *  - delete the Auth account FIRST (authRepo.deleteCurrentUser before any
 *    Firestore purge),
 *  - capture the ID token BEFORE the auth delete (it is required to purge
 *    Firestore after auth deletion succeeds, when the SDK session is gone), and
 *  - attempt the Firestore purge ONLY inside the AuthResult.Success branch, so
 *    a failed auth delete leaves user data completely untouched.
 *
 * Runtime (device) verification is performed in the full-app QA pass; these
 * tests mirror the AppCheckProviderVerificationTest / SaveFirstAsyncCategorizationVerificationTest
 * pattern of reading this repo's own source off disk.
 */
class DeleteAccountGuardVerificationTest {

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
    fun authDeleteHappensBeforeAnyFirestorePurge() {
        val vm = read("app/src/main/java/com/personal/balance/viewmodel/DashboardViewModel.kt")
        val authDelete = vm.indexOf("authRepo.deleteCurrentUser()")
        val purge = vm.indexOf("userRepo.purgeUserDataWithCapturedToken")
        assertTrue("deleteAccount must call authRepo.deleteCurrentUser()", authDelete >= 0)
        assertTrue("deleteAccount must call userRepo.purgeUserDataWithCapturedToken", purge >= 0)
        assertTrue(
            "Auth account deletion MUST run before any Firestore purge (guard-first)",
            authDelete < purge,
        )
        val deleteAccount = vm.substring(vm.indexOf("fun deleteAccount()"))
        assertFalse(
            "The old data-first ordering (userRepo.deleteUserData before auth delete) must be gone from deleteAccount",
            deleteAccount.contains("userRepo.deleteUserData("),
        )
    }

    @Test
    fun tokenIsCapturedBeforeAuthDelete() {
        val authRepo = read("app/src/main/java/com/personal/balance/data/FirebaseAuthRepository.kt")
        assertTrue(
            "FirebaseAuthRepository MUST expose a token-capture method (captureIdToken)",
            authRepo.contains("suspend fun captureIdToken(): String?"),
        )
        val vm = read("app/src/main/java/com/personal/balance/viewmodel/DashboardViewModel.kt")
        val capture = vm.indexOf("authRepo.captureIdToken()")
        val authDelete = vm.indexOf("authRepo.deleteCurrentUser()")
        assertTrue("deleteAccount must capture the token", capture >= 0)
        assertTrue(
            "ID token capture MUST precede the auth account deletion",
            capture < authDelete,
        )
    }

    @Test
    fun firestorePurgeHappensOnlyOnAuthDeleteSuccess() {
        val vm = read("app/src/main/java/com/personal/balance/viewmodel/DashboardViewModel.kt")
        val purge = vm.indexOf("userRepo.purgeUserDataWithCapturedToken")
        assertTrue("purge call must exist", purge >= 0)
        val successBranchStart = vm.indexOf("is AuthResult.Success -> {", purge - 1200)
        val successBranchEnd = vm.indexOf("is AuthResult.Error -> {", successBranchStart)
        assertTrue("AuthResult.Success branch must come before the Error branch", successBranchStart >= 0)
        assertTrue(successBranchEnd > successBranchStart)
        val successBranch = vm.substring(successBranchStart, successBranchEnd)
        assertTrue(
            "Firestore purge MUST be invoked only inside the AuthResult.Success branch",
            successBranch.contains("purgeUserDataWithCapturedToken"),
        )
        assertFalse(
            "purge must NOT be reachable from the Error (recent-login) branch",
            vm.substring(successBranchEnd).substringBefore("is AuthResult.EmailCollision")
                .contains("purgeUserDataWithCapturedToken"),
        )
    }

    @Test
    fun capturedTokenPurgeUsesRestOwnedPaths() {
        val repo = read("app/src/main/java/com/personal/balance/data/FirestoreUserRepository.kt")
        assertTrue(
            "REST purge path must live under users/{userId}",
            repo.contains("\$base/users/"),
        )
        assertTrue("purge must delete transactions too", repo.contains("/transactions"))
        assertTrue("purge must delete the user config document", repo.contains("/users/"))
    }
}