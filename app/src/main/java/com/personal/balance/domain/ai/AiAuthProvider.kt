package com.personal.balance.domain.ai

/**
 * Abstraction over "is there an authenticated Firebase user?" for the AI layer.
 * No UID or email is ever passed from the UI; the backend derives the identity
 * from the current session.
 */
interface AiAuthProvider {
    fun isAuthenticated(): Boolean
}