package com.personal.balance.data

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression test for the account-reuse bug: after a user deletes their account
 * and immediately signs up again with the SAME email (without a process restart),
 * the device-local auth stores must not report the email/UID as still registered.
 *
 * The stores keep an in-memory mirror that survives the app's lifetime; on a JVM
 * unit test there is no FirebaseApp/DataStore, so every read path falls back to
 * that in-memory state — exactly reproducing the "no restart" scenario this bug
 * is about.
 */
class EmailAccountReuseTest {

    private val email = "user@example.com"
    private val oldUid = "uid_old_deleted"
    private val newUid = "uid_new_created"

    @Test
    fun deletedEmailCanBeReSignUpWithoutRestart() = runTest {
        val passwordAccounts = PasswordAccountStore()

        passwordAccounts.record(email, oldUid)
        assertTrue(passwordAccounts.isKnownPasswordAccount(email))
        assertEquals(oldUid, passwordAccounts.getUidForEmail(email))

        // Account deletion purges the device-local evidence (see
        // FirebaseAuthRepository.deleteCurrentUser).
        passwordAccounts.forget(email)
        assertFalse(passwordAccounts.isKnownPasswordAccount(email))
        assertNull(passwordAccounts.getUidForEmail(email))

        // Re-sign-up with the same email, same process: must succeed just like a
        // fresh address, and the mapping must now point at the NEW uid.
        passwordAccounts.record(email, newUid)
        assertTrue(passwordAccounts.isKnownPasswordAccount(email))
        assertEquals(newUid, passwordAccounts.getUidForEmail(email))
    }

    @Test
    fun deletedUidClearsExplicitGoogleLinkWithoutRestart() = runTest {
        val links = ExplicitGoogleLinkStore()

        links.recordLinkedGoogle(oldUid)
        assertTrue(links.isGoogleLinked(oldUid))

        links.forget(oldUid)
        assertFalse(links.isGoogleLinked(oldUid))
    }

    @Test
    fun deletedEmailClearsVerificationGateWithoutRestart() = runTest {
        val gate = VerificationGateStore()

        gate.addPendingVerification(email)
        assertTrue(gate.isPendingVerification(email))

        gate.removePendingVerification(email)
        assertFalse(gate.isPendingVerification(email))
    }

    @Test
    fun reSignUpAfterDeleteIsNotBlockedAsGoogleAccountExists() = runTest {
        // The Google Sign Up pre-check refuses when the device knows the email
        // is still a password account (PasswordAccountStore.isKnownPasswordAccount).
        // After deletion + re-sign-up the same email must not be considered a
        // pre-existing password account the moment the new account is created.
        val passwordAccounts = PasswordAccountStore()

        passwordAccounts.record(email, oldUid)
        passwordAccounts.forget(email)
        passwordAccounts.record(email, newUid)

        assertTrue(passwordAccounts.isKnownPasswordAccount(email))
        // The stored uid is the NEW account; an old stale UID must never surface
        // (it would ruin the GOOGLE_NOT_LINKED login scoping on the old identity).
        assertEquals(newUid, passwordAccounts.getUidForEmail(email))
    }
}