package com.personal.balance.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.personal.balance.domain.ai.AiResult
import com.personal.balance.viewmodel.DebugAiSmokeUiState
import com.personal.balance.viewmodel.DebugAiSmokeViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugAiSmokeScreen(
    onBack: () -> Unit,
    viewModel: DebugAiSmokeViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AI Smoke Test") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                "Development-only test. Verifies on this device: authenticated " +
                    "user -> App Check -> Firebase AI Logic -> gemini-3.1-flash-lite.",
                style = MaterialTheme.typography.bodyMedium
            )
            Button(
                onClick = viewModel::runSmokeTest,
                enabled = uiState !is DebugAiSmokeUiState.Running,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                Text(if (uiState is DebugAiSmokeUiState.Running) "Running..." else "Run AI Smoke Test")
            }

            when (val state = uiState) {
                DebugAiSmokeUiState.Idle -> Text(
                    text = "Not run yet.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                DebugAiSmokeUiState.Running -> Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }

                is DebugAiSmokeUiState.Done -> SmokeResultCard(state.result)
            }
        }
    }
}

@Composable
private fun SmokeResultCard(result: AiResult) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            when (result) {
                is AiResult.Success -> {
                    Text(
                        text = "SUCCESS - model replied",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(result.text, style = MaterialTheme.typography.bodyLarge)
                    result.tokenCount?.let {
                        Text(
                            text = "Tokens: $it",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                is AiResult.Error -> {
                    Text(
                        text = "FAILED - ${result.userSafeMessage}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.error
                    )
                    if (result.diagnostic.isNotBlank()) {
                        Text(
                            text = "Diagnostic: ${result.diagnostic}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}