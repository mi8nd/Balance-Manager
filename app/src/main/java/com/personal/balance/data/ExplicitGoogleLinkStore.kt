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
 * Device-local record of the Firebase UIDs for which THIS app has EXPLICITLY
 * linked the Google provider, via the Settings -> Link Google Account flow
 * (`linkWithCredential` in [FirebaseAuthRepository.linkGoogleAccount]).
 *
 * Purpose: Google LOGIN must never implicitly upgrade an Email/Password account.
 * The app's policy is that `google.com` may only join a password account through
 * the explicit Settings linking flow. Before Google LOGIN calls
 * `signInWithCredential`, the app resolves the Google email to this device's
 * known password-account UID ([PasswordAccountStore.getUidForEmail]); if that UID
 * is NOT recorded here, Google LOGIN is refused (`GOOGLE_NOT_LINKED`) with no
 * Firebase operation, so the provider set is never changed by a login attempt.
 *
 * IMPORTANT SECURITY/ARCHITECTURE LIMITATION (per approved design):
 *  - This is a DEVICE-LOCAL record. It cannot prove whether Google was linked on
 *    a DIFFERENT device, and it is NOT a server-authoritative provider query.
 *    If Google was linked server-side on another device but this device never
 *    linked it locally (and never saw the server provider state), Google LOGIN
 *    for that account is REFUSED here until a local explicit link exists. This
 *    is a deliberate trade-off: the app must not implicitly attach Google just
 *    because the user picked a Google account.
 *  - It may become stale if Google is linked/unlinked outside Firebase; the app
 *    never auto-removes it (no cleanup/migration logic).
 *  - Written to STRICTLY only on explicit `linkWithCredential` success. It is
 *    NOT written merely because a Google login/UI/sign-up/email matched.
 *  - Fail-closed: [isGoogleLinked] returns true only when a positive local record
 *    exists. On DataStore read failure it returns false, which REFUSES Google
 *    LOGIN for a locally-known password account - the safe side of the app's
 *    "never implicitly upgrade" invariant.
 */
class ExplicitGoogleLinkStore {

    private val memoryLinked = Collections.synchronizedSet(mutableSetOf<String>())

    suspend fun recordLinkedGoogle(uid: String) {
        val key = normalize(uid)
        if (key.isEmpty()) return
        synchronized(memoryLinked) { memoryLinked.add(key) }
        val persisted = readPersisted()
        if (key !in persisted) {
            writePersisted(persisted + key)
        }
    }

    suspend fun isGoogleLinked(uid: String): Boolean {
        val key = normalize(uid)
        if (key.isEmpty()) return false
        synchronized(memoryLinked) {
            if (memoryLinked.contains(key)) return true
        }
        val persisted = readPersisted()
        val found = key in persisted
        if (found) {
            synchronized(memoryLinked) { memoryLinked.add(key) }
        }
        return found
    }

    /**
     * Forgets the explicit-link record for a UID on this device. Called when the
     * user deletes their account so a deleted account can never influence later
     * sign-in decisions.
     */
    suspend fun forget(uid: String) {
        val key = normalize(uid)
        if (key.isEmpty()) return
        synchronized(memoryLinked) { memoryLinked.remove(key) }
        val persisted = readPersisted()
        if (key in persisted) {
            writePersisted(persisted - key)
        }
    }

    private fun normalize(uid: String): String = uid.trim()

    private fun appContext(): Context? = try {
        FirebaseApp.getInstance().applicationContext
    } catch (e: Exception) {
        null
    }

    private suspend fun readPersisted(): Set<String> {
        val ctx = appContext() ?: return emptySet()
        return runCatching {
            val prefs = ctx.googleLinkDataStore.data.first()
            prefs[LINKED_GOOGLE_UIDS_KEY] ?: emptySet()
        }.getOrDefault(emptySet())
    }

    private suspend fun writePersisted(uids: Set<String>) {
        val ctx = appContext() ?: return
        runCatching {
            ctx.googleLinkDataStore.edit { it[LINKED_GOOGLE_UIDS_KEY] = uids }
        }
    }

    companion object {
        private val Context.googleLinkDataStore: DataStore<Preferences> by preferencesDataStore(name = "explicit_google_links")
        private val LINKED_GOOGLE_UIDS_KEY = stringSetPreferencesKey("explicitly_linked_google_uids")
    }
}