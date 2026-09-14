package io.workflow.runtime

import io.workflow.compiler.WorkflowCompiler
import io.workflow.core.DeterministicIdSource
import io.workflow.core.ExecutionId
import io.workflow.core.Value
import io.workflow.core.ValueSchema
import io.workflow.provider.EffectClass
import io.workflow.provider.IdempotencyContract
import io.workflow.provider.ProviderDescriptor
import io.workflow.provider.ProviderInvocationRequest
import io.workflow.provider.ProviderLifecycleMessage
import io.workflow.provider.ProviderRegistry
import io.workflow.provider.ReconciliationDisposition
import io.workflow.provider.ReconciliationMode
import io.workflow.provider.ReconciliationProviderImplementation
import io.workflow.provider.ReconciliationRequest
import io.workflow.provider.ReconciliationResult
import java.nio.file.Files
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EffectReconciliationTest {
    @Test
    fun `applied effect with lost reply is reconciled once and its recorded output is published`() {
        val provider = LostReplyEffect(applied = true)
        val result = runner(registry(provider)).run(yaml(), executionId = ExecutionId("effect-applied"))

        assertEquals(1, provider.writes)
        assertEquals("recorded", (result.outputs.getValue("value").value as Value.StringValue).value)
        assertEquals(1, result.journal.assignments().count { it.invocationId != null })
        assertTrue(result.journal.providerEvents().any { it.type == ProviderEventType.RECONCILIATION_DECISION && it.diagnostic!!.contains("reuse-confirmed") })
    }

    @Test
    fun `confirmed not applied permits one retry with the same idempotency key`() {
        val provider = LostReplyEffect(applied = false)
        val result = runner(registry(provider)).run(yaml(), executionId = ExecutionId("effect-not-applied"))

        assertEquals(1, provider.writes)
        assertEquals(2, provider.keys.size)
        assertEquals(1, provider.keys.distinct().size)
        assertEquals("retried", (result.outputs.getValue("value").value as Value.StringValue).value)
    }

    @Test
    fun `unavailable reconciliation leaves an ambiguous durable intervention state`() {
        val implementation = io.workflow.provider.ProviderImplementation { throw IllegalStateException("lost reply") }
        val result = runner(registry(implementation, reconciliation = ReconciliationMode.QUERY_BY_INVOCATION)).run(yaml(), executionId = ExecutionId("effect-unavailable"))
        val intent = result.journal.activationIntents().single()

        assertTrue(result.journal.isAmbiguous(intent.id))
        assertTrue(result.journal.providerEvents().any { it.type == ProviderEventType.RECONCILIATION_DECISION && it.diagnostic!!.contains("INTERVENTION_REQUIRED") })
    }

    @Test
    fun `missing effect declaration is rejected by the compiler`() {
        val providers = ProviderRegistry().also {
            it.register(ProviderDescriptor("effect", 1, ValueSchema.Any, ValueSchema.String,
                effectClass = EffectClass.EFFECT, idempotency = null))
        }
        val result = WorkflowCompiler(providers).compile(yaml())
        assertFalse(result.isValid)
        assertTrue(result.diagnostics.any { it.message.contains("must declare") })
    }

    @Test
    fun `reconciliation is retried after restart without repeating the external effect`() {
        val database = Files.createTempFile("effect-reconcile", ".db")
        val provider = object : ReconciliationProviderImplementation {
            var writes = 0
            var reconcileCalls = 0
            override fun invoke(request: ProviderInvocationRequest): Iterable<ProviderLifecycleMessage> {
                writes += 1
                throw IllegalStateException("reply lost")
            }
            override fun reconcile(request: ReconciliationRequest): ReconciliationResult {
                reconcileCalls += 1
                return if (reconcileCalls == 1) ReconciliationResult(disposition = ReconciliationDisposition.STILL_UNKNOWN)
                else ReconciliationResult(disposition = ReconciliationDisposition.DEFINITELY_APPLIED, recordedResult = Value.StringValue("after-restart"))
            }
        }
        try {
            SqliteJournalStore(database).use { store ->
                val first = runner(registry(provider), store, "effect-first-").run(yaml(), executionId = ExecutionId("effect-restart"))
                assertTrue(first.journal.isAmbiguous(first.journal.activationIntents().single().id))
            }
            SqliteJournalStore(database).use { store ->
                val resumed = runner(registry(provider), store, "effect-resume-").resume(ExecutionId("effect-restart")).result()
                assertEquals("after-restart", (resumed.outputs.getValue("value").value as Value.StringValue).value)
                assertEquals(1, provider.writes)
                assertEquals(2, provider.reconcileCalls)
            }
        } finally {
            Files.deleteIfExists(database)
        }
    }

    private fun runner(providers: ProviderRegistry, store: WorkflowJournalStore = InMemoryJournalStore(), prefix: String = "effect-") =
        InMemoryWorkflowRunner(WorkflowCompiler(providers), idSource = DeterministicIdSource(prefix), journal = store,
            providerRegistry = providers, workerCount = 1)

    private fun registry(
        implementation: io.workflow.provider.ProviderImplementation,
        reconciliation: ReconciliationMode = ReconciliationMode.QUERY_BY_INVOCATION,
    ) = ProviderRegistry().also { providers ->
        providers.register(ProviderDescriptor("effect", 1, ValueSchema.Any, ValueSchema.String,
            effectClass = EffectClass.EFFECT,
            idempotency = IdempotencyContract(reconciliation)), implementation)
    }

    private fun yaml() = """
        workflow:
          id: reconciliation
          version: 1
          context:
            value: {provider: effect, version: 1, policy: {maximum-attempts: 2, retryable-error-classes: [transient]}}
          outputs: [value]
    """.trimIndent()

    private class LostReplyEffect(private val applied: Boolean) : ReconciliationProviderImplementation {
        var writes = 0
        val keys = mutableListOf<String>()
        private var calls = 0
        override fun invoke(request: ProviderInvocationRequest): Iterable<ProviderLifecycleMessage> {
            keys += request.idempotencyKey
            calls += 1
            if (calls == 1) {
                if (applied) writes += 1
                throw IllegalStateException("reply lost")
            }
            writes += 1
            return listOf(ProviderLifecycleMessage.Emission(Value.StringValue("retried"), io.workflow.core.EmissionId("retry"), request.invocationId, request.attemptId), ProviderLifecycleMessage.Completed)
        }
        override fun reconcile(request: ReconciliationRequest) = if (applied) {
            ReconciliationResult(disposition = ReconciliationDisposition.DEFINITELY_APPLIED, recordedResult = Value.StringValue("recorded"))
        } else ReconciliationResult(disposition = ReconciliationDisposition.DEFINITELY_NOT_APPLIED)
    }
}
