package com.personal.balance.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.personal.balance.domain.EGPFormatter
import com.personal.balance.domain.finance.Importance
import com.personal.balance.domain.insights.FinancialInsightsSnapshot
import com.personal.balance.viewmodel.DashboardInsightUiState

/**
 * The "AI Insights" card embedded on the dashboard. Shows a deterministic
 * one-line summary, the top insight and top recommendation from the AI analysis
 * (together with a stale banner when the user's data changed since), and
 * affordances to refresh or open the AI Financial Assistant (where the same
 * analysis can be explored conversationally). Never consumes AI budget by
 * itself — only explicit refresh/ensureLoaded on the shared ViewModel drives the
 * single analysis call.
 */
@Composable
internal fun DashboardInsightsSection(
    state: DashboardInsightUiState,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    onOpenAll: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Filled.Lightbulb,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "AI Insights",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = onRefresh,
                    enabled = !refreshing,
                ) {
                    if (refreshing) {
                        CircularProgressIndicator(
                            modifier = Modifier.requiredSize(18.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh insights")
                    }
                }
            }

            when (state) {
                is DashboardInsightUiState.Idle -> SectionHint("Loading insights…")

                is DashboardInsightUiState.Loading -> SectionHint("Loading insights…")

                is DashboardInsightUiState.NoData -> SectionHint(
                    "Add a few transactions to start seeing AI insights here.",
                )

                is DashboardInsightUiState.InsufficientData -> SectionHint(
                    "Keep adding transactions — insights appear once there's enough activity to analyze.",
                )

                is DashboardInsightUiState.Error -> {
                    SectionHint(state.message)
                    if (state.stale) {
                        StaleBanner()
                    }
                }

                is DashboardInsightUiState.Ready -> {
                    if (state.stale) {
                        StaleBanner()
                    }
                    QuickSummary(state.snapshot)
                    state.insights.firstOrNull()?.let { insight ->
                        InsightRow(importance = insight.importance, title = insight.title, body = insight.explanation)
                    }
                    state.recommendations.firstOrNull()?.let { recommendation ->
                        InsightRow(importance = null, title = recommendation.title, body = recommendation.explanation, accent = MaterialTheme.colorScheme.primary)
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(onClick = onOpenAll) { Text("Open in AI Financial Assistant") }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun StaleBanner() {
    Text(
        text = "Your data changed · refresh for fresh insights.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.tertiary,
    )
}

@Composable
private fun QuickSummary(snapshot: FinancialInsightsSnapshot) {
    val ti = snapshot.intelligence
    val signed = (if (snapshot.netChangeCurrent > 0) "+" else "") + EGPFormatter.formatAmount(snapshot.netChangeCurrent)
    val netColor =
        if (snapshot.netChangeCurrent >= 0L) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.error
    Text(
        text = "${ti.currentLabel}: income ${EGPFormatter.formatAmount(ti.currentIncomeTotal)} · " +
            "expenses ${EGPFormatter.formatAmount(ti.currentExpenseTotal)}",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(2.dp))
    Text(
        text = "Net change: $signed",
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.SemiBold,
        color = netColor,
    )
}

@Composable
private fun InsightRow(
    importance: Importance?,
    title: String,
    body: String,
    accent: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (importance != null) {
            val color = when (importance) {
                Importance.HIGH -> MaterialTheme.colorScheme.error
                Importance.MEDIUM -> MaterialTheme.colorScheme.tertiary
                Importance.LOW -> MaterialTheme.colorScheme.primary
                Importance.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            Box(
                modifier = Modifier
                    .requiredSize(8.dp)
                    .background(color, CircleShape),
            )
            Spacer(Modifier.width(8.dp))
        } else {
            Icon(
                imageVector = Icons.Filled.Lightbulb,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.requiredSize(16.dp),
            )
            Spacer(Modifier.width(8.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}