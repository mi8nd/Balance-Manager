package com.personal.balance.ui.screens.assistant

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import kotlinx.coroutines.delay

/**
 * Character-by-character reveal of a completed assistant reply (there is no
 * streaming backend; the full response arrives at once and is then revealed).
 *
 * The reveal is deterministic and length-scaled: longer answers animate faster
 * per character so the total duration stays bounded. Reveal state is keyed on
 * [fullText] (new messages reset cleanly) but saved in [rememberSaveable], so
 * scrolling an item off-screen and back resumes from where it left off instead
 * of restarting the whole reveal.
 *
 * Performance contract: ALL reveal state lives inside the individual message
 * item ([AssistantReplyContent]) — no parent/screen state is touched on a tick,
 * so a frame of the typewriter never recomposes the rest of the conversation.
 * The feed is a `reverseLayout` list, so the revealed message stays anchored at
 * the bottom edge without any scroll work per character.
 */

/** Characters revealed per tick, scaled so ~45 ticks cover the whole text. */
internal fun revealStep(totalLength: Int): Int =
    (totalLength / 45).coerceIn(1, totalLength.coerceAtLeast(1))

/** Milliseconds between ticks: short answers tick slowly, long ones fast. */
internal fun revealTickMillis(totalLength: Int): Long =
    if (totalLength > 200) 14L else 24L

/**
 * The progressively revealed assistant reply. `revealed` is hoisted here so the
 * only highly-changing state in the whole screen is this item.
 */
@Composable
internal fun AssistantReplyContent(
    fullText: String,
    modifier: Modifier = Modifier,
    textStyle: TextStyle = MaterialTheme.typography.bodyMedium,
) {
    var revealed by rememberSaveable(fullText) { mutableIntStateOf(0) }

    LaunchedEffect(fullText) {
        val total = fullText.length
        if (total == 0) {
            revealed = 0
            return@LaunchedEffect
        }
        val step = revealStep(total)
        val tickMillis = revealTickMillis(total)
        while (revealed < total) {
            delay(tickMillis)
            val next = (revealed + step).coerceAtMost(total)
            if (next != revealed) revealed = next
        }
    }

    MarkdownBody(
        markdown = fullText.take(revealed),
        modifier = modifier,
        baseStyle = textStyle,
        // While characters are still incoming, parse in partial mode so no
        // dangling Markdown markers are ever visible mid-bubble. The message is
        // re-parsed once per changed prefix; completed messages keep their final
        // parse via the MarkdownBody cache.
        partial = revealed < fullText.length,
    )
}