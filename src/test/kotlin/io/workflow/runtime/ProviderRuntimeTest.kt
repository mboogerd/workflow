package io.workflow.runtime

import io.workflow.compiler.WorkflowCompiler
import io.workflow.core.EmissionId
import io.workflow.core.ExecutionId
import io.workflow.core.FixedClock
import io.workflow.core.InvocationId
import io.workflow.core.Value
import io.workflow.provider.ProviderDescriptor
import io.workflow.provider.ProviderImplementation
import io.workflow.provider.ProviderLifecycleMessage
import io.workflow.provider.ProviderRegistry
import io.workflow.core.ValueSchema
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ProviderRuntimeTest {
    private fun descriptor(id: String = "test-provider") = ProviderDescriptor(
        providerId = id,
        version = 1,
        inputSchema = ValueSchema.Any,
        emissionSchema = ValueSchema.String,
        configurationSchema = ValueSchema.Any,
    )

    private fun yaml(id: String = "test-provider", output: String = "value") = """
        workflow:
          id: provider-runtime
          version: 1
          context:
            value:
              provider: $id
              version: 1
            copied: {${'$'}ref: "${'$'}.value"}
          outputs: [$output]
    """.trimIndent()

    private fun registry(
        id: String = "test-provider",
        implementation: ProviderImplementation,
    ) = ProviderRegistry().also { it.register(descriptor(id), implementation) }

    @Test
    fun `provider emissions commit independently and activate downstream work`() {
        val registry = registry { request ->
            listOf(
                ProviderLifecycleMessage.Emission(
                    Value.StringValue("one"), EmissionId("one"), request.invocationId, request.attemptId,
                ),
                ProviderLifecycleMessage.Emission(
                    Value.StringValue("two"), EmissionId("two"), request.invocationId, request.attemptId,
                ),
                ProviderLifecycleMessage.Completed,
            )
        }
        val compiler = WorkflowCompiler(registry)
        val result = InMemoryWorkflowRunner(
            compiler = compiler,
            idSource = io.workflow.core.DeterministicIdSource("provider-"),
            clock = FixedClock(Instant.EPOCH),
            workerCount = 1,
        ).run(yaml(output = "copied"), executionId = ExecutionId("provider-execution"))

        assertTrue(result.isSuccessful, result.failures.joinToString())
        assertEquals(Value.StringValue("two"), result.outputs.getValue("copied").value)
        assertEquals(4, result.journal.assignments().size)
        assertTrue(result.journal.assignments().groupBy { it.registerId }.values.all { assignments ->
            assignments.map { it.revision } == listOf(1L, 2L)
        })
        assertEquals(1, result.journal.providerInvocations().size)
        assertEquals(1, result.journal.providerAttempts().size)
        assertEquals(2, result.journal.providerEmissions().count { it.kind == ProviderEventType.EMISSION_ACCEPTED })
        assertTrue(result.inspectionJson().contains("providerEvents"))
    }

    @Test
    fun `zero emissions complete without fabricating an assignment`() {
        val registry = registry { _ -> listOf(ProviderLifecycleMessage.Completed) }
        val result = InMemoryWorkflowRunner(
            compiler = WorkflowCompiler(registry),
            idSource = io.workflow.core.DeterministicIdSource("zero-"),
        ).run(yaml(), executionId = ExecutionId("zero-execution"))

        assertTrue(result.isSuccessful, result.failures.joinToString())
        assertTrue(result.outputs.isEmpty())
        assertTrue(result.journal.assignments().isEmpty())
        assertEquals(1, result.journal.activations().single().status.let { if (it == ActivationRecord.Status.COMPLETED) 1 else 0 })
    }

    @Test
    fun `open lifecycle remains parked without being treated as a failure`() {
        val registry = registry { _ -> listOf(ProviderLifecycleMessage.Open) }
        val result = InMemoryWorkflowRunner(compiler = WorkflowCompiler(registry)).run(
            yaml(), executionId = ExecutionId("open-execution"),
        )

        assertTrue(result.isSuccessful, result.failures.joinToString())
        assertEquals(ActivationRecord.Status.OPEN, result.journal.activations().single().status)
        assertEquals(null, result.journal.activations().single().completedAt)
        assertFalse(result.journal.isCompleted(result.journal.activationIntents().single().id))
        assertTrue(result.inspectionJson().contains("\"state\":\"open\""))
    }

    @Test
    fun `stream ending without a terminal message is a protocol failure`() {
        val registry = registry { request ->
            listOf(
                ProviderLifecycleMessage.Emission(
                    Value.StringValue("unterminated"), EmissionId("one"), request.invocationId, request.attemptId,
                ),
            )
        }
        val result = InMemoryWorkflowRunner(compiler = WorkflowCompiler(registry)).run(
            yaml(), executionId = ExecutionId("unterminated-execution"),
        )

        assertFalse(result.isSuccessful)
        assertTrue(result.failures.single().contains("PROTOCOL_MISSING_TERMINAL"))
        assertEquals(1, result.journal.assignments().count { it.emissionId != null })
        assertEquals(ActivationRecord.Status.FAILED, result.journal.activations().single { it.invocationId != null }.status)
    }

    @Test
    fun `invalid output and duplicate emission are refused`() {
        val invalidRegistry = registry { request ->
            listOf(
                ProviderLifecycleMessage.Emission(
                    Value.IntegerValue(7), EmissionId("bad"), request.invocationId, request.attemptId,
                ),
            )
        }
        val invalid = InMemoryWorkflowRunner(compiler = WorkflowCompiler(invalidRegistry)).run(
            yaml(), executionId = ExecutionId("invalid-output"),
        )
        assertFalse(invalid.isSuccessful)
        assertTrue(invalid.failures.single().contains("INVALID_OUTPUT"))
        assertTrue(invalid.journal.assignments().isEmpty())
        assertTrue(invalid.journal.providerEmissions().any { it.kind == ProviderEventType.EMISSION_REFUSED })

        val duplicateRegistry = registry { request ->
            listOf(
                ProviderLifecycleMessage.Emission(
                    Value.StringValue("one"), EmissionId("same"), request.invocationId, request.attemptId,
                ),
                ProviderLifecycleMessage.Emission(
                    Value.StringValue("two"), EmissionId("same"), request.invocationId, request.attemptId,
                ),
            )
        }
        val duplicate = InMemoryWorkflowRunner(compiler = WorkflowCompiler(duplicateRegistry)).run(
            yaml(), executionId = ExecutionId("duplicate-output"),
        )
        assertFalse(duplicate.isSuccessful)
        assertTrue(duplicate.failures.single().contains("PROTOCOL_DUPLICATE_EMISSION"))
        assertEquals(2, duplicate.journal.assignments().size)
    }

    @Test
    fun `wrong lifecycle identity and order fail without assigning`() {
        val identityRegistry = registry { request ->
            listOf(
                ProviderLifecycleMessage.Emission(
                    Value.StringValue("bad"), EmissionId("bad"), InvocationId("other"), request.attemptId,
                ),
            )
        }
        val identity = InMemoryWorkflowRunner(compiler = WorkflowCompiler(identityRegistry)).run(
            yaml(), executionId = ExecutionId("identity-output"),
        )
        assertTrue(identity.failures.single().contains("PROTOCOL_INVALID_EMISSION"))
        assertTrue(identity.journal.assignments().isEmpty())

        val orderRegistry = registry { request ->
            listOf(
                ProviderLifecycleMessage.Completed,
                ProviderLifecycleMessage.Emission(
                    Value.StringValue("late"), EmissionId("late"), request.invocationId, request.attemptId,
                ),
            )
        }
        val order = InMemoryWorkflowRunner(compiler = WorkflowCompiler(orderRegistry)).run(
            yaml(), executionId = ExecutionId("order-output"),
        )
        assertTrue(order.failures.single().contains("PROTOCOL_ORDER_VIOLATION"))
        assertTrue(order.journal.assignments().isEmpty())
    }

    @Test
    fun `typed provider failure records safe diagnostics without an assignment`() {
        val registry = registry { _ ->
            listOf(ProviderLifecycleMessage.Failed(Value.StringValue("typed failure")))
        }
        val result = InMemoryWorkflowRunner(compiler = WorkflowCompiler(registry)).run(
            yaml(), executionId = ExecutionId("typed-failure"),
        )

        assertFalse(result.isSuccessful)
        assertTrue(result.failures.single().contains("typed failure"))
        assertTrue(result.journal.assignments().isEmpty())
        assertEquals(1, result.journal.providerFailures().size)
        assertEquals(ActivationRecord.Status.FAILED, result.journal.activations().single().status)
    }

    @Test
    fun `provider exception is recorded without leaking a stack trace`() {
        val registry = registry { _ -> error("provider secret") }
        val result = InMemoryWorkflowRunner(compiler = WorkflowCompiler(registry)).run(
            yaml(), executionId = ExecutionId("provider-exception"),
        )

        assertFalse(result.isSuccessful)
        assertTrue(result.failures.single().contains("provider secret"))
        assertTrue(result.journal.providerFailures().single().diagnostic!!.contains("IllegalStateException"))
        assertFalse(result.inspectionJson().contains("at io.workflow"))
        assertTrue(result.journal.assignments().isEmpty())
    }

    @Test
    fun `completed activation is not invoked again on restart`() {
        val calls = AtomicInteger()
        val registry = registry { request ->
            calls.incrementAndGet()
            listOf(
                ProviderLifecycleMessage.Emission(
                    Value.StringValue("once"), EmissionId("once"), request.invocationId, request.attemptId,
                ),
                ProviderLifecycleMessage.Completed,
            )
        }
        val runner = InMemoryWorkflowRunner(
            compiler = WorkflowCompiler(registry),
            idSource = io.workflow.core.DeterministicIdSource("restart-"),
        )
        runner.run(yaml(), executionId = ExecutionId("restart-execution"))
        runner.run(yaml(), executionId = ExecutionId("restart-execution"))
        assertEquals(1, calls.get())
        assertEquals(1, runner.journal.providerInvocations().size)
    }

    @Test
    fun `independent providers run in parallel on bounded workers`() {
        val entered = CountDownLatch(2)
        val registry = registry { request ->
            entered.countDown()
            check(entered.await(5, TimeUnit.SECONDS)) { "both provider activations must reach the barrier" }
            listOf(
                ProviderLifecycleMessage.Emission(
                    Value.StringValue(request.invocationId.value),
                    EmissionId(request.invocationId.value),
                    request.invocationId,
                    request.attemptId,
                ),
                ProviderLifecycleMessage.Completed,
            )
        }
        val compiler = WorkflowCompiler(registry)
        val result = InMemoryWorkflowRunner(
            compiler = compiler,
            idSource = io.workflow.core.DeterministicIdSource("parallel-"),
            workerCount = 2,
        ).run(
            """
            workflow:
              id: parallel
              version: 1
              context:
                first: {provider: test-provider, version: 1}
                second: {provider: test-provider, version: 1}
              outputs: [first, second]
            """.trimIndent(),
            executionId = ExecutionId("parallel-execution"),
        )

        assertTrue(result.isSuccessful, result.failures.joinToString())
        assertEquals(2, result.journal.providerInvocations().size)
        assertEquals(2, result.journal.assignments().size)
        assertEquals(2, result.journal.providerEmissions().count { it.kind == ProviderEventType.EMISSION_ACCEPTED })
    }

    @Test
    fun `journal commit order is authoritative for parallel providers`() {
        val bothEntered = CountDownLatch(2)
        val secondCommitted = CountDownLatch(1)
        val registry = ProviderRegistry()
        registry.register(descriptor("first-provider")) { request ->
            bothEntered.countDown()
            check(bothEntered.await(5, TimeUnit.SECONDS))
            check(secondCommitted.await(5, TimeUnit.SECONDS))
            listOf(
                ProviderLifecycleMessage.Emission(
                    Value.StringValue("first"), EmissionId("first"), request.invocationId, request.attemptId,
                ),
                ProviderLifecycleMessage.Completed,
            )
        }
        registry.register(descriptor("second-provider")) { request ->
            bothEntered.countDown()
            check(bothEntered.await(5, TimeUnit.SECONDS))
            sequence {
                yield(
                    ProviderLifecycleMessage.Emission(
                        Value.StringValue("second"), EmissionId("second"), request.invocationId, request.attemptId,
                    ),
                )
                // Sequence execution resumes only after the runtime has accepted
                // and committed the yielded emission.
                secondCommitted.countDown()
                yield(ProviderLifecycleMessage.Completed)
            }.asIterable()
        }

        val result = InMemoryWorkflowRunner(
            compiler = WorkflowCompiler(registry),
            workerCount = 2,
        ).run(
            """
            workflow:
              id: authoritative-order
              version: 1
              context:
                first: {provider: first-provider, version: 1}
                second: {provider: second-provider, version: 1}
              outputs: [first, second]
            """.trimIndent(),
            executionId = ExecutionId("authoritative-order-execution"),
        )

        assertTrue(result.isSuccessful, result.failures.joinToString())
        assertEquals(
            listOf(Value.StringValue("second"), Value.StringValue("first")),
            result.journal.assignments().map { it.value },
        )
        assertEquals(Value.StringValue("first"), result.outputs.getValue("first").value)
        assertEquals(Value.StringValue("second"), result.outputs.getValue("second").value)
    }
}
