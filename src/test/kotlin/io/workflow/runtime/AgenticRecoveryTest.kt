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
import io.workflow.provider.AgentToolDescriptor
import io.workflow.provider.EffectClass
import io.workflow.provider.ProviderDescriptor
import io.workflow.provider.ProviderInvocationRequest
import io.workflow.provider.ProviderLifecycleMessage
import io.workflow.provider.ProviderRegistry
import io.workflow.provider.ResolvedAgentMetadata
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AgenticRecoveryTest {
    private val now = Instant.EPOCH

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

    private fun descriptor(budget: AgentBudget) = ProviderDescriptor(
        "agent", 1, ValueSchema.Any, ValueSchema.String, effectClass = EffectClass.AGENTIC,
        agentic = AgenticDescriptor("pinned", "strategy-v1", "prompt-v1", mapOf("read" to AgentToolDescriptor(EffectClass.READ)), budgets = budget),
    )
}
