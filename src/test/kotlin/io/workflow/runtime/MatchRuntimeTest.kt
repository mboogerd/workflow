package io.workflow.runtime

import io.workflow.compiler.CompiledProducer
import io.workflow.compiler.WorkflowCompiler
import io.workflow.core.DeterministicIdSource
import io.workflow.core.EmissionId
import io.workflow.core.ExecutionId
import io.workflow.core.FixedClock
import io.workflow.core.Value
import io.workflow.core.ValueSchema
import io.workflow.provider.ProviderDescriptor
import io.workflow.provider.ProviderImplementation
import io.workflow.provider.ProviderInvocationRequest
import io.workflow.provider.ProviderLifecycleMessage
import io.workflow.provider.ProviderRegistry
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MatchRuntimeTest {
    private val decisionSchema = ValueSchema.TaggedUnion(
        discriminator = "kind",
        variants = mapOf(
            "ok" to ValueSchema.Object(mapOf(
                "kind" to ValueSchema.Object.Field(ValueSchema.String),
                "value" to ValueSchema.Object.Field(ValueSchema.String),
            )),
            "failed" to ValueSchema.Object(mapOf(
                "kind" to ValueSchema.Object.Field(ValueSchema.String),
                "error" to ValueSchema.Object.Field(ValueSchema.String),
            )),
        ),
    )

    private val matchYaml = """
        workflow:
          id: reactive-match
          version: 1
          context:
            decisions: {provider: decisions, version: 1}
            prefix: p/
            selected:
              schema: string
              match:
                value: {${'$'}ref: "${'$'}.decisions"}
                cases:
                  ok:
                    provider: ok-handler
                    version: 1
                    with:
                      ${'$'}concat: [{${'$'}ref: "${'$'}.prefix"}, {${'$'}ref: "${'$'}.match.value"}]
                  failed:
                    provider: failed-handler
                    version: 1
                    with:
                      ${'$'}concat: [{${'$'}ref: "${'$'}.prefix"}, {${'$'}ref: "${'$'}.match.error"}]
          outputs: [selected]
    """.trimIndent()

    @Test
    fun `every discriminator revision executes one branch and retains history`() {
        val calls = mutableListOf<String>()
        val requests = mutableListOf<ProviderInvocationRequest>()
        val result = runner(
            registry(listOf(ok("same"), ok("same"), failed("boom")), calls, requests = requests),
            "match-",
        ).run(matchYaml, executionId = ExecutionId("match-execution"))

        assertTrue(result.isSuccessful, "failures=${result.failures}")
        assertEquals(listOf("ok:p/same", "ok:p/same", "failed:p/boom"), calls)
        assertEquals(Value.StringValue("failed:p/boom"), result.outputs.getValue("selected").value)

        val decisions = assignments(result, "decisions")
        val selected = assignments(result, "selected")
        assertEquals(listOf(1L, 2L, 3L), decisions.map { it.revision })
        assertEquals(listOf(1L, 2L, 3L), selected.map { it.revision })
        assertEquals(
            listOf(Value.StringValue("ok:p/same"), Value.StringValue("ok:p/same"), Value.StringValue("failed:p/boom")),
            selected.map { it.value },
        )
        assertEquals(decisions.map { it.assignmentId }, selected.map { it.discriminatorRevision })
        assertEquals(3, selected.map { it.activationId }.distinct().size)
        assertEquals(3, selected.map { it.parentActivationId }.distinct().size)
        assertNotEquals(selected[0].activationId, selected[1].activationId)

        val prefixRevision = assignments(result, "prefix").single().assignmentId
        val branchIntents = result.journal.activationIntents().filter { it.branchTag != null }
        assertEquals(listOf("ok", "ok", "failed"), branchIntents.map { it.branchTag })
        assertEquals(decisions.map { it.assignmentId }, branchIntents.map { it.discriminatorRevision })
        assertTrue(branchIntents.all { intent ->
            intent.dependencyRevisions.values.containsAll(listOf(intent.discriminatorRevision, prefixRevision))
        })

        val invocations = result.journal.providerInvocations().filter { it.providerId != "decisions" }
        assertEquals(branchIntents.map { it.activationId }.toSet(), invocations.map { it.activationId }.toSet())
        assertTrue(invocations.all { it.parentActivationId != null && it.discriminatorRevision != null })
        assertEquals(decisions.map { it.assignmentId }, requests.map { it.discriminatorRevision })
        assertTrue(requests.all { it.parentActivationId != null })
        assertTrue(result.inspectionJson().contains("\"branchTag\""))
    }

    @Test
    fun `unselected branch performs no provider invocation`() {
        val calls = mutableListOf<String>()
        val result = runner(registry(listOf(ok("chosen")), calls), "one-").run(
            matchYaml,
            executionId = ExecutionId("one-branch"),
        )

        assertTrue(result.isSuccessful, result.failures.joinToString())
        assertEquals(listOf("ok:p/chosen"), calls)
        assertTrue(result.journal.providerInvocations().none { it.providerId == "failed-handler" })
        assertTrue(result.journal.activationIntents().none { it.branchTag == "failed" })
    }

    @Test
    fun `expression branch reads the captured discriminator value`() {
        val registry = descriptorRegistry(sourceMessages = emissions(ok("captured")))
        val result = runner(registry, "expression-").run(
            """
            workflow:
              id: expression-match
              version: 1
              context:
                decisions: {provider: decisions, version: 1}
                selected:
                  schema: string
                  match:
                    value: {${'$'}ref: '${'$'}.decisions'}
                    cases:
                      ok: {${'$'}ref: '${'$'}.match.value'}
                      failed: {${'$'}ref: '${'$'}.match.error'}
              outputs: [selected]
            """.trimIndent(),
            executionId = ExecutionId("expression-match"),
        )

        assertTrue(result.isSuccessful, result.failures.joinToString())
        assertEquals(Value.StringValue("captured"), result.outputs.getValue("selected").value)
        val source = assignments(result, "decisions").single()
        val selected = assignments(result, "selected").single()
        assertEquals(source.assignmentId, selected.discriminatorRevision)
        assertNotNull(selected.parentActivationId)
        assertTrue(selected.producerId.value.endsWith("/producer/selected/match/case/ok"))
    }

    @Test
    fun `selected branch waits for its required outer binding`() {
        val registry = descriptorRegistry(sourceMessages = emissions(ok("captured")))
        registry.register(
            ProviderDescriptor("late", 1, ValueSchema.Any, ValueSchema.String),
        ) { request -> listOf(
            emission(request, Value.StringValue("late-value"), "late"),
            ProviderLifecycleMessage.Completed,
        ) }
        val result = runner(registry, "readiness-").run(
            """
            workflow:
              id: ready-match
              version: 1
              context:
                decisions: {provider: decisions, version: 1}
                zlate:
                  provider: late
                  version: 1
                  with: {${'$'}ref: '${'$'}.decisions'}
                selected:
                  schema: string
                  match:
                    value: {${'$'}ref: '${'$'}.decisions'}
                    cases:
                      ok: {${'$'}ref: '${'$'}.zlate'}
                      failed: failure
              outputs: [selected]
            """.trimIndent(),
            executionId = ExecutionId("ready-match"),
        )

        assertTrue(result.isSuccessful, result.failures.joinToString())
        assertEquals(Value.StringValue("late-value"), result.outputs.getValue("selected").value)
        val intent = result.journal.activationIntents().single { it.branchTag == "ok" }
        assertTrue(intent.dependencyRevisions.values.contains(assignments(result, "zlate").single().assignmentId))
    }

    @Test
    fun `nested provider failure preserves match provenance without replacement`() {
        val result = runner(
            registry(listOf(failed("nested")), mutableListOf(), failFailedHandler = true),
            "failure-",
        ).run(matchYaml, executionId = ExecutionId("nested-failure"))

        assertFalse(result.isSuccessful)
        assertTrue(result.failures.single().contains("PROVIDER_FAILURE"))
        assertTrue(result.outputs.isEmpty())
        val decision = assignments(result, "decisions").single()
        val intent = result.journal.activationIntents().single { it.branchTag == "failed" }
        val requestEvent = result.journal.providerInvocations().single { it.providerId == "failed-handler" }
        val failureEvent = result.journal.providerFailures().single()
        assertEquals(intent.parentActivationId, requestEvent.parentActivationId)
        assertEquals(decision.assignmentId, requestEvent.discriminatorRevision)
        assertEquals(decision.assignmentId, failureEvent.discriminatorRevision)
        assertEquals(ActivationRecord.Status.FAILED, result.journal.activations().single {
            it.activationId == intent.activationId
        }.status)
        assertTrue(assignments(result, "selected").isEmpty())
    }

    @Test
    fun `malformed runtime tags are typed match activation failures`() {
        val compileRegistry = descriptorRegistry()
        val compiled = WorkflowCompiler(compileRegistry).compile(matchYaml)
        assertTrue(compiled.isValid, compiled.diagnostics.joinToString())
        val source = compiled.ir!!.registers.single { it.name == "decisions" }
        val sourceProducer = source.compiledProducer as CompiledProducer.Provider
        val relaxedSource = source.copy(
            schema = ValueSchema.Any,
            compiledProducer = sourceProducer.copy(schema = ValueSchema.Any),
        )
        val relaxedIr = compiled.ir!!.copy(
            registers = compiled.ir!!.registers.map { if (it.name == "decisions") relaxedSource else it },
        )

        listOf(
            Value.ObjectValue(mapOf("kind" to Value.StringValue("unknown"))) to "MATCH_UNKNOWN_TAG",
            Value.ObjectValue(mapOf("kind" to Value.IntegerValue(1))) to "MATCH_INVALID_RUNTIME_TAG",
            Value.StringValue("not-an-object") to "MATCH_INVALID_RUNTIME_TAG",
        ).forEachIndexed { index, (malformed, expectedType) ->
            val runtimeRegistry = descriptorRegistry(
                sourceSchema = ValueSchema.Any,
                sourceMessages = emissions(malformed),
            )
            val result = InMemoryWorkflowRunner(
                compiler = WorkflowCompiler(compileRegistry),
                providerRegistry = runtimeRegistry,
                idSource = DeterministicIdSource("malformed-$index-"),
                workerCount = 1,
            ).execute(relaxedIr, executionId = ExecutionId("malformed-$index"))

            assertFalse(result.isSuccessful)
            assertTrue(result.failures.single().contains(expectedType), result.failures.joinToString())
            assertTrue(result.outputs.isEmpty())
            assertEquals(ActivationRecord.Status.FAILED, result.journal.activations().single {
                it.producerId.value.endsWith("/producer/selected")
            }.status)
            assertTrue(result.journal.activationIntents().none { it.branchTag != null })
        }
    }

    @Test
    fun `nested activation identity survives restart without repeating effects`() {
        val calls = mutableListOf<String>()
        val sourceCalls = AtomicInteger()
        val runner = runner(registry(listOf(ok("restart")), calls, sourceCalls = sourceCalls), "restart-")
        val execution = ExecutionId("restart-execution")
        var crashed = false

        assertThrows(IllegalStateException::class.java) {
            runner.run(matchYaml, executionId = execution) { intent ->
                if (!crashed && intent.branchTag != null) {
                    crashed = true
                    throw IllegalStateException("injected before nested activation")
                }
            }
        }
        val persisted = runner.journal.activationIntents().single { it.branchTag == "ok" }
        assertFalse(runner.journal.isCompleted(persisted.id))

        val resumed = runner.run(matchYaml, executionId = execution)
        assertTrue(resumed.isSuccessful, resumed.failures.joinToString())
        assertEquals(persisted, runner.journal.activationIntents().single { it.branchTag == "ok" })
        assertTrue(runner.journal.isCompleted(persisted.id))
        assertEquals(1, sourceCalls.get())
        assertEquals(listOf("ok:p/restart"), calls)
    }

    private fun assignments(result: WorkflowRunResult, register: String) = result.journal.assignments()
        .filter { it.registerId.value.endsWith("/register/$register") }

    private fun runner(registry: ProviderRegistry, idPrefix: String) = InMemoryWorkflowRunner(
        compiler = WorkflowCompiler(registry),
        providerRegistry = registry,
        idSource = DeterministicIdSource(idPrefix),
        clock = FixedClock(Instant.EPOCH),
        workerCount = 1,
    )

    private fun registry(
        decisions: List<Value>,
        calls: MutableList<String>,
        failFailedHandler: Boolean = false,
        sourceCalls: AtomicInteger = AtomicInteger(),
        requests: MutableList<ProviderInvocationRequest> = mutableListOf(),
    ): ProviderRegistry = descriptorRegistry(
        sourceMessages = { request ->
            sourceCalls.incrementAndGet()
            decisions.mapIndexed { index, value -> emission(request, value, "decision-$index") } +
                ProviderLifecycleMessage.Completed
        },
        okMessages = handler("ok", calls, requests),
        failedMessages = if (failFailedHandler) { request ->
            val input = (request.input as Value.StringValue).value
            calls += "failed:$input"
            listOf(ProviderLifecycleMessage.Failed(Value.StringValue("failed:$input")))
        } else handler("failed", calls, requests),
    )

    private fun handler(
        name: String,
        calls: MutableList<String>,
        requests: MutableList<ProviderInvocationRequest>,
    ): (ProviderInvocationRequest) -> Iterable<ProviderLifecycleMessage> =
        { request ->
            requests += request
            val input = (request.input as Value.StringValue).value
            calls += "$name:$input"
            listOf(
                emission(request, Value.StringValue("$name:$input"), "$name-${request.invocationId.value}"),
                ProviderLifecycleMessage.Completed,
            )
        }

    private fun descriptorRegistry(
        sourceSchema: ValueSchema = decisionSchema,
        sourceMessages: ((ProviderInvocationRequest) -> Iterable<ProviderLifecycleMessage>)? = null,
        okMessages: ((ProviderInvocationRequest) -> Iterable<ProviderLifecycleMessage>)? = null,
        failedMessages: ((ProviderInvocationRequest) -> Iterable<ProviderLifecycleMessage>)? = null,
    ) = ProviderRegistry().also { registry ->
        registry.register(
            ProviderDescriptor("decisions", 1, ValueSchema.Any, sourceSchema),
            sourceMessages?.let(::ProviderImplementation),
        )
        registry.register(
            ProviderDescriptor("ok-handler", 1, ValueSchema.String, ValueSchema.String),
            okMessages?.let(::ProviderImplementation),
        )
        registry.register(
            ProviderDescriptor("failed-handler", 1, ValueSchema.String, ValueSchema.String),
            failedMessages?.let(::ProviderImplementation),
        )
    }

    private fun emissions(vararg values: Value): (ProviderInvocationRequest) -> Iterable<ProviderLifecycleMessage> =
        { request -> values.mapIndexed { index, value -> emission(request, value, "value-$index") } +
            ProviderLifecycleMessage.Completed }

    private fun emission(request: ProviderInvocationRequest, value: Value, id: String) =
        ProviderLifecycleMessage.Emission(value, EmissionId(id), request.invocationId, request.attemptId)

    private fun ok(value: String) = Value.ObjectValue(mapOf(
        "kind" to Value.StringValue("ok"),
        "value" to Value.StringValue(value),
    ))

    private fun failed(error: String) = Value.ObjectValue(mapOf(
        "kind" to Value.StringValue("failed"),
        "error" to Value.StringValue(error),
    ))
}
