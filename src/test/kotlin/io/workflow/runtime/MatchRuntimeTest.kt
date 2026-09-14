package io.workflow.runtime

import io.workflow.compiler.WorkflowCompiler
import io.workflow.core.DeterministicIdSource
import io.workflow.core.EmissionId
import io.workflow.core.ExecutionId
import io.workflow.core.FixedClock
import io.workflow.core.Value
import io.workflow.core.ValueSchema
import io.workflow.core.AssignmentMutation
import io.workflow.core.ContextId
import io.workflow.core.JournalBatch
import io.workflow.core.JournalBatchId
import io.workflow.core.ProducerId
import io.workflow.core.RegisterId
import io.workflow.core.WorkflowId
import io.workflow.core.WorkflowVersionId
import io.workflow.core.ActivationIntent
import io.workflow.core.ActivationIntentId
import io.workflow.core.ActivationId
import io.workflow.provider.ProviderDescriptor
import io.workflow.provider.ProviderLifecycleMessage
import io.workflow.provider.ProviderRegistry
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MatchRuntimeTest {
    private fun descriptor(id: String) = ProviderDescriptor(
        providerId = id,
        version = 1,
        inputSchema = ValueSchema.Any,
        emissionSchema = ValueSchema.String,
        configurationSchema = ValueSchema.Any,
    )

    private val taggedSchema = ValueSchema.TaggedUnion(
        "kind",
        mapOf(
            "Ready" to ValueSchema.Object(mapOf(
                "kind" to ValueSchema.Object.Field(ValueSchema.String),
                "value" to ValueSchema.Object.Field(ValueSchema.String),
            )),
            "Failed" to ValueSchema.Object(mapOf(
                "kind" to ValueSchema.Object.Field(ValueSchema.String),
                "error" to ValueSchema.Object.Field(ValueSchema.String),
            )),
        ),
    )

    private fun yaml(branches: String) = """
        workflow:
          id: matched-runtime
          version: 1
          parameters:
            result:
              schema:
                type: tagged-union
                discriminator: kind
                variants:
                  Ready:
                    type: object
                    fields:
                      kind: {schema: string}
                      value: {schema: string}
                  Failed:
                    type: object
                    fields:
                      kind: {schema: string}
                      error: {schema: string}
          context:
            output:
              schema: string
              match:
                value: {${'$'}ref: "${'$'}.parameters.result"}
                cases:
        ${branches.prependIndent("          ")}
          outputs: [output]
    """.trimIndent()

    @Test
    fun `only selected provider branch invokes and carries match provenance`() {
        val selected = AtomicInteger()
        val unselected = AtomicInteger()
        val registry = ProviderRegistry()
        registry.register(descriptor("ready")) { request ->
            selected.incrementAndGet()
            listOf(
                ProviderLifecycleMessage.Emission(
                    Value.StringValue("ready-result"), EmissionId("ready"), request.invocationId, request.attemptId,
                ),
                ProviderLifecycleMessage.Completed,
            )
        }
        registry.register(descriptor("failed")) { request ->
            unselected.incrementAndGet()
            listOf(ProviderLifecycleMessage.Completed)
        }
        val result = InMemoryWorkflowRunner(
            compiler = WorkflowCompiler(registry),
            idSource = DeterministicIdSource("match-"),
            clock = FixedClock(Instant.EPOCH),
        ).run(
            yaml("""
              Ready: {provider: ready, version: 1}
              Failed: {provider: failed, version: 1}
            """),
            mapOf("result" to Value.ObjectValue(mapOf(
                "kind" to Value.StringValue("Ready"), "value" to Value.StringValue("v"),
            ))),
            ExecutionId("match-execution"),
        )

        assertTrue(result.isSuccessful, result.failures.joinToString())
        assertEquals(Value.StringValue("ready-result"), result.outputs.getValue("output").value)
        assertEquals(1, selected.get())
        assertEquals(0, unselected.get())
        val assignment = result.journal.assignments().single { it.registerId.value.endsWith("/register/output") }
        assertEquals(null, assignment.discriminatorRevision)
        assertTrue(assignment.parentActivationId != null)
        val event = result.journal.providerInvocations().single()
        assertEquals(assignment.discriminatorRevision, event.discriminatorRevision)
        assertEquals(assignment.parentActivationId, event.parentActivationId)
    }

    @Test
    fun `selected branch failure does not fabricate match output`() {
        val calls = AtomicInteger()
        val registry = ProviderRegistry()
        registry.register(descriptor("ready")) { _ ->
            calls.incrementAndGet()
            listOf(ProviderLifecycleMessage.Failed(Value.StringValue("branch unavailable")))
        }
        registry.register(descriptor("failed")) { _ ->
            error("unselected branch must not invoke")
        }
        val result = InMemoryWorkflowRunner(
            compiler = WorkflowCompiler(registry), idSource = DeterministicIdSource("failure-"), clock = FixedClock(Instant.EPOCH),
        ).run(
            yaml("""
              Ready: {provider: ready, version: 1}
              Failed: {provider: failed, version: 1}
            """),
            mapOf("result" to Value.ObjectValue(mapOf(
                "kind" to Value.StringValue("Ready"), "value" to Value.StringValue("v"),
            ))),
            ExecutionId("branch-failure"),
        )
        assertFalse(result.isSuccessful)
        assertTrue(result.failures.single().contains("branch unavailable"))
        assertEquals(1, calls.get())
        assertTrue(result.journal.assignments().isEmpty())
        assertTrue(result.journal.providerFailures().single().parentActivationId != null)
    }

    @Test
    fun `revisions select branches independently and retain earlier assignments`() {
        val discriminatorCalls = AtomicInteger()
        val branchCalls = AtomicInteger()
        val registry = ProviderRegistry()
        registry.register(descriptor("source").copy(emissionSchema = taggedSchema)) { request ->
            discriminatorCalls.incrementAndGet()
            listOf(
                ProviderLifecycleMessage.Emission(
                    Value.ObjectValue(mapOf("kind" to Value.StringValue("Ready"), "value" to Value.StringValue("one"))),
                    EmissionId("one"), request.invocationId, request.attemptId,
                ),
                ProviderLifecycleMessage.Emission(
                    Value.ObjectValue(mapOf("kind" to Value.StringValue("Ready"), "value" to Value.StringValue("again"))),
                    EmissionId("again"), request.invocationId, request.attemptId,
                ),
                ProviderLifecycleMessage.Emission(
                    Value.ObjectValue(mapOf("kind" to Value.StringValue("Failed"), "error" to Value.StringValue("two"))),
                    EmissionId("two"), request.invocationId, request.attemptId,
                ),
                ProviderLifecycleMessage.Completed,
            )
        }
        registry.register(descriptor("ready")) { request ->
            branchCalls.incrementAndGet()
            listOf(ProviderLifecycleMessage.Emission(Value.StringValue("ready"), EmissionId("ready"), request.invocationId, request.attemptId), ProviderLifecycleMessage.Completed)
        }
        registry.register(descriptor("failed")) { request ->
            branchCalls.incrementAndGet()
            listOf(ProviderLifecycleMessage.Emission(Value.StringValue("failed"), EmissionId("failed"), request.invocationId, request.attemptId), ProviderLifecycleMessage.Completed)
        }
        val result = InMemoryWorkflowRunner(
            compiler = WorkflowCompiler(registry), idSource = DeterministicIdSource("revisions-"), clock = FixedClock(Instant.EPOCH), workerCount = 1,
        ).run(
            """
            workflow:
              id: revisions
              version: 1
              context:
                result: {provider: source, version: 1}
                output:
                  schema: string
                  match:
                    value: {${'$'}ref: "${'$'}.result"}
                    cases:
                      Ready: {provider: ready, version: 1}
                      Failed: {provider: failed, version: 1}
              outputs: [output]
            """.trimIndent(),
            executionId = ExecutionId("revisions"),
        )
        assertTrue(result.isSuccessful, result.failures.joinToString())
        assertEquals(Value.StringValue("failed"), result.outputs.getValue("output").value)
        assertEquals(1, discriminatorCalls.get())
        assertEquals(3, branchCalls.get())
        val outputs = result.journal.assignments().filter { it.registerId.value.endsWith("/register/output") }
        assertEquals(3, outputs.size)
        assertEquals(listOf(1L, 2L, 3L), outputs.map { it.revision })
        assertTrue(outputs.all { it.discriminatorRevision != null && it.parentActivationId != null })
        assertEquals(setOf("Ready", "Failed"), result.journal.activationIntents().mapNotNull { it.branchTag }.toSet())
    }

    @Test
    fun `malformed runtime tag is a typed match activation failure`() {
        val registry = ProviderRegistry()
        registry.register(descriptor("source").copy(emissionSchema = taggedSchema)) { error("source must not invoke") }
        val compiler = WorkflowCompiler(registry)
        val compilation = compiler.compile(
            """
            workflow:
              id: malformed-runtime
              version: 1
              context:
                source: {provider: source, version: 1}
                output:
                  schema: string
                  match:
                    value: {${'$'}ref: "${'$'}.source"}
                    cases:
                      Ready: ready
                      Failed: failed
              outputs: [output]
            """.trimIndent(),
        )
        assertTrue(compilation.isValid, compilation.diagnostics.joinToString())
        val workflow = compilation.ir!!
        val source = workflow.registers.single { it.name == "source" }
        val match = workflow.registers.single { it.name == "output" }
        val journal = InMemoryJournalStore(FixedClock(Instant.EPOCH), DeterministicIdSource("malformed-"))
        journal.commit(
            JournalBatch(
                JournalBatchId("malformed-batch"),
                listOf(AssignmentMutation(
                    assignmentId = io.workflow.core.AssignmentId("malformed-assignment"),
                    workflowId = WorkflowId(workflow.workflowId), workflowVersionId = workflow.workflowVersionId,
                    executionId = ExecutionId("malformed"), contextId = ContextId("anonymous"), registerId = source.registerId,
                    value = Value.ObjectValue(mapOf("kind" to Value.StringValue("NotDeclared"))), producerId = source.producerId,
                    occurredAt = Instant.EPOCH,
                )),
                Instant.EPOCH,
            ),
        )
        val planner = object : ActivationPlanner {
            override fun initial(workflow: io.workflow.compiler.WorkflowIrDocument, executionId: ExecutionId, contextId: ContextId, journalBatchId: JournalBatchId, createdAt: Instant) = listOf(
                ActivationIntent(
                    id = ActivationIntentId("malformed-intent"), activationId = ActivationId("malformed-activation"),
                    producerId = match.producerId, workflowId = WorkflowId(workflow.workflowId), workflowVersionId = workflow.workflowVersionId,
                    executionId = executionId, contextId = contextId, journalBatchId = journalBatchId, createdAt = createdAt,
                    dependencyRevisions = mapOf(source.registerId to io.workflow.core.AssignmentId("malformed-assignment")),
                ),
            )

            override fun afterAssignment(workflow: io.workflow.compiler.WorkflowIrDocument, executionId: ExecutionId, contextId: ContextId, assignment: AssignmentMutation, current: Map<RegisterKey, AssignmentMutation>, journalBatchId: JournalBatchId, createdAt: Instant) = emptyList<ActivationIntent>()
        }
        val result = InMemoryWorkflowRunner(
            compiler = compiler, journal = journal, planner = planner, providerRegistry = registry,
        ).execute(workflow, executionId = ExecutionId("malformed"))
        assertFalse(result.isSuccessful)
        assertTrue(result.failures.single().contains("MATCH_UNKNOWN_TAG"))
        assertTrue(result.journal.assignments().none { it.registerId == match.registerId })
    }
}
