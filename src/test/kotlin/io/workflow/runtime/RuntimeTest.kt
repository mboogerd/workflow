package io.workflow.runtime

import io.workflow.compiler.WorkflowCompiler
import io.workflow.core.AssignmentMutation
import io.workflow.core.AssignmentId
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
        assertTrue(inspection.contains("\"executionId\":\"exec\""))
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
                nested: {schema: any}
              context:
                absent: {${'$'}present: {${'$'}optional: "${'$'}.parameters.missing"}}
                assigned-null: {${'$'}present: {${'$'}optional: "${'$'}.parameters.nullable"}}
                missing-path: {${'$'}present: {${'$'}optional: "${'$'}.parameters.nested.child"}}
              outputs: [absent, assigned-null, missing-path]
        """.trimIndent()
        val runner = InMemoryWorkflowRunner(idSource = DeterministicIdSource("optional-"))
        val result = runner.run(
            yaml,
            mapOf("nullable" to Value.Null, "nested" to Value.ObjectValue(emptyMap())),
            ExecutionId("optional-exec"),
        )
        assertEquals(Value.BooleanValue(false), result.outputs.getValue("absent").value)
        assertEquals(Value.BooleanValue(true), result.outputs.getValue("assigned-null").value)
        assertEquals(Value.BooleanValue(false), result.outputs.getValue("missing-path").value)
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

    @Test
    fun `activation evaluates its captured assignment snapshot`() {
        val yaml = """
            workflow:
              id: snapshot
              version: 1
              context:
                source: one
                copied: {${'$'}ref: "${'$'}.source"}
              outputs: [copied]
        """.trimIndent()
        val store = InMemoryJournalStore(FixedClock(Instant.EPOCH), DeterministicIdSource("store-"))
        val runner = InMemoryWorkflowRunner(
            idSource = DeterministicIdSource("snapshot-"),
            clock = FixedClock(Instant.EPOCH),
            journal = store,
        )
        var injected = false
        val result = runner.run(yaml, executionId = ExecutionId("snapshot-exec")) { intent ->
            if (!injected && intent.producerId.value.endsWith("/producer/copied")) {
                injected = true
                val source = store.assignments().single { it.registerId.value.endsWith("/register/source") }
                store.commit(
                    source.copy(
                        assignmentId = AssignmentId("newer-source"),
                        value = Value.StringValue("two"),
                        revision = 1,
                    ),
                )
            }
        }
        assertEquals(Value.StringValue("one"), result.outputs.getValue("copied").value)
        val copied = store.assignments().single { it.registerId.value.endsWith("/register/copied") }
        assertEquals(
            store.assignments().first { it.assignmentId != AssignmentId("newer-source") }.assignmentId,
            copied.dependencyRevisions.values.single(),
        )
    }

    @Test
    fun `equal assignments create distinct dependent vectors and batch retry is idempotent`() {
        val compilation = WorkflowCompiler().compile(
            """
            workflow:
              id: repeated
              version: 1
              context:
                source: same
                copied: {${'$'}ref: "${'$'}.source"}
              outputs: [copied]
            """.trimIndent(),
        )
        assertTrue(compilation.isValid, compilation.diagnostics.joinToString())
        val workflow = compilation.ir!!
        val source = workflow.registers.single { it.name == "source" }
        val planner = ExpressionActivationPlanner()
        val store = InMemoryJournalStore(FixedClock(Instant.EPOCH), DeterministicIdSource("repeat-"))
        val execution = ExecutionId("repeat-exec")
        val context = ContextId("anonymous")

        fun assign(assignmentId: String, batchId: String): JournalBatch {
            val mutation = AssignmentMutation(
                assignmentId = AssignmentId(assignmentId),
                workflowId = WorkflowId(workflow.workflowId),
                workflowVersionId = workflow.workflowVersionId,
                executionId = execution,
                contextId = context,
                registerId = source.registerId,
                value = Value.StringValue("same"),
                producerId = source.producerId,
                occurredAt = Instant.EPOCH,
            )
            val journalBatchId = JournalBatchId(batchId)
            val intents = planner.afterAssignment(
                workflow, execution, context, mutation, store.allCurrent(), journalBatchId, Instant.EPOCH,
            )
            return store.commit(JournalBatch(journalBatchId, listOf(mutation), Instant.EPOCH), intents)
        }

        assign("assignment-a", "batch-a")
        val secondRequest = assign("assignment-b", "batch-b")
        val retried = store.commit(
            JournalBatch(
                JournalBatchId("batch-b"),
                listOf(secondRequest.assignment.copy(revision = 1)),
                Instant.EPOCH,
            ),
            store.activationIntents().filter { it.journalBatchId == JournalBatchId("batch-b") },
        )
        assertEquals(secondRequest, retried)
        assertEquals(listOf(1L, 2L), store.assignments().map { it.revision })
        assertEquals(2, store.activationIntents().size)
        assertEquals(
            setOf(AssignmentId("assignment-a"), AssignmentId("assignment-b")),
            store.activationIntents().map { it.dependencyRevisions.values.single() }.toSet(),
        )
    }

    @Test
    fun `commit boundary rejects invalid raw proposals`() {
        val store = InMemoryJournalStore(FixedClock(Instant.EPOCH), DeterministicIdSource("proposal-"))
        val mutation = AssignmentMutation(
            assignmentId = AssignmentId("a"), workflowId = WorkflowId("w"),
            workflowVersionId = WorkflowVersionId("w@1"), executionId = ExecutionId("e"),
            contextId = ContextId("anonymous"), registerId = RegisterId("r"), value = Value.Null,
            producerId = ProducerId("p"), occurredAt = Instant.EPOCH,
        )
        assertThrows(IllegalArgumentException::class.java, Executable {
            store.commit(JournalBatchProposal(JournalBatchId("empty"), emptyList(), Instant.EPOCH))
        })
        assertThrows(IllegalArgumentException::class.java, Executable {
            store.commit(JournalBatchProposal(JournalBatchId("many"), listOf(mutation, mutation), Instant.EPOCH))
        })
        assertThrows(IllegalArgumentException::class.java, Executable {
            store.commit(JournalBatchProposal(JournalBatchId("future"), listOf(mutation), Instant.EPOCH, formatVersion = 2))
        })
    }

    @Test
    fun `restart deduplicates startup intent when clock advances`() {
        val clock = object : Clock {
            private var second = 0L
            override fun now(): Instant = Instant.ofEpochSecond(second++)
        }
        val store = InMemoryJournalStore(clock, DeterministicIdSource("shared-"))
        val runner = InMemoryWorkflowRunner(
            idSource = DeterministicIdSource("runner-"),
            clock = clock,
            journal = store,
        )
        val execution = ExecutionId("advancing-restart")
        assertThrows(IllegalStateException::class.java) {
            runner.run(hello, mapOf("name" to Value.StringValue("Ada")), execution) {
                throw IllegalStateException("injected")
            }
        }
        val original = store.activationIntents().single()
        val resumed = runner.run(hello, mapOf("name" to Value.StringValue("Ada")), execution)
        assertEquals(Value.StringValue("Hello, Ada!"), resumed.outputs.getValue("copy").value)
        assertEquals(original, store.activationIntents().first())
        assertEquals(2, store.activationIntents().size)
    }

    @Test
    fun `binding failure is reported without fabricating an output`() {
        val yaml = """
            workflow:
              id: failure
              version: 1
              parameters: {payload: {schema: any}}
              context: {value: {${'$'}ref: "${'$'}.parameters.payload.missing"}}
              outputs: [value]
        """.trimIndent()
        val result = InMemoryWorkflowRunner(idSource = DeterministicIdSource("failure-"))
            .run(yaml, mapOf("payload" to Value.ObjectValue(emptyMap())), ExecutionId("failure-exec"))
        assertFalse(result.isSuccessful)
        assertTrue(result.outputs.isEmpty())
        assertTrue(result.failures.single().contains("missing"))
    }

    @Test
    fun `optional context dependency is not a dependency-free startup producer`() {
        val yaml = """
            workflow:
              id: optional-context
              version: 1
              context:
                source: one
                copied: {${'$'}optional: "${'$'}.source"}
              outputs: [copied]
        """.trimIndent()
        val result = InMemoryWorkflowRunner(idSource = DeterministicIdSource("optional-context-"))
            .run(yaml, executionId = ExecutionId("optional-context-exec"))
        assertEquals(Value.TaggedValue("option.some", Value.StringValue("one")), result.outputs.getValue("copied").value)
        assertEquals(1L, result.outputs.getValue("copied").revision)
        assertEquals(2, result.journal.activationIntents().size)
    }

    @Test
    fun `shared store claims and inspects only the selected execution`() {
        val runner = InMemoryWorkflowRunner(
            idSource = DeterministicIdSource("shared-executions-"),
            clock = FixedClock(Instant.EPOCH),
        )
        runner.run(hello, mapOf("name" to Value.StringValue("Ada")), ExecutionId("first-execution"))
        val second = runner.run(hello, mapOf("name" to Value.StringValue("Bob")), ExecutionId("second-execution"))
        assertEquals(Value.StringValue("Hello, Bob!"), second.outputs.getValue("copy").value)
        val inspection = second.inspectionJson()
        assertTrue(inspection.contains("second-execution"))
        assertFalse(inspection.contains("first-execution"))
    }

    @Test
    fun `execution restart cannot change workflow parameters`() {
        val runner = InMemoryWorkflowRunner(
            idSource = DeterministicIdSource("immutable-parameters-"),
            clock = FixedClock(Instant.EPOCH),
        )
        val execution = ExecutionId("immutable-execution")
        runner.run(hello, mapOf("name" to Value.StringValue("Ada")), execution)
        val failure = assertThrows(IllegalArgumentException::class.java, Executable {
            runner.run(hello, mapOf("name" to Value.StringValue("Bob")), execution)
        })
        assertTrue(failure.message!!.contains("different workflow content or parameters"))
    }
}
