package com.personal.balance.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.firebase.FirebaseApp
import java.util.Collections
import kotlinx.coroutines.flow.first

/**
 * Device-local record of the emails for which THIS app created an Email/Password
 * account whose address has NOT been confirmed yet (`emailVerified == false`).
 *
 * Purpose: this is the ONE client side signal (apart from Firebase's
 * provider-discovery APIs, which this app deliberately does not use, and apart
 * from Firestore reads, which the security rules restrict to the document
 * owner) that lets an UNAUTHENTICATED Google sign-in/up attempt detect that the
 * Google email belongs to an unverified Email/Password account created through
 * the app. Without it, `signInWithCredential` lets Firebase silently replace
 * the password provider with `google.com` on authoritative emails (trusted
 * provider precedence) before the app ever gets a chance to react.
 *
 * Scope & limits (intentional, per the approved design):
 *  - Per-device only. The record is written when the account is created or
 *    signed into through this device. A Google sign-in on a different device
 *    than the one that created an unverified Email/Password account is NOT
 *    protected by this store. Cross-device protection would require either a
 *    Firestore email lookup (rejected: leak/email-enumeration surface and the
 *    current rules only allow owner reads) or an Auth provider-discovery API
 *    (rejected).
 *  - It records only accounts the app was directly involved with; there is no
 *    enumeration of Firebase Auth.
 *  - Fail-open: if the DataStore cannot be read, [isPendingVerification]
 *    returns false and Google sign-in is allowed. A stale "pending" record for
 *    an email that was verified on ANOTHER device self-heals the first time the
 *    user signs in with the password on this device or taps "I have verified"
 *    on the verify-email gate ([removePendingVerification]).
 *
 * The authoritative source of truth for "is this account verified" always
 * remains Firebase's own `emailVerified` on the authenticated FirebaseUser;
 * this store is only a pre-authentication heuristic that closes the
 * unauthenticated Google-login takeover window for accounts this device knows
 * about.
 */
class VerificationGateStore {

    private val memoryPending = Collections.synchronizedSet(mutableSetOf<String>())

    suspend fun addPendingVerification(email: String) {
        val key = normalize(email)
        if (key.isEmpty()) return
        synchronized(memoryPending) { memoryPending.add(key) }
        val persisted = readPersisted()
        if (key !in persisted) {
            writePersisted(persisted + key)
        }
    }

    suspend fun removePendingVerification(email: String) {
        val key = normalize(email)
        if (key.isEmpty()) return
        synchronized(memoryPending) { memoryPending.remove(key) }
        val persisted = readPersisted()
        if (key in persisted) {
            writePersisted(persisted - key)
        }
    }

    suspend fun isPendingVerification(email: String): Boolean {
        val key = normalize(email)
        if (key.isEmpty()) return false
        synchronized(memoryPending) {
            if (memoryPending.contains(key)) return true
        }
        val persisted = readPersisted()
        val found = key in persisted
        if (found) {
            synchronized(memoryPending) { memoryPending.add(key) }
        }
        return found
    }

    private fun normalize(email: String): String = email.trim().lowercase()

    private fun appContext(): Context? = try {
        FirebaseApp.getInstance().applicationContext
    } catch (e: Exception) {
        null
    }

    private suspend fun readPersisted(): Set<String> {
        val ctx = appContext() ?: return emptySet()
        return runCatching {
            val prefs = ctx.gateDataStore.data.first()
            prefs[PENDING_EMAILS_KEY] ?: emptySet()
        }.getOrDefault(emptySet())
    }

    private suspend fun writePersisted(emails: Set<String>) {
        val ctx = appContext() ?: return
        runCatching {
            ctx.gateDataStore.edit { it[PENDING_EMAILS_KEY] = emails }
        }
    }

    companion object {
        private val Context.gateDataStore: DataStore<Preferences> by preferencesDataStore(name = "verification_gate")
        private val PENDING_EMAILS_KEY = stringSetPreferencesKey("pending_verification_emails")
    }
}