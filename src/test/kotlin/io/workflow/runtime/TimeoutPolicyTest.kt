package io.workflow.runtime

import io.workflow.compiler.WorkflowCompiler
import io.workflow.core.Clock
import io.workflow.core.DeterministicIdSource
import io.workflow.core.ExecutionId
import io.workflow.core.Value
import io.workflow.core.ValueSchema
import io.workflow.provider.CancellableProviderImplementation
import io.workflow.provider.EffectClass
import io.workflow.provider.IdempotencyContract
import io.workflow.provider.ProviderDescriptor
import io.workflow.provider.ProviderInvocationRequest
import io.workflow.provider.ProviderLifecycle
import io.workflow.provider.ProviderLifecycleMessage
import io.workflow.provider.ProviderRegistry
import io.workflow.provider.ReconciliationMode
import java.time.Instant
import java.util.concurrent.CountDownLatch
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TimeoutPolicyTest {
    @Test
    fun `attempt timeout bounds a provider that does not return`() {
        val provider = object : CancellableProviderImplementation {
            var cancelCalls = 0
            override fun invoke(request: ProviderInvocationRequest): Iterable<ProviderLifecycleMessage> {
                CountDownLatch(1).await()
                return listOf(ProviderLifecycleMessage.Completed)
            }

            override fun cancel(request: ProviderInvocationRequest): Boolean {
                cancelCalls += 1
                return true
            }
        }
        val result = runner(registry(implementation = provider), MutableTestClock()).run(
            yaml("{attempt-timeout-millis: 10, cancellation-grace-millis: 1000}"),
            executionId = ExecutionId("timeout-bounded"),
        )
        assertFalse(result.isSuccessful)
        assertEquals(1, provider.cancelCalls)
        assertTrue(result.journal.providerEvents().any { it.type == ProviderEventType.ATTEMPT_TIMED_OUT })
    }

    @Test
    fun `attempt timeout requests cooperative cancellation and records acknowledgement`() {
        val clock = MutableTestClock()
        val provider = TimedProvider(clock, acknowledgesCancellation = true)
        val result = runner(registry(implementation = provider), clock).run(yaml("{attempt-timeout-millis: 1000, cancellation-grace-millis: 1000}"), executionId = ExecutionId("timeout-cancelled"))

        assertFalse(result.isSuccessful)
        assertEquals(1, provider.cancelCalls)
        assertTrue(result.journal.providerEvents().any { it.type == ProviderEventType.ATTEMPT_TIMED_OUT })
        assertTrue(result.journal.providerEvents().any { it.type == ProviderEventType.CANCELLED && it.diagnostic!!.contains("graceMillis=1000") })
        assertTrue(result.journal.assignments().isEmpty())
    }

    @Test
    fun `attempt timeout records ignored cancellation`() {
        val clock = MutableTestClock()
        val provider = TimedProvider(clock, acknowledgesCancellation = false)
        val result = runner(registry(implementation = provider), clock).run(yaml("{attempt-timeout-millis: 1000, cancellation-grace-millis: 1000}"), executionId = ExecutionId("timeout-ignored"))
        assertFalse(result.isSuccessful)
        assertTrue(result.journal.providerEvents().any { it.type == ProviderEventType.CANCELLATION_IGNORED })
    }

    @Test
    fun `cancellation grace bounds an unresponsive cancellation callback`() {
        val clock = MutableTestClock()
        val provider = object : CancellableProviderImplementation {
            override fun invoke(request: ProviderInvocationRequest): Iterable<ProviderLifecycleMessage> {
                clock.current = clock.current.plusMillis(1000)
                return listOf(ProviderLifecycleMessage.Completed)
            }

            override fun cancel(request: ProviderInvocationRequest): Boolean {
                CountDownLatch(1).await()
                return true
            }
        }
        val result = runner(registry(implementation = provider), clock).run(
            yaml("{attempt-timeout-millis: 1000, cancellation-grace-millis: 10}"),
            executionId = ExecutionId("cancellation-grace"),
        )
        assertFalse(result.isSuccessful)
        assertTrue(result.journal.providerEvents().any {
            it.type == ProviderEventType.CANCELLATION_IGNORED && it.diagnostic!!.contains("graceMillis=10")
        })
    }

    @Test
    fun `ambiguous effect timeout is held for later reconciliation`() {
        val clock = MutableTestClock()
        val provider = TimedProvider(clock, acknowledgesCancellation = false)
        val result = runner(registry(effectClass = EffectClass.EFFECT, implementation = provider), clock).run(
            yaml("{attempt-timeout-millis: 1000, cancellation-grace-millis: 1000}"),
            executionId = ExecutionId("timeout-ambiguous-effect"),
        )
        val intent = result.journal.activationIntents().single()
        assertTrue(result.journal.isAmbiguous(intent.id))
        assertEquals(ActivationRecord.Status.AMBIGUOUS, result.journal.activations().single().status)
    }

    @Test
    fun `activation deadline prevents a retry whose durable backoff is too late`() {
        val clock = MutableTestClock()
        var calls = 0
        val registry = registry { _ -> calls += 1; listOf(
            ProviderLifecycleMessage.Failed(Value.ObjectValue(mapOf("class" to Value.StringValue("transient")))),
        ) }
        val scheduler = RetryScheduler { due, _ -> clock.current = due }
        val result = runner(registry, clock, scheduler).run(
            yaml("{maximum-attempts: 2, retryable-error-classes: [transient], backoff-schedule-millis: [10], activation-deadline-millis: 5}"),
            executionId = ExecutionId("activation-deadline"),
        )

        assertFalse(result.isSuccessful)
        assertEquals(1, calls)
        assertTrue(result.journal.providerEvents().any { it.type == ProviderEventType.ACTIVATION_DEADLINE_EXCEEDED })
        assertEquals(1, result.journal.providerAttempts().size)
    }

    @Test
    fun `compiler rejects timeout and cancellation policies unsupported by descriptor`() {
        val registry = ProviderRegistry().also { providers ->
            providers.register(
                ProviderDescriptor("timed-provider", 1, ValueSchema.Any, ValueSchema.String,
                    configurationSchema = ValueSchema.Any, lifecycle = ProviderLifecycle()),
            ) { listOf(ProviderLifecycleMessage.Completed) }
        }
        val result = WorkflowCompiler(registry).compile(yaml("{attempt-timeout-millis: 5, cancellation-grace-millis: 1}"))
        assertFalse(result.isValid)
        assertTrue(result.diagnostics.any { it.message.contains("timeout support") })
        assertTrue(result.diagnostics.any { it.message.contains("cancellation support") })
    }

    private fun runner(registry: ProviderRegistry, clock: Clock, scheduler: RetryScheduler = RetryScheduler { _, _ -> }) =
        InMemoryWorkflowRunner(
            compiler = WorkflowCompiler(registry), providerRegistry = registry, clock = clock,
            idSource = DeterministicIdSource("timeout-"), workerCount = 1, retryScheduler = scheduler,
        )

    private fun registry(
        effectClass: EffectClass = EffectClass.PURE,
        implementation: io.workflow.provider.ProviderImplementation,
    ) = ProviderRegistry().also { providers ->
        providers.register(
            ProviderDescriptor("timed-provider", 1, ValueSchema.Any, ValueSchema.String,
                configurationSchema = ValueSchema.Any,
                effectClass = effectClass,
                idempotency = if (effectClass in setOf(EffectClass.EFFECT, EffectClass.AGENTIC))
                    IdempotencyContract(ReconciliationMode.HUMAN_INTERVENTION) else null,
                lifecycle = ProviderLifecycle(supportsTimeout = true, supportsCancellation = true)),
            implementation,
        )
    }

    private fun yaml(policy: String) = """
        workflow:
          id: timeout-policy
          version: 1
          context:
            value: {provider: timed-provider, version: 1, policy: $policy}
          outputs: [value]
    """.trimIndent()

    private class MutableTestClock(var current: Instant = Instant.EPOCH) : Clock {
        override fun now(): Instant = current
    }

    private class TimedProvider(
        private val clock: MutableTestClock,
        private val acknowledgesCancellation: Boolean,
    ) : CancellableProviderImplementation {
        var cancelCalls = 0
        override fun invoke(request: ProviderInvocationRequest): Iterable<ProviderLifecycleMessage> {
            clock.current = clock.current.plusMillis(1000)
            return listOf(ProviderLifecycleMessage.Completed)
        }

        override fun cancel(request: ProviderInvocationRequest): Boolean {
            cancelCalls += 1
            return acknowledgesCancellation
        }
    }
}
