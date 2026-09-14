package io.workflow.runtime

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
import java.sql.DriverManager
import java.time.Instant
import java.time.Duration
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class SqliteJournalStoreTest {
    @TempDir lateinit var tempDir: Path

    private fun mutation(id: String, register: String = "r") = AssignmentMutation(
        assignmentId = AssignmentId(id),
        workflowId = WorkflowId("w"),
        workflowVersionId = WorkflowVersionId("w@1"),
        executionId = ExecutionId("e"),
        contextId = ContextId("anonymous"),
        registerId = RegisterId(register),
        value = Value.StringValue(id),
        producerId = ProducerId("p"),
        occurredAt = Instant.EPOCH,
    )

    @Test
    fun `reopen preserves journal projection and intent lifecycle`() {
        val path = tempDir.resolve("journal.db")
        val intent = io.workflow.core.ActivationIntent(
            id = io.workflow.core.ActivationIntentId("intent"),
            activationId = io.workflow.core.ActivationId("activation"),
            producerId = ProducerId("p"), workflowId = WorkflowId("w"), workflowVersionId = WorkflowVersionId("w@1"),
            executionId = ExecutionId("e"), contextId = ContextId("anonymous"), journalBatchId = JournalBatchId("b"),
            createdAt = Instant.EPOCH,
        )
        SqliteJournalStore(path, FixedClock(Instant.EPOCH), DeterministicIdSource("id-")).use { store ->
            store.commit(JournalBatch(JournalBatchId("b"), listOf(mutation("a")), Instant.EPOCH), listOf(intent))
            assertEquals(1L, store.current(RegisterKey(ExecutionId("e"), ContextId("anonymous"), RegisterId("r")))!!.revision)
            assertEquals(intent, store.activationIntents().single())
        }
        SqliteJournalStore(path, FixedClock(Instant.EPOCH), DeterministicIdSource("other-")).use { reopened ->
            assertEquals(listOf("a"), reopened.assignments().map { it.assignmentId.value })
            assertEquals(1L, reopened.current(RegisterKey(ExecutionId("e"), ContextId("anonymous"), RegisterId("r")))!!.revision)
            assertEquals(intent, reopened.activationIntents().single())
            val claimed = reopened.claimNextActivation(ExecutionId("e"))!!
            reopened.completeActivation(claimed.id)
            assertTrue(reopened.isCompleted(claimed.id))
        }
    }

    @Test
    fun `an expired claimed intent is recovered after reopening`() {
        val path = tempDir.resolve("claimed.db")
        val intent = io.workflow.core.ActivationIntent(
            id = io.workflow.core.ActivationIntentId("claimed-intent"),
            activationId = io.workflow.core.ActivationId("claimed-activation"),
            producerId = ProducerId("p"), workflowId = WorkflowId("w"), workflowVersionId = WorkflowVersionId("w@1"),
            executionId = ExecutionId("e"), contextId = ContextId("anonymous"), journalBatchId = JournalBatchId("startup"),
            createdAt = Instant.EPOCH,
        )
        SqliteJournalStore(
            path,
            FixedClock(Instant.EPOCH),
            DeterministicIdSource("claim-"),
            claimLease = Duration.ofSeconds(30),
        ).use { store ->
            store.persistActivationIntents(listOf(intent))
            assertEquals(intent, store.claimNextActivation(ExecutionId("e")))
        }

        SqliteJournalStore(path, FixedClock(Instant.EPOCH.plusSeconds(31))).use { reopened ->
            assertEquals(1, reopened.recoverExpiredClaims())
            assertEquals(intent, reopened.claimNextActivation(ExecutionId("e")))
        }
    }

    @Test
    fun `commit fault rolls back all visible pieces`() {
        SqliteCommitStep.entries.forEach { failingStep ->
            val path = tempDir.resolve("fault-${failingStep.name}.db")
            val store = SqliteJournalStore(
                path,
                FixedClock(Instant.EPOCH),
                DeterministicIdSource("fault-"),
                faultInjector = { step -> if (step == failingStep) error("injected") },
            )
            val intent = io.workflow.core.ActivationIntent(
                io.workflow.core.ActivationIntentId("intent"), io.workflow.core.ActivationId("activation"), ProducerId("p"),
                WorkflowId("w"), WorkflowVersionId("w@1"), ExecutionId("e"), ContextId("anonymous"), JournalBatchId("b"), Instant.EPOCH,
            )
            assertThrows(IllegalStateException::class.java) {
                store.commit(JournalBatch(JournalBatchId("b"), listOf(mutation("a")), Instant.EPOCH), listOf(intent))
            }
            assertTrue(store.batches().isEmpty(), failingStep.name)
            assertTrue(store.assignments().isEmpty(), failingStep.name)
            assertTrue(store.allCurrent().isEmpty(), failingStep.name)
            assertTrue(store.activationIntents().isEmpty(), failingStep.name)
            store.close()
        }
    }

    @Test
    fun `concurrent writers serialize register revisions`() {
        val path = tempDir.resolve("concurrent.db")
        val stores = List(2) { SqliteJournalStore(path, FixedClock(Instant.EPOCH), DeterministicIdSource("writer-$it-")) }
        val executor = Executors.newFixedThreadPool(2)
        try {
            val results = stores.mapIndexed { index, store -> executor.submit(Callable { store.commit(mutation("a$index")) }) }
            results.forEach { it.get(20, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
            stores.forEach(SqliteJournalStore::close)
        }
        SqliteJournalStore(path, FixedClock(Instant.EPOCH)).use { reopened ->
            assertEquals(listOf(1L, 2L), reopened.assignments().map { it.revision })
        }
    }

    @Test
    fun `newer database versions are rejected`() {
        val path = tempDir.resolve("newer.db")
        DriverManager.getConnection("jdbc:sqlite:$path").use { connection ->
            connection.createStatement().use { it.execute("PRAGMA user_version = 99") }
        }
        assertThrows(IllegalArgumentException::class.java) { SqliteJournalStore(path) }
    }

    @Test
    fun `in memory runner can use sqlite journal`() {
        val path = tempDir.resolve("runner.db")
        val yaml = """
            workflow:
              id: sqlite-runner
              version: 1
              context:
                source: one
                copied: {${'$'}ref: "${'$'}.source"}
              outputs: [copied]
        """.trimIndent()
        SqliteJournalStore(path, FixedClock(Instant.EPOCH), DeterministicIdSource("sqlite-")).use { store ->
            val result = InMemoryWorkflowRunner(
                clock = FixedClock(Instant.EPOCH),
                idSource = DeterministicIdSource("runner-"),
                journal = store,
            ).run(yaml, executionId = ExecutionId("sqlite-execution"))
            assertEquals(Value.StringValue("one"), result.outputs.getValue("copied").value)
            assertEquals(2, store.assignments().size)
            assertEquals(2, store.activations().size)
            assertEquals(2, store.activationIntents().size)
        }
    }
}
