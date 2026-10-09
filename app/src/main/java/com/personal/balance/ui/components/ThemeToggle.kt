package com.personal.balance.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.personal.balance.domain.ThemeMode

@Composable
fun ThemeToggle(
    currentTheme: ThemeMode,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    IconButton(
        onClick = onToggle,
        modifier = modifier
    ) {
        Icon(
            imageVector = if (currentTheme == ThemeMode.LIGHT) Icons.Filled.DarkMode else Icons.Filled.LightMode,
            contentDescription = "Toggle theme"
        )
    }
}
