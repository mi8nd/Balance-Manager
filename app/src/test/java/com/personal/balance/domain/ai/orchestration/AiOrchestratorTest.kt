package com.personal.balance.domain.ai.orchestration

import com.personal.balance.domain.ai.AiRequest
import com.personal.balance.domain.ai.AiResult
import com.personal.balance.domain.ai.AskAiUseCase
import com.personal.balance.domain.ai.AiAssistantRepository
import com.personal.balance.domain.ai.AiAuthProvider
import com.personal.balance.domain.ai.AiUsageController
import com.personal.balance.domain.ai.ChatSender
import com.personal.balance.domain.ai.ConversationTurn
import com.personal.balance.domain.finance.FinancialTransactionsResult
import com.personal.balance.domain.finance.FinancialTransactionsSource
import com.personal.balance.domain.finance.Importance
import com.personal.balance.domain.finance.TransactionInsight
import com.personal.balance.model.Transaction
import com.personal.balance.model.TransactionType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class AiOrchestratorTest {

    private val zone = ZoneId.of("UTC")
    private val nowEpochMillis = Instant.parse("2026-08-15T12:00:00Z").toEpochMilli()

    private class FakeAuthProvider(var authenticated: Boolean) : AiAuthProvider {
        override fun isAuthenticated() = authenticated
    }

    private class RecordingUsageController : AiUsageController {
        var allowed = true
        var calls = 0
        override suspend fun isCallAllowed() = allowed
        override suspend fun recordCall() { calls++ }
    }

    private class FakePlanner : AiRequestPlanner {
        var nextOutcome: PlanningOutcome = PlanningOutcome.Planned(
            AiRequestPlanDraft(intent = AiIntent.GENERAL_CONVERSATION, financialRelevance = false),
        )
        var calls = 0
        var lastQuestion: String? = null
        var lastConversation: List<ConversationTurn> = emptyList()

        override suspend fun plan(
            userQuestion: String,
            conversation: List<ConversationTurn>,
        ): PlanningOutcome {
            calls++
            lastQuestion = userQuestion
            lastConversation = conversation
            return nextOutcome
        }
    }

    private class RecordingAssistant(
        private val responses: List<AiResult>,
    ) : AiAssistantRepository {
        val requests = mutableListOf<AiRequest>()
        override suspend fun ask(request: AiRequest): AiResult {
            requests.add(request)
            return responses[requests.size - 1]
        }
    }

    private class RecordingSource(
        private val result: FinancialTransactionsResult,
    ) : FinancialTransactionsSource {
        var calls = 0
        override suspend fun loadTransactions(uid: String, startEpochMillis: Long, endEpochMillis: Long): FinancialTransactionsResult {
            calls++
            return result
        }
    }

    private fun tx(
        amount: Long,
        timestamp: Long,
        type: TransactionType = TransactionType.EXPENSE,
        category: String = "Food",
        description: String = "tx",
    ) = Transaction(userId = "uid-1", type = type, amount = amount, description = description, category = category, timestamp = timestamp)

    private fun planDraft(
        intent: AiIntent,
        relevance: Boolean = true,
        dataNeeds: Set<AiDataNeed> = emptySet(),
        needsContext: Boolean = false,
    ) = AiRequestPlanDraft(
        intent = intent,
        financialRelevance = relevance,
        dataNeeds = dataNeeds,
        needsConversationContext = needsContext,
    )

    private fun orchestrator(
        planner: FakePlanner = FakePlanner(),
        assistant: RecordingAssistant = RecordingAssistant(listOf(AiResult.Success("test", 1))),
        sourceResult: FinancialTransactionsResult = FinancialTransactionsResult.Success(emptyList()),
        source: RecordingSource = RecordingSource(sourceResult),
        authenticated: Boolean = true,
        usage: RecordingUsageController = RecordingUsageController(),
        goalSource: GoalCoachSource = NoGoalManagerGoalSource,
    ): AiOrchestrator {
        val askAi = AskAiUseCase(FakeAuthProvider(authenticated), usage, assistant)
        return AiOrchestrator(
            currentUserId = { if (authenticated) "uid-1" else null },
            transactionsSource = source,
            planner = planner,
            askAi = askAi,
            goalCoachSource = goalSource,
            nowEpochMillis = { nowEpochMillis },
            zone = zone,
        )
    }

    @Test
    fun greeting_noAiCalls_noDataLoad() = runBlocking {
        val source = RecordingSource(FinancialTransactionsResult.Success(emptyList()))
        val usage = RecordingUsageController()
        val planner = FakePlanner()
        val orch = orchestrator(source = source, usage = usage, planner = planner)

        val result = orch.route("Hello!", emptyList())

        assertTrue(result is AiOrchestrationResult.Text)
        assertTrue((result as AiOrchestrationResult.Text).text.contains("Financial Assistant"))
        assertEquals(0, planner.calls)
        assertEquals(0, source.calls)
        assertEquals(0, usage.calls)
    }

    @Test
    fun greetingWithFinancialCue_routed() = runBlocking {
        val planner = FakePlanner()
        val orch = orchestrator(planner = planner)

        val result = orch.route("Hi, how much did I spend this month?", emptyList())

        // Financial cue bypasses triage -> planner called -> plan result used.
        assertEquals(1, planner.calls)
        assertTrue(result is AiOrchestrationResult.Text || result is AiOrchestrationResult.Error)
    }

    @Test
    fun generalOnly_composerCalledOnce_noDataLoad() = runBlocking {
        val source = RecordingSource(FinancialTransactionsResult.Success(emptyList()))
        val assistant = RecordingAssistant(
            listOf(AiResult.Success("The best way to save is to track spending.", 4)),
        )
        val planner = FakePlanner()
        planner.nextOutcome = PlanningOutcome.Planned(
            planDraft(AiIntent.GENERAL_CONVERSATION, relevance = false),
        )
        val usage = RecordingUsageController()
        val orch = orchestrator(
            planner = planner,
            assistant = assistant,
            source = source,
            usage = usage,
        )

        val result = orch.route("How do I save money?", emptyList())

        assertTrue(result is AiOrchestrationResult.Text)
        assertEquals("The best way to save is to track spending.", (result as AiOrchestrationResult.Text).text)
        assertEquals(1, planner.calls)
        assertEquals(1, assistant.requests.size) // only the composer call (planner is faked)
        assertEquals(0, source.calls) // general answers need no transaction load
        assertEquals(1, usage.calls) // only the composer askAi call
    }

    @Test
    fun financeOnly_loadsOnce_andComposerReceivesFacts() = runBlocking {
        val tx1 = tx(150_00L, Instant.parse("2026-08-01T10:00:00Z").toEpochMilli())
        val source = RecordingSource(FinancialTransactionsResult.Success(listOf(tx1)))
        val assistant = RecordingAssistant(
            listOf(AiResult.Success("You spent 1500.00 EGP this month.", 8)),
        )
        val planner = FakePlanner()
        planner.nextOutcome = PlanningOutcome.Planned(
            planDraft(AiIntent.GET_CURRENT_SPENDING, dataNeeds = setOf(AiDataNeed.CURRENT_PERIOD_TOTALS)),
        )
        val usage = RecordingUsageController()
        val orch = orchestrator(
            planner = planner,
            assistant = assistant,
            source = source,
            usage = usage,
        )

        val result = orch.route("How much did I spend?", emptyList())

        assertTrue(result is AiOrchestrationResult.Text)
        assertEquals(1, source.calls)
        assertEquals(1, assistant.requests.size)
        assertEquals(1, usage.calls)
        assertTrue(assistant.requests.single().structuredFacts.containsKey("current_expense_total"))
    }

    @Test
    fun financeOnly_composerInstructionsTeachSmartTableUse() = runBlocking {
        val tx1 = tx(150_00L, Instant.parse("2026-08-01T10:00:00Z").toEpochMilli())
        val source = RecordingSource(FinancialTransactionsResult.Success(listOf(tx1)))
        val assistant = RecordingAssistant(
            listOf(AiResult.Success("| Category | Amount | Share |\n|---|---:|---:|\n| Food | 1500.00 EGP | 100% |", 8)),
        )
        val planner = FakePlanner()
        planner.nextOutcome = PlanningOutcome.Planned(
            planDraft(AiIntent.GET_CURRENT_SPENDING, dataNeeds = setOf(AiDataNeed.CURRENT_PERIOD_TOTALS)),
        )
        val orch = orchestrator(planner = planner, assistant = assistant, source = source)

        orch.route("How much did I spend?", emptyList())

        val instructions = assistant.requests.single().additionalInstructions.orEmpty()
        // Table-selection guidance must reach the composer for data-backed replies.
        assertTrue("category tables", instructions.contains("Category | Amount | Share"))
        assertTrue("period comparison tables", instructions.contains("Period | Amount | Change"))
        assertTrue("income vs expenses split", instructions.contains("Group | Amount | Share"))
        assertTrue("no tables for single numbers", instructions.contains("must not use a table"))
        assertTrue("values from facts only", instructions.contains("never invent amounts"))
    }

    @Test
    fun dataNeedsRespected_unlistedFactsExcluded() = runBlocking {
        val tx1 = tx(100_00L, Instant.parse("2026-08-01T10:00:00Z").toEpochMilli())
        val source = RecordingSource(FinancialTransactionsResult.Success(listOf(tx1)))
        val assistant = RecordingAssistant(listOf(AiResult.Success("Compared.", 3)))
        val planner = FakePlanner()
        planner.nextOutcome = PlanningOutcome.Planned(
            planDraft(AiIntent.COMPARE_PERIODS), // defaults = current + previous totals
        )
        val orch = orchestrator(planner = planner, assistant = assistant, source = source)

        orch.route("Compare this month and last.", emptyList())

        val facts = assistant.requests.single().structuredFacts
        assertTrue(facts.containsKey("current_expense_total"))
        assertTrue(facts.containsKey("previous_expense_total"))
        // Unrequested fact groups are trimmed by the selector.
        assertTrue(!facts.containsKey("recent_transactions"))
        assertTrue(!facts.containsKey("largest_expense_in_current_period"))
    }

    @Test
    fun multiCapability_explainChange_financePlusTi_inOneComposer() = runBlocking {
        val tx1 = tx(100_00L, Instant.parse("2026-08-01T10:00:00Z").toEpochMilli())
        val tx2 = tx(200_00L, Instant.parse("2026-08-02T10:00:00Z").toEpochMilli())
        val source = RecordingSource(FinancialTransactionsResult.Success(listOf(tx1, tx2)))
        val assistant = RecordingAssistant(
            listOf(AiResult.Success(
                "Food drove the rise.\nINSIGHT|HIGH|Food spike|Food spending led the increase.",
                12,
            )),
        )
        val planner = FakePlanner()
        planner.nextOutcome = PlanningOutcome.Planned(
            planDraft(AiIntent.EXPLAIN_SPENDING_CHANGE),
        )
        val usage = RecordingUsageController()
        val orch = orchestrator(
            planner = planner,
            assistant = assistant,
            source = source,
            usage = usage,
        )

        val result = orch.route("Why did my spending increase, and what habits should I change?", emptyList())

        assertTrue(result is AiOrchestrationResult.Text)
        val text = result as AiOrchestrationResult.Text
        assertEquals("Food drove the rise.", text.text)
        assertEquals(1, text.insights.size)
        assertEquals(1, source.calls) // ONE shared load for FA + TI
        assertEquals(1, assistant.requests.size) // ONE unified composer call
        assertEquals(1, usage.calls)
        val facts = assistant.requests.single().structuredFacts
        assertTrue(facts.containsKey("current_expense_total")) // FA facts
        assertTrue(facts.containsKey("previous_expense_total")) // FA previous
        assertTrue(facts.containsKey("category_deltas")) // TI pattern facts
        assertTrue(assistant.requests.single().additionalInstructions?.contains("INSIGHT") == true)
    }

    @Test
    fun tiOnly_composerRequestIncludesInsightInstruction() = runBlocking {
        val tx1 = tx(100_00L, Instant.parse("2026-08-01T10:00:00Z").toEpochMilli())
        val tx2 = tx(200_00L, Instant.parse("2026-08-02T10:00:00Z").toEpochMilli())
        val source = RecordingSource(FinancialTransactionsResult.Success(listOf(tx1, tx2)))
        val assistant = RecordingAssistant(
            listOf(AiResult.Success("Prose.\nINSIGHT|HIGH|Big|Your biggest expense is 200.", 10)),
        )
        val planner = FakePlanner()
        planner.nextOutcome = PlanningOutcome.Planned(
            planDraft(AiIntent.IDENTIFY_SPENDING_PATTERNS),
        )
        val usage = RecordingUsageController()
        val orch = orchestrator(
            planner = planner,
            assistant = assistant,
            source = source,
            usage = usage,
        )

        val result = orch.route("What patterns stand out?", emptyList())

        assertTrue(result is AiOrchestrationResult.Text)
        val text = result as AiOrchestrationResult.Text
        assertEquals("Prose.", text.text) // prose should be stripped of insight lines
        assertEquals(1, text.insights.size)
        assertEquals("Big", text.insights[0].title)
        // Composer instruction contains INSIGHT format guidance.
        assertTrue(assistant.requests.single().additionalInstructions?.contains("INSIGHT") == true)
    }

    @Test
    fun tiOnlyNoData_localReply_noComposer() = runBlocking {
        val source = RecordingSource(FinancialTransactionsResult.Success(emptyList()))
        val assistant = RecordingAssistant(emptyList()) // should never be called
        val planner = FakePlanner()
        planner.nextOutcome = PlanningOutcome.Planned(
            planDraft(AiIntent.IDENTIFY_SPENDING_PATTERNS),
        )
        val usage = RecordingUsageController()
        val orch = orchestrator(
            planner = planner,
            assistant = assistant,
            source = source,
            usage = usage,
        )

        val result = orch.route("Any patterns?", emptyList())

        assertTrue(result is AiOrchestrationResult.Text)
        assertTrue((result as AiOrchestrationResult.Text).text.contains("aren't any transactions"))
        assertEquals(1, source.calls)
        assertEquals(0, assistant.requests.size) // composer never called
        assertEquals(0, usage.calls)
    }

    @Test
    fun tiOnlyInsufficientData_localReply() = runBlocking {
        val singleTx = tx(100_00L, Instant.parse("2026-08-05T10:00:00Z").toEpochMilli())
        val source = RecordingSource(FinancialTransactionsResult.Success(listOf(singleTx)))
        val assistant = RecordingAssistant(emptyList())
        val planner = FakePlanner()
        planner.nextOutcome = PlanningOutcome.Planned(
            planDraft(AiIntent.IDENTIFY_SPENDING_PATTERNS),
        )
        val usage = RecordingUsageController()
        val orch = orchestrator(
            planner = planner,
            assistant = assistant,
            source = source,
            usage = usage,
        )

        val result = orch.route("Any patterns?", emptyList())

        assertTrue(result is AiOrchestrationResult.Text)
        assertTrue((result as AiOrchestrationResult.Text).text.contains("isn't enough transaction history"))
        assertEquals(0, assistant.requests.size)
        assertEquals(0, usage.calls)
    }

    @Test
    fun goalOnlyUnavailable_localReply_noComposer() = runBlocking {
        val source = RecordingSource(FinancialTransactionsResult.Success(emptyList()))
        val assistant = RecordingAssistant(emptyList())
        val planner = FakePlanner()
        planner.nextOutcome = PlanningOutcome.Planned(
            planDraft(AiIntent.ASK_GOAL_PROGRESS),
        )
        val usage = RecordingUsageController()
        val orch = orchestrator(
            planner = planner,
            assistant = assistant,
            source = source,
            usage = usage,
        )

        val result = orch.route("What's my savings goal progress?", emptyList())

        assertTrue(result is AiOrchestrationResult.Text)
        assertTrue((result as AiOrchestrationResult.Text).text.contains("savings goal"))
        assertEquals(0, source.calls)
        assertEquals(0, assistant.requests.size)
        assertEquals(0, usage.calls)
    }

    @Test
    fun goalOnlyActive_composerCalledWithGoalFacts() = runBlocking {
        val source = RecordingSource(FinancialTransactionsResult.Success(emptyList()))
        val assistant = RecordingAssistant(
            listOf(AiResult.Success("Your vacation fund is 60% funded.", 5)),
        )
        val planner = FakePlanner()
        planner.nextOutcome = PlanningOutcome.Planned(
            planDraft(AiIntent.ASK_GOAL_PROGRESS),
        )
        val usage = RecordingUsageController()
        val activeGoal = GoalCoachState.Active("Vacation Fund", 500_00L, 300_00L, completed = false)
        val orch = orchestrator(
            planner = planner,
            assistant = assistant,
            source = source,
            usage = usage,
            goalSource = GoalCoachSource { _ -> activeGoal },
        )

        val result = orch.route("How's my vacation goal?", emptyList())

        assertTrue(result is AiOrchestrationResult.Text)
        assertEquals(0, source.calls) // no transaction load
        assertEquals(1, assistant.requests.size)
        assertEquals(1, usage.calls)
        assertEquals("Vacation Fund", assistant.requests.single().structuredFacts["goal_name"])
        assertEquals("200.00 EGP", assistant.requests.single().structuredFacts["goal_remaining"])
        assertEquals("60%", assistant.requests.single().structuredFacts["goal_progress_percent"])
        assertEquals("false", assistant.requests.single().structuredFacts["goal_completed"])
    }

    @Test
    fun goalPlusFinance_goalStatusFactIncluded() = runBlocking {
        val tx1 = tx(100_00L, Instant.parse("2026-08-01T10:00:00Z").toEpochMilli())
        val source = RecordingSource(FinancialTransactionsResult.Success(listOf(tx1)))
        val assistant = RecordingAssistant(
            listOf(AiResult.Success("Your goal is on track and you spent 1000 EGP.", 8)),
        )
        val planner = FakePlanner()
        planner.nextOutcome = PlanningOutcome.Planned(
            planDraft(
                AiIntent.ASK_GOAL_PROGRESS,
                dataNeeds = setOf(AiDataNeed.GOAL_DATA, AiDataNeed.CURRENT_PERIOD_TOTALS),
            ),
        )
        val usage = RecordingUsageController()
        val orch = orchestrator(
            planner = planner,
            assistant = assistant,
            source = source,
            usage = usage,
            goalSource = GoalCoachSource { _ -> GoalCoachState.Unavailable },
        )

        val result = orch.route("How's my goal and spending?", emptyList())

        assertTrue(result is AiOrchestrationResult.Text)
        assertEquals(1, source.calls)
        assertEquals(1, assistant.requests.size)
        assertEquals(1, usage.calls)
        val facts = assistant.requests.single().structuredFacts
        assertTrue(facts.containsKey("goal_status"))
        assertTrue(facts.containsKey("current_expense_total"))
    }

    @Test
    fun ambiguousNonFinancial_localClarification_noComposer() = runBlocking {
        val source = RecordingSource(FinancialTransactionsResult.Success(emptyList()))
        val assistant = RecordingAssistant(emptyList())
        val planner = FakePlanner()
        planner.nextOutcome = PlanningOutcome.Planned(
            planDraft(AiIntent.AMBIGUOUS, relevance = false),
        )
        val usage = RecordingUsageController()
        val orch = orchestrator(
            planner = planner,
            assistant = assistant,
            source = source,
            usage = usage,
        )

        val result = orch.route("Hmm?", emptyList())

        assertTrue(result is AiOrchestrationResult.Text)
        assertTrue((result as AiOrchestrationResult.Text).text.contains("little more about what you'd like"))
        assertEquals(1, planner.calls)
        assertEquals(0, source.calls)
        assertEquals(0, assistant.requests.size)
        assertEquals(0, usage.calls)
    }

    @Test
    fun ambiguousFinanciallyRelevant_proceedsBroadFinancialPlan() = runBlocking {
        val tx1 = tx(100_00L, Instant.parse("2026-08-01T10:00:00Z").toEpochMilli())
        val source = RecordingSource(FinancialTransactionsResult.Success(listOf(tx1)))
        val assistant = RecordingAssistant(listOf(AiResult.Success("Here's an overview.", 4)))
        val planner = FakePlanner()
        planner.nextOutcome = PlanningOutcome.Planned(
            planDraft(AiIntent.AMBIGUOUS, relevance = true),
        )
        val usage = RecordingUsageController()
        val orch = orchestrator(
            planner = planner,
            assistant = assistant,
            source = source,
            usage = usage,
        )

        val result = orch.route("Tell me about my money", emptyList())

        assertTrue(result is AiOrchestrationResult.Text)
        assertEquals(1, source.calls)
        assertEquals(1, assistant.requests.size)
        assertEquals(1, usage.calls)
    }

    @Test
    fun danglingReference_noConversation_clarification() = runBlocking {
        val source = RecordingSource(FinancialTransactionsResult.Success(emptyList()))
        val assistant = RecordingAssistant(emptyList())
        val planner = FakePlanner()
        planner.nextOutcome = PlanningOutcome.Planned(
            planDraft(AiIntent.GENERAL_CONVERSATION, relevance = false, needsContext = true),
        )
        val orch = orchestrator(planner = planner, assistant = assistant, source = source)

        val result = orch.route("What about it?", emptyList())

        assertTrue(result is AiOrchestrationResult.Text)
        assertTrue((result as AiOrchestrationResult.Text).text.contains("little more about what you'd like"))
        assertEquals(0, source.calls)
        assertEquals(0, assistant.requests.size)
    }

    @Test
    fun generalIntentButFinanciallyRelevant_routedToData() = runBlocking {
        val tx1 = tx(100_00L, Instant.parse("2026-08-01T10:00:00Z").toEpochMilli())
        val source = RecordingSource(FinancialTransactionsResult.Success(listOf(tx1)))
        val assistant = RecordingAssistant(listOf(AiResult.Success("Data-backed answer.", 3)))
        val planner = FakePlanner()
        // The model said GENERAL but flagged it financially relevant: the app
        // must NOT dump a financial question on the general assistant.
        planner.nextOutcome = PlanningOutcome.Planned(
            planDraft(AiIntent.GENERAL_CONVERSATION, relevance = true),
        )
        val orch = orchestrator(planner = planner, assistant = assistant, source = source)

        val result = orch.route("Anything notable this month?", emptyList())

        assertTrue(result is AiOrchestrationResult.Text)
        assertEquals(1, source.calls)
        assertEquals(1, assistant.requests.size)
    }

    @Test
    fun plannerFailure_errorPropagates_noDataLoad() = runBlocking {
        val source = RecordingSource(FinancialTransactionsResult.Success(emptyList()))
        val assistant = RecordingAssistant(emptyList())
        val planner = FakePlanner()
        planner.nextOutcome = PlanningOutcome.Failed(
            AiResult.Error.NetworkFailure("Router down", "net"),
        )
        val usage = RecordingUsageController()
        val orch = orchestrator(
            planner = planner,
            assistant = assistant,
            source = source,
            usage = usage,
        )

        val result = orch.route("Spend?", emptyList())

        assertTrue(result is AiOrchestrationResult.Error)
        val error = (result as AiOrchestrationResult.Error).error
        assertTrue(error is AiResult.Error.NetworkFailure)
        assertEquals(1, planner.calls)
        assertEquals(0, source.calls)
        assertEquals(0, assistant.requests.size)
        assertEquals(0, usage.calls)
    }

    @Test
    fun sourceFailure_returnsError() = runBlocking {
        val source = RecordingSource(FinancialTransactionsResult.Error("read_failed"))
        val assistant = RecordingAssistant(emptyList())
        val planner = FakePlanner()
        planner.nextOutcome = PlanningOutcome.Planned(
            planDraft(AiIntent.GET_CURRENT_SPENDING),
        )
        val usage = RecordingUsageController()
        val orch = orchestrator(
            planner = planner,
            assistant = assistant,
            source = source,
            usage = usage,
        )

        val result = orch.route("How much did I spend?", emptyList())

        assertTrue(result is AiOrchestrationResult.Error)
        val error = (result as AiOrchestrationResult.Error).error
        assertTrue(error is AiResult.Error.NetworkFailure)
        assertEquals(0, assistant.requests.size)
        assertEquals(0, usage.calls)
    }

    @Test
    fun unauthenticated_dataRequest_returnsError() = runBlocking {
        val source = RecordingSource(FinancialTransactionsResult.Success(emptyList()))
        val assistant = RecordingAssistant(emptyList())
        val planner = FakePlanner()
        planner.nextOutcome = PlanningOutcome.Planned(
            planDraft(AiIntent.GET_CURRENT_SPENDING),
        )
        val usage = RecordingUsageController()
        val orch = orchestrator(
            planner = planner,
            assistant = assistant,
            source = source,
            usage = usage,
            authenticated = false,
        )

        val result = orch.route("How much did I spend?", emptyList())

        assertTrue(result is AiOrchestrationResult.Error)
        val error = (result as AiOrchestrationResult.Error).error
        assertTrue(error is AiResult.Error.Unauthenticated)
        assertEquals(0, source.calls)
        assertEquals(0, assistant.requests.size)
    }

    @Test
    fun conversationPassedToPlannerAndComposer() = runBlocking {
        val tx1 = tx(100_00L, Instant.parse("2026-08-01T10:00:00Z").toEpochMilli())
        val source = RecordingSource(FinancialTransactionsResult.Success(listOf(tx1)))
        val assistant = RecordingAssistant(
            listOf(AiResult.Success("Yes, food spending is rising.", 6)),
        )
        val planner = FakePlanner()
        planner.nextOutcome = PlanningOutcome.Planned(
            planDraft(AiIntent.EXPLAIN_SPENDING_CHANGE),
        )
        val usage = RecordingUsageController()
        val orch = orchestrator(
            planner = planner,
            assistant = assistant,
            source = source,
            usage = usage,
        )

        val turns = listOf(
            ConversationTurn(ChatSender.USER, "How's food spending?"),
            ConversationTurn(ChatSender.ASSISTANT, "It's stable."),
        )
        orch.route("What about food?", turns)

        assertEquals(turns, planner.lastConversation)
        assertEquals(turns, assistant.requests.single().conversation)
    }

    @Test
    fun composerProseOnly_noInsights() = runBlocking {
        val source = RecordingSource(FinancialTransactionsResult.Success(emptyList()))
        val assistant = RecordingAssistant(
            listOf(AiResult.Success("You spent more this month overall.", 5)),
        )
        val planner = FakePlanner()
        planner.nextOutcome = PlanningOutcome.Planned(
            planDraft(AiIntent.COMPARE_PERIODS),
        )
        val orch = orchestrator(planner = planner, assistant = assistant, source = source)

        val result = orch.route("Why did my spending rise?", emptyList())

        assertTrue(result is AiOrchestrationResult.Text)
        val text = result as AiOrchestrationResult.Text
        assertEquals("You spent more this month overall.", text.text)
        assertTrue(text.insights.isEmpty())
    }

    @Test
    fun composerOnlyInsights_noProse_prefaceText() = runBlocking {
        val tx1 = tx(100_00L, Instant.parse("2026-08-01T10:00:00Z").toEpochMilli())
        val tx2 = tx(200_00L, Instant.parse("2026-08-02T10:00:00Z").toEpochMilli())
        val source = RecordingSource(FinancialTransactionsResult.Success(listOf(tx1, tx2)))
        val assistant = RecordingAssistant(
            listOf(AiResult.Success(
                "INSIGHT|HIGH|Big shift|Food spending rose 50% this month.\nINSIGHT|LOW|Stable|Rent held steady.",
                10,
            )),
        )
        val planner = FakePlanner()
        planner.nextOutcome = PlanningOutcome.Planned(
            planDraft(AiIntent.IDENTIFY_SPENDING_PATTERNS),
        )
        val orch = orchestrator(planner = planner, assistant = assistant, source = source)

        val result = orch.route("What patterns?", emptyList())

        assertTrue(result is AiOrchestrationResult.Text)
        val text = result as AiOrchestrationResult.Text
        assertTrue(text.text.contains("what stands out"))
        assertEquals(2, text.insights.size)
    }
}