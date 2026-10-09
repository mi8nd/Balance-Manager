package com.personal.balance.domain.ai.orchestration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure unit tests for the deterministic [GoalCoachFacts] projection. These guard
 * that the composer facts are computed locally (no LLM arithmetic) and that no
 * fabricated value ever leaks into the prompt.
 */
class GoalCoachSupportTest {

    @Test
    fun active_computesRemainingAndProgressLocally() {
        val facts = GoalCoachFacts.from(
            GoalCoachState.Active("Vacation Fund", targetPiastres = 500_00L, currentPiastres = 300_00L, completed = false),
        )
        assertEquals("Vacation Fund", facts["goal_name"])
        assertEquals("500.00 EGP", facts["goal_target"])
        assertEquals("300.00 EGP", facts["goal_current"])
        assertEquals("200.00 EGP", facts["goal_remaining"])
        assertEquals("60%", facts["goal_progress_percent"])
        assertEquals("false", facts["goal_completed"])
    }

    @Test
    fun overFundedGoal_remainingClampedPercentCapped() {
        val facts = GoalCoachFacts.from(
            GoalCoachState.Active("Goal", targetPiastres = 100L, currentPiastres = 150L, completed = true),
        )
        assertEquals("0.00 EGP", facts["goal_remaining"])
        assertEquals("100%", facts["goal_progress_percent"])
        assertEquals("true", facts["goal_completed"])
    }

    @Test
    fun zeroTarget_guarded() {
        val facts = GoalCoachFacts.from(
            GoalCoachState.Active("Goal", targetPiastres = 0L, currentPiastres = 0L, completed = false),
        )
        assertEquals("0.00 EGP", facts["goal_remaining"])
        assertEquals("0%", facts["goal_progress_percent"])
    }

    @Test
    fun progressUsesIntegerFloor_matchingAppDisplay() {
        val facts = GoalCoachFacts.from(
            GoalCoachState.Active("Goal", targetPiastres = 300L, currentPiastres = 100L, completed = false),
        )
        // 100/300 = 33.3% -> floor 33%, exactly like the dashboard's integer display.
        assertEquals("33%", facts["goal_progress_percent"])
    }

    @Test
    fun amountsAdvertisedInEGP_matchingInsightsBuilder() {
        // Goal amounts are EGP-formatted (piastres -> EGP) so the model quotes real
        // values, mirroring FinancialInsightsContextBuilder.money().
        val facts = GoalCoachFacts.from(
            GoalCoachState.Active("Vacation Fund", targetPiastres = 1_500_000L, currentPiastres = 1_274_000L, completed = false),
        )
        assertEquals("15,000.00 EGP", facts["goal_target"])
        assertEquals("12,740.00 EGP", facts["goal_current"])
        assertEquals("2,260.00 EGP", facts["goal_remaining"])
        assertEquals("84%", facts["goal_progress_percent"])
    }

    @Test
    fun unavailable_exposesGoalStatusOnly() {
        val facts = GoalCoachFacts.from(GoalCoachState.Unavailable)
        assertTrue(facts.containsKey("goal_status"))
        assertEquals(1, facts.size)
    }
}