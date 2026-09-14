package io.workflow.runtime

import io.workflow.compiler.WorkflowCompiler
import io.workflow.compiler.WorkflowIrCodec
import io.workflow.core.DeterministicIdSource
import io.workflow.core.ContextId
import io.workflow.core.AttemptId
import io.workflow.core.AssignmentId
import io.workflow.core.AssignmentMutation
import io.workflow.core.EmissionId
import io.workflow.core.ExecutionId
import io.workflow.core.FixedClock
import io.workflow.core.InvocationId
import io.workflow.core.JournalBatchId
import io.workflow.core.Value
import io.workflow.core.ValueSchema
import io.workflow.core.WorkflowId
import io.workflow.provider.EffectClass
import io.workflow.provider.IdempotencyContract
import io.workflow.provider.ProviderDescriptor
import io.workflow.provider.ProviderImplementation
import io.workflow.provider.ProviderLifecycleMessage
import io.workflow.provider.ProviderRegistry
import io.workflow.provider.ReconciliationMode
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createTempFile
import kotlin.io.path.deleteIfExists
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class RestartReplayTest {
    @TempDir lateinit var tempDir: Path

    private val expressionWorkflow = """
        workflow:
          id: restart
          version: 1
          context:
            source: one
            copied: {${'$'}ref: "${'$'}.source"}
          outputs: [copied]
    """.trimIndent()

    @Test
    fun `canonical deployed IR round trips and keeps its hash`() {
        val compiled = WorkflowCompiler().compile(expressionWorkflow).ir!!
        val decoded = WorkflowIrCodec.decode(compiled.canonicalJson())
        assertEquals(compiled.canonicalJson(), decoded.canonicalJson())
    }

    @Test
    fun `reopen resumes without invoking completed provider`() {
        val path = tempDir.resolve("resume.db")
        val calls = AtomicInteger()
        val registry = registry { request ->
            calls.incrementAndGet()
            listOf(ProviderLifecycleMessage.Emission(Value.StringValue("value"), io.workflow.core.EmissionId("e"), request.invocationId, request.attemptId), ProviderLifecycleMessage.Completed)
        }
        val workflow = WorkflowCompiler(registry).compile(providerWorkflow()).ir!!
        SqliteJournalStore(path, FixedClock(Instant.EPOCH), DeterministicIdSource("store-")).use { store ->
            InMemoryWorkflowRunner(compiler = WorkflowCompiler(registry), journal = store, providerRegistry = registry).execute(
                workflow, executionId = ExecutionId("e"),
            )
        }
        SqliteJournalStore(path, FixedClock(Instant.EPOCH), DeterministicIdSource("reopen-")).use { store ->
            val refusing = registry { error("completed providers must not be invoked during resume") }
            val result = InMemoryWorkflowRunner(compiler = WorkflowCompiler(refusing), journal = store, providerRegistry = refusing)
                .resume(ExecutionId("e")).result()
            assertEquals(Value.StringValue("value"), result.outputs.getValue("value").value)
            assertEquals(1, calls.get())
            assertEquals(1, store.providerInvocations().size)
        }
    }

    @Test
    fun `replay rebuilds outputs without invoking providers`() {
        val calls = AtomicInteger()
        val registry = registry { request ->
            calls.incrementAndGet()
            listOf(ProviderLifecycleMessage.Emission(Value.StringValue("value"), io.workflow.core.EmissionId("e"), request.invocationId, request.attemptId), ProviderLifecycleMessage.Completed)
        }
        val workflow = WorkflowCompiler(registry).compile(providerWorkflow()).ir!!
        val journal = InMemoryJournalStore(FixedClock(Instant.EPOCH), DeterministicIdSource("memory-"))
        InMemoryWorkflowRunner(compiler = WorkflowCompiler(registry), journal = journal, providerRegistry = registry).execute(
            workflow, executionId = ExecutionId("replay"),
        )
        val replay = WorkflowReplay.replayOne(journal, ExecutionId("replay"))
        assertEquals(Value.StringValue("value"), replay.outputs(workflow).getValue("value").value)
        assertEquals(1, calls.get())
        assertTrue(replay.providerEvents.isNotEmpty())
    }

    @Test
    fun `pending and claimed activations resume after a real database reopen`() {
        listOf(false, true).forEach { claimed ->
            val path = tempDir.resolve("pending-$claimed.db")
            val calls = AtomicInteger()
            val registry = registry { request ->
                calls.incrementAndGet()
                listOf(
                    ProviderLifecycleMessage.Emission(
                        Value.StringValue("resumed"), io.workflow.core.EmissionId("resumed"), request.invocationId, request.attemptId,
                    ),
                    ProviderLifecycleMessage.Completed,
                )
            }
            val workflow = WorkflowCompiler(registry).compile(providerWorkflow()).ir!!
            seedExecution(path, workflow, claimed = claimed)

            SqliteJournalStore(path, FixedClock(Instant.EPOCH), DeterministicIdSource("resume-$claimed-")).use { store ->
                val result = InMemoryWorkflowRunner(
                    compiler = WorkflowCompiler(registry), journal = store, providerRegistry = registry,
                ).resume(ExecutionId("pending-$claimed")).result()
                assertEquals(Value.StringValue("resumed"), result.outputs.getValue("value").value)
                assertEquals(1, calls.get())
            }
        }
    }

    @Test
    fun `interrupted pure attempt retries under its logical invocation while effect attempt is held ambiguous`() {
        listOf(EffectClass.PURE, EffectClass.READ, EffectClass.EFFECT, EffectClass.AGENTIC).forEach { effectClass ->
            val path = tempDir.resolve("interrupted-${effectClass.name}.db")
            val calls = AtomicInteger()
            val registry = registry(effectClass) { request ->
                calls.incrementAndGet()
                listOf(
                    ProviderLifecycleMessage.Emission(
                        Value.StringValue("retried"), io.workflow.core.EmissionId("retried"), request.invocationId, request.attemptId,
                    ),
                    ProviderLifecycleMessage.Completed,
                )
            }
            val workflow = WorkflowCompiler(registry).compile(providerWorkflow()).ir!!
            val unsafe = effectClass == EffectClass.EFFECT || effectClass == EffectClass.AGENTIC
            val seeded = seedExecution(
                path,
                workflow,
                claimed = true,
                interruptedAttempt = true,
                interruptedEmission = unsafe,
            )

            SqliteJournalStore(path, FixedClock(Instant.EPOCH), DeterministicIdSource("resume-${effectClass.name}-")).use { store ->
                val host = InMemoryWorkflowRunner(
                    compiler = WorkflowCompiler(registry), journal = store, providerRegistry = registry,
                ).resume(ExecutionId("pending-true"))
                if (effectClass == EffectClass.PURE || effectClass == EffectClass.READ) {
                    assertEquals(1, calls.get())
                    assertEquals(Value.StringValue("retried"), host.result().outputs.getValue("value").value)
                    assertEquals(1, store.providerInvocations().map { it.invocationId }.distinct().size)
                    assertEquals(2, store.providerAttempts().size)
                } else {
                    assertEquals(0, calls.get())
                    assertTrue(store.isAmbiguous(seeded.id))
                    assertEquals(ActivationRecord.Status.AMBIGUOUS, store.activations().single().status)
                    assertEquals(Value.StringValue("interrupted"), host.result().outputs.getValue("value").value)
                    assertEquals(0, host.result().journal.assignments().count { it.value == Value.StringValue("retried") })
                }
            }
        }
    }

    @Test
    fun `resume rejects incompatible IR before claiming pending work`() {
        val path = tempDir.resolve("incompatible.db")
        val registry = registry { error("incompatible workflow must not invoke providers") }
        val workflow = WorkflowCompiler(registry).compile(providerWorkflow()).ir!!
        val intent = seedExecution(path, workflow, claimed = false)
        val incompatible = workflow.copy(contentHash = "incompatible")

        SqliteJournalStore(path, FixedClock(Instant.EPOCH)).use { store ->
            val runner = InMemoryWorkflowRunner(compiler = WorkflowCompiler(registry), journal = store, providerRegistry = registry)
            assertThrows(IllegalArgumentException::class.java) {
                runner.resume(incompatible, ExecutionId("pending-false"))
            }
            assertEquals(intent, store.claimNextActivation(ExecutionId("pending-false")))
        }
    }

    private fun providerWorkflow() = """
        workflow:
          id: provider-restart
          version: 1
          context:
            value: {provider: restart-provider, version: 1}
          outputs: [value]
    """.trimIndent()

    private fun registry(
        effectClass: EffectClass = EffectClass.PURE,
        implementation: ProviderImplementation,
    ) = ProviderRegistry().also {
        it.register(
            ProviderDescriptor("restart-provider", 1, ValueSchema.Any, ValueSchema.String, effectClass = effectClass,
                idempotency = if (effectClass in setOf(EffectClass.EFFECT, EffectClass.AGENTIC))
                    IdempotencyContract(ReconciliationMode.HUMAN_INTERVENTION) else null),
            implementation,
        )
    }

    private fun seedExecution(
        path: Path,
        workflow: io.workflow.compiler.WorkflowIrDocument,
        claimed: Boolean,
        interruptedAttempt: Boolean = false,
        interruptedEmission: Boolean = false,
    ): io.workflow.core.ActivationIntent {
        val executionId = ExecutionId("pending-$claimed")
        val intent = ExpressionActivationPlanner().initial(
            workflow,
            executionId,
            ContextId(InMemoryWorkflowRunner.ANONYMOUS_CONTEXT),
            JournalBatchId(InMemoryWorkflowRunner.STARTUP_BATCH),
            Instant.EPOCH,
        ).single()
        SqliteJournalStore(path, FixedClock(Instant.EPOCH), DeterministicIdSource("seed-")).use { store ->
            store.recordWorkflowDefinition(
                WorkflowId(workflow.workflowId), workflow.workflowVersionId, workflow.contentHash, workflow.canonicalJson(),
            )
            store.bindExecution(executionId, workflow.workflowVersionId, workflow.contentHash, emptyMap())
            store.persistActivationIntents(listOf(intent))
            if (claimed) assertEquals(intent, store.claimNextActivation(executionId))
            if (interruptedAttempt) {
                val invocationId = invocationId(intent)
                val attemptId = AttemptId("interrupted-attempt")
                store.recordProviderEvent(event("invocation", ProviderEventType.INVOCATION, intent, invocationId, null))
                store.recordProviderEvent(event("attempt", ProviderEventType.ATTEMPT_STARTED, intent, invocationId, attemptId))
                if (interruptedEmission) {
                    val emissionId = EmissionId("interrupted-emission")
                    store.commit(
                        io.workflow.core.JournalBatch(
                            JournalBatchId("interrupted-batch"),
                            listOf(AssignmentMutation(
                                assignmentId = AssignmentId("interrupted-assignment"),
                                workflowId = intent.workflowId,
                                workflowVersionId = intent.workflowVersionId,
                                executionId = intent.executionId,
                                contextId = intent.contextId,
                                registerId = workflow.registers.single().registerId,
                                value = Value.StringValue("interrupted"),
                                producerId = intent.producerId,
                                activationId = intent.activationId,
                                dependencyRevisions = intent.dependencyRevisions,
                                invocationId = invocationId,
                                emissionId = emissionId,
                                occurredAt = Instant.EPOCH,
                            )),
                            Instant.EPOCH,
                        ),
                    )
                    val received = event("received", ProviderEventType.EMISSION_RECEIVED, intent, invocationId, attemptId)
                        .copy(emissionId = emissionId, value = Value.StringValue("interrupted"))
                    store.recordProviderEvent(received)
                    store.recordProviderEvent(
                        received.copy(eventId = "accepted", type = ProviderEventType.EMISSION_ACCEPTED, causationId = received.eventId),
                    )
                }
            }
        }
        return intent
    }

    private fun event(
        id: String,
        type: ProviderEventType,
        intent: io.workflow.core.ActivationIntent,
        invocationId: InvocationId,
        attemptId: AttemptId?,
    ) = ProviderLifecycleEvent(
        eventId = id,
        type = type,
        workflowId = intent.workflowId,
        workflowVersionId = intent.workflowVersionId,
        executionId = intent.executionId,
        contextId = intent.contextId,
        producerId = intent.producerId,
        activationId = intent.activationId,
        intentId = intent.id,
        invocationId = invocationId,
        attemptId = attemptId,
        occurredAt = Instant.EPOCH,
        providerId = "restart-provider",
        providerVersion = 1,
    )

    private fun invocationId(intent: io.workflow.core.ActivationIntent): InvocationId {
        val source = buildString {
            append(intent.executionId.value).append('|')
            append(intent.contextId.value).append('|').append(intent.producerId.value)
            intent.dependencyRevisions.toSortedMap(compareBy { it.value }).forEach { (register, assignment) ->
                append('|').append(register.value).append('=').append(assignment.value)
            }
        }
        val hash = java.security.MessageDigest.getInstance("SHA-256")
            .digest(source.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return InvocationId("invocation-$hash")
    }
}
