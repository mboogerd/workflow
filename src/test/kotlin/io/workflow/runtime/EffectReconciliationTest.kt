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
import org.junit.jupiter.api.Assertions.assertThrows
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
        val effectAttempt = result.journal.providerAttempts().single().attemptId
        val reconciliationAttempt = result.journal.providerEvents().single { it.type == ProviderEventType.RECONCILIATION_ATTEMPTED }.attemptId
        assertTrue(reconciliationAttempt != null && reconciliationAttempt != effectAttempt)
        assertEquals(listOf(reconciliationAttempt), provider.reconciliationAttemptIds)
        val calls = provider.reconcileCalls
        val replay = WorkflowReplay.replayOne(result.journal, ExecutionId("effect-applied"))
        assertEquals("recorded", (replay.outputs(WorkflowCompiler(registry(provider)).compile(yaml()).ir!!)
            .getValue("value").value as Value.StringValue).value)
        assertEquals(calls, provider.reconcileCalls, "replay must not invoke reconciliation")
    }

    @Test
    fun `confirmed not applied permits one retry with the same idempotency key`() {
        val provider = LostReplyEffect(applied = false)
        val result = runner(registry(provider)).run(yaml(), executionId = ExecutionId("effect-not-applied"))

        assertEquals(1, provider.writes)
        assertEquals(2, provider.keys.size)
        assertEquals(1, provider.keys.distinct().size)
        // Reconciliation must query the identity the external write actually used, which is
        // the author-bound idempotency-key rather than the logical invocation id.
        assertEquals(listOf("reconciliation-value"), provider.reconcileKeys)
        assertEquals(listOf("reconciliation-value"), provider.keys.distinct())
        assertTrue(result.journal.providerEvents().any {
            it.type == ProviderEventType.RECONCILIATION_REQUESTED &&
                it.diagnostic!!.contains("idempotencyKey=reconciliation-value")
        })
        assertEquals("retried", (result.outputs.getValue("value").value as Value.StringValue).value)
    }

    @Test
    fun `unavailable reconciliation leaves an ambiguous durable intervention state`() {
        val implementation = object : ReconciliationProviderImplementation {
            override fun invoke(request: ProviderInvocationRequest): Iterable<ProviderLifecycleMessage> =
                throw IllegalStateException("lost reply")
            override fun reconcile(request: ReconciliationRequest): ReconciliationResult =
                throw IllegalStateException("reconciliation unavailable")
        }
        val result = runner(registry(implementation, reconciliation = ReconciliationMode.QUERY_BY_INVOCATION)).run(yaml(), executionId = ExecutionId("effect-unavailable"))
        val intent = result.journal.activationIntents().single()

        assertTrue(result.journal.isAmbiguous(intent.id))
        assertTrue(result.journal.providerEvents().any { it.type == ProviderEventType.RECONCILIATION_DECISION && it.diagnostic!!.contains("INTERVENTION_REQUIRED") })
    }

    @Test
    fun `invalid confirmed result is a protocol intervention and is never published`() {
        val implementation = object : ReconciliationProviderImplementation {
            override fun invoke(request: ProviderInvocationRequest): Iterable<ProviderLifecycleMessage> =
                throw IllegalStateException("reply lost")
            override fun reconcile(request: ReconciliationRequest) = ReconciliationResult(
                disposition = ReconciliationDisposition.DEFINITELY_APPLIED,
                recordedResult = Value.IntegerValue(7),
            )
        }
        val result = runner(registry(implementation)).run(yaml(), executionId = ExecutionId("effect-invalid-result"))
        assertTrue(result.journal.isAmbiguous(result.journal.activationIntents().single().id))
        assertTrue(result.journal.assignments().isEmpty())
        assertTrue(result.journal.providerEvents().any {
            it.type == ProviderEventType.RECONCILIATION_DECISION && it.diagnostic!!.contains("invalid recorded result")
        })
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

        val queryWithoutImplementation = ProviderRegistry().also {
            it.register(ProviderDescriptor("effect", 1, ValueSchema.Any, ValueSchema.String,
                effectClass = EffectClass.EFFECT,
                idempotency = IdempotencyContract(ReconciliationMode.QUERY_BY_INVOCATION))) { emptyList() }
        }
        val invalidQuery = WorkflowCompiler(queryWithoutImplementation).compile(yaml())
        assertFalse(invalidQuery.isValid)
        assertTrue(invalidQuery.diagnostics.any { it.message.contains("requires a reconciliation implementation") })
    }

    @Test
    fun `idempotent duplicate retry reuses the invocation key and performs one external write`() {
        var attempts = 0
        var writes = 0
        val keys = mutableListOf<String>()
        val implementation = io.workflow.provider.ProviderImplementation { request ->
            attempts += 1
            keys += request.idempotencyKey
            if (writes == 0) writes += 1
            if (attempts == 1) throw IllegalStateException("reply lost")
            listOf(
                ProviderLifecycleMessage.Emission(Value.StringValue("deduplicated"), io.workflow.core.EmissionId("deduplicated"), request.invocationId, request.attemptId),
                ProviderLifecycleMessage.Completed,
            )
        }
        val result = runner(registry(implementation, ReconciliationMode.IDEMPOTENT_BY_INVOCATION))
            .run(yaml(), executionId = ExecutionId("effect-idempotent"))
        assertEquals(2, attempts)
        assertEquals(1, writes)
        assertEquals(1, keys.distinct().size)
        assertEquals("deduplicated", (result.outputs.getValue("value").value as Value.StringValue).value)
    }

    @Test
    fun `durable applied result is consumed after crash without another reconciliation call`() {
        val database = Files.createTempFile("effect-result-crash", ".db")
        val provider = LostReplyEffect(applied = true)
        try {
            SqliteJournalStore(database).use { store ->
                assertThrows(SimulatedCrash::class.java) {
                    runner(registry(provider), store, "result-crash-", beforeDecision = { throw SimulatedCrash() })
                        .run(yaml(), executionId = ExecutionId("effect-result-crash"))
                }
                assertEquals(1, store.providerEvents().count { it.type == ProviderEventType.RECONCILIATION_RESULT })
                assertEquals(0, store.assignments().size)
            }
            val reconcileCalls = provider.reconcileCalls
            SqliteJournalStore(database).use { store ->
                val resumed = runner(registry(provider), store, "result-resume-")
                    .resume(ExecutionId("effect-result-crash")).result()
                assertEquals("recorded", (resumed.outputs.getValue("value").value as Value.StringValue).value)
                assertEquals(reconcileCalls, provider.reconcileCalls)
                assertEquals(1, store.assignments().count { it.invocationId != null })
            }
        } finally {
            Files.deleteIfExists(database)
        }
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

    private fun runner(
        providers: ProviderRegistry,
        store: WorkflowJournalStore = InMemoryJournalStore(),
        prefix: String = "effect-",
        beforeDecision: (ReconciliationResult) -> Unit = {},
    ) =
        InMemoryWorkflowRunner(WorkflowCompiler(providers), idSource = DeterministicIdSource(prefix), journal = store,
            providerRegistry = providers, workerCount = 1, beforeReconciliationDecision = beforeDecision)

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
            value: {provider: effect, version: 1, idempotency-key: reconciliation-value, policy: {maximum-attempts: 2, retryable-error-classes: [transient]}}
          outputs: [value]
    """.trimIndent()

    private class LostReplyEffect(private val applied: Boolean) : ReconciliationProviderImplementation {
        var writes = 0
        var reconcileCalls = 0
        val reconciliationAttemptIds = mutableListOf<io.workflow.core.AttemptId?>()
        val keys = mutableListOf<String>()
        val reconcileKeys = mutableListOf<String>()
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
        override fun reconcile(request: ReconciliationRequest): ReconciliationResult {
            reconcileCalls += 1
            reconciliationAttemptIds += request.reconciliationAttemptId
            reconcileKeys += request.idempotencyKey
            return if (applied) {
            ReconciliationResult(disposition = ReconciliationDisposition.DEFINITELY_APPLIED, recordedResult = Value.StringValue("recorded"))
            } else ReconciliationResult(disposition = ReconciliationDisposition.DEFINITELY_NOT_APPLIED)
        }
    }

    private class SimulatedCrash : RuntimeException()
}
