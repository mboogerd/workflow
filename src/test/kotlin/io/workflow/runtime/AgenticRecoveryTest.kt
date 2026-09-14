package io.workflow.runtime

import io.workflow.compiler.WorkflowCompiler
import io.workflow.core.DeterministicIdSource
import io.workflow.core.FixedClock
import io.workflow.core.Value
import io.workflow.core.ValueSchema
import io.workflow.provider.AgentBudget
import io.workflow.provider.AgentBudgetMeter
import io.workflow.provider.AgentCapabilityGate
import io.workflow.provider.AgenticDescriptor
import io.workflow.provider.AgenticInvocationEnvironment
import io.workflow.provider.AgenticProviderImplementation
import io.workflow.provider.AgentOperationResult
import io.workflow.provider.AgentOutputAcceptance
import io.workflow.provider.AgentToolDescriptor
import io.workflow.provider.EffectClass
import io.workflow.provider.ProviderDescriptor
import io.workflow.provider.ProviderInvocationRequest
import io.workflow.provider.ProviderLifecycleMessage
import io.workflow.provider.ProviderRegistry
import io.workflow.provider.ResolvedAgentMetadata
import io.workflow.provider.IdempotencyContract
import io.workflow.provider.ReconciliationMode
import java.time.Instant
import java.nio.file.Path
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AgenticRecoveryTest {
    private val now = Instant.EPOCH
    @TempDir lateinit var temporaryDirectory: Path

    @Test fun `agentic descriptor is mandatory and records resolved metadata plus refused operations`() {
        assertThrows(IllegalArgumentException::class.java) {
            ProviderDescriptor("bad", 1, ValueSchema.Any, ValueSchema.String, effectClass = EffectClass.AGENTIC)
        }
        val descriptor = descriptor(AgentBudget(maxTokens = 1, maxToolCalls = 0, maxIterations = 1))
        val implementation = object : AgenticProviderImplementation {
            override fun resolveMetadata(request: ProviderInvocationRequest, now: Instant) = ResolvedAgentMetadata("fake-model-2026", mapOf("temperature" to "0", "secret" to "hidden"), now)
            override fun invokeAgentic(request: ProviderInvocationRequest, environment: AgenticInvocationEnvironment): Iterable<ProviderLifecycleMessage> {
                assertTrue(environment.capabilities.useTool("undeclared") is AgentOperationResult.Refused)
                assertTrue(environment.budget.consume(tokens = 2, now = now) is AgentOperationResult.Refused)
                return listOf(ProviderLifecycleMessage.Emission(Value.StringValue("ok"), io.workflow.core.EmissionId("out"), request.invocationId, request.attemptId), ProviderLifecycleMessage.Completed)
            }
        }
        val registry = ProviderRegistry().also { it.register(descriptor, implementation) }
        val workflow = WorkflowCompiler(registry).compile("""
            workflow:
              id: agentic
              version: 1
              context:
                answer: {provider: agent, version: 1}
              outputs: [answer]
        """.trimIndent()).ir!!
        val journal = InMemoryJournalStore(FixedClock(now), DeterministicIdSource("agent-"))
        val result = InMemoryWorkflowRunner(
            compiler = WorkflowCompiler(registry), clock = FixedClock(now), idSource = DeterministicIdSource("runner-"),
            journal = journal, providerRegistry = registry,
        ).execute(workflow)
        assertEquals(Value.StringValue("ok"), result.outputs.getValue("answer").value)
        val diagnostics = journal.providerEvents().mapNotNull { it.diagnostic }
        assertTrue(diagnostics.any { it.contains("model=fake-model-2026") && !it.contains("hidden") })
        assertTrue(journal.providerEvents().any { it.type == ProviderEventType.AGENTIC_CAPABILITY_REFUSED })
        assertTrue(journal.providerEvents().any { it.type == ProviderEventType.AGENTIC_BUDGET_REFUSED })
    }

    @Test fun `all constrained recovery variants are representable and only validated actions proceed`() {
        val request = RecoveryRequest("a", io.workflow.core.InvocationId("i"), Value.StringValue("failure"), mapOf("input" to io.workflow.core.AssignmentId("r1")), mapOf("input" to io.workflow.core.AssignmentId("r1")))
        val policy = RecoveryPolicy(RecoveryAction.entries.toSet(), emptySet())
        val gate = AgentCapabilityGate(AgenticDescriptor("pinned", "recovery-v1", "recovery-v1", RecoveryAction.entries.associate { "recovery:${it.name.lowercase()}" to AgentToolDescriptor(EffectClass.PURE) }), emptySet())
        val variants = listOf<RecoveryProposal>(RecoveryProposal.Retry(), RecoveryProposal.Reconcile(Value.StringValue("q")), RecoveryProposal.Compensate("undo"), RecoveryProposal.Substitute("other"), RecoveryProposal.Emit(Value.StringValue("value")), RecoveryProposal.RequestHuman("choose", listOf(Value.StringValue("yes"))), RecoveryProposal.Skip("not needed"), RecoveryProposal.Abort("unsafe"))
        variants.forEach { proposal ->
            val result = RecoveryValidator.validate(proposal, request, policy, emptySet(), AgentBudgetMeter(AgentBudget(maxIterations = 10), now), gate, now)
            assertTrue(result.accepted, "${proposal.action()} should be accepted")
        }
        val stale = request.copy(currentDependencyRevisions = mapOf("input" to io.workflow.core.AssignmentId("r2")))
        assertEquals("STALE_DEPENDENCY_REVISIONS", RecoveryValidator.validate(RecoveryProposal.Retry(), stale, policy, emptySet(), AgentBudgetMeter(AgentBudget(), now), gate, now).reason)
    }

    @Test fun `human intervention is typed durable pause then resume`() {
        val ledger = InMemoryRecoveryLedger()
        ledger.pause(HumanIntervention("h", "a", "continue?", listOf(Value.StringValue("yes"), Value.StringValue("no"))))
        assertEquals(HumanInterventionState.PAUSED, ledger.intervention("h")!!.state)
        assertEquals(Value.StringValue("yes"), ledger.submitAnswer("h", Value.StringValue("yes")).answer)
        assertThrows(IllegalArgumentException::class.java) { ledger.submitAnswer("h", Value.StringValue("no")) }
    }

    @Test fun `recovery decision and human answer survive restart without invoking agent again`() {
        val database = temporaryDirectory.resolve("agent-recovery.db")
        val request = RecoveryRequest(
            "activation", io.workflow.core.InvocationId("recovery-invocation"), Value.StringValue("failure"),
            mapOf("input" to io.workflow.core.AssignmentId("r1")), mapOf("input" to io.workflow.core.AssignmentId("r1")),
            executionId = io.workflow.core.ExecutionId("execution"),
        )
        val recoveryDescriptor = AgenticDescriptor(
            "pinned", "recovery-v1", "prompt-v1",
            mapOf("recovery:emit" to AgentToolDescriptor(EffectClass.PURE)),
        )
        val policy = RecoveryPolicy(setOf(RecoveryAction.EMIT), emptySet())
        var proposals = 0
        val firstExecutions = mutableListOf<RecoveryProposal>()
        SqliteJournalStore(database, FixedClock(now)).use { store ->
            val outcome = RecoveryCoordinator(store) { now }.recover(
                request, policy, emptySet(), recoveryDescriptor, emptySet(),
                propose = { proposals++; RecoveryProposal.Emit(Value.StringValue("recovered")) },
                execute = firstExecutions::add,
            )
            assertEquals(RecoveryOutcome.Executed(RecoveryProposal.Emit(Value.StringValue("recovered")), false), outcome)
        }
        val replayExecutions = mutableListOf<RecoveryProposal>()
        SqliteJournalStore(database, FixedClock(now)).use { reopened ->
            val replayed = RecoveryCoordinator(reopened) { now }.recover(
                request, policy, emptySet(), recoveryDescriptor, emptySet(),
                propose = { error("recovery agent must not run during replay") },
                execute = replayExecutions::add,
            )
            assertEquals(RecoveryOutcome.Executed(RecoveryProposal.Emit(Value.StringValue("recovered")), true), replayed)
        }
        assertEquals(1, proposals)
        assertEquals(firstExecutions, replayExecutions)

        val proposalOnlyRequest = request.copy(invocationId = io.workflow.core.InvocationId("proposal-only"))
        SqliteJournalStore(database, FixedClock(now)).use { store ->
            store.recordProposal(proposalOnlyRequest, RecoveryProposal.Emit(Value.StringValue("recorded-before-crash")))
        }
        SqliteJournalStore(database, FixedClock(now)).use { reopened ->
            val resumed = RecoveryCoordinator(reopened) { now }.recover(
                proposalOnlyRequest, policy, emptySet(), recoveryDescriptor, emptySet(),
                propose = { error("recorded proposal must prevent reinvoking the recovery agent") }, execute = {},
            ) as RecoveryOutcome.Executed
            assertEquals(RecoveryProposal.Emit(Value.StringValue("recorded-before-crash")), resumed.proposal)
        }

        val humanRequest = request.copy(invocationId = io.workflow.core.InvocationId("human-invocation"))
        val humanDescriptor = recoveryDescriptor.copy(tools = mapOf("recovery:request_human" to AgentToolDescriptor(EffectClass.PURE)))
        SqliteJournalStore(database, FixedClock(now)).use { store ->
            val outcome = RecoveryCoordinator(store) { now }.recover(
                humanRequest, RecoveryPolicy(setOf(RecoveryAction.REQUEST_HUMAN), emptySet()), emptySet(), humanDescriptor, emptySet(),
                propose = { RecoveryProposal.RequestHuman("Continue?", listOf(Value.StringValue("yes"))) }, execute = { error("pause is not executed") },
            ) as RecoveryOutcome.Paused
            assertEquals(HumanInterventionState.PAUSED, outcome.intervention.state)
        }
        SqliteJournalStore(database, FixedClock(now)).use { reopened ->
            assertEquals(HumanInterventionState.PAUSED, reopened.intervention("human-human-invocation")!!.state)
            reopened.submitAnswer("human-human-invocation", Value.StringValue("yes"))
        }
        SqliteJournalStore(database, FixedClock(now)).use { reopened ->
            assertEquals(HumanInterventionState.ANSWERED, reopened.intervention("human-human-invocation")!!.state)
            assertEquals(listOf(HumanInterventionState.PAUSED, HumanInterventionState.ANSWERED), reopened.interventionHistory("human-human-invocation").map { it.state })
            val resumed = RecoveryCoordinator(reopened) { now }.recover(
                humanRequest, RecoveryPolicy(setOf(RecoveryAction.REQUEST_HUMAN), emptySet()), emptySet(), humanDescriptor, emptySet(),
                propose = { error("recovery agent must not run after a human answer") }, execute = { error("human answer is data, not an unvalidated action") },
            ) as RecoveryOutcome.HumanAnswered
            assertEquals(Value.StringValue("yes"), resumed.intervention.answer)
            assertTrue(resumed.replayed)
        }
    }

    @Test fun `recovery enforces action authority budget and declared tools before execution`() {
        val request = RecoveryRequest(
            "a", io.workflow.core.InvocationId("i"), Value.StringValue("failure"), emptyMap(), emptyMap(),
        )
        val compensate = RecoveryProposal.Compensate("undo")
        val descriptor = AgenticDescriptor(
            "pinned", "recovery-v1", "prompt-v1",
            mapOf("recovery:compensate" to AgentToolDescriptor(EffectClass.EFFECT)),
        )
        val authorityRefusal = RecoveryValidator.validate(
            compensate, request,
            RecoveryPolicy(setOf(RecoveryAction.COMPENSATE), emptySet(), actionCapabilities = mapOf(RecoveryAction.COMPENSATE to setOf("write"))),
            emptySet(), AgentBudgetMeter(AgentBudget(), now), AgentCapabilityGate(descriptor, emptySet()), now,
        )
        assertEquals("RECOVERY_AUTHORITY_EXCEEDED", authorityRefusal.reason)
        val budgetRefusal = RecoveryValidator.validate(
            compensate, request,
            RecoveryPolicy(setOf(RecoveryAction.COMPENSATE), setOf("write"), budget = AgentBudget(maxIterations = 0), actionCapabilities = mapOf(RecoveryAction.COMPENSATE to setOf("write"))),
            emptySet(), AgentBudgetMeter(AgentBudget(maxIterations = 0), now), AgentCapabilityGate(descriptor, emptySet()), now,
        )
        assertEquals("RECOVERY_BUDGET_EXHAUSTED", budgetRefusal.reason)
        val undeclaredTool = RecoveryValidator.validate(
            RecoveryProposal.Abort("stop"), request, RecoveryPolicy(setOf(RecoveryAction.ABORT), emptySet()),
            emptySet(), AgentBudgetMeter(AgentBudget(), now), AgentCapabilityGate(descriptor, emptySet()), now,
        )
        assertEquals("UNDECLARED_TOOL", undeclaredTool.reason)
    }

    @Test fun `agent output acceptance is enforced and deterministic fallback precedes agent recovery`() {
        val invalidDescriptor = descriptor(AgentBudget()).copy(
            emissionSchema = ValueSchema.Any,
            agentic = descriptor(AgentBudget()).agentic!!.copy(outputAcceptance = AgentOutputAcceptance(ValueSchema.String)),
        )
        val invalidAgent = object : AgenticProviderImplementation {
            override fun resolveMetadata(request: ProviderInvocationRequest, now: Instant) = ResolvedAgentMetadata("fake", emptyMap(), now)
            override fun invokeAgentic(request: ProviderInvocationRequest, environment: AgenticInvocationEnvironment) = listOf(
                ProviderLifecycleMessage.Emission(Value.IntegerValue(java.math.BigInteger.ONE), io.workflow.core.EmissionId("invalid"), request.invocationId, request.attemptId),
                ProviderLifecycleMessage.Completed,
            )
        }
        val registry = ProviderRegistry().also { it.register(invalidDescriptor, invalidAgent) }
        val workflow = WorkflowCompiler(registry).compile("""
            workflow:
              id: invalid-agent-output
              version: 1
              context:
                answer: {provider: agent, version: 1, schema: any}
              outputs: [answer]
        """.trimIndent()).ir!!
        val journal = InMemoryJournalStore(FixedClock(now), DeterministicIdSource("invalid-"))
        InMemoryWorkflowRunner(WorkflowCompiler(registry), FixedClock(now), DeterministicIdSource("runner-"), journal = journal, providerRegistry = registry).execute(workflow)
        assertTrue(journal.providerEvents().any { it.type == ProviderEventType.EMISSION_REFUSED && it.diagnostic!!.contains("INVALID_OUTPUT") })

        val request = RecoveryRequest("a", io.workflow.core.InvocationId("deterministic"), Value.StringValue("failed"), emptyMap(), emptyMap())
        val recoveryDescriptor = AgenticDescriptor("pinned", "r", "p", mapOf("recovery:skip" to AgentToolDescriptor(EffectClass.PURE)))
        var agentCalled = false
        val executed = mutableListOf<RecoveryProposal>()
        val outcome = RecoveryCoordinator(InMemoryRecoveryLedger()) { now }.recover(
            request, RecoveryPolicy(setOf(RecoveryAction.SKIP), emptySet()), emptySet(), recoveryDescriptor, emptySet(),
            deterministic = { RecoveryProposal.Skip("configured fallback") },
            propose = { agentCalled = true; RecoveryProposal.Abort("agent") }, execute = executed::add,
        )
        assertEquals(RecoveryOutcome.Executed(RecoveryProposal.Skip("configured fallback"), false), outcome)
        assertTrue(!agentCalled)
        assertEquals(listOf(RecoveryProposal.Skip("configured fallback")), executed)
    }

    @Test fun `human answer CLI submits a typed durable answer`() {
        val database = temporaryDirectory.resolve("human-cli.db")
        SqliteJournalStore(database, FixedClock(now)).use { store ->
            store.pause(HumanIntervention("cli-human", "activation", "Continue?", listOf(Value.StringValue("yes"))))
        }
        val original = System.out
        val output = ByteArrayOutputStream()
        try {
            System.setOut(PrintStream(output))
            io.workflow.main(arrayOf("human-answer", database.toString(), "--intervention", "cli-human", "--answer", "\"yes\""))
        } finally {
            System.setOut(original)
        }
        assertTrue(output.toString().contains("\"state\":\"answered\""))
        SqliteJournalStore(database, FixedClock(now)).use { reopened ->
            assertEquals(Value.StringValue("yes"), reopened.intervention("cli-human")!!.answer)
        }
    }

    private fun descriptor(budget: AgentBudget) = ProviderDescriptor(
        "agent", 1, ValueSchema.Any, ValueSchema.String, effectClass = EffectClass.AGENTIC,
        idempotency = IdempotencyContract(ReconciliationMode.HUMAN_INTERVENTION),
        agentic = AgenticDescriptor("pinned", "strategy-v1", "prompt-v1", mapOf("read" to AgentToolDescriptor(EffectClass.READ)), budgets = budget),
    )
}
