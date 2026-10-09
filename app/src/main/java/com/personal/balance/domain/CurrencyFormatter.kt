package com.personal.balance.domain

import java.text.NumberFormat
import java.util.Currency
import java.util.Locale

object CurrencyFormatter {

    // Safely format minor units to currency string
    // e.g., 2500 cents -> "$25.00"
    fun formatAmount(amountInMinorUnits: Long, currencyCode: String): String {
        return try {
            val currency = Currency.getInstance(currencyCode)
            val fractionDigits = currency.defaultFractionDigits
            
            // Adjust the amount by fractional digits 
            val divisor = Math.pow(10.0, fractionDigits.toDouble())
            val amountValue = amountInMinorUnits / divisor
            
            val format = NumberFormat.getCurrencyInstance(Locale.getDefault())
            format.currency = currency
            format.format(amountValue)
        } catch (e: Exception) {
            // Safe fallback if currency code is invalid or formatting fails
            "$ $amountInMinorUnits" // rudimentary fallback
        }
    }
    
    // Validates and parses user input string (e.g. "25.00") back into minor units
    fun parseInputToMinorUnits(input: String, currencyCode: String): Long? {
        if (input.isBlank()) return null
        
        return try {
            val currency = Currency.getInstance(currencyCode)
            val fractionDigits = currency.defaultFractionDigits
            val multiplier = Math.pow(10.0, fractionDigits.toDouble())
            
            val cleanInput = input.replace(",", ".").trim()
            val parsedDouble = cleanInput.toDoubleOrNull() ?: return null
            
            if (parsedDouble < 0) return null // Prevent negative entry
            
            (parsedDouble * multiplier).toLong()
        } catch (e: Exception) {
            null
        }
    }
}
