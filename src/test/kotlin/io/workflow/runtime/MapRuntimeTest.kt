package io.workflow.runtime

import io.workflow.compiler.WorkflowCompiler
import io.workflow.core.DeterministicIdSource
import io.workflow.core.EmissionId
import io.workflow.core.ExecutionId
import io.workflow.core.FixedClock
import io.workflow.core.Value
import io.workflow.core.ValueSchema
import io.workflow.provider.ProviderDescriptor
import io.workflow.provider.ProviderInvocationRequest
import io.workflow.provider.ProviderLifecycleMessage
import io.workflow.provider.ProviderRegistry
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MapRuntimeTest {
    @Test
    fun `empty single and array maps gather their corresponding shapes`() {
        val yaml = expressionMapYaml()
        fun run(values: List<Value>): Value = InMemoryWorkflowRunner(
            idSource = DeterministicIdSource("shape-"),
            clock = FixedClock(Instant.EPOCH),
        ).run(yaml, mapOf("values" to Value.ArrayValue(values)), ExecutionId("shape-${values.size}"))
            .outputs.getValue("mapped").value

        assertEquals(Value.ArrayValue(emptyList()), run(emptyList()))
        assertEquals(Value.ArrayValue(listOf(Value.StringValue("one"))), run(listOf(Value.StringValue("one"))))
        assertEquals(
            Value.ArrayValue(listOf(Value.StringValue("a"), Value.StringValue("b"))),
            run(listOf(Value.StringValue("a"), Value.StringValue("b"))),
        )
    }

    @Test
    fun `each item executes its nested dependency graph`() {
        val result = InMemoryWorkflowRunner(
            idSource = DeterministicIdSource("nested-"),
            clock = FixedClock(Instant.EPOCH),
            workerCount = 2,
        ).run(
            """
            workflow:
              id: nested-map
              version: 1
              parameters:
                values: {schema: {type: array, items: string}}
              context:
                prefix: p-
                mapped:
                  map:
                    over: {${'$'}ref: '${'$'}.parameters.values'}
                    context:
                      initial: {${'$'}concat: [{${'$'}ref: '${'$'}.prefix'}, {${'$'}ref: '${'$'}.item'}]}
                      selected: {${'$'}ref: '${'$'}.initial'}
                    output: selected
              outputs: [mapped]
            """.trimIndent(),
            values("a", "b"),
            ExecutionId("nested-dependency-execution"),
        )

        assertEquals(
            Value.ArrayValue(listOf(Value.StringValue("p-a"), Value.StringValue("p-b"))),
            result.outputs.getValue("mapped").value,
        )
        val nested = result.journal.assignments().filter { it.mapItemId != null }
        assertEquals(4, nested.size)
        assertTrue(nested.all { it.dependencyRevisions.size == 1 })
    }

    @Test
    fun `empty and populated keyed maps gather in canonical key order`() {
        val empty = InMemoryWorkflowRunner(
            idSource = DeterministicIdSource("keyed-"),
            clock = FixedClock(Instant.EPOCH),
        ).run(
            """
            workflow:
              id: keyed-runtime
              version: 1
              context:
                mapped:
                  map:
                    over: {}
                    context:
                      value: unused
                    output: value
              outputs: [mapped]
            """.trimIndent(),
            executionId = ExecutionId("keyed-empty"),
        )
        assertEquals(Value.ObjectValue(emptyMap()), empty.outputs.getValue("mapped").value)

        val result = InMemoryWorkflowRunner(
            idSource = DeterministicIdSource("keyed-"),
            clock = FixedClock(Instant.EPOCH),
        ).run(
            """
            workflow:
              id: keyed-runtime
              version: 1
              context:
                source: {b: two, a: one}
                mapped:
                  map:
                    over: {${'$'}ref: '${'$'}.source'}
                    context:
                      value: {${'$'}concat: [{${'$'}ref: '${'$'}.key'}, =, {${'$'}ref: '${'$'}.item'}]}
                    output: value
              outputs: [mapped]
            """.trimIndent(),
            executionId = ExecutionId("keyed-populated"),
        )
        assertEquals(
            Value.ObjectValue(linkedMapOf("a" to Value.StringValue("a=one"), "b" to Value.StringValue("b=two"))),
            result.outputs.getValue("mapped").value,
        )
        val nested = result.journal.assignments().filter { it.mapItemId != null }
        assertEquals(2, nested.size)
        assertEquals(setOf("a", "b"), nested.mapNotNull { it.mapItemKey }.toSet())
        assertTrue(nested.all { it.mapInputRevision != null && it.mapActivationId != null })
    }

    @Test
    fun `barrier proves gather follows array index rather than completion order`() {
        val entered = CountDownLatch(2)
        val secondCommitted = CountDownLatch(1)
        val registry = itemProvider { request ->
            val item = (request.input as Value.StringValue).value
            entered.countDown()
            check(entered.await(5, TimeUnit.SECONDS))
            if (item == "first") {
                check(secondCommitted.await(5, TimeUnit.SECONDS))
                emissionSequence(request, "first-result")
            } else sequence {
                yield(emission(request, "second-result", "second"))
                secondCommitted.countDown()
                yield(ProviderLifecycleMessage.Completed)
            }.asIterable()
        }
        val result = mapRunner(registry, 2).run(
            providerMapYaml(),
            values("first", "second"),
            ExecutionId("out-of-order-execution"),
        )

        assertTrue(result.isSuccessful, result.failures.joinToString())
        assertEquals(
            listOf(Value.StringValue("second-result"), Value.StringValue("first-result")),
            result.journal.assignments().filter { it.mapItemId != null }.map { it.value },
        )
        assertEquals(
            Value.ArrayValue(listOf(Value.StringValue("first-result"), Value.StringValue("second-result"))),
            result.outputs.getValue("mapped").value,
        )
        val gather = result.journal.assignments().single { it.registerId.value.endsWith("/register/mapped") }
        assertTrue(gather.causationId!!.startsWith("gather-assignment-"))
        assertEquals(parentMapActivation(result).activationId, gather.activationId)
        assertTrue(result.journal.batches().all { it.mutations.size == 1 && it.assignment.mutationOrdinal == 0 })
    }

    @Test
    fun `every selected output after readiness emits a current full gather`() {
        val initialCommitted = CountDownLatch(2)
        val registry = itemProvider { request ->
            val item = (request.input as Value.StringValue).value
            sequence {
                yield(emission(request, "$item-v1", "$item-1"))
                initialCommitted.countDown()
                check(initialCommitted.await(5, TimeUnit.SECONDS))
                if (item == "first") yield(emission(request, "first-v2", "first-2"))
                yield(ProviderLifecycleMessage.Completed)
            }.asIterable()
        }
        val result = mapRunner(registry, 2).run(
            providerMapYaml(),
            values("first", "second"),
            ExecutionId("repeated-output-execution"),
        )

        assertTrue(result.isSuccessful, result.failures.joinToString())
        val gathers = result.journal.assignments().filter { it.registerId.value.endsWith("/register/mapped") }
        assertEquals(
            listOf(
                Value.ArrayValue(listOf(Value.StringValue("first-v1"), Value.StringValue("second-v1"))),
                Value.ArrayValue(listOf(Value.StringValue("first-v2"), Value.StringValue("second-v1"))),
            ),
            gathers.map { it.value },
        )
        assertEquals(listOf(1L, 2L), gathers.map { it.revision })
        assertTrue(gathers.all { it.causationId!!.startsWith("gather-assignment-") })
    }

    @Test
    fun `item failure before readiness fails map without a gathered assignment`() {
        val registry = itemProvider { request ->
            if ((request.input as Value.StringValue).value == "bad") {
                listOf(ProviderLifecycleMessage.Failed(Value.StringValue("before readiness")))
            } else emissionSequence(request, "good-result")
        }
        val result = mapRunner(registry, 2).run(
            providerMapYaml(),
            values("good", "bad"),
            ExecutionId("failure-before-execution"),
        )

        assertFalse(result.isSuccessful)
        assertTrue(result.failures.any { it.contains("before readiness") })
        assertTrue(result.journal.assignments().none { it.registerId.value.endsWith("/register/mapped") })
        assertEquals(ActivationRecord.Status.FAILED, parentMapActivation(result).status)
    }

    @Test
    fun `item failure after readiness preserves gather and fails map`() {
        val initialCommitted = CountDownLatch(2)
        val registry = itemProvider { request ->
            val item = (request.input as Value.StringValue).value
            sequence {
                yield(emission(request, "$item-result", item))
                initialCommitted.countDown()
                check(initialCommitted.await(5, TimeUnit.SECONDS))
                if (item == "bad") yield(ProviderLifecycleMessage.Failed(Value.StringValue("after readiness")))
                else yield(ProviderLifecycleMessage.Completed)
            }.asIterable()
        }
        val result = mapRunner(registry, 2).run(
            providerMapYaml(),
            values("good", "bad"),
            ExecutionId("failure-after-execution"),
        )

        assertFalse(result.isSuccessful)
        assertTrue(result.failures.any { it.contains("after readiness") })
        assertEquals(
            Value.ArrayValue(listOf(Value.StringValue("good-result"), Value.StringValue("bad-result"))),
            result.outputs.getValue("mapped").value,
        )
        assertEquals(ActivationRecord.Status.FAILED, parentMapActivation(result).status)
    }

    @Test
    fun `completed item without selected output fails instead of hanging`() {
        val registry = itemProvider { _ -> listOf(ProviderLifecycleMessage.Completed) }
        val result = mapRunner(registry, 1).run(
            providerMapYaml(),
            values("silent"),
            ExecutionId("no-output-execution"),
        )

        assertFalse(result.isSuccessful)
        assertTrue(result.failures.any { it.contains("completed without output") })
        assertTrue(result.outputs.isEmpty())
        assertEquals(ActivationRecord.Status.FAILED, parentMapActivation(result).status)
    }

    @Test
    fun `repeated collection revisions create distinct immutable item families`() {
        val registry = ProviderRegistry().also {
            it.register(
                ProviderDescriptor("collections", 1, ValueSchema.Any, ValueSchema.Array(ValueSchema.String), ValueSchema.Any),
            ) { request ->
                listOf(
                    ProviderLifecycleMessage.Emission(
                        Value.ArrayValue(listOf(Value.StringValue("first"))), EmissionId("first"), request.invocationId, request.attemptId,
                    ),
                    ProviderLifecycleMessage.Emission(
                        Value.ArrayValue(listOf(Value.StringValue("second"))), EmissionId("second"), request.invocationId, request.attemptId,
                    ),
                    ProviderLifecycleMessage.Completed,
                )
            }
        }
        val result = InMemoryWorkflowRunner(
            compiler = WorkflowCompiler(registry),
            idSource = DeterministicIdSource("revision-"),
            clock = FixedClock(Instant.EPOCH),
            workerCount = 1,
        ).run(
            """
            workflow:
              id: repeated-map
              version: 1
              context:
                source: {provider: collections, version: 1}
                mapped:
                  map:
                    over: {${'$'}ref: '${'$'}.source'}
                    context:
                      value: {${'$'}ref: '${'$'}.item'}
                    output: value
              outputs: [mapped]
            """.trimIndent(),
            executionId = ExecutionId("revision-execution"),
        )

        assertTrue(result.isSuccessful, result.failures.joinToString())
        assertEquals(Value.ArrayValue(listOf(Value.StringValue("second"))), result.outputs.getValue("mapped").value)
        val nested = result.journal.assignments().filter { it.mapItemId != null }
        assertEquals(2, nested.mapNotNull { it.mapInputRevision }.toSet().size)
        assertEquals(2, nested.mapNotNull { it.mapActivationId }.toSet().size)
        assertEquals(2, nested.map { it.contextId }.toSet().size)
        assertEquals(1, nested.mapNotNull { it.mapItemId }.toSet().size)
    }

    @Test
    fun `nested identities and parent revision provenance are stable across deterministic fixtures`() {
        fun run(): WorkflowRunResult {
            val registry = itemProvider { request ->
                emissionSequence(request, "${(request.input as Value.StringValue).value}-result")
            }
            return mapRunner(registry, 1).run(
                providerMapYaml(),
                values("a", "b"),
                ExecutionId("stable-map-execution"),
            )
        }
        val first = run()
        val second = run()
        fun identityFixture(result: WorkflowRunResult) = result.journal.activationIntents()
            .filter { it.mapItemId != null }
            .sortedBy { it.mapItemIndex }
            .map { listOf(it.mapActivationId!!.value, it.mapItemId!!, it.activationId.value, it.mapInputRevision!!.value, it.contextId.value) }

        assertEquals(identityFixture(first), identityFixture(second))
        val nestedAssignments = first.journal.assignments().filter { it.mapItemId != null }
        val inputRevision = nestedAssignments.mapNotNull { it.mapInputRevision }.toSet().single()
        assertTrue(nestedAssignments.all { it.mapActivationId != null && it.mapInputRevision == inputRevision })
        assertTrue(first.journal.activations().filter { it.mapItemId != null }.all { it.mapInputRevision == inputRevision })
        assertTrue(first.journal.providerEvents().filter { it.mapItemId != null }.all { it.mapInputRevision == inputRevision })
        assertTrue(first.inspectionJson().contains("\"mapInputRevision\""))
    }

    @Test
    fun `map waits for outer bindings without reactivating for their later revisions`() {
        val registry = ProviderRegistry().also {
            it.register(
                ProviderDescriptor(
                    "collections",
                    1,
                    ValueSchema.Any,
                    ValueSchema.Array(ValueSchema.String),
                    ValueSchema.Any,
                ),
            ) { request -> listOf(
                ProviderLifecycleMessage.Emission(
                    Value.ArrayValue(listOf(Value.StringValue("item"))),
                    EmissionId("collection"),
                    request.invocationId,
                    request.attemptId,
                ),
                ProviderLifecycleMessage.Completed,
            ) }
            it.register(
                ProviderDescriptor("prefixes", 1, ValueSchema.Any, ValueSchema.String, ValueSchema.Any),
            ) { request -> listOf(
                ProviderLifecycleMessage.Emission(
                    Value.StringValue("old-"), EmissionId("old-prefix"), request.invocationId, request.attemptId,
                ),
                ProviderLifecycleMessage.Emission(
                    Value.StringValue("new-"), EmissionId("new-prefix"), request.invocationId, request.attemptId,
                ),
                ProviderLifecycleMessage.Completed,
            ) }
        }
        val result = InMemoryWorkflowRunner(
            compiler = WorkflowCompiler(registry),
            providerRegistry = registry,
            idSource = DeterministicIdSource("deferred-"),
            clock = FixedClock(Instant.EPOCH),
            workerCount = 1,
        ).run(
            """
            workflow:
              id: deferred-map
              version: 1
              context:
                source: {provider: collections, version: 1}
                mapped:
                  map:
                    over: {${'$'}ref: '${'$'}.source'}
                    context:
                      value: {${'$'}concat: [{${'$'}ref: '${'$'}.prefix'}, {${'$'}ref: '${'$'}.item'}]}
                    output: value
                prefix:
                  provider: prefixes
                  version: 1
                  with: {${'$'}ref: '${'$'}.source'}
              outputs: [mapped]
            """.trimIndent(),
            executionId = ExecutionId("deferred-map-execution"),
        )

        assertTrue(result.isSuccessful, result.failures.joinToString())
        assertEquals(
            Value.ArrayValue(listOf(Value.StringValue("new-item"))),
            result.outputs.getValue("mapped").value,
        )
        assertEquals(1, result.journal.activationIntents().count {
            it.producerId.value.endsWith("/producer/mapped")
        })
        assertEquals(1, result.journal.assignments().count {
            it.registerId.value.endsWith("/register/mapped")
        })
        val sourceRevision = result.journal.assignments().single {
            it.registerId.value.endsWith("/register/source")
        }.assignmentId
        assertTrue(result.journal.assignments().filter { it.mapItemId != null }.all {
            it.mapInputRevision == sourceRevision
        })
    }

    @Test
    fun `match nested in a map participates in the item completion barrier`() {
        val choiceSchema = ValueSchema.TaggedUnion(
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
        val registry = ProviderRegistry().also {
            it.register(
                ProviderDescriptor("choices", 1, ValueSchema.Any, ValueSchema.Array(choiceSchema), ValueSchema.Any),
            ) { request -> listOf(
                ProviderLifecycleMessage.Emission(
                    Value.ArrayValue(listOf(
                        Value.ObjectValue(mapOf(
                            "kind" to Value.StringValue("ok"),
                            "value" to Value.StringValue("chosen"),
                        )),
                        Value.ObjectValue(mapOf(
                            "kind" to Value.StringValue("failed"),
                            "error" to Value.StringValue("handled"),
                        )),
                    )),
                    EmissionId("choices"),
                    request.invocationId,
                    request.attemptId,
                ),
                ProviderLifecycleMessage.Completed,
            ) }
        }
        val result = InMemoryWorkflowRunner(
            compiler = WorkflowCompiler(registry),
            providerRegistry = registry,
            idSource = DeterministicIdSource("nested-match-"),
            clock = FixedClock(Instant.EPOCH),
            workerCount = 2,
        ).run(
            """
            workflow:
              id: nested-match-map
              version: 1
              context:
                source: {provider: choices, version: 1}
                mapped:
                  map:
                    over: {${'$'}ref: '${'$'}.source'}
                    context:
                      selected:
                        schema: string
                        match:
                          value: {${'$'}ref: '${'$'}.item'}
                          cases:
                            ok: {${'$'}ref: '${'$'}.match.value'}
                            failed: {${'$'}ref: '${'$'}.match.error'}
                    output: selected
              outputs: [mapped]
            """.trimIndent(),
            executionId = ExecutionId("nested-match-map-execution"),
        )

        assertTrue(result.isSuccessful, result.failures.joinToString())
        assertEquals(
            Value.ArrayValue(listOf(Value.StringValue("chosen"), Value.StringValue("handled"))),
            result.outputs.getValue("mapped").value,
        )
        assertEquals(ActivationRecord.Status.COMPLETED, parentMapActivation(result).status)
        val branches = result.journal.activationIntents().filter { it.branchTag != null }
        assertEquals(2, branches.size)
        assertTrue(branches.all { it.mapActivationId != null && it.mapItemId != null && it.parentActivationId != null })
    }

    @Test
    fun `map does not wait for an outer dependency used only by an unselected match case`() {
        val registry = ProviderRegistry().also {
            it.register(
                ProviderDescriptor("silent", 1, ValueSchema.Any, ValueSchema.String, ValueSchema.Any),
            ) { listOf(ProviderLifecycleMessage.Completed) }
        }
        val result = InMemoryWorkflowRunner(
            compiler = WorkflowCompiler(registry),
            providerRegistry = registry,
            idSource = DeterministicIdSource("unselected-"),
            clock = FixedClock(Instant.EPOCH),
            workerCount = 2,
        ).run(
            """
            workflow:
              id: unselected-map-dependency
              version: 1
              parameters:
                values:
                  schema:
                    type: array
                    items:
                      type: tagged-union
                      discriminator: kind
                      variants:
                        Ready:
                          type: object
                          fields:
                            kind: {schema: string}
                            value: {schema: string}
                        Failed:
                          type: object
                          fields:
                            kind: {schema: string}
                            error: {schema: string}
              context:
                never: {provider: silent, version: 1}
                mapped:
                  map:
                    over: {${'$'}ref: '${'$'}.parameters.values'}
                    context:
                      selected:
                        schema: string
                        match:
                          value: {${'$'}ref: '${'$'}.item'}
                          cases:
                            Ready: {${'$'}ref: '${'$'}.match.value'}
                            Failed: {${'$'}ref: '${'$'}.never'}
                    output: selected
              outputs: [mapped]
            """.trimIndent(),
            mapOf(
                "values" to Value.ArrayValue(listOf(Value.ObjectValue(mapOf(
                    "kind" to Value.StringValue("Ready"),
                    "value" to Value.StringValue("chosen"),
                )))),
            ),
            ExecutionId("unselected-map-dependency-execution"),
        )

        assertTrue(result.isSuccessful, result.failures.joinToString())
        assertEquals(
            Value.ArrayValue(listOf(Value.StringValue("chosen"))),
            result.outputs.getValue("mapped").value,
        )
        assertEquals(ActivationRecord.Status.COMPLETED, parentMapActivation(result).status)
    }

    @Test
    fun `selected map item match case resumes when its outer dependency arrives`() {
        val registry = ProviderRegistry().also {
            it.register(
                ProviderDescriptor("late", 1, ValueSchema.String, ValueSchema.String, ValueSchema.Any),
            ) { request -> listOf(
                ProviderLifecycleMessage.Emission(
                    Value.StringValue("-outer"), EmissionId("late"), request.invocationId, request.attemptId,
                ),
                ProviderLifecycleMessage.Completed,
            ) }
        }
        val result = InMemoryWorkflowRunner(
            compiler = WorkflowCompiler(registry),
            providerRegistry = registry,
            idSource = DeterministicIdSource("selected-late-"),
            clock = FixedClock(Instant.EPOCH),
            workerCount = 1,
        ).run(
            """
            workflow:
              id: selected-late-map-dependency
              version: 1
              parameters:
                values:
                  schema:
                    type: array
                    items:
                      type: tagged-union
                      discriminator: kind
                      variants:
                        Ready:
                          type: object
                          fields:
                            kind: {schema: string}
                            value: {schema: string}
                        Failed:
                          type: object
                          fields:
                            kind: {schema: string}
                            error: {schema: string}
              context:
                mapped:
                  map:
                    over: {${'$'}ref: '${'$'}.parameters.values'}
                    context:
                      selected:
                        schema: string
                        match:
                          value: {${'$'}ref: '${'$'}.item'}
                          cases:
                            Ready:
                              ${'$'}concat: [{${'$'}ref: '${'$'}.match.value'}, {${'$'}ref: '${'$'}.late'}]
                            Failed: {${'$'}ref: '${'$'}.match.error'}
                    output: selected
                trigger: trigger
                late:
                  provider: late
                  version: 1
                  with: {${'$'}ref: '${'$'}.trigger'}
              outputs: [mapped]
            """.trimIndent(),
            mapOf(
                "values" to Value.ArrayValue(listOf(Value.ObjectValue(mapOf(
                    "kind" to Value.StringValue("Ready"),
                    "value" to Value.StringValue("chosen"),
                )))),
            ),
            ExecutionId("selected-late-map-dependency-execution"),
        )

        assertTrue(result.isSuccessful, result.failures.joinToString())
        assertEquals(
            Value.ArrayValue(listOf(Value.StringValue("chosen-outer"))),
            result.outputs.getValue("mapped").value,
        )
        assertEquals(ActivationRecord.Status.COMPLETED, parentMapActivation(result).status)
    }

    @Test
    fun `map nested in match retains outer lexical bindings and assignment provenance`() {
        val result = InMemoryWorkflowRunner(
            idSource = DeterministicIdSource("match-map-"),
            clock = FixedClock(Instant.EPOCH),
            workerCount = 2,
        ).run(
            """
            workflow:
              id: match-map-runtime
              version: 1
              parameters:
                result:
                  schema:
                    type: tagged-union
                    discriminator: kind
                    variants:
                      Ready:
                        type: object
                        fields:
                          kind: {schema: string}
                          prefix: {schema: string}
                          items: {schema: {type: array, items: string}}
              context:
                handled:
                  match:
                    value: {${'$'}ref: '${'$'}.parameters.result'}
                    cases:
                      Ready:
                        map:
                          over: {${'$'}ref: '${'$'}.match.items'}
                          context:
                            value:
                              ${'$'}concat: [{${'$'}ref: '${'$'}.match.prefix'}, {${'$'}ref: '${'$'}.item'}]
                          output: value
              outputs: [handled]
            """.trimIndent(),
            mapOf(
                "result" to Value.ObjectValue(mapOf(
                    "kind" to Value.StringValue("Ready"),
                    "prefix" to Value.StringValue("p/"),
                    "items" to Value.ArrayValue(listOf(Value.StringValue("a"), Value.StringValue("b"))),
                )),
            ),
            ExecutionId("match-map-execution"),
        )

        assertTrue(result.isSuccessful, result.failures.joinToString())
        assertEquals(
            Value.ArrayValue(listOf(Value.StringValue("p/a"), Value.StringValue("p/b"))),
            result.outputs.getValue("handled").value,
        )
        val matchActivation = result.journal.activations().single {
            it.producerId.value.endsWith("/producer/handled")
        }
        val gather = result.journal.assignments().single {
            it.registerId.value.endsWith("/register/handled")
        }
        assertEquals(matchActivation.activationId, gather.parentActivationId)
        assertTrue(gather.producerId.value.endsWith("/producer/handled/match/case/Ready"))
    }

    private fun expressionMapYaml(): String = """
        workflow:
          id: expression-map
          version: 1
          parameters:
            values: {schema: {type: array, items: string}}
          context:
            mapped:
              map:
                over: {${'$'}ref: '${'$'}.parameters.values'}
                context:
                  value: {${'$'}ref: '${'$'}.item'}
                output: value
          outputs: [mapped]
    """.trimIndent()

    private fun providerMapYaml(): String = """
        workflow:
          id: provider-map
          version: 1
          parameters:
            values: {schema: {type: array, items: string}}
          context:
            mapped:
              map:
                over: {${'$'}ref: '${'$'}.parameters.values'}
                context:
                  value:
                    provider: item-provider
                    version: 1
                    with: {${'$'}ref: '${'$'}.item'}
                output: value
          outputs: [mapped]
    """.trimIndent()

    private fun itemProvider(
        invoke: (ProviderInvocationRequest) -> Iterable<ProviderLifecycleMessage>,
    ): ProviderRegistry = ProviderRegistry().also {
        it.register(
            ProviderDescriptor("item-provider", 1, ValueSchema.String, ValueSchema.String, ValueSchema.Any),
            invoke,
        )
    }

    private fun mapRunner(registry: ProviderRegistry, workerCount: Int) = InMemoryWorkflowRunner(
        compiler = WorkflowCompiler(registry),
        idSource = DeterministicIdSource("map-"),
        clock = FixedClock(Instant.EPOCH),
        workerCount = workerCount,
    )

    private fun values(vararg items: String): Map<String, Value> = mapOf(
        "values" to Value.ArrayValue(items.map(Value::StringValue)),
    )

    private fun emission(request: ProviderInvocationRequest, value: String, id: String) = ProviderLifecycleMessage.Emission(
        Value.StringValue(value), EmissionId(id), request.invocationId, request.attemptId,
    )

    private fun emissionSequence(request: ProviderInvocationRequest, value: String): List<ProviderLifecycleMessage> = listOf(
        emission(request, value, value),
        ProviderLifecycleMessage.Completed,
    )

    private fun parentMapActivation(result: WorkflowRunResult): ActivationRecord = result.journal.activations()
        .single { it.producerId.value.endsWith("/producer/mapped") }
}
