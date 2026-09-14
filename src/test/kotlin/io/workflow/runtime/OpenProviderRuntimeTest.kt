package io.workflow.runtime

import io.workflow.compiler.WorkflowCompiler
import io.workflow.core.DeterministicIdSource
import io.workflow.core.EmissionId
import io.workflow.core.ExecutionId
import io.workflow.core.FixedClock
import io.workflow.core.Value
import io.workflow.core.ValueSchema
import io.workflow.provider.ProviderDescriptor
import io.workflow.provider.ProviderLifecycleMessage
import io.workflow.provider.ProviderRegistry
import java.time.Instant as JavaInstant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CorrelationOpenProviderTest {
    private fun registry(implementation: (io.workflow.provider.ProviderInvocationRequest) -> Iterable<ProviderLifecycleMessage>): ProviderRegistry =
        ProviderRegistry().also { registry ->
            registry.register(
                ProviderDescriptor(
                    providerId = "open-provider",
                    version = 1,
                    inputSchema = ValueSchema.Any,
                    emissionSchema = ValueSchema.String,
                ),
                io.workflow.provider.ProviderImplementation(implementation),
            )
        }

    private val workflow = """
        workflow:
          id: open
          version: 1
          context:
            event: {provider: open-provider, version: 1}
            copied: {${'$'}ref: "${'$'}.event"}
          outputs: [copied]
    """.trimIndent()

    @Test
    fun `hosted open provider can receive bounded repeated pushes`() {
        val runner = InMemoryWorkflowRunner(
            compiler = WorkflowCompiler(registry { listOf(ProviderLifecycleMessage.Open) }),
            idSource = DeterministicIdSource("open-"),
            clock = FixedClock(JavaInstant.EPOCH),
            workerCount = 1,
        )
        val host = runner.start(workflow, executionId = ExecutionId("open-execution"))

        assertEquals(WorkflowExecutionState.QUIESCENT, host.state)
        val invocation = host.openProviders().single().invocationId
        assertTrue(host.result().outputs.isEmpty())

        host.emit(invocation, Value.StringValue("one"), EmissionId("one"))
        assertEquals(Value.StringValue("one"), host.result().outputs.getValue("copied").value)
        host.emit(invocation, Value.StringValue("one"), EmissionId("two"))
        assertEquals(Value.StringValue("one"), host.result().outputs.getValue("copied").value)
        assertEquals(
            listOf(1L, 2L),
            host.result().journal.assignments().filter { it.registerId.value.endsWith("/register/event") }.map { it.revision },
        )

        host.complete(invocation)
        assertEquals(WorkflowExecutionState.QUIESCENT, host.state)
        assertEquals(ProviderEventType.COMPLETED, host.result().journal.providerEvents().last().type)
    }

    @Test
    fun `correlation creates isolated context and never restarts roots`() {
        val runner = InMemoryWorkflowRunner(
            compiler = WorkflowCompiler(registry { request ->
                listOf(
                    ProviderLifecycleMessage.Emission(
                        Value.StringValue("keyed"), EmissionId("keyed"), request.invocationId, request.attemptId, "customer-1",
                    ),
                    ProviderLifecycleMessage.Open,
                )
            }),
            idSource = DeterministicIdSource("correlation-"),
            clock = FixedClock(JavaInstant.EPOCH),
            workerCount = 1,
        )
        val host = runner.start(workflow, executionId = ExecutionId("correlation-execution"))

        val assignments = host.result().journal.assignments().filter { it.registerId.value.endsWith("/register/event") }
        assertEquals(listOf("customer-1"), assignments.map { it.contextId.value })
        assertTrue(host.result().journal.providerInvocations().single().contextId.value == "anonymous")
        assertTrue(host.result().inspectionJson().contains("\"kind\":\"correlated\""))
        assertEquals(1, host.result().journal.providerInvocations().size)
        assertTrue(host.result().outputs.isEmpty())

        host.emit(host.openProviders().single().invocationId, Value.StringValue("again"), EmissionId("again"), "customer-1")
        assertEquals(
            listOf(1L, 2L),
            host.result().journal.assignments()
                .filter { it.registerId.value.endsWith("/register/event") && it.contextId.value == "customer-1" }
                .map { it.revision },
        )
    }

    @Test
    fun `new correlated context does not copy unrelated anonymous values`() {
        val yaml = """
            workflow:
              id: isolated
              version: 1
              context:
                seed: anonymous-only
                event: {provider: open-provider, version: 1}
                joined: {${'$'}concat: [{${'$'}ref: "${'$'}.event"}, {${'$'}ref: "${'$'}.seed"}]}
              outputs: [joined]
        """.trimIndent()
        val runner = InMemoryWorkflowRunner(
            compiler = WorkflowCompiler(registry { listOf(ProviderLifecycleMessage.Open) }),
            idSource = DeterministicIdSource("isolation-"),
            clock = FixedClock(JavaInstant.EPOCH),
            workerCount = 1,
        )
        val host = runner.start(yaml, executionId = ExecutionId("isolation-execution"))
        val invocation = host.openProviders().single().invocationId
        host.emit(invocation, Value.StringValue("keyed"), EmissionId("keyed"), "customer-1")

        assertTrue(host.result().journal.assignments().none { it.registerId.value.endsWith("/register/joined") })
        assertEquals(1, host.result().journal.providerInvocations().size)
    }

    @Test
    fun `administrative stop does not synthesize provider completion or assignment`() {
        val runner = InMemoryWorkflowRunner(
            compiler = WorkflowCompiler(registry { listOf(ProviderLifecycleMessage.Open) }),
            idSource = DeterministicIdSource("stop-"),
            clock = FixedClock(JavaInstant.EPOCH),
        )
        val host = runner.start(workflow, executionId = ExecutionId("stop-execution"))
        val before = host.result().journal.assignments().size
        val stopped = host.stop()

        assertEquals(WorkflowExecutionState.STOPPED, stopped.executionState)
        assertEquals(before, stopped.journal.assignments().size)
        assertTrue(stopped.journal.providerEvents().none { it.type == ProviderEventType.COMPLETED })
        assertTrue(stopped.journal.providerEvents().any { it.type == ProviderEventType.CANCELLED })
        assertEquals(ActivationRecord.Status.STOPPED, stopped.journal.activations().single().status)
        assertFalse(stopped.journal.isOpen(stopped.journal.activationIntents().single().id))
    }
}
