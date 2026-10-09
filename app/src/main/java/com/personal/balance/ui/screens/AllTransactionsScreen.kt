package com.personal.balance.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.personal.balance.domain.EGPFormatter
import com.personal.balance.model.TransactionType
import com.personal.balance.viewmodel.AllTransactionsUiState
import com.personal.balance.viewmodel.AllTransactionsViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * All Transactions: the complete history of the signed-in user's income and
 * expenses, newest first. Data is loaded per-screen; an empty history shows an
 * empty state instead of a blank list.
 */
@Composable
fun AllTransactionsScreen(
    onBack: () -> Unit,
    viewModel: AllTransactionsViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .height(52.dp),
            ) {
                IconButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterStart)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
                Text(
                    text = "All Transactions",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
            when (val state = uiState) {
                is AllTransactionsUiState.Loading -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }

                is AllTransactionsUiState.Error -> Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 32.dp),
                    contentAlignment = Alignment.Center,
                ) { Text(state.message, color = MaterialTheme.colorScheme.error) }

                AllTransactionsUiState.Empty -> Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "No transactions yet. Add your first income or expense from the dashboard.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                is AllTransactionsUiState.Ready -> TransactionList(state.transactions)
            }
        }
    }
}

@Composable
private fun TransactionList(transactions: List<com.personal.balance.model.Transaction>) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
    ) {
        items(transactions, key = { it.id }) { transaction ->
            val date = Instant.ofEpochMilli(transaction.timestamp)
                .atZone(ZoneId.systemDefault())
                .toLocalDate()
                .format(DATE_FORMATTER)
            ListItem(
                headlineContent = {
                    Column {
                        Text(transaction.description.ifBlank { "General" }, maxLines = 1)
                        Text(
                            date,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                supportingContent = {
                    Text(
                        transaction.category.ifBlank { "General" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                trailingContent = {
                    Text(
                        (if (transaction.type == TransactionType.INCOME) "+" else "-") +
                            EGPFormatter.formatAmount(transaction.amount),
                        fontWeight = FontWeight.SemiBold,
                        color = if (transaction.type == TransactionType.INCOME)
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.error,
                    )
                },
            )
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

private val DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy")