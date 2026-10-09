package com.personal.balance.data

import com.personal.balance.domain.ai.orchestration.GoalCoachState
import com.personal.balance.model.UserConfig
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure unit tests for the [FirestoreGoalCoachSource] mapping rules. The
 * Firestore-backed load is injected as a fake, so no Firebase is touched.
 */
class FirestoreGoalCoachSourceTest {

    private fun userConfig(
        targetEnabled: Boolean = false,
        targetAmount: Long = 0L,
        targetName: String = "",
        currentBalance: Long = 0L,
        targetCompleted: Boolean = false,
    ) = UserConfig(
        userId = "uid-1",
        targetEnabled = targetEnabled,
        targetAmount = targetAmount,
        targetName = targetName,
        currentBalance = currentBalance,
        targetCompleted = targetCompleted,
    )

    private fun source(result: UserConfigResult) =
        FirestoreGoalCoachSource(loadUserConfig = { _ -> result })

    private suspend fun active(result: UserConfigResult): GoalCoachState.Active {
        val state = source(result).load("uid-1")
        assertTrue("expected Active but was $state", state is GoalCoachState.Active)
        return state as GoalCoachState.Active
    }

    @Test
    fun enabledTarget_mapsToActiveState() = runBlocking {
        val state = active(
            UserConfigResult.Success(
                userConfig(
                    targetEnabled = true,
                    targetAmount = 500_00L,
                    targetName = " Vacation Fund ",
                    currentBalance = 300_00L,
                    targetCompleted = false,
                ),
            ),
        )
        assertEquals("Vacation Fund", state.goalName)
        assertEquals(500_00L, state.targetPiastres)
        assertEquals(300_00L, state.currentPiastres)
        assertEquals(false, state.completed)
    }

    @Test
    fun completedTarget_carriesAuthoritativeFlag() = runBlocking {
        val state = active(
            UserConfigResult.Success(
                userConfig(
                    targetEnabled = true,
                    targetAmount = 100_00L,
                    currentBalance = 100_00L,
                    targetCompleted = true,
                ),
            ),
        )
        assertEquals(true, state.completed)
    }

    @Test
    fun blankName_staysUnnamed() = runBlocking {
        val state = active(
            UserConfigResult.Success(
                userConfig(
                    targetEnabled = true,
                    targetAmount = 100_00L,
                    targetName = "",
                ),
            ),
        )
        assertEquals("", state.goalName)
    }

    @Test
    fun disabledTarget_unavailable() = runBlocking {
        val state = source(
            UserConfigResult.Success(userConfig(targetEnabled = false)),
        ).load("uid-1")
        assertEquals(GoalCoachState.Unavailable, state)
    }

    @Test
    fun zeroOrNegativeAmount_unavailable() = runBlocking {
        val entries = listOf(0L, -100L)
        for (amount in entries) {
            val state = source(
                UserConfigResult.Success(userConfig(targetEnabled = true, targetAmount = amount)),
            ).load("uid-1")
            assertEquals(GoalCoachState.Unavailable, state)
        }
    }

    @Test
    fun readError_unavailable() = runBlocking {
        assertEquals(
            GoalCoachState.Unavailable,
            source(UserConfigResult.Error("read_failed")).load("uid-1"),
        )
    }

    @Test
    fun missingUserConfig_unavailable() = runBlocking {
        assertEquals(
            GoalCoachState.Unavailable,
            source(UserConfigResult.Error("User config not found")).load("uid-1"),
        )
    }

    @Test
    fun nullUid_unavailable() = runBlocking {
        assertEquals(
            GoalCoachState.Unavailable,
            source(UserConfigResult.Success(userConfig(targetEnabled = true, targetAmount = 100L))).load(null),
        )
    }
}