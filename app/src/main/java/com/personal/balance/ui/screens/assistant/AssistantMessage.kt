package com.personal.balance.ui.screens.assistant

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.personal.balance.domain.finance.Importance
import com.personal.balance.domain.finance.TransactionInsight
import com.personal.balance.viewmodel.ChatMessage
import kotlin.math.PI
import kotlin.math.cos

private val messageRadius = RoundedCornerShape(18.dp)

/**
 * Typing-indicator tuning.
 *
 * One shared ramp of [TYPING_WAVE_DURATION_MS] is the only clock; each dot reads it
 * at a phase offset of [TYPING_DOT_STAGGER_MS]. A dot is "in bounce" for the first
 * [TYPING_DOT_BOUNCE_MS] of its own cycle and rests at the baseline for the remainder.
 *
 * Because the stagger is exactly half of the bounce (330 = 660 / 2), the three dots
 * hand off one at a time — dot1 rises, hands to dot2, hands to dot3, then repeats —
 * which is what produces a clear travelling wave instead of three dots pulsing
 * together. The full cycle is 1320ms (= 2 x bounce = 4 x stagger), so dot3's bounce
 * completes exactly as dot1's next bounce begins and the loop is seamless.
 *
 * Rise/scale are px offsets applied via graphicsLayer (not layout dp), so layout,
 * bubble design, dot size, spacing and colour are untouched.
 */
private const val TYPING_WAVE_DURATION_MS = 1320
private const val TYPING_DOT_BOUNCE_MS = 660
private const val TYPING_DOT_STAGGER_MS = 330

/** Rise travel, px. Deliberately a touch larger than the previous 4px so the bounce reads. */
private const val TYPING_DOT_RISE_PX = 7f

/** Restrained scale-up; the vertical bounce is the primary motion now. */
private const val TYPING_DOT_SCALE_MAX = 1.2f

/** Short one-shot entrance so the indicator does not appear at full strength. */
private const val TYPING_ENTRANCE_DURATION_MS = 220

/** 330ms stagger expressed as a fraction of the shared 1320ms ramp. */
private const val TYPING_DOT_STAGGER_FRACTION = TYPING_DOT_STAGGER_MS.toFloat() / TYPING_WAVE_DURATION_MS.toFloat()

/** Fraction of each dot's own cycle spent bouncing (660 / 1320 = 0.5); the rest is rest. */
private const val TYPING_BOUNCE_FRACTION = TYPING_DOT_BOUNCE_MS.toFloat() / TYPING_WAVE_DURATION_MS.toFloat()

/**
 * User content: a compact, clearly tinted pill on the end side. Uses the calmer
 * `primaryContainer` role (not a solid primary block) and symmetric corners, so
 * it reads as "what you asked" without shouting.
 */
@Composable
internal fun UserMessage(
    text: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            shape = messageRadius,
            modifier = Modifier.widthIn(max = 320.dp),
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
            )
        }
    }
}

/**
 * Assistant content: natural, bubble-free text on the surface. Structured
 * insight cards, when present, hang directly beneath the prose via a slim accent
 * rule - part of the same reply, not a separate dashboard.
 */
@Composable
internal fun AssistantMessageItem(
    message: ChatMessage.Assistant,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AssistantReplyContent(
            fullText = message.text,
            textStyle = MaterialTheme.typography.bodyMedium,
        )
        if (message.insights.isNotEmpty()) {
            InsightCardList(message.insights)
        }
    }
}

/**
 * Inline structured insights, visually tied to the assistant reply that produced
 * them: a thin importance-colored rule, the title, one line of explanation and a
 * quiet importance chip. No full-width card "section" - the insights belong to
 * the response they sit under.
 */
@Composable
internal fun InsightCardList(
    insights: List<TransactionInsight>,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        insights.forEach { insight -> InsightItem(insight) }
    }
}

@Composable
private fun InsightItem(insight: TransactionInsight) {
    val accent = when (insight.importance) {
        Importance.HIGH -> MaterialTheme.colorScheme.error
        Importance.MEDIUM -> MaterialTheme.colorScheme.primary
        Importance.LOW -> MaterialTheme.colorScheme.secondary
        Importance.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(accent, RoundedCornerShape(2.dp)),
        )
        Spacer(Modifier.width(10.dp))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = insight.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.width(8.dp))
                ImportanceRowLabel(insight.importance)
            }
            Text(
                text = insight.explanation,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ImportanceRowLabel(importance: Importance) {
    val (label, color) = when (importance) {
        Importance.HIGH -> "High" to MaterialTheme.colorScheme.error
        Importance.MEDIUM -> "Notable" to MaterialTheme.colorScheme.primary
        Importance.LOW -> "Minor" to MaterialTheme.colorScheme.secondary
        Importance.INFO -> "Info" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        color = color.copy(alpha = 0.12f),
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
        )
    }
}

/**
 * Error content: a compact tinted block with the safe message and a quiet inline
 * "Try again". Radius/layout deliberately differ from normal replies so errors
 * never read as assistant answers.
 */
@Composable
internal fun ErrorMessageRow(
    message: ChatMessage.Error,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
    ) {
        Surface(
            color = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.widthIn(max = 340.dp),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Text(
                    text = message.text,
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(
                    onClick = onRetry,
                    contentPadding = PaddingValues(0.dp),
                    modifier = Modifier.padding(top = 2.dp),
                ) {
                    Text(
                        text = "Try again",
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }
    }
}

/**
 * Lightweight "someone is typing..." indicator: three quiet dots on one baseline with
 * a deliberate travelling wave.
 *
 * Structure (ONE shared animation, staggered phase only):
 *  - Exactly ONE [rememberInfiniteTransition] + ONE `animateFloat` drive the whole
 *    indicator. Each dot reads that same 0..1 ramp at a staggered phase offset
 *    ([TYPING_DOT_STAGGER_FRACTION] = 330/1320 = 0.25 apart). The three dots are NOT
 *    three unrelated animations.
 *  - Each dot bounces for the first half of its own cycle and rests at the baseline
 *    for the second half. The stagger is exactly half the bounce, so at any moment one
 *    dot is rising while the previous one falls — a travelling wave with visible
 *    hand-off, not simultaneous pulsing.
 *  - The per-dot envelope is a raised cosine over the bounce, `0.5 - 0.5*cos(2*pi*t)`:
 *    it is C1-continuous with zero slope at the start, the peak and the rest, so the
 *    dot leaves the baseline softly, peaks softly, and settles back softly with no
 *    corner/kink. Outside the bounce the value is pinned to 0, which is continuous
 *    with the envelope's own end value, so the rest period cannot flicker.
 *  - `RepeatMode.Restart` on a linear ramp is seamless: the envelope is 0 at both ends
 *    of the cycle and the cycle length divides evenly into the stagger/bounce maths.
 *
 * Motion: a clearly visible but subtle vertical rise, a restrained scale, and alpha.
 * All three are driven from the same envelope, so prominence changes smoothly instead
 * of snapping between states.
 *
 * Entrance: a short one-shot fade/scale ramp ([TYPING_ENTRANCE_DURATION_MS]) plays on
 * first composition so the dots do not pop in at full strength. It is deliberately
 * brief — the indicator must appear quickly so the user never thinks the app is idle.
 *
 * Visibility is driven purely by the composable being present in the tree (the
 * caller renders it only while a request is in flight); it is NOT timed by any
 * artificial timeout, and the wave runs for as long as the request is pending.
 * [AnimatedVisibility] gives a smooth fade/scale exit when the request finishes,
 * fails, or is cancelled.
 *
 * The animation state is fully local to this list item, so nothing else recomposes
 * each frame, and the assistant's message-bubble layout/keyboard behavior is untouched.
 */
@Composable
internal fun AssistantTypingIndicator(
    modifier: Modifier = Modifier,
) {
    // One shared, single infinite animation drives all three dots.
    val transition = rememberInfiniteTransition(label = "typing-dots")
    val ramp by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            // Linear so the cosine envelope alone controls the easing; any easing here
            // would distort the soft rise/fall of each bounce.
            animation = tween(durationMillis = TYPING_WAVE_DURATION_MS, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "typing-ramp",
    )

    // Short one-shot entrance, independent of the request lifetime.
    val entrance = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        entrance.animateTo(
            targetValue = 1f,
            animationSpec = tween(
                durationMillis = TYPING_ENTRANCE_DURATION_MS,
                easing = FastOutSlowInEasing,
            ),
        )
    }

    Row(
        modifier = modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(3) { index ->
            // Staggered phase = travelling wave from a single shared animation.
            // The offset is SUBTRACTED so dot 0 leads at t=0, dot 1 at 330ms and dot 2
            // at 660ms, each bouncing for 660ms (330 up / 330 down). Adding instead
            // would push each dot's rest period across the loop seam, leaving it active
            // for the whole cycle and collapsing the wave.
            val phase = (ramp - index * TYPING_DOT_STAGGER_FRACTION).mod(1f)
            // Normalised progress through this dot's own bounce window, clamped to 1 so
            // the rest of the cycle reads as a flat baseline.
            val bounce = (phase / TYPING_BOUNCE_FRACTION).coerceIn(0f, 1f)
            // Raised cosine over the bounce: soft departure, soft peak, soft settle.
            // Clamping keeps the value in range, and equals 0 across the whole rest
            // period, so there is no edge to cross.
            val envelope = (0.5f - 0.5f * cos(2.0f * PI.toFloat() * bounce)).coerceIn(0f, 1f)
            val intensity = envelope * entrance.value

            Box(
                modifier = Modifier
                    .size(7.dp)
                    .graphicsLayer {
                        // Gentle rise: positive translationY moves the dot down in
                        // Compose, so negate to lift it as intensity grows.
                        translationY = TYPING_DOT_RISE_PX * (1f - intensity)
                        val scale = 1f + (TYPING_DOT_SCALE_MAX - 1f) * intensity
                        scaleX = scale
                        scaleY = scale
                    }
                    .background(
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                            .copy(alpha = 0.35f + (1f - 0.35f) * intensity),
                        shape = RoundedCornerShape(50),
                    ),
            )
        }
    }
}