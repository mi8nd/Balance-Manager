package com.personal.balance.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Static, search-based verification for the App Check provider architecture:
 * reCAPTCHA Enterprise is the SOLE installed provider (debug AND release), Play
 * Integrity and the Firebase App Check debug provider / any debug-token workflow
 * are gone, the provider is installed after Firebase initialization and before
 * any Firebase-dependent AI usage, and the AI/Firebase stack is otherwise
 * untouched. These tests read the repository's own source and config files, so
 * a regression that reintroduces Play Integrity or the debug provider fails the
 * suite loudly instead of being missed during review.
 */
class AppCheckProviderVerificationTest {

    private val repoRoot: File by lazy { locateRepoRoot() }

    private fun locateRepoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir")!!).absoluteFile
        while (dir != null) {
            // Gradle unit tests run with the module dir (app/) as cwd; walk up to
            // the directory containing app/build.gradle.kts (the repo root).
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

    /** Every Kotlin source file in the production source set (main only). */
    private fun mainSourceFiles(): List<File> =
        File(repoRoot, "app/src/main")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()

    @Test
    fun recaptchaDependencyIsDeclared() {
        val buildFile = read("app/build.gradle.kts")
        assertTrue("build.gradle.kts must depend on firebase-appcheck-recaptcha", buildFile.contains("firebase-appcheck-recaptcha"))
    }

    @Test
    fun playIntegrityIsAbsentFromDependencies() {
        val buildFile = read("app/build.gradle.kts")
        assertFalse("build.gradle.kts must not depend on firebase-appcheck-playintegrity", buildFile.contains("firebase-appcheck-playintegrity"))
    }

    @Test
    fun recaptchaProviderIsUsed() {
        val applicationCode = read("app/src/main/java/com/personal/balance/BalanceApplication.kt")
        assertTrue(
            "RecaptchaAppCheckProviderFactory.getInstance(siteKey) must be the installed provider",
            applicationCode.contains("RecaptchaAppCheckProviderFactory.getInstance"),
        )
    }

    @Test
    fun playIntegrityIsAbsentFromApplicationCode() {
        val forbidden = listOf(
            "PlayIntegrityAppCheckProviderFactory",
            "firebase-appcheck-playintegrity",
            "play-integrity",
        )
        for (file in mainSourceFiles()) {
            val content = file.readText()
            for (token in forbidden) {
                assertFalse(
                    "${file.path} must not reference '$token'",
                    content.contains(token, ignoreCase = false),
                )
            }
        }
        assertFalse(
            "build.gradle.kts must not reference Play Integrity",
            read("app/build.gradle.kts").contains("PlayIntegrityAppCheckProvider"),
        )
    }

    @Test
    fun debugProviderIsNotReferenced() {
        val applicationCode = read("app/src/main/java/com/personal/balance/BalanceApplication.kt")
        val buildFile = read("app/build.gradle.kts")

        assertFalse("BalanceApplication must not reference the Debug provider", applicationCode.contains("DebugAppCheckProvider"))
        assertFalse("build.gradle.kts must not pull the debug App Check artifact", buildFile.contains("firebase-appcheck-debug"))
        assertFalse("build.gradle.kts must not reference the Debug provider class", buildFile.contains("DebugAppCheckProvider"))
    }

    @Test
    fun noDebugTokenIsHardcodedOrRequired() {
        val forbidden = listOf(
            "DebugAppCheckProviderFactory",
            "DebugAppCheckProvider",
            "debugToken",
            "firebase_appcheck_debugsecret",
        )
        for (file in mainSourceFiles()) {
            val content = file.readText()
            for (token in forbidden) {
                assertFalse(
                    "${file.path} must not reference '$token'",
                    content.contains(token, ignoreCase = false),
                )
            }
        }
        // local.properties must not be (re)introduced as a debug-secret carrier.
        assertFalse("build.gradle.kts must not define a debugsecret lane", read("app/build.gradle.kts").contains("debugsecret"))
    }

    @Test
    fun appCheckInstallsAfterFirebaseInitialization() {
        val applicationCode = read("app/src/main/java/com/personal/balance/BalanceApplication.kt")
        val firebaseIndex = applicationCode.indexOf("FirebaseApp.initializeApp")
        val installIndex = applicationCode.indexOf("installAppCheckProviderFactory")
        assertTrue("FirebaseApp.initializeApp must be called", firebaseIndex >= 0)
        assertTrue("installAppCheckProviderFactory must be called", installIndex >= 0)
        assertTrue(
            "App Check provider must be installed AFTER Firebase initialization",
            firebaseIndex != -1 && installIndex != -1 && firebaseIndex < installIndex,
        )
        assertFalse(
            "App Check provider selection must not branch on BuildConfig.DEBUG",
            applicationCode.contains("if (BuildConfig.DEBUG)"),
        )
    }

    @Test
    fun providerInstalledBeforeFirebaseAiUsage() {
        // The provider is installed in BalanceApplication.onCreate AND nowhere else:
        // Firebase AI Logic requests are made lazily from ViewModels/repositories later,
        // so no other file may install an App Check provider (i.e. App Check is fully
        // configured before any Firebase-dependent AI code path can run).
        val installers = mainSourceFiles().filter { it.readText().contains("installAppCheckProviderFactory") }
        assertEquals(
            "installAppCheckProviderFactory must appear in exactly one production file (BalanceApplication)",
            listOf(File(repoRoot, "app/src/main/java/com/personal/balance/BalanceApplication.kt")),
            installers,
        )
        val repository = read("app/src/main/java/com/personal/balance/data/ai/FirebaseAiAssistantRepository.kt")
        assertFalse("AI repository must not install App Check itself", repository.contains("installAppCheckProviderFactory"))
        assertFalse("AI repository must not reference FirebaseAppCheck directly", repository.contains("FirebaseAppCheck"))
    }

    @Test
    fun noSecondFirebaseAppOrClient() {
        val allMain = mainSourceFiles().map { it.readText() }.joinToString("\n")
        val occurrences = allMain.split("FirebaseApp.initializeApp").size - 1
        assertEquals(
            "FirebaseApp.initializeApp must appear exactly once across app/src/main (found $occurrences)",
            1,
            occurrences,
        )
        assertFalse("No named/second Firebase app initialization", allMain.contains("FirebaseApp.initializeApp(this,"))
        assertFalse("No FirebaseOptions/second app options", allMain.contains("FirebaseOptions"))
        // FirebaseApp.getInstance() (no args) only ACQUIRES the already-initialized default app
        // (used e.g. by the DataStore stores for getApplicationContext()); it never creates a
        // second app. "No second app" is proven by the single initializeApp above + no options/named init.
        assertTrue(
            "An existing default-app acquisition must stay present (VerificationGateStore/PasswordAccountStore/ExplicitGoogleLinkStore)",
            allMain.contains("FirebaseApp.getInstance().applicationContext"),
        )
    }

    @Test
    fun aiModelNameIsUnchanged() {
        val aiConfig = read("app/src/main/java/com/personal/balance/domain/ai/AiConfig.kt")
        assertTrue("Gemini model must remain gemini-3.1-flash-lite", aiConfig.contains("gemini-3.1-flash-lite"))
    }

    @Test
    fun firebaseAiAssistantRepositoryIsUnchanged() {
        val repository = read("app/src/main/java/com/personal/balance/data/ai/FirebaseAiAssistantRepository.kt")
        assertTrue(
            "App Check enforcement mapping must remain in the AI repository",
            repository.contains("AiResult.Error.AppCheckFailure"),
        )
        assertTrue(
            "AI repository must keep using the Firebase AI Logic backend",
            repository.contains("GenerativeBackend.googleAI"),
        )
    }

    @Test
    fun sharedAiUsageControllerIsUnchanged() {
        val controller = read("app/src/main/java/com/personal/balance/data/ai/SharedAiUsageController.kt")
        assertTrue(controller.contains("object SharedAiUsageController"))
        assertTrue(controller.contains("LocalAiUsageController"))
    }

    @Test
    fun recaptchaSiteKeyIsConfiguredViaResource() {
        val resource = read("app/src/main/res/values/appcheck.xml")
        val applicationCode = read("app/src/main/java/com/personal/balance/BalanceApplication.kt")

        assertTrue(
            "reCAPTCHA site key must be declared as an Android string resource",
            resource.contains("name=\"app_check_recaptcha_site_key\""),
        )
        assertTrue(
            "BalanceApplication must read the site key resource (no hardcoded key)",
            applicationCode.contains("R.string.app_check_recaptcha_site_key"),
        )
        assertFalse("No placeholder site key may be hardcoded", resource.contains("YOUR_SITE_KEY"))
        assertFalse("No placeholder site key may appear in application code", applicationCode.contains("YOUR_SITE_KEY"))
        assertFalse(
            "No invented/fabricated reCAPTCHA key literal may live in the resource (a 6L... key is only issued by the console)",
            resource.contains("\"6L"),
        )
        assertFalse(
            "No invented/fabricated reCAPTCHA key literal may be hardcoded in application code",
            applicationCode.contains("\"6L"),
        )
    }
}