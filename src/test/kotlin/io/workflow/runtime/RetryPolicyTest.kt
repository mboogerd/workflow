package io.workflow.runtime

import io.workflow.compiler.WorkflowCompiler
import io.workflow.core.Clock
import io.workflow.core.DeterministicIdSource
import io.workflow.core.EmissionId
import io.workflow.core.ExecutionId
import io.workflow.core.Value
import io.workflow.core.ValueSchema
import io.workflow.provider.EffectClass
import io.workflow.provider.ProviderDescriptor
import io.workflow.provider.ProviderImplementation
import io.workflow.provider.ProviderLifecycleMessage
import io.workflow.provider.ProviderRegistry
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

class RetryPolicyTest {
    @Test
    fun `retry success and exhaustion retain one invocation and numbered physical attempts`() {
        val clock = MutableTestClock()
        val dueTimes = mutableListOf<Instant>()
        val calls = AtomicInteger()
        val successRegistry = registry { request ->
            if (calls.incrementAndGet() == 1) failed("transient") else listOf(
                ProviderLifecycleMessage.Emission(Value.StringValue("ok"), EmissionId("ok"), request.invocationId, request.attemptId),
                ProviderLifecycleMessage.Completed,
            )
        }
        val success = runner(successRegistry, clock, RetryScheduler { due, _ -> dueTimes += due; clock.current = due })
            .run(yaml(maximumAttempts = 3, retryable = "[transient]", backoff = "[25, 50]"), executionId = ExecutionId("retry-success"))

        assertTrue(success.isSuccessful, success.failures.joinToString())
        assertEquals(Value.StringValue("ok"), success.outputs.getValue("value").value)
        assertEquals(1, success.journal.providerInvocations().size)
        assertEquals(2, success.journal.providerAttempts().size)
        assertEquals(1, success.journal.providerAttempts().map { it.invocationId }.distinct().size)
        assertEquals(listOf("attemptNumber=1", "attemptNumber=2"), success.journal.providerAttempts().map { it.diagnostic })
        assertEquals(listOf(Instant.EPOCH.plusMillis(25)), dueTimes)
        val replay = WorkflowReplay.replayOne(success.journal, ExecutionId("retry-success"))
        assertEquals(Value.StringValue("ok"), replay.outputs(WorkflowCompiler(successRegistry).compile(
            yaml(maximumAttempts = 3, retryable = "[transient]", backoff = "[25, 50]"),
        ).ir!!).getValue("value").value)
        assertEquals(2, calls.get(), "replay must consume the recorded decision without invoking the provider")
        assertEquals(1, replay.providerEvents.count { it.type == ProviderEventType.RETRY_SCHEDULED })

        val exhaustedCalls = AtomicInteger()
        val exhausted = runner(registry { exhaustedCalls.incrementAndGet(); failed("transient") }, MutableTestClock())
            .run(yaml(maximumAttempts = 2, retryable = "[transient]"), executionId = ExecutionId("retry-exhausted"))
        assertFalse(exhausted.isSuccessful)
        assertEquals(2, exhaustedCalls.get())
        assertEquals(2, exhausted.journal.providerAttempts().size)
    }

    @Test
    fun `non retryable failures and unsafe policies do not retry`() {
        val calls = AtomicInteger()
        val registry = registry { calls.incrementAndGet(); failed("permanent") }
        val result = runner(registry, MutableTestClock()).run(
            yaml(maximumAttempts = 3, retryable = "[transient]"), executionId = ExecutionId("non-retryable"),
        )
        assertFalse(result.isSuccessful)
        assertEquals(1, calls.get())
        assertTrue(result.journal.providerEvents().none { it.type == ProviderEventType.RETRY_SCHEDULED })

        val effectCompiler = WorkflowCompiler(registry(effectClass = EffectClass.EFFECT) { failed("transient") })
        val unsafe = effectCompiler.compile(yaml(maximumAttempts = 2, retryable = "[transient]"))
        assertFalse(unsafe.isValid)
        assertTrue(unsafe.diagnostics.any { it.message.contains("reconciliation is required") })

        val malformed = WorkflowCompiler(registry).compile(yaml(policy = "{maximum-attempts: two}"))
        assertFalse(malformed.isValid)
        assertTrue(malformed.diagnostics.any { it.message.contains("maximum-attempts must be an integer") })
    }

    @Test
    fun `crash during durable backoff resumes recorded attempt budget and due time`() {
        val database = createTempFile("retry-resume", ".db")
        database.deleteIfExists()
        val executionId = ExecutionId("retry-resume")
        try {
            val firstClock = MutableTestClock()
            SqliteJournalStore(database, firstClock, DeterministicIdSource("first-")) .use { store ->
                val registry = registry { failed("transient") }
                val crashingScheduler = RetryScheduler { _, _ -> throw SimulatedCrash() }
                assertThrows(SimulatedCrash::class.java) {
                    runner(registry, firstClock, crashingScheduler, store, "first-")
                        .run(yaml(maximumAttempts = 2, retryable = "[transient]", backoff = "[40]"), executionId = executionId)
                }
                assertEquals(1, store.providerInvocations().size)
                assertEquals(1, store.providerAttempts().size)
                assertEquals(1, store.providerEvents().count { it.type == ProviderEventType.RETRY_SCHEDULED })
            }

            val resumeClock = MutableTestClock()
            val resumedDueTimes = mutableListOf<Instant>()
            SqliteJournalStore(database, resumeClock, DeterministicIdSource("resume-")) .use { store ->
                val registry = registry { request -> listOf(
                    ProviderLifecycleMessage.Emission(Value.StringValue("resumed"), EmissionId("resumed"), request.invocationId, request.attemptId),
                    ProviderLifecycleMessage.Completed,
                ) }
                val resumed = runner(registry, resumeClock, RetryScheduler { due, _ -> resumedDueTimes += due; resumeClock.current = due }, store, "resume-")
                    .resume(executionId).result()
                assertTrue(resumed.isSuccessful, resumed.failures.joinToString())
                assertEquals(Value.StringValue("resumed"), resumed.outputs.getValue("value").value)
                assertEquals(listOf(Instant.EPOCH.plusMillis(40)), resumedDueTimes)
                assertEquals(1, store.providerInvocations().size)
                assertEquals(2, store.providerAttempts().size)
                assertEquals(1, store.providerAttempts().map { it.invocationId }.distinct().size)
                assertEquals(listOf("attemptNumber=1", "attemptNumber=2"), store.providerAttempts().map { it.diagnostic })
                assertEquals(1, store.providerEvents().count { it.type == ProviderEventType.RETRY_SCHEDULED })
            }
        } finally {
            database.deleteIfExists()
        }
    }

    private fun runner(
        registry: ProviderRegistry,
        clock: Clock,
        scheduler: RetryScheduler = RetryScheduler { _, _ -> },
        store: WorkflowJournalStore = InMemoryJournalStore(clock, DeterministicIdSource("journal-")),
        idPrefix: String = "runner-",
    ) = InMemoryWorkflowRunner(
        compiler = WorkflowCompiler(registry), clock = clock, idSource = DeterministicIdSource(idPrefix),
        journal = store, providerRegistry = registry, workerCount = 1, retryScheduler = scheduler,
    )

    private fun registry(
        effectClass: EffectClass = EffectClass.PURE,
        implementation: ProviderImplementation = ProviderImplementation { failed("transient") },
    ) = ProviderRegistry().also { providers ->
        providers.register(
            ProviderDescriptor("retry-provider", 1, ValueSchema.Any, ValueSchema.String,
                configurationSchema = ValueSchema.Any, effectClass = effectClass),
            implementation,
        )
    }

    private fun failed(errorClass: String) = listOf(
        ProviderLifecycleMessage.Failed(Value.ObjectValue(mapOf("class" to Value.StringValue(errorClass)))),
    )

    private fun yaml(
        maximumAttempts: Int = 1,
        retryable: String = "[]",
        backoff: String = "[]",
        policy: String = "{maximum-attempts: $maximumAttempts, retryable-error-classes: $retryable, backoff-schedule-millis: $backoff}",
    ) = """
        workflow:
          id: retry-policy
          version: 1
          context:
            value: {provider: retry-provider, version: 1, policy: $policy}
          outputs: [value]
    """.trimIndent()

    private class MutableTestClock(var current: Instant = Instant.EPOCH) : Clock {
        override fun now(): Instant = current
    }

    private class SimulatedCrash : RuntimeException()
}
