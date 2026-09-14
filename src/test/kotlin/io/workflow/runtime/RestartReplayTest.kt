package io.workflow.runtime

import io.workflow.compiler.WorkflowCompiler
import io.workflow.compiler.WorkflowIrCodec
import io.workflow.core.DeterministicIdSource
import io.workflow.core.ExecutionId
import io.workflow.core.FixedClock
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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class RestartReplayTest {
    @TempDir lateinit var tempDir: Path

    private val expressionWorkflow = """
        workflow:
          id: restart
          version: 1
          context:
            source: one
            copied: {${'$'}ref: "${'$'}.source"}
          outputs: [copied]
    """.trimIndent()

    @Test
    fun `canonical deployed IR round trips and keeps its hash`() {
        val compiled = WorkflowCompiler().compile(expressionWorkflow).ir!!
        val decoded = WorkflowIrCodec.decode(compiled.canonicalJson())
        assertEquals(compiled.canonicalJson(), decoded.canonicalJson())
    }

    @Test
    fun `reopen resumes without invoking completed provider`() {
        val path = tempDir.resolve("resume.db")
        val calls = AtomicInteger()
        val registry = registry { request ->
            calls.incrementAndGet()
            listOf(ProviderLifecycleMessage.Emission(Value.StringValue("value"), io.workflow.core.EmissionId("e"), request.invocationId, request.attemptId), ProviderLifecycleMessage.Completed)
        }
        val workflow = WorkflowCompiler(registry).compile(providerWorkflow()).ir!!
        SqliteJournalStore(path, FixedClock(Instant.EPOCH), DeterministicIdSource("store-")).use { store ->
            InMemoryWorkflowRunner(compiler = WorkflowCompiler(registry), journal = store, providerRegistry = registry).execute(
                workflow, executionId = ExecutionId("e"),
            )
        }
        SqliteJournalStore(path, FixedClock(Instant.EPOCH), DeterministicIdSource("reopen-")).use { store ->
            val result = InMemoryWorkflowRunner(compiler = WorkflowCompiler(registry), journal = store, providerRegistry = registry)
                .resume(ExecutionId("e")).result()
            assertEquals(Value.StringValue("value"), result.outputs.getValue("value").value)
            assertEquals(1, calls.get())
            assertEquals(1, store.providerInvocations().size)
        }
    }

    @Test
    fun `replay rebuilds outputs without invoking providers`() {
        val calls = AtomicInteger()
        val registry = registry { request ->
            calls.incrementAndGet()
            listOf(ProviderLifecycleMessage.Emission(Value.StringValue("value"), io.workflow.core.EmissionId("e"), request.invocationId, request.attemptId), ProviderLifecycleMessage.Completed)
        }
        val workflow = WorkflowCompiler(registry).compile(providerWorkflow()).ir!!
        val journal = InMemoryJournalStore(FixedClock(Instant.EPOCH), DeterministicIdSource("memory-"))
        InMemoryWorkflowRunner(compiler = WorkflowCompiler(registry), journal = journal, providerRegistry = registry).execute(
            workflow, executionId = ExecutionId("replay"),
        )
        val replay = WorkflowReplay.replayOne(journal, ExecutionId("replay"))
        assertEquals(Value.StringValue("value"), replay.outputs(workflow).getValue("value").value)
        assertEquals(1, calls.get())
        assertTrue(replay.providerEvents.isNotEmpty())
    }

    private fun providerWorkflow() = """
        workflow:
          id: provider-restart
          version: 1
          context:
            value: {provider: restart-provider, version: 1}
          outputs: [value]
    """.trimIndent()

    private fun registry(implementation: ProviderImplementation) = ProviderRegistry().also {
        it.register(ProviderDescriptor("restart-provider", 1, ValueSchema.Any, ValueSchema.String), implementation)
    }
}
