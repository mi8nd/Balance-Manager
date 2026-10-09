package com.personal.balance.domain

import java.text.NumberFormat
import java.util.Locale

object EGPFormatter {

    // Safely format piastres (minor units) to currency string
    // e.g., 2500 piastres -> "25.00 EGP"
    fun formatAmount(piastres: Long): String {
        val amountValue = piastres / 100.0
        val format = NumberFormat.getNumberInstance(Locale.US)
        format.minimumFractionDigits = 2
        format.maximumFractionDigits = 2

        return "${format.format(amountValue)} EGP"
    }

    // Validates and parses user input string (e.g. "25.5") back into piastres
    fun parseInputToPiastres(input: String): Long? {
        if (input.isBlank()) return null

        return try {
            val cleanInput = input.replace(",", ".").trim()
            val parsedDouble = cleanInput.toDoubleOrNull() ?: return null

            if (parsedDouble < 0) return null // Prevent negative entry

            Math.round(parsedDouble * 100)
        } catch (e: Exception) {
            null
        }
    }
}
