package com.personal.balance.model

data class UserConfig(
    val userId: String = "",
    val onboardingCompleted: Boolean = false,
    val currentBalance: Long = 0L,         // Stored in piastres
    val targetAmount: Long = 0L,           // Stored in piastres
    val targetName: String = "",
    val targetEnabled: Boolean = false,
    val targetCompleted: Boolean = false,
    val targetAcknowledged: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)
