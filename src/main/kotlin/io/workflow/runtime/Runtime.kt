package io.workflow.runtime

import io.workflow.compiler.CompiledRegister
import io.workflow.compiler.Expression
import io.workflow.compiler.PathStep
import io.workflow.compiler.Requirement
import io.workflow.compiler.WorkflowCompiler
import io.workflow.compiler.WorkflowIrDocument
import io.workflow.core.AssignmentId
import io.workflow.core.AssignmentMutation
import io.workflow.core.ActivationId
import io.workflow.core.ActivationIntent
import io.workflow.core.ActivationIntentId
import io.workflow.core.AttemptId
import io.workflow.core.Clock
import io.workflow.core.ContextId
import io.workflow.core.DeterministicIdSource
import io.workflow.core.EmissionId
import io.workflow.core.ExecutionId
import io.workflow.core.IdSource
import io.workflow.core.InvocationId
import io.workflow.core.JournalBatch
import io.workflow.core.JournalBatchId
import io.workflow.core.ProducerId
import io.workflow.core.RegisterId
import io.workflow.core.SystemClock
import io.workflow.core.Value
import io.workflow.core.WorkflowId
import io.workflow.core.WorkflowVersionId
import io.workflow.core.CanonicalValueJson
import io.workflow.core.validate
import java.math.BigDecimal
import java.math.BigInteger
import java.security.MessageDigest
import java.time.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.Json

/** The key of one materialized register in one workflow execution. */
data class RegisterKey(
    val executionId: ExecutionId,
    val contextId: ContextId,
    val registerId: RegisterId,
)

/** Unvalidated input to the commit boundary; cardinality is checked there. */
data class JournalBatchProposal(
    val journalBatchId: JournalBatchId,
    val mutations: List<AssignmentMutation>,
    val committedAt: Instant,
    val formatVersion: Int = 1,
)

/** A committed activation lifecycle record retained for inspection and replay. */
data class ActivationRecord(
    val activationId: ActivationId,
    val intentId: ActivationIntentId,
    val producerId: ProducerId,
    val contextId: ContextId,
    val dependencyRevisions: Map<RegisterId, AssignmentId>,
    val status: Status,
    val startedAt: Instant,
    val completedAt: Instant?,
    val failure: String? = null,
) {
    enum class Status { COMPLETED, FAILED }
}

/** The result made available by the output publication boundary. */
data class PublishedOutput(val value: Value, val revision: Long)

/** Provider invocation is deliberately only a seam in this milestone. */
data class ProviderInvocation(
    val workflowId: WorkflowId,
    val workflowVersionId: WorkflowVersionId,
    val executionId: ExecutionId,
    val contextId: ContextId,
    val producerId: ProducerId,
    val invocationId: InvocationId,
    val attemptId: AttemptId,
    val input: Value,
)

data class ProviderEmission(
    val value: Value,
    val emissionId: EmissionId,
    val correlationId: String? = null,
)

fun interface ProviderInvoker {
    fun invoke(invocation: ProviderInvocation): Iterable<ProviderEmission>
}

interface OutputPublisher {
    fun publish(outputs: Map<String, PublishedOutput>)
}

/** The journal/current-view boundary used by the in-memory backend. */
interface JournalBatchCommitter {
    fun commit(batch: JournalBatch, activationIntents: Collection<ActivationIntent> = emptyList()): JournalBatch
}

interface CurrentViewProjection {
    fun current(key: RegisterKey): AssignmentMutation?
    fun currentFor(executionId: ExecutionId, contextId: ContextId): Map<RegisterId, AssignmentMutation>
    fun allCurrent(): Map<RegisterKey, AssignmentMutation>
}

/**
 * An ordered in-memory journal. The lock covers batch validation, revision
 * allocation, journal append, current-view projection, and intent persistence.
 */
class InMemoryJournalStore(
    private val clock: Clock = SystemClock,
    private val idSource: IdSource = DeterministicIdSource("journal-"),
) : JournalBatchCommitter, CurrentViewProjection {
    private val lock = Any()
    private val journalBatches = mutableListOf<JournalBatch>()
    private val currentAssignments = linkedMapOf<RegisterKey, AssignmentMutation>()
    private val activationIntents = linkedMapOf<ActivationIntentId, ActivationIntent>()
    private val completedIntentIds = linkedSetOf<ActivationIntentId>()
    private val activationRecords = mutableListOf<ActivationRecord>()

    override fun commit(batch: JournalBatch, activationIntents: Collection<ActivationIntent>): JournalBatch =
        synchronized(lock) {
            require(batch.mutations.size == 1) { "v1 journal batch must contain exactly one assignment mutation" }
            val mutation = batch.assignment
            val key = RegisterKey(mutation.executionId, mutation.contextId, mutation.registerId)
            val nextRevision = (currentAssignments[key]?.revision ?: 0L) + 1L
            val committedMutation = mutation.copy(revision = nextRevision)
            val committedBatch = batch.copy(mutations = listOf(committedMutation))

            val duplicateBatch = journalBatches.firstOrNull { it.journalBatchId == committedBatch.journalBatchId }
            if (duplicateBatch != null) {
                require(duplicateBatch == committedBatch) { "journal batch id is already committed with different contents" }
                return@synchronized duplicateBatch
            }
            activationIntents.forEach { intent ->
                val duplicate = this.activationIntents[intent.id]
                require(duplicate == null || duplicate == intent) {
                    "activation intent id is already persisted with different contents"
                }
            }

            // These operations are intentionally one critical section: no observer
            // can see an assignment without its downstream durable intents.
            journalBatches += committedBatch
            currentAssignments[key] = committedMutation
            activationIntents.forEach { intent -> this.activationIntents.putIfAbsent(intent.id, intent) }
            committedBatch
        }

    /**
     * Commit an unvalidated proposal. Keeping this overload separate makes the
     * v1 cardinality check live at the storage boundary even for callers that
     * do not construct the guarded core JournalBatch first.
     */
    fun commit(proposal: JournalBatchProposal, activationIntents: Collection<ActivationIntent> = emptyList()): JournalBatch {
        require(proposal.mutations.size == 1) { "v1 journal batch must contain exactly one assignment mutation" }
        require(proposal.mutations.single().mutationOrdinal == 0) {
            "the only v1 mutation must have ordinal zero"
        }
        return commit(
            JournalBatch(
                journalBatchId = proposal.journalBatchId,
                mutations = proposal.mutations,
                committedAt = proposal.committedAt,
                formatVersion = proposal.formatVersion,
            ),
            activationIntents,
        )
    }

    /** Convenience boundary for tests that construct a mutation directly. */
    fun commit(mutation: AssignmentMutation, activationIntents: Collection<ActivationIntent> = emptyList()): JournalBatch {
        val batch = JournalBatch(
            journalBatchId = JournalBatchId("batch-${idSource.nextId()}"),
            mutations = listOf(mutation),
            committedAt = clock.now(),
        )
        return commit(batch, activationIntents)
    }

    /** Persist startup work, which has no assignment mutation as its cause. */
    fun persistActivationIntents(intents: Collection<ActivationIntent>) = synchronized(lock) {
        intents.forEach { intent ->
            val duplicate = activationIntents[intent.id]
            require(duplicate == null || duplicate == intent) {
                "activation intent id is already persisted with different contents"
            }
            activationIntents.putIfAbsent(intent.id, intent)
        }
    }

    override fun current(key: RegisterKey): AssignmentMutation? = synchronized(lock) { currentAssignments[key] }

    override fun currentFor(executionId: ExecutionId, contextId: ContextId): Map<RegisterId, AssignmentMutation> =
        synchronized(lock) {
            currentAssignments.filterKeys { it.executionId == executionId && it.contextId == contextId }
                .mapKeys { it.key.registerId }
        }

    override fun allCurrent(): Map<RegisterKey, AssignmentMutation> = synchronized(lock) { currentAssignments.toMap() }

    fun batches(): List<JournalBatch> = synchronized(lock) { journalBatches.toList() }
    fun assignments(): List<AssignmentMutation> = synchronized(lock) { journalBatches.map { it.assignment } }
    fun activationIntents(): List<ActivationIntent> = synchronized(lock) { activationIntents.values.toList() }
    fun activations(): List<ActivationRecord> = synchronized(lock) { activationRecords.toList() }

    /** Claiming does not delete or acknowledge an intent; only completion does. */
    fun claimNextActivation(): ActivationIntent? = synchronized(lock) {
        activationIntents.values.firstOrNull { it.id !in completedIntentIds }
    }

    fun completeActivation(intentId: ActivationIntentId) = synchronized(lock) {
        require(intentId in activationIntents) { "cannot complete an unknown activation intent" }
        completedIntentIds += intentId
    }

    fun recordActivation(record: ActivationRecord) = synchronized(lock) {
        activationRecords += record
    }

    fun isCompleted(intentId: ActivationIntentId): Boolean = synchronized(lock) { intentId in completedIntentIds }

    /** Rebuild the projection from authoritative ordered assignment records. */
    fun rebuildCurrentView(): Map<RegisterKey, AssignmentMutation> =
        CurrentViews.rebuild(batches()).also { rebuilt -> synchronized(lock) {
            currentAssignments.clear()
            currentAssignments.putAll(rebuilt)
        } }
}

object CurrentViews {
    fun rebuild(batches: Iterable<JournalBatch>): Map<RegisterKey, AssignmentMutation> {
        val result = linkedMapOf<RegisterKey, AssignmentMutation>()
        batches.forEach { batch ->
            require(batch.mutations.size == 1) { "v1 journal batch must contain exactly one assignment mutation" }
            val assignment = batch.assignment
            val key = RegisterKey(assignment.executionId, assignment.contextId, assignment.registerId)
            val previous = result[key]
            require(previous == null || assignment.revision > previous.revision) {
                "assignment revisions must increase for each register"
            }
            result[key] = assignment
        }
        return result
    }
}

interface ActivationPlanner {
    fun initial(
        workflow: WorkflowIrDocument,
        executionId: ExecutionId,
        contextId: ContextId,
        journalBatchId: JournalBatchId,
        createdAt: Instant,
    ): List<ActivationIntent>

    fun afterAssignment(
        workflow: WorkflowIrDocument,
        executionId: ExecutionId,
        contextId: ContextId,
        assignment: AssignmentMutation,
        current: Map<RegisterKey, AssignmentMutation>,
        journalBatchId: JournalBatchId,
        createdAt: Instant,
    ): List<ActivationIntent>
}

/** Default planner for the expression-only graph emitted by WFL-102. */
class ExpressionActivationPlanner : ActivationPlanner {
    override fun initial(
        workflow: WorkflowIrDocument,
        executionId: ExecutionId,
        contextId: ContextId,
        journalBatchId: JournalBatchId,
        createdAt: Instant,
    ): List<ActivationIntent> = workflow.registers
        .filter { requiredContextDependencies(it.producer).isEmpty() }
        .map { intentFor(workflow, executionId, contextId, it, emptyMap(), journalBatchId, createdAt) }

    override fun afterAssignment(
        workflow: WorkflowIrDocument,
        executionId: ExecutionId,
        contextId: ContextId,
        assignment: AssignmentMutation,
        current: Map<RegisterKey, AssignmentMutation>,
        journalBatchId: JournalBatchId,
        createdAt: Instant,
    ): List<ActivationIntent> {
        val assignedRegister = workflow.registers.firstOrNull { it.registerId == assignment.registerId }?.name
            ?: return emptyList()
        val view = current + (RegisterKey(executionId, contextId, assignment.registerId) to assignment)
        return workflow.registers
            .filter { assignedRegister in it.dependencies }
            .mapNotNull { register ->
                val required = requiredContextDependencies(register.producer)
                if (required.any { name -> currentRegister(view, executionId, contextId, workflow, name) == null }) null
                else intentFor(workflow, executionId, contextId, register, view, journalBatchId, createdAt)
            }
    }

    private fun intentFor(
        workflow: WorkflowIrDocument,
        executionId: ExecutionId,
        contextId: ContextId,
        register: CompiledRegister,
        current: Map<RegisterKey, AssignmentMutation>,
        journalBatchId: JournalBatchId,
        createdAt: Instant,
    ): ActivationIntent {
        val vector = register.dependencies.associate { name ->
            val dependency = workflow.registers.first { it.name == name }
            val assignment = currentRegister(current, executionId, contextId, workflow, name)
            dependency.registerId to (assignment?.assignmentId ?: AssignmentId(ABSENT_REVISION))
        }.toSortedMap(compareBy { it.value })
        val identity = stableIdentity(executionId, contextId, register.producerId, vector)
        return ActivationIntent(
            id = ActivationIntentId("intent-$identity"),
            activationId = ActivationId("activation-$identity"),
            producerId = register.producerId,
            contextId = contextId,
            journalBatchId = journalBatchId,
            createdAt = createdAt,
            dependencyRevisions = vector,
        )
    }

    private fun currentRegister(
        current: Map<RegisterKey, AssignmentMutation>,
        executionId: ExecutionId,
        contextId: ContextId,
        workflow: WorkflowIrDocument,
        name: String,
    ): AssignmentMutation? {
        val register = workflow.registers.firstOrNull { it.name == name } ?: return null
        return current[RegisterKey(executionId, contextId, register.registerId)]
    }

    companion object {
        const val ABSENT_REVISION = "<absent>"

        fun requiredContextDependencies(expression: Expression): Set<String> = when (expression) {
            is Expression.Ref -> if (expression.requirement == Requirement.REQUIRED && expression.root !in LEXICAL_ROOTS) setOf(expression.root) else emptySet()
            is Expression.Literal -> emptySet()
            is Expression.ObjectValue -> expression.fields.values.flatMapTo(linkedSetOf(), ::requiredContextDependencies)
            is Expression.ArrayValue -> expression.items.flatMapTo(linkedSetOf(), ::requiredContextDependencies)
            is Expression.Concat -> expression.parts.flatMapTo(linkedSetOf(), ::requiredContextDependencies)
            is Expression.Equals -> requiredContextDependencies(expression.left) + requiredContextDependencies(expression.right)
            is Expression.Present -> requiredContextDependencies(expression.value)
            is Expression.And -> expression.predicates.flatMapTo(linkedSetOf(), ::requiredContextDependencies)
            is Expression.Or -> expression.predicates.flatMapTo(linkedSetOf(), ::requiredContextDependencies)
            is Expression.Not -> requiredContextDependencies(expression.predicate)
        }

        private val LEXICAL_ROOTS = setOf("parameters", "item", "key", "match")

        private fun stableIdentity(
            executionId: ExecutionId,
            contextId: ContextId,
            producerId: ProducerId,
            vector: Map<RegisterId, AssignmentId>,
        ): String {
            val source = buildString {
                append(executionId.value).append('|')
                append(contextId.value).append('|').append(producerId.value)
                vector.toSortedMap(compareBy { it.value }).forEach { (register, assignment) ->
                    append('|').append(register.value).append('=').append(assignment.value)
                }
            }
            return MessageDigest.getInstance("SHA-256")
                .digest(source.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        }
    }
}

data class WorkflowRunResult(
    val executionId: ExecutionId,
    val outputs: Map<String, PublishedOutput>,
    val journal: InMemoryJournalStore,
    val failures: List<String> = emptyList(),
) {
    val isSuccessful: Boolean get() = failures.isEmpty()

    fun outputsJson(): String {
        val outputObject = outputs.toSortedMap().mapValues { (_, output) ->
            JsonObject(linkedMapOf(
                "value" to Json.parseToJsonElement(CanonicalValueJson.encode(output.value)),
                "revision" to JsonPrimitive(output.revision),
            ))
        }
        return JsonObject(mapOf("outputs" to JsonObject(outputObject))).toString()
    }

    fun inspectionJson(): String = WorkflowInspection.toJson(this)
}

class WorkflowExecutionException(message: String) : IllegalArgumentException(message)

private class BindingFailure(message: String) : IllegalArgumentException(message)

private sealed interface Evaluated {
    data class ValueResult(val value: Value) : Evaluated
    data class OptionalResult(val present: Boolean, val value: Value? = null) : Evaluated
}

/** Executes expression IR against the in-memory journal until no intent is runnable. */
class InMemoryWorkflowRunner(
    private val compiler: WorkflowCompiler = WorkflowCompiler(),
    private val clock: Clock = SystemClock,
    private val idSource: IdSource = DeterministicIdSource("run-"),
    val journal: InMemoryJournalStore = InMemoryJournalStore(clock, idSource),
    private val planner: ActivationPlanner = ExpressionActivationPlanner(),
) {
    fun run(
        yamlText: String,
        parameters: Map<String, Value> = emptyMap(),
        executionId: ExecutionId = nextExecutionId(),
        beforeActivation: (ActivationIntent) -> Unit = {},
    ): WorkflowRunResult {
        val compilation = compiler.compile(yamlText)
        if (!compilation.isValid) throw WorkflowExecutionException(compilation.diagnostics.joinToString("\n"))
        return execute(compilation.ir!!, parameters, executionId, beforeActivation)
    }

    fun execute(
        workflow: WorkflowIrDocument,
        parameters: Map<String, Value> = emptyMap(),
        executionId: ExecutionId = nextExecutionId(),
        beforeActivation: (ActivationIntent) -> Unit = {},
    ): WorkflowRunResult {
        validateParameters(workflow, parameters)
        val contextId = ContextId(ANONYMOUS_CONTEXT)
        journal.persistActivationIntents(
            planner.initial(workflow, executionId, contextId, JournalBatchId(STARTUP_BATCH), clock.now()),
        )

        val failures = mutableListOf<String>()
        while (true) {
            val intent = journal.claimNextActivation() ?: break
            val register = workflow.registers.firstOrNull { it.producerId == intent.producerId }
                ?: throw WorkflowExecutionException("activation refers to unknown producer ${intent.producerId.value}")
            val started = clock.now()
            // The hook is deliberately before any acknowledgement. If it throws,
            // the persisted intent remains available for a later worker.
            beforeActivation(intent)
            try {
                val context = journal.currentFor(executionId, intent.contextId)
                val evaluated = evaluate(register.producer, workflow, parameters, context)
                val value = materialize(evaluated)
                if (evaluated !is Evaluated.OptionalResult) {
                    val validation = register.schema.validate(value)
                    if (!validation.isValid) throw BindingFailure(validation.errors.joinToString { "${it.path}: ${it.message}" })
                }
                val assignmentId = AssignmentId("assignment-${idSource.nextId()}")
                val batchId = JournalBatchId("batch-${idSource.nextId()}")
                val candidate = AssignmentMutation(
                    assignmentId = assignmentId,
                    workflowId = WorkflowId(workflow.workflowId),
                    workflowVersionId = workflow.workflowVersionId,
                    executionId = executionId,
                    contextId = intent.contextId,
                    registerId = register.registerId,
                    value = value,
                    producerId = register.producerId,
                    activationId = intent.activationId,
                    dependencyRevisions = intent.dependencyRevisions,
                    occurredAt = clock.now(),
                    mutationOrdinal = 0,
                )
                val currentWithCandidate = journal.allCurrent() +
                    (RegisterKey(executionId, intent.contextId, register.registerId) to candidate)
                val downstream = planner.afterAssignment(
                    workflow,
                    executionId,
                    intent.contextId,
                    candidate,
                    currentWithCandidate,
                    batchId,
                    clock.now(),
                )
                journal.commit(
                    JournalBatch(batchId, listOf(candidate), clock.now()),
                    downstream,
                )
                journal.recordActivation(
                    ActivationRecord(
                        activationId = intent.activationId,
                        intentId = intent.id,
                        producerId = intent.producerId,
                        contextId = intent.contextId,
                        dependencyRevisions = intent.dependencyRevisions,
                        status = ActivationRecord.Status.COMPLETED,
                        startedAt = started,
                        completedAt = clock.now(),
                    ),
                )
                journal.completeActivation(intent.id)
            } catch (failure: BindingFailure) {
                journal.recordActivation(
                    ActivationRecord(
                        activationId = intent.activationId,
                        intentId = intent.id,
                        producerId = intent.producerId,
                        contextId = intent.contextId,
                        dependencyRevisions = intent.dependencyRevisions,
                        status = ActivationRecord.Status.FAILED,
                        startedAt = started,
                        completedAt = clock.now(),
                        failure = failure.message,
                    ),
                )
                journal.completeActivation(intent.id)
                failures += "${register.name}: ${failure.message}"
            }
        }

        val outputs = workflow.outputs.associateWith { outputName ->
            val register = workflow.registers.first { it.name == outputName }
            val assignment = journal.current(RegisterKey(executionId, contextId, register.registerId))
                ?: throw WorkflowExecutionException("output '$outputName' received no assignment")
            PublishedOutput(assignment.value, assignment.revision)
        }
        return WorkflowRunResult(executionId, outputs, journal, failures)
    }

    private fun validateParameters(workflow: WorkflowIrDocument, parameters: Map<String, Value>) {
        workflow.parameters.forEach { (name, schema) ->
            parameters[name]?.let { value ->
                val result = schema.validate(value)
                if (!result.isValid) throw WorkflowExecutionException(
                    result.errors.joinToString { "parameter '$name' ${it.path}: ${it.message}" },
                )
            }
        }
    }

    private fun evaluate(
        expression: Expression,
        workflow: WorkflowIrDocument,
        parameters: Map<String, Value>,
        context: Map<RegisterId, AssignmentMutation>,
    ): Evaluated = when (expression) {
        is Expression.Literal -> Evaluated.ValueResult(expression.value)
        is Expression.Ref -> {
            val root = when {
                expression.root == "parameters" -> Value.ObjectValue(parameters)
                expression.root == "item" || expression.root == "key" || expression.root == "match" -> Value.Null
                else -> {
                    val register = workflow.registers.firstOrNull { it.name == expression.root }
                        ?: throw BindingFailure("unknown reference root '${expression.root}'")
                    context[register.registerId]?.value
                        ?: if (expression.requirement == Requirement.OPTIONAL) return Evaluated.OptionalResult(false)
                        else throw BindingFailure("required reference '${expression.root}' is unassigned")
                }
            }
            val selected = selectPath(root, expression.path)
            if (expression.requirement == Requirement.OPTIONAL) Evaluated.OptionalResult(selected != null, selected)
            else Evaluated.ValueResult(selected ?: throw BindingFailure("required reference path is missing"))
        }
        is Expression.ObjectValue -> Evaluated.ValueResult(Value.ObjectValue(expression.fields.mapValues { materialize(evaluate(it.value, workflow, parameters, context)) }))
        is Expression.ArrayValue -> Evaluated.ValueResult(Value.ArrayValue(expression.items.map { materialize(evaluate(it, workflow, parameters, context)) }))
        is Expression.Concat -> Evaluated.ValueResult(Value.StringValue(expression.parts.joinToString("") {
            (materialize(evaluate(it, workflow, parameters, context)) as? Value.StringValue)?.value
                ?: throw BindingFailure("concat operand is not a string")
        }))
        is Expression.Equals -> Evaluated.ValueResult(
            Value.BooleanValue(equivalent(evaluate(expression.left, workflow, parameters, context), evaluate(expression.right, workflow, parameters, context))),
        )
        is Expression.Present -> {
            val value = evaluate(expression.value, workflow, parameters, context)
            Evaluated.ValueResult(Value.BooleanValue(value is Evaluated.OptionalResult && value.present))
        }
        is Expression.And -> Evaluated.ValueResult(Value.BooleanValue(expression.predicates.all { boolValue(evaluate(it, workflow, parameters, context)) }))
        is Expression.Or -> Evaluated.ValueResult(Value.BooleanValue(expression.predicates.any { boolValue(evaluate(it, workflow, parameters, context)) }))
        is Expression.Not -> Evaluated.ValueResult(Value.BooleanValue(!boolValue(evaluate(expression.predicate, workflow, parameters, context))))
    }

    private fun selectPath(value: Value, path: List<PathStep>): Value? {
        var current: Value? = value
        path.forEach { step ->
            current = when (step) {
                is PathStep.Field -> (current as? Value.ObjectValue)?.fields?.get(step.name)
                is PathStep.Index -> (current as? Value.ArrayValue)?.values?.getOrNull(step.index)
            }
        }
        return current
    }

    private fun materialize(value: Evaluated): Value = when (value) {
        is Evaluated.ValueResult -> value.value
        is Evaluated.OptionalResult -> if (value.present) {
            Value.TaggedValue("option.some", value.value ?: Value.Null)
        } else {
            Value.TaggedValue("option.none", Value.Null)
        }
    }

    private fun equivalent(left: Evaluated, right: Evaluated): Boolean = when {
        left is Evaluated.OptionalResult && right is Evaluated.OptionalResult -> left.present == right.present && (!left.present || left.value == right.value)
        left is Evaluated.OptionalResult && !left.present -> false
        right is Evaluated.OptionalResult && !right.present -> false
        else -> materialize(left) == materialize(right)
    }

    private fun boolValue(value: Evaluated): Boolean = (value as? Evaluated.ValueResult)?.value.let { it as? Value.BooleanValue }
        ?.value ?: throw BindingFailure("boolean expression required")

    private fun nextExecutionId(): ExecutionId = ExecutionId("execution-${idSource.nextId()}")

    companion object {
        const val ANONYMOUS_CONTEXT = "anonymous"
        const val STARTUP_BATCH = "startup"
    }
}

typealias WorkflowRunner = InMemoryWorkflowRunner

private object WorkflowInspection {
    fun toJson(result: WorkflowRunResult): String {
        val journal = result.journal
        val batches = journal.batches().map { batch -> JsonObject(linkedMapOf(
            "journalBatchId" to JsonPrimitive(batch.journalBatchId.value),
            "committedAt" to JsonPrimitive(batch.committedAt.toString()),
            "mutations" to JsonArray(batch.mutations.map(::assignmentJson)),
        )) }
        val intents = journal.activationIntents().map { intent ->
            val state = if (journal.isCompleted(intent.id)) "completed" else "pending"
            JsonObject(linkedMapOf(
                "id" to JsonPrimitive(intent.id.value),
                "activationId" to JsonPrimitive(intent.activationId.value),
                "producerId" to JsonPrimitive(intent.producerId.value),
                "contextId" to JsonPrimitive(intent.contextId.value),
                "journalBatchId" to JsonPrimitive(intent.journalBatchId.value),
                "dependencyRevisions" to revisionVector(intent.dependencyRevisions),
                "createdAt" to JsonPrimitive(intent.createdAt.toString()),
                "state" to JsonPrimitive(state),
            ))
        }
        val activations = journal.activations().map { activation ->
            val fields = linkedMapOf<String, JsonElement>(
                "activationId" to JsonPrimitive(activation.activationId.value),
                "intentId" to JsonPrimitive(activation.intentId.value),
                "producerId" to JsonPrimitive(activation.producerId.value),
                "contextId" to JsonPrimitive(activation.contextId.value),
                "dependencyRevisions" to revisionVector(activation.dependencyRevisions),
                "status" to JsonPrimitive(activation.status.name.lowercase()),
                "startedAt" to JsonPrimitive(activation.startedAt.toString()),
            )
            activation.completedAt?.let { fields["completedAt"] = JsonPrimitive(it.toString()) }
            activation.failure?.let { fields["failure"] = JsonPrimitive(it) }
            JsonObject(fields)
        }
        val outputObject = Json.parseToJsonElement(result.outputsJson()) as JsonObject
        return JsonObject(linkedMapOf(
            "executionId" to JsonPrimitive(result.executionId.value),
            "outputs" to outputObject["outputs"]!!,
            "batches" to JsonArray(batches),
            "assignments" to JsonArray(journal.assignments().map(::assignmentJson)),
            "activationIntents" to JsonArray(intents),
            "activations" to JsonArray(activations),
        )).toString()
    }

    private fun assignmentJson(assignment: AssignmentMutation): JsonElement {
        val fields = linkedMapOf<String, JsonElement>(
            "assignmentId" to JsonPrimitive(assignment.assignmentId.value),
            "workflowId" to JsonPrimitive(assignment.workflowId.value),
            "workflowVersionId" to JsonPrimitive(assignment.workflowVersionId.value),
            "executionId" to JsonPrimitive(assignment.executionId.value),
            "contextId" to JsonPrimitive(assignment.contextId.value),
            "registerId" to JsonPrimitive(assignment.registerId.value),
            "revision" to JsonPrimitive(assignment.revision),
            "value" to Json.parseToJsonElement(CanonicalValueJson.encode(assignment.value)),
            "producerId" to JsonPrimitive(assignment.producerId.value),
            "dependencyRevisions" to revisionVector(assignment.dependencyRevisions),
            "occurredAt" to JsonPrimitive(assignment.occurredAt.toString()),
            "mutationOrdinal" to JsonPrimitive(assignment.mutationOrdinal),
        )
        assignment.activationId?.let { fields["activationId"] = JsonPrimitive(it.value) }
        assignment.causationId?.let { fields["causationId"] = JsonPrimitive(it) }
        return JsonObject(fields)
    }

    private fun revisionVector(vector: Map<RegisterId, AssignmentId>): JsonObject =
        JsonObject(vector.toSortedMap(compareBy { it.value }).mapKeys { it.key.value }.mapValues { JsonPrimitive(it.value.value) })
}
