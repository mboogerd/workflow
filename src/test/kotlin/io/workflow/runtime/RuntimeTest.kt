package io.workflow.runtime

import io.workflow.compiler.WorkflowCompiler
import io.workflow.core.AssignmentMutation
import io.workflow.core.Clock
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
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.function.Executable

class RuntimeTest {
    private val hello = """
        workflow:
          id: hello
          version: 1
          parameters:
            name: {schema: string}
          context:
            greeting:
              ${'$'}concat: ["Hello, ", {${'$'}ref: "${'$'}.parameters.name"}, "!"]
            copy: {${'$'}ref: "${'$'}.greeting"}
          outputs: [copy]
    """.trimIndent()

    @Test
    fun `expression workflow executes and exposes provenance`() {
        val result = InMemoryWorkflowRunner(
            idSource = DeterministicIdSource("test-"),
            clock = FixedClock(Instant.EPOCH),
        ).run(hello, mapOf("name" to Value.StringValue("Ada")), ExecutionId("exec"))

        assertEquals(Value.StringValue("Hello, Ada!"), result.outputs.getValue("copy").value)
        assertEquals(1L, result.outputs.getValue("copy").revision)
        assertEquals(2, result.journal.assignments().size)
        assertEquals(2, result.journal.activationIntents().size)
        assertEquals(2, result.journal.activations().size)
        val inspection = result.inspectionJson()
        assertTrue(inspection.contains("activationIntents"))
        assertTrue(inspection.contains("assignments"))
        assertFalse(inspection.contains("io.workflow.runtime"))
    }

    @Test
    fun `multi-input producer waits for every required input regardless of declaration order`() {
        val sourceFirst = """
            workflow:
              id: join
              version: 1
              context:
                first: one
                second: two
                joined: {${'$'}concat: [{${'$'}ref: "${'$'}.first"}, {${'$'}ref: "${'$'}.second"}]}
              outputs: [joined]
        """.trimIndent()
        val reordered = sourceFirst.replace(
            "first: one\n                second: two\n                joined:",
            "joined:",
        ).replace(
            "joined: {${'$'}concat: [{${'$'}ref: \"${'$'}.first\"}, {${'$'}ref: \"${'$'}.second\"}]}\n              outputs",
            "joined: {${'$'}concat: [{${'$'}ref: \"${'$'}.first\"}, {${'$'}ref: \"${'$'}.second\"}]}\n                first: one\n                second: two\n              outputs",
        )
        val compiler = WorkflowCompiler()
        val first = compiler.compile(sourceFirst)
        val second = compiler.compile(reordered)
        assertTrue(first.isValid, first.diagnostics.joinToString())
        assertTrue(second.isValid, second.diagnostics.joinToString())
        val runner = InMemoryWorkflowRunner(idSource = DeterministicIdSource("join-"))
        val result = runner.execute(first.ir!!, executionId = ExecutionId("join-exec"))
        assertEquals(Value.StringValue("onetwo"), result.outputs.getValue("joined").value)
        assertEquals(first.ir!!.contentHash, second.ir!!.contentHash)
        val joinedIntents = result.journal.activationIntents().filter { it.producerId.value.endsWith("/producer/joined") }
        assertEquals(1, joinedIntents.size)
        assertEquals(2, joinedIntents.single().dependencyRevisions.size)
    }

    @Test
    fun `optional references distinguish absent from assigned null`() {
        val yaml = """
            workflow:
              id: optional
              version: 1
              parameters:
                missing: {schema: string}
                nullable: {schema: any}
              context:
                absent: {${'$'}present: {${'$'}optional: "${'$'}.parameters.missing"}}
                assigned-null: {${'$'}present: {${'$'}optional: "${'$'}.parameters.nullable"}}
              outputs: [absent, assigned-null]
        """.trimIndent()
        val runner = InMemoryWorkflowRunner(idSource = DeterministicIdSource("optional-"))
        val result = runner.run(yaml, mapOf("nullable" to Value.Null), ExecutionId("optional-exec"))
        assertEquals(Value.BooleanValue(false), result.outputs.getValue("absent").value)
        assertEquals(Value.BooleanValue(true), result.outputs.getValue("assigned-null").value)
    }

    @Test
    fun `equal payloads still receive new revisions`() {
        val ids = DeterministicIdSource("direct-")
        val store = InMemoryJournalStore(FixedClock(Instant.EPOCH), ids)
        fun mutation(id: String) = AssignmentMutation(
            assignmentId = io.workflow.core.AssignmentId(id),
            workflowId = WorkflowId("w"),
            workflowVersionId = WorkflowVersionId("w@1"),
            executionId = ExecutionId("e"),
            contextId = ContextId("anonymous"),
            registerId = RegisterId("r"),
            value = Value.StringValue("same"),
            producerId = ProducerId("p"),
            occurredAt = Instant.EPOCH,
        )
        store.commit(mutation("a"))
        store.commit(mutation("b"))
        assertEquals(listOf(1L, 2L), store.assignments().map { it.revision })
        assertEquals(2L, store.current(RegisterKey(ExecutionId("e"), ContextId("anonymous"), RegisterId("r")))!!.revision)
    }

    @Test
    fun `committed intent survives worker failure and restart does not duplicate it`() {
        val store = InMemoryJournalStore(FixedClock(Instant.EPOCH), DeterministicIdSource("shared-"))
        val ids = DeterministicIdSource("runner-")
        val runner = InMemoryWorkflowRunner(idSource = ids, clock = FixedClock(Instant.EPOCH), journal = store)
        val execution = ExecutionId("recoverable")
        assertThrows(IllegalStateException::class.java, Executable {
            runner.run(hello, mapOf("name" to Value.StringValue("Ada")), execution) { throw IllegalStateException("injected") }
        })
        val intentsBefore = store.activationIntents().size
        assertEquals(1, intentsBefore)
        assertEquals(0, store.assignments().size)
        val resumed = runner.run(hello, mapOf("name" to Value.StringValue("Ada")), execution)
        assertEquals(Value.StringValue("Hello, Ada!"), resumed.outputs.getValue("copy").value)
        assertEquals(intentsBefore + 1, store.activationIntents().size)
        assertEquals(2, store.assignments().size)
    }

    @Test
    fun `current view can be rebuilt from ordered journal batches`() {
        val store = InMemoryJournalStore(FixedClock(Instant.EPOCH), DeterministicIdSource("rebuild-"))
        val workflow = WorkflowId("w")
        val mutation = AssignmentMutation(
            assignmentId = io.workflow.core.AssignmentId("a"), workflowId = workflow,
            workflowVersionId = WorkflowVersionId("w@1"), executionId = ExecutionId("e"),
            contextId = ContextId("anonymous"), registerId = RegisterId("r"),
            value = Value.IntegerValue(1), producerId = ProducerId("p"), occurredAt = Instant.EPOCH,
        )
        store.commit(JournalBatch(JournalBatchId("b"), listOf(mutation), Instant.EPOCH))
        val live = store.allCurrent()
        store.rebuildCurrentView()
        assertEquals(live, store.allCurrent())
        assertEquals(live, CurrentViews.rebuild(store.batches()))
    }
}
