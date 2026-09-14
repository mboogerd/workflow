package io.workflow.runtime

import io.workflow.core.ActivationId
import io.workflow.core.ActivationIntent
import io.workflow.core.ActivationIntentId
import io.workflow.core.AssignmentId
import io.workflow.core.AssignmentMutation
import io.workflow.core.ContextId
import io.workflow.core.DeterministicIdSource
import io.workflow.core.ExecutionId
import io.workflow.core.FixedClock
import io.workflow.core.JournalBatch
import io.workflow.core.JournalBatchId
import io.workflow.core.ProducerId
import io.workflow.core.RegisterId
import io.workflow.core.Value
import io.workflow.core.WorkflowId
import io.workflow.core.WorkflowVersionId
import java.nio.file.Path
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class JournalStoreContractTest {
    @TempDir lateinit var tempDir: Path

    @Test
    fun `in memory and sqlite stores share singleton batch and projection semantics`() = withStores { store ->
        val first = store.commit(batch("b1", mutation("a1", "one")))
        val second = store.commit(batch("b2", mutation("a2", "two")))

        assertEquals(listOf(1L, 2L), store.assignments().map { it.revision })
        assertEquals(listOf("b1", "b2"), store.batches().map { it.journalBatchId.value })
        assertEquals(first.assignment.assignmentId, store.assignment(AssignmentId("a1"))!!.assignmentId)
        assertEquals(second.assignment, store.current(key()))
        assertEquals(store.allCurrent(), store.rebuildCurrentView())
        assertThrows(IllegalArgumentException::class.java) {
            store.commit(
                JournalBatchProposal(
                    JournalBatchId("invalid"),
                    listOf(mutation("a3", "three"), mutation("a4", "four")),
                    Instant.EPOCH,
                ),
            )
        }
    }

    @Test
    fun `in memory and sqlite stores share durable intent lifecycle semantics`() = withStores { store ->
        val intent = intent("intent")
        store.persistActivationIntents(listOf(intent))

        assertEquals(intent, store.claimNextActivation(ExecutionId("e")))
        store.releaseActivation(intent.id)
        assertEquals(intent, store.claimNextActivation(ExecutionId("e")))
        store.keepActivationOpen(intent.id)
        assertTrue(store.isOpen(intent.id))
        assertFalse(store.hasPendingActivations(ExecutionId("e")))
        store.stopActivation(intent.id)
        assertTrue(store.isStopped(intent.id))
        assertFalse(store.isOpen(intent.id))
        assertEquals(null, store.claimNextActivation(ExecutionId("e")))
    }

    private fun withStores(assertions: (WorkflowJournalStore) -> Unit) {
        val stores = listOf<WorkflowJournalStore>(
            InMemoryJournalStore(FixedClock(Instant.EPOCH), DeterministicIdSource("memory-")),
            SqliteJournalStore(tempDir.resolve("contract-${System.nanoTime()}.db"), FixedClock(Instant.EPOCH), DeterministicIdSource("sqlite-")),
        )
        stores.forEach { store ->
            try {
                assertions(store)
            } finally {
                (store as? AutoCloseable)?.close()
            }
        }
    }

    private fun key() = RegisterKey(ExecutionId("e"), ContextId("anonymous"), RegisterId("r"))

    private fun mutation(id: String, value: String) = AssignmentMutation(
        assignmentId = AssignmentId(id),
        workflowId = WorkflowId("w"),
        workflowVersionId = WorkflowVersionId("w@1"),
        executionId = ExecutionId("e"),
        contextId = ContextId("anonymous"),
        registerId = RegisterId("r"),
        value = Value.StringValue(value),
        producerId = ProducerId("p"),
        occurredAt = Instant.EPOCH,
    )

    private fun batch(id: String, mutation: AssignmentMutation) =
        JournalBatch(JournalBatchId(id), listOf(mutation), Instant.EPOCH)

    private fun intent(id: String) = ActivationIntent(
        id = ActivationIntentId(id),
        activationId = ActivationId("activation-$id"),
        producerId = ProducerId("p"),
        workflowId = WorkflowId("w"),
        workflowVersionId = WorkflowVersionId("w@1"),
        executionId = ExecutionId("e"),
        contextId = ContextId("anonymous"),
        journalBatchId = JournalBatchId("startup"),
        createdAt = Instant.EPOCH,
    )
}
