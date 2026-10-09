package com.personal.balance.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.personal.balance.ui.screens.assistant.AssistantComposer
import com.personal.balance.ui.screens.assistant.AssistantEmptyState
import com.personal.balance.ui.screens.assistant.AssistantMessageItem
import com.personal.balance.ui.screens.assistant.AssistantTopBar
import com.personal.balance.ui.screens.assistant.AssistantTypingIndicator
import com.personal.balance.ui.screens.assistant.ErrorMessageRow
import com.personal.balance.ui.screens.assistant.UserMessage
import com.personal.balance.viewmodel.ChatMessage
import com.personal.balance.viewmodel.FinancialAssistantViewModel

/**
 * Smooth enter/exit for the AI typing row. Visibility is bound to request state
 * (FinancialAssistantViewModel running flag) in the screen below; these transitions
 * only shape *how* it appears/disappears. Timings are modest so the dots neither
 * pop in nor linger after a response.
 */
private val typingIndicatorEnter: EnterTransition =
    fadeIn(animationSpec = tween(durationMillis = 180)) +
        scaleIn(animationSpec = tween(durationMillis = 180), initialScale = 0.9f)

private val typingIndicatorExit: ExitTransition =
    fadeOut(animationSpec = tween(durationMillis = 220)) +
        scaleOut(animationSpec = tween(durationMillis = 220), targetScale = 0.9f)

/**
 * AI Financial Assistant (rebuilt from scratch, Task 9).
 *
 * The conversation is the entire experience - there is no persistent insight
 * dashboard and no AI hub. Rendering is a single reversed list feed:
 *  - `reverseLayout = true` pins the newest message at the bottom edge, so a new
 *    user question and its follow-on replies dock themselves with no scroll
 *    code, while a manual scroll up is never yanked back by reveal ticks.
 *  - Each assistant reply owns its reveal as local state ([AssistantReplyContent]),
 *    so a typewriter frame recomposes just that item, not the whole screen.
 *  - Content is prose-first; structured insight cards hang directly beneath the
 *    reply that produced them via a slim accent rule.
 *
 * The palette comes from the existing Material theme (background, surface,
 * primary, surfaceVariant, roles). No old assistant component is reused.
 */
@Composable
fun FinancialAssistantScreen(
    onBack: () -> Unit,
    viewModel: FinancialAssistantViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    val listState = rememberLazyListState()
    LaunchedEffect(uiState.messages.size, uiState.running) {
        // reverseLayout keeps the newest message at index 0 (bottom edge), but
        // appending at that edge while the IME resizes the viewport leaves the
        // freshly added bubble off-screen. Re-dock on every conversation change
        // so the newest message is always revealed; only an in-flight drag is
        // left alone (the user owns the gesture). Mirrors the ready dock of the
        // reversed feed while removing the IME blind spot.
        if (uiState.messages.isEmpty()) return@LaunchedEffect
        if (!listState.isScrollInProgress) {
            listState.scrollToItem(0)
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { AssistantTopBar(onBack = onBack) },
        bottomBar = {
            AssistantComposer(
                value = uiState.input,
                onValueChange = viewModel::onInputChange,
                onSend = viewModel::ask,
                running = uiState.running,
            )
        },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(paddingValues),
        ) {
            if (uiState.messages.isEmpty()) {
                AssistantEmptyState(
                    suggestionChips = FinancialAssistantViewModel.SUGGESTIONS,
                    onSuggestionClick = viewModel::askSuggestion,
                    modifier = Modifier.weight(1f),
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    reverseLayout = true,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    // The typing row is always present in the list so AnimatedVisibility can actually
                    // play its exit transition; visibility is driven purely by request
                    // state (uiState.running) - never by an artificial timeout. When not
                    // running it collapses to zero size, and when running toggles off
                    // (success, failure, or cancel) the dots fade/scale out smoothly.
                    item(key = "typing") {
                        AnimatedVisibility(
                            visible = uiState.running,
                            enter = typingIndicatorEnter,
                            exit = typingIndicatorExit,
                        ) {
                            AssistantTypingIndicator()
                        }
                    }
                    items(
                        items = uiState.messages.asReversed(),
                        key = { it.id },
                    ) { message ->
                        when (message) {
                            is ChatMessage.User -> UserMessage(text = message.text)

                            is ChatMessage.Assistant -> AssistantMessageItem(message = message)

                            is ChatMessage.Error -> ErrorMessageRow(
                                message = message,
                                onRetry = { viewModel.retryAfterError(message.id) },
                            )
                        }
                    }
                }
            }
        }
    }
}