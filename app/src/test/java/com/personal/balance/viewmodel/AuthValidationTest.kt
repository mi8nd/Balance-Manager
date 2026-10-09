package com.personal.balance.viewmodel

import org.junit.Assert.*
import org.junit.Test

class AuthValidationTest {

    @Test
    fun validateEmail_validEmail() {
        assertTrue(isValidEmail("user@example.com"))
    }

    @Test
    fun validateEmail_invalidEmail_noAt() {
        assertFalse(isValidEmail("userexample.com"))
    }

    @Test
    fun validateEmail_invalidEmail_noDomain() {
        assertFalse(isValidEmail("user@"))
    }

    @Test
    fun validateEmail_invalidEmail_noDot() {
        assertFalse(isValidEmail("user@examplecom"))
    }

    @Test
    fun validateEmail_validEmail_subdomain() {
        assertTrue(isValidEmail("user@mail.example.com"))
    }

    @Test
    fun validatePassword_validPassword() {
        assertTrue(isValidPassword("password123"))
    }

    @Test
    fun validatePassword_tooShort() {
        assertFalse(isValidPassword("pass"))
    }

    @Test
    fun validatePassword_empty() {
        assertFalse(isValidPassword(""))
    }

    @Test
    fun validatePasswordMatch_match() {
        assertTrue(passwordsMatch("password123", "password123"))
    }

    @Test
    fun validatePasswordMatch_noMatch() {
        assertFalse(passwordsMatch("password123", "password456"))
    }

    private fun isValidEmail(email: String): Boolean {
        return email.matches(Regex("^[A-Za-z0-9+_.-]+@([A-Za-z0-9.-]+\\.[A-Za-z]{2,})$"))
    }

    private fun isValidPassword(password: String): Boolean {
        return password.length >= 6
    }

    private fun passwordsMatch(password: String, confirmPassword: String): Boolean {
        return password == confirmPassword
    }
}
