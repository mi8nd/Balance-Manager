# AGENTS.md

Single-module Compose Android app (`com.personal.balance`, module `:app`). Windows host; build/test with `.\gradlew.bat`. `PROJECT_MAP.md` is the authoritative build spec and task-by-task audit — read the latest numbered section before changing AI/security/auth code, and append a new section when you ship a new task.

## Build & verify commands
- JDK 17 is required (Kotlin `jvmTarget` 17). On this machine builds fail with the default JDK — run gated by: `$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.17.10-hotspot"`
- When running any Gradle build/verify command, set a **2-minute (120000 ms) timeout** on the shell tool call.
- Compile after main edits: `.\gradlew.bat :app:compileDebugKotlin`
- Full unit tests: `.\gradlew.bat :app:testDebugUnitTest`
- Focused tests: `.\gradlew.bat :app:testDebugUnitTest --tests "*Chat*"` (Gradle needs the precise current suite; keep full-suite green after any test change)
- Lint: `.\gradlew.bat :app:lintDebug` (currently 0 errors, ~19 pre-existing GradleDependency/icon warnings — don't churn on those)
- Debug APK: `.\gradlew.bat :app:assembleDebug`. Release (`assembleRelease`) needs Git-ignored `app/keystore.properties` + `app/keystore/balance-release.jks`; otherwise it builds unsigned. Never print or commit those secrets.
- `app/google-services.json` is **Git-ignored** (project id, API keys, OAuth client ids, signing-cert hashes). Every machine needs its own copy, downloaded from the Firebase console (Project settings → Your apps → `com.personal.balance`); `app/google-services.json.example` shows the shape. Without it the `com.google.gms.google-services` plugin fails the build. The e2e Python harness reads the web API key / cert hash from that same file (`e2e/lib/creds.py`), so it needs no committed secrets either.
- `.claude/` is local agent tooling, not part of the project, and stays Git-ignored.

## Architecture facts that change behavior
- One AI stack, reused everywhere: `AskAiUseCase` (auth → shared device-local budget → assistant), `SharedAiUsageController.instance` (the ONLY usage controller), `FirebaseAiAssistantRepository`, `DefaultAiConfig` (`gemini-3.1-flash-lite`, `text/plain`, fixed safety settings). Add a feature by varying only `systemPrompt` (e.g. `DefaultChatAiConfig`). Do NOT add a new AI SDK/backend/model/controller — static `*VerificationTest`s enforce this and will fail.
- Firestore always `users/{uid}/...`; the data layer resolves uid from `FirebaseAuth.getInstance().currentUser?.uid` — never from UI/model input. New collections must mirror `firestore.rules` ownership (`request.auth.uid == userId`) without weakening the existing rules; `firestore.rules` is NOT auto-deployed, flag manual console deploy for new rules.
- ViewModels use manual constructor injection with default repos; screens get them via `viewModel()`. Follow existing patterns rather than introducing DI.

## Testing quirks
- `app/src/test` are JVM unit tests (no device). Compose `androidTest` / anything needing an emulator or device: **drive it through mobile-mcp** (`mobile_list_available_devices` first; use `mobile_*` for install/launch/interact/screenshot/logs) and `adb *` where shell-level access is needed. Never claim device verification without actually running it.
- This repo also has an adb/uiautomator Python harness (`e2e/lib/`, requires `adb`) and Maestro flows (`e2e/flows/`). `e2e/.secrets/`, `e2e/evidence/` are Git-ignored and never committed.
- Some "unit" tests are static invariant checks that read repo files off disk and locate the repo root by walking up from the module cwd (`AppCheckProviderVerificationTest`, `AiConfigTest`). Intentional changes to source/rules must keep them green.
- ViewModel/use-case tests use `MainDispatcherRule` (defined in `FinancialAssistantViewModelTest.kt`) with `StandardTestDispatcher`; drive coroutines via `scheduler.runCurrent()` / `advanceUntilIdle()`. Pure suspend use-case tests use `runTest`.
- Historical test counts in `PROJECT_MAP.md` (343/403/etc.) are stale per-task snapshots — report the current run's actual numbers instead.
- AI tests must fake `AskAiUseCase` (real gate with fake `AiAuthProvider`/`AiUsageController`/`AiAssistantRepository`); never hit the real Gemini backend or consume the budget in unit tests.