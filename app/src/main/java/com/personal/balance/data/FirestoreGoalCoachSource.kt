package com.personal.balance.data

import com.personal.balance.domain.ai.orchestration.GoalCoachSource
import com.personal.balance.domain.ai.orchestration.GoalCoachState

/**
 * Production [GoalCoachSource] backed by the authoritative Goal Manager data:
 * the savings Target stored on the user's own config document
 * (`users/{uid}`, read through [FirestoreUserRepository]).
 *
 * Isolation: the uid is ALWAYS the currently authenticated user supplied by the
 * orchestrator's `currentUserId` boundary — never taken from the question or the
 * AI output. This source is read-only by construction: it never creates,
 * modifies, or deletes goals, and it never caches another account's goal.
 *
 * Missing goals and failed reads both collapse to [GoalCoachState.Unavailable],
 * so the orchestrator answers honestly with a local reply instead of spending
 * budget on a composer call that has nothing authoritative to ground on. No
 * fabricated values are ever produced.
 */
class FirestoreGoalCoachSource(
    private val loadUserConfig: suspend (uid: String) -> UserConfigResult = { uid ->
        FirestoreUserRepository().getUserConfig(uid)
    },
) : GoalCoachSource {

    override suspend fun load(currentUserId: String?): GoalCoachState {
        val uid = currentUserId ?: return GoalCoachState.Unavailable
        val result = loadUserConfig(uid)
        if (result !is UserConfigResult.Success) return GoalCoachState.Unavailable
        val config = result.config
        if (!config.targetEnabled || config.targetAmount <= 0L) return GoalCoachState.Unavailable
        return GoalCoachState.Active(
            goalName = config.targetName.trim(),
            targetPiastres = config.targetAmount,
            currentPiastres = config.currentBalance,
            completed = config.targetCompleted,
        )
    }
}