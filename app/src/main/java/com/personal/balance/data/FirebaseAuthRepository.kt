package com.personal.balance.data

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed class AuthResult {
    data class Success(val userId: String, val isNewUser: Boolean = false) : AuthResult()
    data class Error(val message: String) : AuthResult()
    data class EmailCollision(val message: String) : AuthResult()
    /**
     * The operation is GUARDED by the verified-email gate: the account that would
     * be affected has (or is known, on this device, to have) an Email/Password
     * credential whose email address is not verified (`emailVerified == false`).
     * Allowing Google to proceed would let Firebase's trusted-provider precedence
     * silently replace `password` with `google.com`. The app never performs that
     * overwrite: it does nothing destructive and reports this instead.
     */
    data class VerificationRequired(val email: String, val message: String) : AuthResult()
}

sealed class SessionValidationResult {
    data class Valid(val userId: String) : SessionValidationResult()
    object InvalidAccount : SessionValidationResult()
    object NetworkError : SessionValidationResult()
}

class FirebaseAuthRepository(
    private val firebaseAuth: FirebaseAuth = FirebaseAuth.getInstance(),
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance(),
    private val verificationGate: VerificationGateStore = VerificationGateStore(),
    private val passwordAccounts: PasswordAccountStore = PasswordAccountStore(),
    private val explicitGoogleLink: ExplicitGoogleLinkStore = ExplicitGoogleLinkStore()
) {

    suspend fun signUp(email: String, password: String): AuthResult = withContext(Dispatchers.IO) {
        return@withContext try {
            firebaseAuth.createUserWithEmailAndPassword(email, password).await()
            val userId = firebaseAuth.currentUser?.uid ?: ""
            if (userId.isEmpty()) {
                AuthResult.Error("Failed to create user account")
            } else {
                // TEMP-DIAG (Pass 4): capture the BEFORE-GOOGLE account state
                Log.d("FIREBASE_AUTH", "=== SIGNUP RESULT ===")
                Log.d("FIREBASE_AUTH", "uid=$userId")
                Log.d("FIREBASE_AUTH", "email=${firebaseAuth.currentUser?.email}")
                Log.d("FIREBASE_AUTH", "emailVerified=${firebaseAuth.currentUser?.isEmailVerified}")
                Log.d("FIREBASE_AUTH", "providers=${firebaseAuth.currentUser?.providerData?.map { it.providerId }}")
                // Verification gate: a brand-new Email/Password account is created
                // unverified, so the user is asked to confirm the address. The
                // confirmation email is sent immediately so the verified-email
                // onboarding/account state (emailVerified) can be reached before
                // any Google authentication is allowed to act on this account.
                sendVerificationEmailForCurrentUser()
                if (!email.isNullOrBlank()) {
                    verificationGate.addPendingVerification(email)
                    // Device-local evidence for the pre-authentication Google
                    // checks (SIGN_UP ACCOUNT_EXISTS and LOGIN link scoping): this
                    // device now knows this email is a password account, regardless
                    // of whether the address gets verified later.
                    passwordAccounts.record(email, userId)
                }
                AuthResult.Success(userId, isNewUser = true)
            }
        } catch (e: FirebaseAuthException) {
            Log.d("FIREBASE_AUTH", "signUpError code=${e.errorCode} message=${e.message}")
            AuthResult.Error(mapFirebaseAuthError(e))
        } catch (e: Exception) {
            AuthResult.Error(e.message ?: "Authentication failed")
        }
    }

    suspend fun signIn(email: String, password: String): AuthResult = withContext(Dispatchers.IO) {
        return@withContext try {
            firebaseAuth.signInWithEmailAndPassword(email, password).await()
            val userId = firebaseAuth.currentUser?.uid ?: ""
            if (userId.isEmpty()) {
                AuthResult.Error("Failed to sign in")
            } else {
                // TEMP-DIAG (Pass 4): state after Email/Password sign-in
                Log.d("FIREBASE_AUTH", "=== PASSWORD SIGNIN RESULT ===")
                Log.d("FIREBASE_AUTH", "uid=$userId")
                Log.d("FIREBASE_AUTH", "email=${firebaseAuth.currentUser?.email}")
                Log.d("FIREBASE_AUTH", "emailVerified=${firebaseAuth.currentUser?.isEmailVerified}")
                Log.d("FIREBASE_AUTH", "providers=${firebaseAuth.currentUser?.providerData?.map { it.providerId }}")
                // Device-local evidence for the pre-authentication Google checks:
                // this device successfully signed into this Email/Password account.
                if (!email.isNullOrBlank()) {
                    passwordAccounts.record(email, userId)
                }
                AuthResult.Success(userId, isNewUser = false)
            }
        } catch (e: FirebaseAuthException) {
            // TEMP-DIAG (Pass 4): exact error code for the post-Google password login
            Log.d("FIREBASE_AUTH", "=== PASSWORD SIGNIN ERROR ===")
            Log.d("FIREBASE_AUTH", "errorCode=${e.errorCode}")
            Log.d("FIREBASE_AUTH", "message=${e.message}")
            AuthResult.Error(mapFirebaseAuthError(e))
        } catch (e: Exception) {
            AuthResult.Error(e.message ?: "Sign in failed")
        }
    }

    /**
     * Authenticates with Google. This operation NEVER calls `linkWithCredential`
     * automatically: a Google attempt can only authenticate or CREATE an account,
     * never attach the Google provider to an unrelated account by itself. The
     * intended linking flow is the explicit `linkGoogleAccount` (Settings) only.
     *
     * ORDERING (Pass 8) - the rejecting decisions are made BEFORE any mutating
     * Firebase credential operation, never as a post-hoc repair/undo:
     *
     *  1. VERIFIED-EMAIL GATE: if this device knows the Google email is an
     *     `emailVerified == false` Email/Password account, refuse immediately
     *     (`VerificationRequired`) with NO Firebase operation. Google is
     *     authoritative for Google-hosted addresses, so on an unverified password
     *     account `signInWithCredential` would silently replace `password` with
     *     `google.com` (trusted-provider precedence, reproduced on device).
     *  2. GOOGLE SIGN UP PRE-CHECK: the Android SDK has no creation-only Google
     *     primitive, so Google Sign Up refuses here whenever this device knows the
     *     Google email belongs to a password account (see [PasswordAccountStore]),
     *     returning `ACCOUNT_EXISTS` / `EmailCollision` WITHOUT calling Firebase.
     *  3. GOOGLE LOGIN PRE-CHECK: Google LOGIN must never implicitly upgrade an
     *     Email/Password account. `signInWithCredential` would attach `google.com`
     *     to any existing password account, and there is no client-only Firebase
     *     API that can preflight the provider set. If this device knows the Google
     *     email is a password account whose UID is NOT in the local explicit-link
     *     record ([ExplicitGoogleLinkStore], written only by Settings -> Link
     *     Google Account), Google LOGIN is refused here (`GOOGLE_NOT_LINKED`)
     *     BEFORE `signInWithCredential`; Firebase is never touched. Google-only
     *     accounts (unknown to the password store) and locally-linked password
     *     accounts proceed to step 4.
     *  4. LOGIN uses `signInWithCredential`: on non-authoritative domains an email
     *     collision fails with `ERROR_ACCOUNT_EXISTS_WITH_DIFFERENT_CREDENTIAL`
     *     (no mutation) and is reported as `EmailCollision`; on Google-hosted
     *     addresses an allowed account authenticates normally to the same UID.
     *  5. DEFENSIVE SIGN_UP/LOGIN nets: if an existing account ever still slips
     *     through (e.g. created/cross-linked outside this device, which no
     *     client-only non-enumerating signal can detect), SIGN_UP refuses with
     *     `ACCOUNT_EXISTS` instead of silently becoming a Login, and LOGIN never
     *     deletes or repairs provider state (Pass 3).
     *
     * `fetchSignInMethodsForEmail` is NOT used for provider detection: with Email
     * Enumeration Protection enabled it returns an empty list for every address
     * (verified against this project's backend), so no point-of-call pre-check can
     * distinguish "no account" from "account exists with another provider". Every
     * collision is instead handled from the error the operation actually returns.
     *
     * The intended account-linking flow is separate and explicit:
     * 1. Authenticate the existing provider first (sign in with Email/Password).
     * 2. Then link the pending Google credential via [linkGoogleAccount]
     *    (`linkWithCredential`).
     * See: https://firebase.google.com/docs/auth/android/manage-users#link_auth_providers
     */
    private suspend fun authenticateWithGoogle(
        idToken: String,
        flowType: FlowType
    ): AuthResult = withContext(Dispatchers.IO) {
        return@withContext try {
            // Extract email from ID token for logging only (no sensitive data)
            val googleEmail = extractEmailFromIdToken(idToken) ?: "unknown"

            // LOG: State BEFORE operation
            val currentUserBefore = firebaseAuth.currentUser
            val uidBefore = currentUserBefore?.uid ?: "null"
            val providersBefore = currentUserBefore?.providerData?.map { it.providerId } ?: emptyList()
            Log.d("FIREBASE_AUTH", "=== GOOGLE AUTH START ===")
            Log.d("FIREBASE_AUTH", "flow=$flowType")
            Log.d("FIREBASE_AUTH", "googleEmail=$googleEmail")
            Log.d("FIREBASE_AUTH", "currentUserBefore=$uidBefore")
            Log.d("FIREBASE_AUTH", "providersBefore=$providersBefore")

            // VERIFICATION GATE (pre-sign-in, non-destructive, NO provider
            // discovery): if this device knows that an Email/Password account at
            // googleEmail was created/signed into through this app and its address
            // has not been verified yet, calling `signInWithCredential` right now
            // would let Firebase's trusted-provider precedence (Google is
            // authoritative for e.g. @gmail.com) silently replace `password` with
            // `google.com`. The app refuses to do that: it reports
            // VerificationRequired and performs NO Firebase mutation whatsoever.
            if (googleEmail != "unknown") {
                val blocked = runCatching {
                    verificationGate.isPendingVerification(googleEmail)
                }.getOrDefault(false)
                if (blocked) {
                    Log.d("FIREBASE_AUTH", "=== GOOGLE AUTH RESULT ===")
                    Log.d("FIREBASE_AUTH", "flow=$flowType")
                    Log.d("FIREBASE_AUTH", "result=VERIFICATION_REQUIRED")
                    Log.d("FIREBASE_AUTH", "email=$googleEmail")
                    return@withContext AuthResult.VerificationRequired(
                        googleEmail,
                        "Your email address has not been verified yet. To keep your " +
                            "email/password sign-in working, verify your email before " +
                            "using Google: log in with your email and password, then use " +
                            "the \"Resend Email\" option."
                    )
                }
            }

            // SIGN_UP PRE-CHECK (Pass 6 fix): Google Sign Up must NEVER mutate an existing
            // account. The Android SDK has no creation-only Google primitive, so the
            // decision is made client-side BEFORE any Firebase credential operation,
            // using this device's record of Email/Password accounts: if this device
            // knows the Google email is a password account, Sign Up refuses right
            // here - no `signInWithCredential`, no server-side provider change, no
            // emailVerified flip. (The previous implementation called
            // `signInWithCredential` first and rejected AFTER Firebase had already
            // attached `google.com` to the existing password account and changed
            // emailVerified - that server-side mutation persisted even after the
            // app signed out, which was the unsafe behavior.)
            if (flowType == FlowType.SIGN_UP && googleEmail != "unknown") {
                val knownPasswordAccount = runCatching {
                    passwordAccounts.isKnownPasswordAccount(googleEmail)
                }.getOrDefault(false)
                if (knownPasswordAccount) {
                    Log.d("FIREBASE_AUTH", "=== GOOGLE AUTH RESULT ===")
                    Log.d("FIREBASE_AUTH", "flow=$flowType")
                    Log.d("FIREBASE_AUTH", "googleEmail=$googleEmail")
                    Log.d("FIREBASE_AUTH", "result=ACCOUNT_EXISTS")
                    Log.d("FIREBASE_AUTH", "reason=known_password_account_rejected_before_mutation")
                    if (isGoogleHostedEmail(googleEmail)) {
                        return@withContext AuthResult.Error("ACCOUNT_EXISTS")
                    }
                    return@withContext AuthResult.EmailCollision(
                        "This email is already registered with Email/Password. " +
                            "Please log in using your email and password."
                    )
                }
            }

            // GOOGLE LOGIN PRE-CHECK (Pass 8 fix): Google LOGIN must NEVER
            // implicitly upgrade an Email/Password account. Firebase attaches
            // `google.com` to an existing password account whenever a Google
            // credential is signed in with `signInWithCredential`, and there is no
            // client-only Firebase API that can preflight "is Google linked?"
            // without relying on a provider-discovery API (deprecated + EEP-blind)
            // or a Firestore email index (rejected). The app therefore keeps its
            // OWN explicit-link record, keyed by Firebase UID, written ONLY after
            // a successful Settings -> Link Google Account (`linkWithCredential`).
            // If this device knows the Google email is a password account whose
            // UID is NOT in that record, Google LOGIN is refused right here -
            // `signInWithCredential` is never reached and Firebase stays untouched.
            if (flowType == FlowType.LOGIN && googleEmail != "unknown") {
                val knownUid = runCatching {
                    passwordAccounts.getUidForEmail(googleEmail)
                }.getOrDefault(null)
                if (knownUid != null) {
                    val locallyLinked = runCatching {
                        explicitGoogleLink.isGoogleLinked(knownUid)
                    }.getOrDefault(false)
                    if (!locallyLinked) {
                        Log.d("FIREBASE_AUTH", "=== GOOGLE AUTH RESULT ===")
                        Log.d("FIREBASE_AUTH", "flow=$flowType")
                        Log.d("FIREBASE_AUTH", "googleEmail=$googleEmail")
                        Log.d("FIREBASE_AUTH", "result=GOOGLE_NOT_LINKED")
                        Log.d("FIREBASE_AUTH", "reason=password_account_not_explicitly_linked_before_mutation")
                        return@withContext AuthResult.Error("GOOGLE_NOT_LINKED")
                    }
                    Log.d("FIREBASE_AUTH", "info=google_login_allowed_local_link_confirmed uid=$knownUid")
                }
            }

            val credential = GoogleAuthProvider.getCredential(idToken, null)
            val authResult = firebaseAuth.signInWithCredential(credential).await()

            val userId = firebaseAuth.currentUser?.uid ?: ""
            if (userId.isEmpty()) {
                Log.d("FIREBASE_AUTH", "=== GOOGLE AUTH RESULT ===")
                Log.d("FIREBASE_AUTH", "flow=$flowType")
                Log.d("FIREBASE_AUTH", "result=error")
                Log.d("FIREBASE_AUTH", "error=empty_uid")
                return@withContext AuthResult.Error("Failed to authenticate with Google")
            }

            val isNewUser = authResult.additionalUserInfo?.isNewUser ?: false
            val currentUser = firebaseAuth.currentUser!!

            // Reload to get fresh provider data. Used for diagnostics logging only;
            // the flow decisions below never depend on the provider set.
            try {
                currentUser.reload().await()
            } catch (re: Exception) {
                Log.d("FIREBASE_AUTH", "googleReloadWarning=${re.message}")
            }

            val providers = currentUser.providerData.map { it.providerId }
            val hasGoogleProvider = providers.contains("google.com")
            val hasPasswordProvider = providers.contains("password")

            // LOG: State AFTER operation
            Log.d("FIREBASE_AUTH", "=== GOOGLE AUTH RESULT ===")
            Log.d("FIREBASE_AUTH", "flow=$flowType")
            Log.d("FIREBASE_AUTH", "isNewUser=$isNewUser")
            Log.d("FIREBASE_AUTH", "uid=$userId")
            Log.d("FIREBASE_AUTH", "email=${currentUser.email}")
            Log.d("FIREBASE_AUTH", "emailVerified=${currentUser.isEmailVerified}")
            Log.d("FIREBASE_AUTH", "providers=$providers")
            Log.d("FIREBASE_AUTH", "hasGoogleProvider=$hasGoogleProvider")
            Log.d("FIREBASE_AUTH", "hasPasswordProvider=$hasPasswordProvider")

            // TEMP-DIAG (Pass 4, remove after evidence collected). Runs IMMEDIATELY
            // after signInWithCredential + reload and BEFORE any sign-out/navigation/
            // deletion/Firestore initialization that happens below.
            // The Email/Password signup created users/{signupUid}; if this returned
            // UID has NO users/ document, the Google sign-in resolved to a DIFFERENT
            // UID than the one created at signup (a transfer/new account). providers
            // comes from reload() which is server-synced (accounts:lookup backend).
            var firestoreDocForUidExists = false
            var firestoreDocProbeError: String? = null
            try {
                val doc = firestore.collection("users").document(userId).get().await()
                firestoreDocForUidExists = doc.exists()
            } catch (de: Exception) {
                firestoreDocProbeError = de.message
            }
            Log.d("FIREBASE_AUTH", "DIAGNOSTIC_AFTER_GOOGLE_SIGNIN")
            Log.d("FIREBASE_AUTH", "uid=$userId")
            Log.d("FIREBASE_AUTH", "email=${currentUser.email}")
            Log.d("FIREBASE_AUTH", "providers=$providers")
            Log.d("FIREBASE_AUTH", "isNewUser=$isNewUser")
            Log.d("FIREBASE_AUTH", "firestoreDocForUidExists=$firestoreDocForUidExists")
            if (firestoreDocProbeError != null) {
                Log.d("FIREBASE_AUTH", "firestoreDocProbeError=$firestoreDocProbeError")
            }
            Log.d("FIREBASE_AUTH", "DIAGNOSTIC_AFTER_GOOGLE_SIGNIN_END")

            // LOGIN FLOW: user claims to already have an account
            if (flowType == FlowType.LOGIN) {
                if (isNewUser) {
                    // Firebase reported this Google credential as a NEW user during
                    // a LOGIN attempt. Two server-side situations produce this, and
                    // this app cannot reliably distinguish them from the client:
                    //
                    // 1. The Google identity genuinely has no account in this
                    //    project, and the sign-in just created a fresh one.
                    // 2. A Google-authoritative email (e.g. @gmail.com or a Google
                    //    Workspace domain) whose existing Email/Password account
                    //    was taken over by the Google provider during
                    //    `signInWithCredential` - the password provider is detached
                    //    server-side and the resulting account surfaces as new.
                    //    This is the behavior reproduced on a physical device
                    //    (device Test 4 / Test 5).
                    //
                    // In BOTH cases the Login flow must be NON-DESTRUCTIVE: in
                    // situation 2 this account is the account the user actually
                    // wanted. The previous implementation deleted the user here
                    // (`cleanupNewlyCreatedUser`), which permanently destroyed
                    // accounts the user intended to log in to - that deletion was
                    // the root cause of the "account vanished" bug. The app does
                    // NOT attempt to repair situation 2 (that transition happens
                    // server-side during the sign-in and cannot be undone from the
                    // client without the password or the Firebase Admin SDK).
                    //
                    // Sign out so the app is never left authenticated as an account
                    // the user did not intentionally sign in to, and report
                    // NO_ACCOUNT so the user is routed to Sign Up - on purpose and
                    // never by accident.
                    firebaseAuth.signOut()
                    Log.d("FIREBASE_AUTH", "result=NO_ACCOUNT")
                    return@withContext AuthResult.Error("NO_ACCOUNT")
                }
                // Existing Firebase user: the sign-in resolved to an existing
                // account, so the same UID holds the same app data. (On a
                // non-authoritative domain a real collision would have thrown
                // ERROR_ACCOUNT_EXISTS_WITH_DIFFERENT_CREDENTIAL above and never
                // reached this point.) No provider mutation is attempted.
                if (!hasGoogleProvider) {
                    Log.d("FIREBASE_AUTH", "info=google_provider_not_in_provider_data uid=$userId")
                }
                Log.d("FIREBASE_AUTH", "result=SUCCESS")
                return@withContext AuthResult.Success(userId, isNewUser = false)
            }

            // SIGN UP FLOW: user wants to create a NEW account
            else { // FlowType.SIGN_UP
                if (isNewUser) {
                    // NEW Firebase user created - exactly what Sign Up requires
                    Log.d("FIREBASE_AUTH", "result=SUCCESS_NEW")
                    return@withContext AuthResult.Success(userId, isNewUser = true)
                }
                // Existing Firebase user - Sign Up must NOT silently become Login.
                // Defensive-only net: the SIGN_UP pre-check above already refuses
                // any account this device knows about BEFORE Firebase is called.
                // This branch is the residual catch for accounts created outside
                // this device (no client-only, non-enumerating signal exists to
                // detect them earlier). Refuse and sign out so the app is never
                // left authenticated as an account the user did not intend to
                // sign in to.
                firebaseAuth.signOut()
                Log.d("FIREBASE_AUTH", "result=ACCOUNT_EXISTS")
                return@withContext AuthResult.Error("ACCOUNT_EXISTS")
            }
        } catch (e: FirebaseAuthException) {
            if (e.errorCode == "ERROR_ACCOUNT_EXISTS_WITH_DIFFERENT_CREDENTIAL") {
                // A Google credential targeted an existing account authenticated
                // with a different provider (Email/Password). The credential call
                // FAILED here, so no provider mutation occurred - the collision
                // was rejected by Firebase itself.
                // Sign out just in case the SDK left a partial state.
                firebaseAuth.signOut()
                Log.d("FIREBASE_AUTH", "=== GOOGLE AUTH RESULT ===")
                Log.d("FIREBASE_AUTH", "flow=$flowType")
                Log.d("FIREBASE_AUTH", "result=EMAIL_COLLISION")
                Log.d("FIREBASE_AUTH", "errorCode=${e.errorCode}")
                // Defensive: SIGN_UP normally refuses collisions at the device-local
                // pre-check above (before Firebase is ever called). If a SIGN_UP
                // collision ever surfaces here via `signInWithCredential` (e.g. on
                // a non-authoritative domain), reject the account the same way -
                // never let Sign Up resolve to an existing account.
                if (flowType == FlowType.SIGN_UP) {
                    Log.d("FIREBASE_AUTH", "result=ACCOUNT_EXISTS")
                    if (isGoogleHostedEmail(extractEmailFromIdToken(idToken) ?: "")) {
                        return@withContext AuthResult.Error("ACCOUNT_EXISTS")
                    }
                    return@withContext AuthResult.EmailCollision(
                        "This email is already registered with Email/Password. " +
                            "Please log in using your email and password."
                    )
                }
                return@withContext AuthResult.EmailCollision(
                    "This email is already registered with Email/Password. Please log in using your email and password."
                )
            }
            Log.d("FIREBASE_AUTH", "=== GOOGLE AUTH RESULT ===")
            Log.d("FIREBASE_AUTH", "flow=$flowType")
            Log.d("FIREBASE_AUTH", "result=error")
            Log.d("FIREBASE_AUTH", "errorCode=${e.errorCode}")
            AuthResult.Error(mapFirebaseAuthError(e))
        } catch (e: Exception) {
            // If the failure happened after a successful sign-in (e.g. during
            // inspection), never leave the app signed in as a user it did not
            // intentionally sign in to.
            if (firebaseAuth.currentUser != null) {
                firebaseAuth.signOut()
            }
            Log.d("FIREBASE_AUTH", "=== GOOGLE AUTH RESULT ===")
            Log.d("FIREBASE_AUTH", "flow=$flowType")
            Log.d("FIREBASE_AUTH", "result=error")
            Log.d("FIREBASE_AUTH", "error=${e.message}")
            AuthResult.Error(e.message ?: "Google sign-in failed")
        }
    }

    /**
     * True when the given email lives on a Google-owned address space. Used only
     * to pick the message when an existing account blocks Google Sign Up: for
     * Google-hosted addresses the user is told to use Google Login (Google is the
     * authoritative sign-in for that address); for other domains the message
     * points at the Email/Password sign-in instead.
     */
    private fun isGoogleHostedEmail(email: String): Boolean =
        email.endsWith("@gmail.com", ignoreCase = true) ||
            email.endsWith("@googlemail.com", ignoreCase = true)

    /**
     * Extracts the email claim from a Google ID token (JWT) for logging purposes.
     * Does not log the full token. Returns null when the token cannot be parsed
     * (e.g. emulator-generated fake tokens) - this is non-fatal.
     */
    private fun extractEmailFromIdToken(idToken: String): String? {
        try {
            val parts = idToken.split(".")
            if (parts.size >= 2) {
                val payload = parts[1]
                val paddedPayload = if (payload.length % 4 != 0) {
                    payload + "=".repeat(4 - payload.length % 4)
                } else payload
                val decoded = android.util.Base64.decode(paddedPayload, android.util.Base64.URL_SAFE)
                val json = String(decoded)
                val emailStart = json.indexOf("\"email\":\"")
                if (emailStart != -1) {
                    val start = emailStart + 9
                    val end = json.indexOf("\"", start)
                    if (end != -1) {
                        return json.substring(start, end)
                    }
                }
            }
        } catch (e: Exception) {
            // Ignore parsing errors, just return null
        }
        return null
    }

    enum class FlowType { LOGIN, SIGN_UP }

    suspend fun signOut() {
        withContext(Dispatchers.IO) {
            firebaseAuth.signOut()
        }
    }

    /**
     * Captures the current user's Firebase ID token BEFORE any destructive auth
     * operation runs. The token is needed by the account-deletion guard: when
     * auth deletion succeeds, the SDK session is already gone, so the Firestore
     * purge must be driven over the Firestore REST API with this same token
     * (rules gate those paths on `request.auth.uid == userId`, which this token
     * still carries). The token is captured FIRST — never after — so a failed
     * auth delete CANNOT have already wiped user data (see §28 fix). Returns
     * null when there is nothing signed in or the token cannot be minted.
     */
    suspend fun captureIdToken(): String? = withContext(Dispatchers.IO) {
        return@withContext try {
            val currentUser = firebaseAuth.currentUser ?: return@withContext null
            currentUser.getIdToken(false).await()?.token
        } catch (e: Exception) {
            null
        }
    }

    suspend fun deleteCurrentUser(): AuthResult = withContext(Dispatchers.IO) {
        return@withContext try {
            val currentUser = firebaseAuth.currentUser ?: return@withContext AuthResult.Error("No user is currently signed in")
            val deletedUid = currentUser.uid
            val deletedEmail = currentUser.email
            currentUser.delete().await()
            // Purge this device's local auth evidence when the account is gone so
            // the same email/UID is IMMEDIATELY reusable: a user who deletes an
            // account and signs up again with the same email (without restarting
            // the app) must not be told the address is "already registered" by
            // stale in-memory/persisted records (password-account store, explicit
            // Google link, verification gate).
            if (!deletedEmail.isNullOrBlank()) {
                passwordAccounts.forget(deletedEmail)
                verificationGate.removePendingVerification(deletedEmail)
            }
            explicitGoogleLink.forget(deletedUid)
            AuthResult.Success(deletedUid, isNewUser = false)
        } catch (e: FirebaseAuthException) {
            if (e.errorCode == "ERROR_REQUIRES_RECENT_LOGIN") {
                AuthResult.Error("This operation requires recent authentication. Please sign out and sign in again before deleting your account.")
            } else {
                AuthResult.Error(mapFirebaseAuthError(e))
            }
        } catch (e: Exception) {
            AuthResult.Error(e.message ?: "Failed to delete account")
        }
    }

    suspend fun validateSession(): SessionValidationResult = withContext(Dispatchers.IO) {
        return@withContext try {
            val currentUser = firebaseAuth.currentUser
            if (currentUser == null) {
                SessionValidationResult.InvalidAccount
            } else {
                currentUser.reload().await()
                val userId = firebaseAuth.currentUser?.uid
                if (userId.isNullOrEmpty()) {
                    SessionValidationResult.InvalidAccount
                } else {
                    SessionValidationResult.Valid(userId)
                }
            }
        } catch (e: FirebaseAuthInvalidUserException) {
            firebaseAuth.signOut()
            SessionValidationResult.InvalidAccount
        } catch (e: FirebaseAuthException) {
            if (e.errorCode?.contains("NETWORK", ignoreCase = true) == true ||
                e.message?.contains("network", ignoreCase = true) == true) {
                SessionValidationResult.NetworkError
            } else {
                SessionValidationResult.InvalidAccount
            }
        } catch (e: Exception) {
            SessionValidationResult.NetworkError
        }
    }

    fun getCurrentUser(): String? = firebaseAuth.currentUser?.uid

    fun isAuthenticated(): Boolean = firebaseAuth.currentUser != null

    fun getCurrentEmail(): String? = firebaseAuth.currentUser?.email

    suspend fun getCurrentUserProviders(): List<String> = withContext(Dispatchers.IO) {
        return@withContext try {
            val currentUser = firebaseAuth.currentUser ?: return@withContext emptyList()
            currentUser.reload().await()
            currentUser.providerData.map { it.providerId }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Refreshes the authenticated user's state (reload) and returns whether the
     * current Firebase account's email address is verified. `false` is returned
     * on any failure (no user, network error, etc.) so the app NEVER treats an
     * unverifiable account as verified.
     */
    suspend fun isCurrentUserEmailVerified(): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            val currentUser = firebaseAuth.currentUser ?: return@withContext false
            currentUser.reload().await()
            currentUser.isEmailVerified
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Sends the email-address verification email to the authenticated user.
     * Returns true when the send succeeded. Non-destructive.
     */
    suspend fun sendVerificationEmail(): Boolean = withContext(Dispatchers.IO) {
        return@withContext sendVerificationEmailForCurrentUser()
    }

    private suspend fun sendVerificationEmailForCurrentUser(): Boolean {
        val user = firebaseAuth.currentUser ?: return false
        if (user.isEmailVerified) return true
        return try {
            user.sendEmailVerification().await()
            Log.d("FIREBASE_AUTH", "verificationEmail=sent email=${user.email}")
            true
        } catch (e: Exception) {
            Log.d("FIREBASE_AUTH", "verificationEmailError=${e.message}")
            false
        }
    }

    /**
     * Reconciles the device-local verification-gate record with the SERVER-side
     * truth (`emailVerified` on the authenticated user, after reload):
     *  - verified  -> remove any pending (stale) record for this email
     *  - unverified -> record the email as pending so an unauthenticated Google
     *    sign-in/up on this device is refused instead of overwriting `password`.
     * Returns the reloaded `emailVerified` value (false on any failure).
     */
    suspend fun syncVerificationGateState(): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            val currentUser = firebaseAuth.currentUser ?: return@withContext false
            currentUser.reload().await()
            val email = currentUser.email
            val verified = currentUser.isEmailVerified
            if (!email.isNullOrBlank()) {
                if (verified) {
                    verificationGate.removePendingVerification(email)
                } else {
                    verificationGate.addPendingVerification(email)
                }
            }
            verified
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Google Sign-In for LOGIN flow.
     * Refuses BEFORE `signInWithCredential` when the Google email is known to be a
     * password account on this device whose UID has NOT been explicitly linked via
     * Settings -> Link Google Account (`GOOGLE_NOT_LINKED`), so login never
     * implicitly upgrades the provider set. Locally-linked password accounts and
     * Google-only accounts proceed through the normal Firebase flow.
     */
    suspend fun signInWithGoogleForLogin(idToken: String): AuthResult =
        authenticateWithGoogle(idToken, FlowType.LOGIN)

    /**
     * Google Sign-Up flow: CREATE a brand-new account from the Google credential.
     * Refuses BEFORE any Firebase credential operation when this device knows the
     * Google email is a password account (`ACCOUNT_EXISTS` for Google-hosted
     * emails, `EmailCollision` otherwise) so an existing account is NEVER mutated
     * or linked by Sign Up. A defensive net in the SIGN_UP branch also refuses if
     * an existing account ever still surfaces. NEVER automatically links Google
     * to an existing account.
     */
    suspend fun signInWithGoogleForSignUp(idToken: String): AuthResult =
        authenticateWithGoogle(idToken, FlowType.SIGN_UP)

    /**
     * Explicitly links the Google provider to the currently authenticated user.
     *
     * This is the ONLY place `linkWithCredential` is used. It is invoked when the
     * user is already authenticated (Email/Password) and deliberately chooses
     * Settings -> Link Google Account. After success the SAME Firebase UID has
     * both `password` and `google.com` providers, and all application data stays
     * on that UID.
     */
    suspend fun linkGoogleAccount(idToken: String): AuthResult = withContext(Dispatchers.IO) {
        return@withContext try {
            val currentUser = firebaseAuth.currentUser ?: return@withContext AuthResult.Error("No user is currently signed in")

            // Verification gate: linking the Google provider is only offered to a
            // user whose email is verified. For an UNVERIFIED Email/Password
            // account, explicit linking could expose the same trusted-provider
            // overwrite of the password provider. Nothing is mutated here.
            try {
                currentUser.reload().await()
            } catch (re: Exception) {
                Log.d("FIREBASE_AUTH", "linkReloadForVerifiedWarning=${re.message}")
            }
            if (!currentUser.isEmailVerified) {
                Log.d("FIREBASE_AUTH", "=== LINK GOOGLE RESULT ===")
                Log.d("FIREBASE_AUTH", "uid=${currentUser.uid}")
                Log.d("FIREBASE_AUTH", "result=VERIFICATION_REQUIRED")
                return@withContext AuthResult.VerificationRequired(
                    currentUser.email ?: "",
                    "Your email address has not been verified yet. Verify your email before linking a Google account " +
                        "so your email/password sign-in is preserved."
                )
            }

            val providersBefore = currentUser.providerData.map { it.providerId }
            Log.d("FIREBASE_AUTH", "=== LINK GOOGLE START ===")
            Log.d("FIREBASE_AUTH", "uid=${currentUser.uid}")
            Log.d("FIREBASE_AUTH", "providersBefore=$providersBefore")

            val credential = GoogleAuthProvider.getCredential(idToken, null)
            val authResult = currentUser.linkWithCredential(credential).await()

            val userId = authResult.user?.uid ?: currentUser.uid
            val isNewUser = authResult.additionalUserInfo?.isNewUser ?: false

            // Reload is non-fatal: a successful link must not be reported as a
            // failure just because the follow-up reload hit a transient network error.
            try {
                currentUser.reload().await()
            } catch (re: Exception) {
                Log.d("FIREBASE_AUTH", "linkReloadWarning=${re.message}")
            }

            val providersAfter = currentUser.providerData.map { it.providerId }
            Log.d("FIREBASE_AUTH", "=== LINK GOOGLE RESULT ===")
            Log.d("FIREBASE_AUTH", "uid=$userId")
            Log.d("FIREBASE_AUTH", "providersAfter=$providersAfter")
            Log.d("FIREBASE_AUTH", "result=SUCCESS")

            // Pass 8: record the explicit Google link in the device-local store.
            // This is the ONLY place that record is written - strictly after a
            // successful `linkWithCredential`. It enables future Google LOGIN for
            // this UID on this device and is never written merely because a
            // Google login/UI/sign-up/email matched.
            explicitGoogleLink.recordLinkedGoogle(userId)
            Log.d("FIREBASE_AUTH", "localGoogleLink=recorded uid=$userId")

            AuthResult.Success(userId, isNewUser = isNewUser)
        } catch (e: FirebaseAuthException) {
            when (e.errorCode) {
                "ERROR_CREDENTIAL_ALREADY_IN_USE" -> {
                    Log.d("FIREBASE_AUTH", "=== LINK GOOGLE RESULT ===")
                    Log.d("FIREBASE_AUTH", "result=ERROR_CREDENTIAL_ALREADY_IN_USE")
                    AuthResult.Error("This Google account is already linked to another account.")
                }
                "ERROR_PROVIDER_ALREADY_LINKED" -> {
                    Log.d("FIREBASE_AUTH", "=== LINK GOOGLE RESULT ===")
                    Log.d("FIREBASE_AUTH", "result=ERROR_PROVIDER_ALREADY_LINKED")
                    AuthResult.Error("Google account is already linked to this account.")
                }
                else -> {
                    Log.d("FIREBASE_AUTH", "=== LINK GOOGLE RESULT ===")
                    Log.d("FIREBASE_AUTH", "result=error")
                    Log.d("FIREBASE_AUTH", "errorCode=${e.errorCode}")
                    AuthResult.Error(mapFirebaseAuthError(e))
                }
            }
        } catch (e: Exception) {
            Log.d("FIREBASE_AUTH", "=== LINK GOOGLE RESULT ===")
            Log.d("FIREBASE_AUTH", "result=error")
            Log.d("FIREBASE_AUTH", "error=${e.message}")
            AuthResult.Error(e.message ?: "Failed to link Google account")
        }
    }

    fun addAuthStateListener(callback: (isAuthenticated: Boolean) -> Unit) {
        firebaseAuth.addAuthStateListener { auth ->
            callback(auth.currentUser != null)
        }
    }

    private fun mapFirebaseAuthError(exception: FirebaseAuthException): String {
        return when (exception.errorCode) {
            "ERROR_INVALID_EMAIL" -> "Invalid email address"
            "ERROR_EMAIL_ALREADY_IN_USE" -> "An account with this email already exists. Please log in instead."
            "ERROR_WEAK_PASSWORD" -> "Password must be at least 6 characters"
            "ERROR_WRONG_PASSWORD" -> "Incorrect password"
            "ERROR_USER_NOT_FOUND" -> "No account found with this email"
            "ERROR_USER_DISABLED" -> "This account has been disabled"
            "ERROR_TOO_MANY_REQUESTS" -> "Too many login attempts. Try again later"
            "ERROR_NETWORK_REQUEST_FAILED" -> "Network error. Check your connection"
            "ERROR_ACCOUNT_EXISTS_WITH_DIFFERENT_CREDENTIAL" -> "This email is already registered with a different sign-in method"
            "ERROR_CREDENTIAL_ALREADY_IN_USE" -> "This sign-in method is already in use by another account"
            "ERROR_PROVIDER_ALREADY_LINKED" -> "This sign-in method is already linked to this account"
            "ERROR_OPERATION_NOT_ALLOWED" -> "This sign-in method is not enabled for this application"
            "ERROR_INVALID_CREDENTIAL" -> "The supplied authentication credential is incorrect"
            "ERROR_USER_CANCELLED" -> "Sign-in was cancelled"
            else -> exception.message ?: "Authentication failed"
        }
    }
}