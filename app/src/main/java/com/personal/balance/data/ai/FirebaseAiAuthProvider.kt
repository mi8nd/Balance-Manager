package com.personal.balance.data.ai

import com.google.firebase.auth.FirebaseAuth
import com.personal.balance.domain.ai.AiAuthProvider

/**
 * Identity for the AI layer comes from the existing Firebase session. There is
 * no second authentication system and no UID/email accepted as input.
 */
class FirebaseAiAuthProvider(
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
) : AiAuthProvider {

    override fun isAuthenticated(): Boolean = auth.currentUser != null
}