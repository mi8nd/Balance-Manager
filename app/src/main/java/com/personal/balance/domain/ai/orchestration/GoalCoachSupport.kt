package com.personal.balance.domain.ai.orchestration

import com.personal.balance.domain.EGPFormatter

/**
 * Goal Coach capability state.
 *
 * [Active] carries ONLY the authoritative goal data produced by the app's Goal
 * Manager (the savings Target stored on the user's own config document) plus
 * values derived deterministically from it. [Unavailable] means there is no
 * active goal for the current account (or no authenticated user): the
 * orchestrator answers honestly with a local reply and never spends budget on a
 * composer call that has nothing authoritative to ground on.
 *
 * This is the app's single Goal Coach model: nothing parallel exists and nothing
 * here reads Firestore or stores goals. The Goal Manager owns all goal data and
 * only has to supply/update a [GoalCoachSource].
 */
sealed interface GoalCoachState {
    data class Active(
        val goalName: String,
        val targetPiastres: Long,
        val currentPiastres: Long,
        val completed: Boolean,
    ) : GoalCoachState

    data object Unavailable : GoalCoachState
}

/**
 * Loads the current user's goal-coach state.
 *
 * The current Firebase uid is passed in for every load so a source can NEVER
 * resolve a different account, cache another account's goals, or accept a user
 * identity that came from the AI prompt. The uid is always the app's own
 * authenticated-user boundary.
 */
fun interface GoalCoachSource {
    suspend fun load(currentUserId: String?): GoalCoachState
}

/**
 * Fallback source: goal tracking is not implemented, so always unavailable. The
 * production implementation
 * [com.personal.balance.data.FirestoreGoalCoachSource] reads the authoritative
 * Target managed by the Goal Manager UI.
 */
object NoGoalManagerGoalSource : GoalCoachSource {
    override suspend fun load(currentUserId: String?): GoalCoachState = GoalCoachState.Unavailable
}

/** Deterministic facts the composer is allowed to see about goals. */
object GoalCoachFacts {
    fun from(state: GoalCoachState): Map<String, String> = when (state) {
        is GoalCoachState.Active -> {
            val remaining = (state.targetPiastres - state.currentPiastres).coerceAtLeast(0L)
            val progressPercent = if (state.targetPiastres > 0L) {
                ((state.currentPiastres * 100) / state.targetPiastres).toInt().coerceIn(0, 100)
            } else {
                0
            }
            linkedMapOf(
                "goal_name" to state.goalName,
                "goal_target" to EGPFormatter.formatAmount(state.targetPiastres),
                "goal_current" to EGPFormatter.formatAmount(state.currentPiastres),
                "goal_remaining" to EGPFormatter.formatAmount(remaining),
                "goal_progress_percent" to "$progressPercent%",
                "goal_completed" to state.completed.toString(),
            )
        }

        GoalCoachState.Unavailable -> mapOf(
            "goal_status" to "No savings goal is currently tracked in the app.",
        )
    }
}

/** Honest, local replies for goal questions — never fabricated progress. */
object GoalCoachReplies {
    const val UNAVAILABLE: String =
        "I can't see an active savings goal to coach you on right now. " +
            "You can set a target from your dashboard, and then I'll track your progress " +
            "towards it. Meanwhile I can still help with your spending, income, balance, " +
            "and transaction patterns."
}