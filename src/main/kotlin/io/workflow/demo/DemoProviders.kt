package io.workflow.demo

import io.workflow.core.EmissionId
import io.workflow.core.Value
import io.workflow.core.ValueSchema
import io.workflow.provider.EffectClass
import io.workflow.provider.IdempotencyContract
import io.workflow.provider.ProviderDescriptor
import io.workflow.provider.deterministicAgenticDescriptor
import io.workflow.provider.ProviderLifecycleMessage
import io.workflow.provider.ProviderRegistry
import io.workflow.provider.ReconciliationMode
import io.workflow.provider.ReconciliationDisposition
import io.workflow.provider.ReconciliationProviderImplementation
import io.workflow.provider.ReconciliationRequest
import io.workflow.provider.ReconciliationResult
import java.math.BigInteger
import java.util.concurrent.ConcurrentHashMap

/** Deterministic, offline providers used by the single-repository example. */
object DemoProviders {
    const val READER = "demo.repository-reader"
    const val BUILDER = "demo.model-builder"
    /** Providers used by the finite multi-repository example. */
    const val INVENTORY = "demo.repository-inventory"
    const val REPOSITORY_MODEL = "demo.repository-model"
    const val TOPOLOGY = "demo.dependency-topology"
    const val SYSTEM_MODEL = "demo.system-model"
    const val ARCHITECTURE_PUBLICATION = "demo.architecture-publication"
    /** Durable, controllable providers used by the continuous repository demonstration. */
    const val COMMIT_EVENTS = "demo.commit-events"
    const val CONTINUOUS_INVENTORY = "demo.continuous-repository-inventory"
    const val REPOSITORY_PROJECTION = "demo.repository-projection"
    const val INVENTORY_READER = INVENTORY
    const val REPOSITORY_CONTEXT_BUILDER = REPOSITORY_MODEL
    const val DEPENDENCY_RESOLVER = TOPOLOGY
    const val SYSTEM_ARCHITECTURE = SYSTEM_MODEL

    private val string = ValueSchema.String
    private val sourceFile = ValueSchema.Object(mapOf(
        "path" to ValueSchema.Object.Field(string),
        "language" to ValueSchema.Object.Field(string),
        "lines" to ValueSchema.Object.Field(ValueSchema.Integer),
    ))
    private val snapshot = ValueSchema.Object(mapOf(
        "repository" to ValueSchema.Object.Field(string),
        "commit" to ValueSchema.Object.Field(string),
        "files" to ValueSchema.Object.Field(ValueSchema.Array(sourceFile)),
    ))
    private val evidence = ValueSchema.Object(mapOf(
        "sourcePath" to ValueSchema.Object.Field(string),
        "startLine" to ValueSchema.Object.Field(ValueSchema.Integer),
        "endLine" to ValueSchema.Object.Field(ValueSchema.Integer),
    ))
    private val entity = ValueSchema.Object(mapOf(
        "id" to ValueSchema.Object.Field(string),
        "kind" to ValueSchema.Object.Field(string),
        "name" to ValueSchema.Object.Field(string),
        "evidence" to ValueSchema.Object.Field(ValueSchema.Array(evidence)),
    ))
    val modelSchema = ValueSchema.Object(mapOf(
        "repository" to ValueSchema.Object.Field(string),
        "commit" to ValueSchema.Object.Field(string),
        "summary" to ValueSchema.Object.Field(string),
        "entities" to ValueSchema.Object.Field(ValueSchema.Array(entity)),
    ))

    val repositoryInputSchema = ValueSchema.Object(mapOf(
        "repository" to ValueSchema.Object.Field(string),
        "commit" to ValueSchema.Object.Field(string),
        "dependencies" to ValueSchema.Object.Field(ValueSchema.Array(string)),
    ))
    val repositoryInventorySchema = ValueSchema.Object(mapOf(
        "repositories" to ValueSchema.Object.Field(ValueSchema.Array(repositoryInputSchema)),
    ))
    /**
     * The provider-facing event envelope deliberately preserves delivery id as
     * audit data only.  The runtime treats every accepted emission as new work.
     */
    val commitEventSchema = ValueSchema.Object(mapOf(
        "repository" to ValueSchema.Object.Field(string),
        "branch" to ValueSchema.Object.Field(string),
        "deliveryId" to ValueSchema.Object.Field(string),
        "commit" to ValueSchema.Object.Field(string),
    ))
    val dependencyEvidenceSchema = ValueSchema.Object(mapOf(
        "sourcePath" to ValueSchema.Object.Field(string),
        "startLine" to ValueSchema.Object.Field(ValueSchema.Integer),
        "endLine" to ValueSchema.Object.Field(ValueSchema.Integer),
        "relation" to ValueSchema.Object.Field(string),
    ))
    val dependencyEdgeSchema = ValueSchema.Object(mapOf(
        "from" to ValueSchema.Object.Field(string),
        "to" to ValueSchema.Object.Field(string),
        "sourceModelRevision" to ValueSchema.Object.Field(string),
        "targetModelRevision" to ValueSchema.Object.Field(string),
        "evidence" to ValueSchema.Object.Field(ValueSchema.Array(dependencyEvidenceSchema)),
    ))
    val repositoryModelSchema = ValueSchema.Object(mapOf(
        "repository" to ValueSchema.Object.Field(string),
        "commit" to ValueSchema.Object.Field(string),
        "revision" to ValueSchema.Object.Field(string),
        "summary" to ValueSchema.Object.Field(string),
        "dependencies" to ValueSchema.Object.Field(ValueSchema.Array(string)),
        "entities" to ValueSchema.Object.Field(ValueSchema.Array(entity)),
    ))
    private val repositoryView = ValueSchema.Object(mapOf(
        "repository" to ValueSchema.Object.Field(string),
        "dependencies" to ValueSchema.Object.Field(ValueSchema.Array(dependencyEdgeSchema)),
    ))
    val topologySchema = ValueSchema.Object(mapOf(
        "models" to ValueSchema.Object.Field(ValueSchema.Array(repositoryModelSchema)),
        "edges" to ValueSchema.Object.Field(ValueSchema.Array(dependencyEdgeSchema)),
        "outgoing" to ValueSchema.Object.Field(ValueSchema.Array(repositoryView)),
        "incoming" to ValueSchema.Object.Field(ValueSchema.Array(repositoryView)),
        "modelRevisions" to ValueSchema.Object.Field(ValueSchema.Array(string)),
        "gatheredModelRevisions" to ValueSchema.Object.Field(ValueSchema.Array(string)),
    ))
    val resolvedTopologySchema = topologySchema
    val systemArchitectureSchema = ValueSchema.Object(mapOf(
        "repositories" to ValueSchema.Object.Field(ValueSchema.Array(string)),
        "models" to ValueSchema.Object.Field(ValueSchema.Array(repositoryModelSchema)),
        "edges" to ValueSchema.Object.Field(ValueSchema.Array(dependencyEdgeSchema)),
        "outgoing" to ValueSchema.Object.Field(ValueSchema.Array(repositoryView)),
        "incoming" to ValueSchema.Object.Field(ValueSchema.Array(repositoryView)),
        "modelRevisions" to ValueSchema.Object.Field(ValueSchema.Array(string)),
        "gatheredModelRevisions" to ValueSchema.Object.Field(ValueSchema.Array(string)),
        "summary" to ValueSchema.Object.Field(string),
    ))
    val publicationSchema = ValueSchema.Object(mapOf(
        "architecture" to ValueSchema.Object.Field(systemArchitectureSchema),
        "externalWrites" to ValueSchema.Object.Field(ValueSchema.Integer),
    ))

    fun registry(): ProviderRegistry = ProviderRegistry().also { registry ->
        val modelAttempts = ConcurrentHashMap<String, Int>()
        val publication = FakeArchitecturePublication()
        registry.register(
            ProviderDescriptor(COMMIT_EVENTS, 1, ValueSchema.Any, commitEventSchema, effectClass = EffectClass.READ),
        ) {
            // The host injects deterministic fixture events through its durable
            // open-provider handle. No network service or process-local queue is involved.
            listOf(ProviderLifecycleMessage.Open)
        }
        registry.register(
            ProviderDescriptor(CONTINUOUS_INVENTORY, 1, commitEventSchema, repositoryInventorySchema, effectClass = EffectClass.READ),
        ) { request ->
            val event = request.input as Value.ObjectValue
            val branch = event.string("branch")
            val inventory = if (branch == "main") continuousInventory(event.string("commit")) else emptyList()
            listOf(
                ProviderLifecycleMessage.Emission(
                    Value.ObjectValue(mapOf("repositories" to Value.ArrayValue(inventory))),
                    EmissionId("continuous-inventory-${request.invocationId.value}"), request.invocationId, request.attemptId,
                ),
                ProviderLifecycleMessage.Completed,
            )
        }
        registry.register(
            ProviderDescriptor(REPOSITORY_PROJECTION, 1, commitEventSchema, commitEventSchema, effectClass = EffectClass.PURE),
        ) { request ->
            val event = request.input as Value.ObjectValue
            listOf(
                ProviderLifecycleMessage.Emission(
                    event,
                    EmissionId("repository-projection-${request.invocationId.value}"), request.invocationId, request.attemptId,
                    event.string("repository"),
                ),
                ProviderLifecycleMessage.Completed,
            )
        }
        registry.register(
            ProviderDescriptor(INVENTORY, 1, repositoryInventorySchema, repositoryInventorySchema, effectClass = EffectClass.READ),
        ) { request ->
            val input = request.input as Value.ObjectValue
            val repositories = (input.fields.getValue("repositories") as Value.ArrayValue).values
                .map { it as Value.ObjectValue }
                .sortedBy { (it.fields.getValue("repository") as Value.StringValue).value }
            listOf(
                ProviderLifecycleMessage.Emission(
                    Value.ObjectValue(mapOf("repositories" to Value.ArrayValue(repositories))),
                    EmissionId("inventory-${request.invocationId.value}"), request.invocationId, request.attemptId,
                ),
                ProviderLifecycleMessage.Completed,
            )
        }
        registry.register(
            ProviderDescriptor(REPOSITORY_MODEL, 1, repositoryInputSchema, repositoryModelSchema, effectClass = EffectClass.AGENTIC,
                idempotency = IdempotencyContract(ReconciliationMode.IDEMPOTENT_BY_INVOCATION),
                agentic = deterministicAgenticDescriptor("repository-model-v1")),
        ) { request ->
            val input = request.input as Value.ObjectValue
            val repository = input.string("repository")
            val commit = input.string("commit")
            val attempt = modelAttempts.merge(request.invocationId.value, 1, Int::plus)!!
            if (commit.startsWith("release-fault-") && attempt == 1) return@register listOf(
                ProviderLifecycleMessage.Failed(Value.ObjectValue(mapOf("class" to Value.StringValue("transient")))),
            )
            val dependencies = input.strings("dependencies").sorted()
            val files = fixture(repository, commit)
            val entities = files.map { file ->
                val path = (file as Value.ObjectValue).string("path")
                val lines = (file.fields.getValue("lines") as Value.IntegerValue).value
                Value.ObjectValue(mapOf(
                    "id" to Value.StringValue("file:$repository:$path"),
                    "kind" to Value.StringValue("source-file"),
                    "name" to Value.StringValue(path),
                    "evidence" to Value.ArrayValue(listOf(Value.ObjectValue(mapOf(
                        "sourcePath" to Value.StringValue(path),
                        "startLine" to Value.IntegerValue(BigInteger.ONE),
                        "endLine" to Value.IntegerValue(lines),
                    )))),
                ))
            }
            val revision = "$repository@$commit"
            val model = Value.ObjectValue(mapOf(
                "repository" to Value.StringValue(repository),
                "commit" to Value.StringValue(commit),
                "revision" to Value.StringValue(revision),
                "summary" to Value.StringValue("${entities.size} source files in $repository at $commit"),
                "dependencies" to Value.ArrayValue(dependencies.map(Value::StringValue)),
                "entities" to Value.ArrayValue(entities),
            ))
            listOf(ProviderLifecycleMessage.Emission(model, EmissionId("repository-model-${request.invocationId.value}"), request.invocationId, request.attemptId), ProviderLifecycleMessage.Completed)
        }
        registry.register(
            ProviderDescriptor(TOPOLOGY, 1, ValueSchema.Array(repositoryModelSchema), topologySchema, effectClass = EffectClass.PURE),
        ) { request ->
            val models = (request.input as Value.ArrayValue).values.map { it as Value.ObjectValue }
                .sortedBy { it.string("repository") }
            val byRepository = models.associateBy { it.string("repository") }
            fun edge(from: Value.ObjectValue, to: String): Value.ObjectValue {
                val target = byRepository[to]
                val targetRevision = target?.string("revision") ?: "$to@missing"
                return Value.ObjectValue(mapOf(
                    "from" to Value.StringValue(from.string("repository")),
                    "to" to Value.StringValue(to),
                    "sourceModelRevision" to Value.StringValue(from.string("revision")),
                    "targetModelRevision" to Value.StringValue(targetRevision),
                    "evidence" to Value.ArrayValue(listOf(Value.ObjectValue(mapOf(
                        "sourcePath" to Value.StringValue("repository-manifest.yaml"),
                        "startLine" to Value.IntegerValue(BigInteger.ONE),
                        "endLine" to Value.IntegerValue(BigInteger.ONE),
                        "relation" to Value.StringValue("declared dependency"),
                    )))),
                ))
            }
            val edges = models.flatMap { model -> model.strings("dependencies").sorted().map { edge(model, it) } }
                .sortedWith(compareBy({ it.string("from") }, { it.string("to") }))
            fun view(repository: String, values: List<Value.ObjectValue>) = Value.ObjectValue(mapOf(
                "repository" to Value.StringValue(repository),
                "dependencies" to Value.ArrayValue(values),
            ))
            val outgoing = models.map { model -> view(model.string("repository"), edges.filter { it.string("from") == model.string("repository") }) }
            val incoming = models.map { model -> view(model.string("repository"), edges.filter { it.string("to") == model.string("repository") }.sortedBy { it.string("from") }) }
            val revisions = models.map { it.string("revision") }.sorted()
            val topology = Value.ObjectValue(mapOf(
                "models" to Value.ArrayValue(models),
                "edges" to Value.ArrayValue(edges),
                "outgoing" to Value.ArrayValue(outgoing),
                "incoming" to Value.ArrayValue(incoming),
                "modelRevisions" to Value.ArrayValue(revisions.map(Value::StringValue)),
                "gatheredModelRevisions" to Value.ArrayValue(revisions.map(Value::StringValue)),
            ))
            listOf(ProviderLifecycleMessage.Emission(topology, EmissionId("topology-${request.invocationId.value}"), request.invocationId, request.attemptId), ProviderLifecycleMessage.Completed)
        }
        registry.register(
            ProviderDescriptor(SYSTEM_MODEL, 1, topologySchema, systemArchitectureSchema, effectClass = EffectClass.PURE),
        ) { request ->
            val topology = request.input as Value.ObjectValue
            val models = topology.objects("models").sortedBy { it.string("repository") }
            val revisions = topology.strings("gatheredModelRevisions").sorted()
            val architecture = Value.ObjectValue(mapOf(
                "repositories" to Value.ArrayValue(models.map { Value.StringValue(it.string("repository")) }),
                "models" to Value.ArrayValue(models),
                "edges" to Value.ArrayValue(topology.objects("edges")),
                "outgoing" to Value.ArrayValue(topology.objects("outgoing")),
                "incoming" to Value.ArrayValue(topology.objects("incoming")),
                "modelRevisions" to Value.ArrayValue(revisions.map(Value::StringValue)),
                "gatheredModelRevisions" to Value.ArrayValue(revisions.map(Value::StringValue)),
                "summary" to Value.StringValue("${models.size} repository models and ${topology.objects("edges").size} dependency edges"),
            ))
            listOf(ProviderLifecycleMessage.Emission(architecture, EmissionId("system-${request.invocationId.value}"), request.invocationId, request.attemptId), ProviderLifecycleMessage.Completed)
        }
        registry.register(
            ProviderDescriptor(
                ARCHITECTURE_PUBLICATION, 1, systemArchitectureSchema, publicationSchema,
                effectClass = EffectClass.EFFECT,
                idempotency = IdempotencyContract(ReconciliationMode.QUERY_BY_INVOCATION),
            ),
            publication,
        )
        registry.register(
            ProviderDescriptor(READER, 1, readerInput, snapshot, effectClass = EffectClass.READ),
        ) { request ->
            val input = request.input as Value.ObjectValue
            val repository = (input.fields.getValue("repository") as Value.StringValue).value
            val commit = (input.fields.getValue("commit") as Value.StringValue).value
            val files = fixture(repository, commit)
            listOf(
                ProviderLifecycleMessage.Emission(Value.ObjectValue(mapOf(
                    "repository" to Value.StringValue(repository),
                    "commit" to Value.StringValue(commit),
                    "files" to Value.ArrayValue(files),
                )), EmissionId("reader-${repository}-${commit}"), request.invocationId, request.attemptId),
                ProviderLifecycleMessage.Completed,
            )
        }
        registry.register(
            ProviderDescriptor(BUILDER, 1, snapshot, modelSchema, effectClass = EffectClass.AGENTIC,
                idempotency = IdempotencyContract(ReconciliationMode.HUMAN_INTERVENTION),
                agentic = deterministicAgenticDescriptor("builder-v1")),
        ) { request ->
            val input = request.input as Value.ObjectValue
            val repository = (input.fields.getValue("repository") as Value.StringValue).value
            val commit = (input.fields.getValue("commit") as Value.StringValue).value
            val files = input.fields.getValue("files") as Value.ArrayValue
            val entities = files.values.map { file ->
                val fields = (file as Value.ObjectValue).fields
                val path = (fields.getValue("path") as Value.StringValue).value
                val lines = (fields.getValue("lines") as Value.IntegerValue).value
                Value.ObjectValue(mapOf(
                    "id" to Value.StringValue("file:${repository}:${path}"),
                    "kind" to Value.StringValue("source-file"),
                    "name" to Value.StringValue(path),
                    "evidence" to Value.ArrayValue(listOf(Value.ObjectValue(mapOf(
                        "sourcePath" to Value.StringValue(path),
                        "startLine" to Value.IntegerValue(BigInteger.ONE),
                        "endLine" to Value.IntegerValue(lines),
                    )))),
                ))
            }
            val model = Value.ObjectValue(mapOf(
                "repository" to Value.StringValue(repository),
                "commit" to Value.StringValue(commit),
                "summary" to Value.StringValue("${entities.size} source files in $repository at $commit"),
                "entities" to Value.ArrayValue(entities),
            ))
            listOf(ProviderLifecycleMessage.Emission(model, EmissionId("model-${repository}-${commit}"), request.invocationId, request.attemptId), ProviderLifecycleMessage.Completed)
        }
    }

    private val readerInput = ValueSchema.Object(mapOf(
        "repository" to ValueSchema.Object.Field(string),
        "commit" to ValueSchema.Object.Field(string),
    ))

    private fun fixture(repository: String, commit: String): List<Value> {
        val suffix = if (commit.endsWith("2")) "src/changed.kt" else "src/Main.kt"
        return listOf(
            Value.ObjectValue(mapOf("path" to Value.StringValue(suffix), "language" to Value.StringValue("kotlin"), "lines" to Value.IntegerValue(if (commit.endsWith("2")) 24 else 18))),
            Value.ObjectValue(mapOf("path" to Value.StringValue("README.md"), "language" to Value.StringValue("markdown"), "lines" to Value.IntegerValue(10))),
        )
    }

    /** Authoritative finite fixtures for the v0.4 demonstration. */
    private fun continuousInventory(triggerCommit: String): List<Value> {
        val repositories = if (triggerCommit.endsWith("b") || triggerCommit.endsWith("-b")) {
            listOf(
                Triple("app/service", "svc-b", listOf("platform/base")),
                Triple("platform/base", "base-a", emptyList()),
                Triple("new/repository", "new-b", emptyList()),
            )
        } else {
            listOf(
                Triple("app/service", "svc-a", listOf("lib/core")),
                Triple("lib/core", "core-a", listOf("platform/base")),
                Triple("platform/base", "base-a", emptyList()),
            )
        }
        return repositories.mapIndexed { index, (repository, commit, dependencies) ->
            Value.ObjectValue(mapOf(
                "repository" to Value.StringValue(repository),
                "commit" to Value.StringValue(if (triggerCommit.startsWith("release-fault-") && index == 0) triggerCommit else commit),
                "dependencies" to Value.ArrayValue(dependencies.map(Value::StringValue)),
            ))
        }
    }

    /** Lost-reply fixture: one external write, then reconciliation returns that recorded result. */
    private class FakeArchitecturePublication : ReconciliationProviderImplementation {
        private val applied = ConcurrentHashMap<String, Value>()

        override fun invoke(request: io.workflow.provider.ProviderInvocationRequest): Iterable<ProviderLifecycleMessage> {
            check(!applied.containsKey(request.invocationId.value)) { "duplicate fake publication" }
            val result = Value.ObjectValue(mapOf(
                "architecture" to request.input,
                "externalWrites" to Value.IntegerValue(BigInteger.ONE),
            ))
            applied[request.invocationId.value] = result
            throw IllegalStateException("simulated lost publication reply")
        }

        override fun reconcile(request: ReconciliationRequest): ReconciliationResult = applied[request.invocationId.value]?.let {
            ReconciliationResult(disposition = ReconciliationDisposition.DEFINITELY_APPLIED, recordedResult = it)
        } ?: ReconciliationResult(disposition = ReconciliationDisposition.DEFINITELY_NOT_APPLIED)
    }

    private fun Value.ObjectValue.string(name: String): String =
        (fields.getValue(name) as Value.StringValue).value

    private fun Value.ObjectValue.strings(name: String): List<String> =
        (fields.getValue(name) as Value.ArrayValue).values.map { (it as Value.StringValue).value }

    private fun Value.ObjectValue.objects(name: String): List<Value.ObjectValue> =
        (fields.getValue(name) as Value.ArrayValue).values.map { it as Value.ObjectValue }
}
