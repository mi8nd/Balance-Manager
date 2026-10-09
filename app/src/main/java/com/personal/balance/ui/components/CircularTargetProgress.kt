package com.personal.balance.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun CircularTargetProgress(
    progress: Float,
    percentage: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.size(120.dp),
        contentAlignment = Alignment.Center
    ) {
        // Background ring (full circle)
        CircularProgressIndicator(
            progress = 1f,
            modifier = Modifier.size(120.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            strokeWidth = 10.dp,
            trackColor = MaterialTheme.colorScheme.background
        )

        // Foreground progress ring
        CircularProgressIndicator(
            progress = progress.coerceIn(0f, 1f),
            modifier = Modifier.size(120.dp),
            color = MaterialTheme.colorScheme.primary,
            strokeWidth = 10.dp,
            trackColor = MaterialTheme.colorScheme.background
        )

        // Centered percentage text
        Text(
            text = percentage,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
    }
}
