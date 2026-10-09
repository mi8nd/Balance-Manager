package com.personal.balance.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.firebase.FirebaseApp
import java.util.Collections
import kotlinx.coroutines.flow.first

/**
 * Device-local record of the emails for which THIS device has interacted with an
 * Email/Password account (created it through Sign Up, or signed into it with a
 * working password). It is written for every successful Email/Password sign-up
 * and sign-in on this device and is NOT cleared when `emailVerified` changes.
 * Each email is associated with the Firebase UID of the account THIS device
 * created/used, so a pre-authentication flow (which only knows the Google email
 * from the ID token) can resolve it to the locally-known UID.
 *
 * Purpose: the trusted-provider bug on Google-hosted addresses means that even a
 * VERIFIED Email/Password account is silently linked with `google.com` (and its
 * `emailVerified` is flipped) when a Google credential is presented to
 * `signInWithCredential`. The pre-authentication checks use this store:
 *  - Google SIGN UP must never mutate an existing account, so if this device
 *    knows the Google email is a password account, Sign Up refuses IMMEDIATELY
 *    (`ACCOUNT_EXISTS`) - no Firebase credential operation is ever performed.
 *  - Google LOGIN must never implicitly upgrade a password account, so the app
 *    first resolves the Google email to this device's known UID; if that UID is
 *    NOT in the explicit Google-link record ([ExplicitGoogleLinkStore]), Google
 *    LOGIN is refused before `signInWithCredential` (`GOOGLE_NOT_LINKED`).
 *
 * Scope & limits (intentional, per the approved design):
 *  - Per-device only, like the verification gate. An account created on a
 *    different device (or outside the app) is not in this store. There is no
 *    client-only, non-enumerating signal that can detect those accounts before
 *    signing in, so they remain outside the app's control (same limitation as the
 *    verified-email gate).
 *  - Records only accounts the app was directly involved with; no enumeration of
 *    Firebase Auth, no Firestore reads, no provider-discovery APIs.
 *  - Fail-open on the "known password account?" predicate: if the DataStore
 *    cannot be read, [isKnownPasswordAccount]/[getUidForEmail] return false/null
 *    and the Google operation proceeds (matching the verification gate).
 *
 * The authoritative "does this email have a password account" truth always lives
 * in Firebase Auth; this store is only a pre-authentication heuristic that closes
 * the Google-SIGN_UP-mutates / Google-LOGIN-implicitly-upgrades windows for
 * accounts this device knows about.
 */
class PasswordAccountStore {

    private val memoryEmails = Collections.synchronizedSet(mutableSetOf<String>())
    private val memoryUidByEmail = Collections.synchronizedMap(mutableMapOf<String, String>())

    suspend fun record(email: String, uid: String) {
        val key = normalize(email)
        if (key.isEmpty() || uid.isBlank()) return
        synchronized(memoryEmails) { memoryEmails.add(key) }
        synchronized(memoryUidByEmail) { memoryUidByEmail[key] = uid }
        val persisted = readPersisted()
        if (key !in persisted) {
            writePersisted(persisted + key)
        }
        writePersistedUid(key, uid)
    }

    suspend fun isKnownPasswordAccount(email: String): Boolean {
        val key = normalize(email)
        if (key.isEmpty()) return false
        synchronized(memoryEmails) {
            if (memoryEmails.contains(key)) return true
        }
        val persisted = readPersisted()
        val found = key in persisted
        if (found) {
            synchronized(memoryEmails) { memoryEmails.add(key) }
        }
        return found
    }

    /**
     * Returns the locally-known Firebase UID for an email, or null when this
     * device has no password-account evidence for it. Used to scope the Google
     * LOGIN link check to the exact UID instead of building an email-based trust
     * mechanism.
     */
    suspend fun getUidForEmail(email: String): String? {
        val key = normalize(email)
        if (key.isEmpty()) return null
        synchronized(memoryUidByEmail) {
            memoryUidByEmail[key]?.let { return it }
        }
        val uid = readPersistedUid(key)
        if (uid != null) {
            synchronized(memoryUidByEmail) { memoryUidByEmail[key] = uid }
        }
        return uid
    }

    /**
     * Forgets this email's password-account record on THIS device. Called when
     * the user deletes their account: the email must be reusable immediately
     * (sign up again with the same address) without the stale in-memory/persisted
     * record making the app believe the address is still registered.
     */
    suspend fun forget(email: String) {
        val key = normalize(email)
        if (key.isEmpty()) return
        synchronized(memoryEmails) { memoryEmails.remove(key) }
        synchronized(memoryUidByEmail) { memoryUidByEmail.remove(key) }
        val persisted = readPersisted()
        if (key in persisted) {
            writePersisted(persisted - key)
        }
        writePersistedRemovedUid(key)
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
            val prefs = ctx.passwordAccountDataStore.data.first()
            prefs[KNOWN_EMAILS_KEY] ?: emptySet()
        }.getOrDefault(emptySet())
    }

    private suspend fun readPersistedUid(email: String): String? {
        val ctx = appContext() ?: return null
        return runCatching {
            val prefs = ctx.passwordAccountDataStore.data.first()
            prefs[uidKeyFor(email)]
        }.getOrNull()
    }

    private suspend fun writePersistedUid(email: String, uid: String) {
        val ctx = appContext() ?: return
        runCatching {
            ctx.passwordAccountDataStore.edit { it[uidKeyFor(email)] = uid }
        }
    }

    private suspend fun writePersistedRemovedUid(email: String) {
        val ctx = appContext() ?: return
        runCatching {
            ctx.passwordAccountDataStore.edit { it.remove(uidKeyFor(email)) }
        }
    }

    private suspend fun writePersisted(emails: Set<String>) {
        val ctx = appContext() ?: return
        runCatching {
            ctx.passwordAccountDataStore.edit { it[KNOWN_EMAILS_KEY] = emails }
        }
    }

    companion object {
        private val Context.passwordAccountDataStore: DataStore<Preferences> by preferencesDataStore(name = "password_accounts")
        private val KNOWN_EMAILS_KEY = stringSetPreferencesKey("known_password_account_emails")
        private fun uidKeyFor(email: String): Preferences.Key<String> =
            stringPreferencesKey("known_password_account_uid_by_email_$email")
    }
}