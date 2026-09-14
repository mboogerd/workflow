package io.workflow.runtime

import io.workflow.compiler.CompiledRegister
import io.workflow.compiler.CompiledMatch
import io.workflow.compiler.CompiledProducer
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
import io.workflow.core.EmissionId
import io.workflow.core.ExecutionId
import io.workflow.core.IdSource
import io.workflow.core.InvocationId
import io.workflow.core.JournalBatch
import io.workflow.core.JournalBatchId
import io.workflow.core.ProducerId
import io.workflow.core.RegisterId
import io.workflow.core.SystemClock
import io.workflow.core.UuidIdSource
import io.workflow.core.Value
import io.workflow.core.WorkflowId
import io.workflow.core.WorkflowVersionId
import io.workflow.core.CanonicalValueJson
import io.workflow.core.validate
import io.workflow.provider.ProviderInvocationRequest
import io.workflow.provider.ProviderLifecycleMessage
import io.workflow.provider.ProviderRegistry
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlinx.coroutines.asCoroutineDispatcher
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
    val workflowId: WorkflowId,
    val workflowVersionId: WorkflowVersionId,
    val executionId: ExecutionId,
    val producerId: ProducerId,
    val contextId: ContextId,
    val dependencyRevisions: Map<RegisterId, AssignmentId>,
    val status: Status,
    val startedAt: Instant,
    val completedAt: Instant?,
    val failure: String? = null,
    val invocationId: InvocationId? = null,
    val attemptId: AttemptId? = null,
    val parentActivationId: ActivationId? = null,
    val discriminatorRevision: AssignmentId? = null,
    val branchTag: String? = null,
) {
    enum class Status { COMPLETED, OPEN, FAILED }
}

/** The append-only provider lifecycle records emitted by the activation boundary. */
enum class ProviderEventType {
    INVOCATION,
    ATTEMPT_STARTED,
    EMISSION_RECEIVED,
    EMISSION_ACCEPTED,
    EMISSION_REFUSED,
    COMPLETED,
    OPEN,
    FAILED;

    companion object {
        // Compatibility names for consumers that use the full lifecycle wording.
        val INVOCATION_STARTED: ProviderEventType get() = INVOCATION
        val ATTEMPT_START: ProviderEventType get() = ATTEMPT_STARTED
        val EMISSION_REJECTED: ProviderEventType get() = EMISSION_REFUSED
    }
}

data class ProviderLifecycleEvent(
    val eventId: String,
    val type: ProviderEventType,
    val workflowId: WorkflowId,
    val workflowVersionId: WorkflowVersionId,
    val executionId: ExecutionId,
    val contextId: ContextId,
    val producerId: ProducerId,
    val activationId: ActivationId,
    val intentId: ActivationIntentId,
    val invocationId: InvocationId,
    val attemptId: AttemptId? = null,
    val emissionId: EmissionId? = null,
    val causationId: String? = null,
    val value: Value? = null,
    val error: Value? = null,
    val diagnostic: String? = null,
    val correlationId: String? = null,
    val occurredAt: Instant,
    val providerId: String? = null,
    val providerVersion: Int? = null,
    val parentActivationId: ActivationId? = null,
    val discriminatorRevision: AssignmentId? = null,
) {
    /** `kind` is a convenient protocol-facing name for inspection clients. */
    val kind: ProviderEventType get() = type
    val eventType: ProviderEventType get() = type
    val safeDiagnostics: String? get() = diagnostic
}

typealias ProviderJournalEvent = ProviderLifecycleEvent

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
    val parentActivationId: ActivationId? = null,
    val discriminatorRevision: AssignmentId? = null,
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

interface ActivationClaimer {
    fun claimNextActivation(executionId: ExecutionId): ActivationIntent?
    fun completeActivation(intentId: ActivationIntentId)
}

/**
 * An ordered in-memory journal. The lock covers batch validation, revision
 * allocation, journal append, current-view projection, and intent persistence.
 */
class InMemoryJournalStore(
    private val clock: Clock = SystemClock,
    private val idSource: IdSource = UuidIdSource(),
) : JournalBatchCommitter, CurrentViewProjection, ActivationClaimer {
    private val lock = Any()
    private val journalBatches = mutableListOf<JournalBatch>()
    private val currentAssignments = linkedMapOf<RegisterKey, AssignmentMutation>()
    private val activationIntents = linkedMapOf<ActivationIntentId, ActivationIntent>()
    private val completedIntentIds = linkedSetOf<ActivationIntentId>()
    private val claimedIntentIds = linkedSetOf<ActivationIntentId>()
    private val openIntentIds = linkedSetOf<ActivationIntentId>()
    private val deferredIntentIds = linkedSetOf<ActivationIntentId>()
    private val activationRecords = mutableListOf<ActivationRecord>()
    private val providerEvents = mutableListOf<ProviderLifecycleEvent>()
    private val providerEventIds = mutableSetOf<String>()
    private val assignmentsById = linkedMapOf<AssignmentId, AssignmentMutation>()
    private val executionBindings = linkedMapOf<ExecutionId, ExecutionBinding>()

    private data class ExecutionBinding(
        val workflowVersionId: WorkflowVersionId,
        val contentHash: String,
        val parameters: Map<String, Value>,
    )

    fun bindExecution(
        executionId: ExecutionId,
        workflowVersionId: WorkflowVersionId,
        contentHash: String,
        parameters: Map<String, Value>,
    ): Map<String, Value> = synchronized(lock) {
        val proposed = ExecutionBinding(workflowVersionId, contentHash, parameters.toMap())
        val existing = executionBindings[executionId]
        require(existing == null || existing == proposed) {
            "execution id is already bound to different workflow content or parameters"
        }
        executionBindings.putIfAbsent(executionId, proposed)
        executionBindings.getValue(executionId).parameters
    }

    override fun commit(batch: JournalBatch, activationIntents: Collection<ActivationIntent>): JournalBatch =
        synchronized(lock) {
            require(batch.mutations.size == 1) { "v1 journal batch must contain exactly one assignment mutation" }
            require(batch.formatVersion == 1) { "unsupported journal batch format version ${batch.formatVersion}" }
            val mutation = batch.assignment
            require(mutation.formatVersion == 1) { "unsupported assignment format version ${mutation.formatVersion}" }
            require(mutation.mutationOrdinal == 0) { "the only v1 mutation must have ordinal zero" }

            val duplicateBatch = journalBatches.firstOrNull { it.journalBatchId == batch.journalBatchId }
            if (duplicateBatch != null) {
                val requestedMutation = mutation
                val committedMutation = duplicateBatch.assignment
                require(
                    duplicateBatch.formatVersion == batch.formatVersion &&
                        duplicateBatch.committedAt == batch.committedAt &&
                        committedMutation.copy(revision = requestedMutation.revision) == requestedMutation,
                ) { "journal batch id is already committed with different contents" }
                if (activationIntents.isNotEmpty()) {
                    val persisted = this.activationIntents.values.filter { it.journalBatchId == batch.journalBatchId }
                    require(persisted.size == activationIntents.size && activationIntents.all { proposed ->
                        persisted.any { it.id == proposed.id && it.sameIdentityAndProvenance(proposed) }
                    }) { "journal batch id is already committed with different activation intents" }
                }
                return@synchronized duplicateBatch
            }

            val duplicateAssignment = assignmentsById[mutation.assignmentId]
            require(duplicateAssignment == null) { "assignment id is already committed" }
            val key = RegisterKey(mutation.executionId, mutation.contextId, mutation.registerId)
            val nextRevision = (currentAssignments[key]?.revision ?: 0L) + 1L
            val committedMutation = mutation.copy(revision = nextRevision)
            val committedBatch = batch.copy(mutations = listOf(committedMutation))
            activationIntents.forEach { intent ->
                require(intent.workflowId == mutation.workflowId)
                require(intent.workflowVersionId == mutation.workflowVersionId)
                require(intent.executionId == mutation.executionId)
                require(intent.contextId == mutation.contextId)
                require(intent.journalBatchId == batch.journalBatchId)
                val duplicate = this.activationIntents[intent.id]
                require(duplicate == null || duplicate.sameIdentityAndProvenance(intent)) {
                    "activation intent id is already persisted with different contents"
                }
            }

            // These operations are intentionally one critical section: no observer
            // can see an assignment without its downstream durable intents.
            journalBatches += committedBatch
            assignmentsById[committedMutation.assignmentId] = committedMutation
            currentAssignments[key] = committedMutation
            activationIntents.forEach { intent -> this.activationIntents.putIfAbsent(intent.id, intent) }
            deferredIntentIds.removeIf { deferredId ->
                this.activationIntents[deferredId]?.let { deferred ->
                    deferred.executionId == mutation.executionId && deferred.contextId == mutation.contextId
                } == true
            }
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
            require(duplicate == null || duplicate.sameIdentityAndProvenance(intent)) {
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
    fun providerEvents(): List<ProviderLifecycleEvent> = synchronized(lock) { providerEvents.toList() }
    fun events(): List<ProviderLifecycleEvent> = providerEvents()
    fun providerLifecycleEvents(): List<ProviderLifecycleEvent> = providerEvents()
    fun providerInvocations(): List<ProviderLifecycleEvent> = providerEvents().filter { it.type == ProviderEventType.INVOCATION }
    fun providerAttempts(): List<ProviderLifecycleEvent> = providerEvents().filter { it.type == ProviderEventType.ATTEMPT_STARTED }
    fun providerEmissions(): List<ProviderLifecycleEvent> = providerEvents().filter {
        it.type == ProviderEventType.EMISSION_RECEIVED ||
            it.type == ProviderEventType.EMISSION_ACCEPTED ||
            it.type == ProviderEventType.EMISSION_REFUSED
    }
    fun providerFailures(): List<ProviderLifecycleEvent> = providerEvents().filter { it.type == ProviderEventType.FAILED }
    fun invocationRecords(): List<ProviderLifecycleEvent> = providerInvocations()
    fun attemptRecords(): List<ProviderLifecycleEvent> = providerAttempts()
    fun emissionRecords(): List<ProviderLifecycleEvent> = providerEmissions()
    fun failureRecords(): List<ProviderLifecycleEvent> = providerFailures()
    fun assignment(id: AssignmentId): AssignmentMutation? = synchronized(lock) { assignmentsById[id] }

    fun recordProviderEvent(event: ProviderLifecycleEvent) = synchronized(lock) {
        require(providerEventIds.add(event.eventId)) { "provider event id is already committed" }
        providerEvents += event
    }

    /** Claiming reserves an intent; completion acknowledges it durably. */
    override fun claimNextActivation(executionId: ExecutionId): ActivationIntent? = synchronized(lock) {
        activationIntents.values.firstOrNull {
            it.executionId == executionId &&
                it.id !in completedIntentIds &&
                it.id !in claimedIntentIds &&
                it.id !in openIntentIds &&
                it.id !in deferredIntentIds
        }?.also { claimedIntentIds += it.id }
    }

    override fun completeActivation(intentId: ActivationIntentId) = synchronized(lock) {
        require(intentId in activationIntents) { "cannot complete an unknown activation intent" }
        claimedIntentIds -= intentId
        openIntentIds -= intentId
        completedIntentIds += intentId
    }

    /** Return an in-flight claim to the durable queue after an unhandled worker error. */
    fun releaseActivation(intentId: ActivationIntentId) = synchronized(lock) {
        require(intentId in activationIntents) { "cannot release an unknown activation intent" }
        claimedIntentIds -= intentId
    }

    /** Park an open activation until a future stream-driving API resumes it. */
    fun keepActivationOpen(intentId: ActivationIntentId) = synchronized(lock) {
        require(intentId in activationIntents) { "cannot keep an unknown activation intent open" }
        require(intentId !in completedIntentIds) { "cannot reopen a completed activation intent" }
        claimedIntentIds -= intentId
        openIntentIds += intentId
    }

    /**
     * Park work only while a required register is still absent. Readiness and
     * the claim transition share the journal lock to prevent a lost wake-up.
     */
    fun deferActivationIfMissing(intentId: ActivationIntentId, required: Set<RegisterKey>): Boolean = synchronized(lock) {
        require(intentId in activationIntents) { "cannot defer an unknown activation intent" }
        require(intentId !in completedIntentIds) { "cannot defer a completed activation intent" }
        if (required.none { it !in currentAssignments }) return@synchronized false
        claimedIntentIds -= intentId
        deferredIntentIds += intentId
        true
    }

    /** Reconsider durable deferred work when an execution resumes. */
    fun wakeDeferredActivations(executionId: ExecutionId) = synchronized(lock) {
        deferredIntentIds.removeIf { deferredId -> activationIntents[deferredId]?.executionId == executionId }
    }

    fun hasPendingActivations(executionId: ExecutionId): Boolean = synchronized(lock) {
        activationIntents.values.any {
            it.executionId == executionId &&
                it.id !in completedIntentIds &&
                it.id !in openIntentIds &&
                it.id !in deferredIntentIds
        }
    }

    fun isOpen(intentId: ActivationIntentId): Boolean = synchronized(lock) { intentId in openIntentIds }

    fun recordActivation(record: ActivationRecord) = synchronized(lock) {
        val intent = activationIntents[record.intentId]
            ?: error("cannot record activation for an unknown intent")
        require(record.activationId == intent.activationId)
        require(record.workflowId == intent.workflowId)
        require(record.workflowVersionId == intent.workflowVersionId)
        require(record.executionId == intent.executionId)
        require(record.contextId == intent.contextId)
        require(record.producerId == intent.producerId)
        require(record.dependencyRevisions == intent.dependencyRevisions)
        require(record.parentActivationId == intent.parentActivationId)
        require(record.discriminatorRevision == intent.discriminatorRevision)
        require(record.branchTag == intent.branchTag)
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

private fun ActivationIntent.sameIdentityAndProvenance(other: ActivationIntent): Boolean =
    copy(createdAt = other.createdAt) == other

object CurrentViews {
    fun rebuild(batches: Iterable<JournalBatch>): Map<RegisterKey, AssignmentMutation> {
        val result = linkedMapOf<RegisterKey, AssignmentMutation>()
        val batchIds = mutableSetOf<JournalBatchId>()
        val assignmentIds = mutableSetOf<AssignmentId>()
        batches.forEach { batch ->
            require(batch.mutations.size == 1) { "v1 journal batch must contain exactly one assignment mutation" }
            require(batchIds.add(batch.journalBatchId)) { "journal batch ids must be unique" }
            val assignment = batch.assignment
            require(assignmentIds.add(assignment.assignmentId)) { "assignment ids must be unique" }
            val key = RegisterKey(assignment.executionId, assignment.contextId, assignment.registerId)
            val previous = result[key]
            val expectedRevision = (previous?.revision ?: 0L) + 1L
            require(assignment.revision == expectedRevision) {
                "assignment revisions must be contiguous for each register"
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
        .filter { activationDependencies(it).isEmpty() }
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
            .filter { assignedRegister in activationDependencies(it) }
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
        val vector = activationDependencies(register).associate { name ->
            val dependency = workflow.registers.first { it.name == name }
            val assignment = currentRegister(current, executionId, contextId, workflow, name)
            dependency.registerId to (assignment?.assignmentId ?: AssignmentId(ABSENT_REVISION))
        }.toSortedMap(compareBy { it.value })
        val identity = stableIdentity(executionId, contextId, register.producerId, vector)
        return ActivationIntent(
            id = ActivationIntentId("intent-$identity"),
            activationId = ActivationId("activation-$identity"),
            producerId = register.producerId,
            workflowId = WorkflowId(workflow.workflowId),
            workflowVersionId = workflow.workflowVersionId,
            executionId = executionId,
            contextId = contextId,
            journalBatchId = journalBatchId,
            createdAt = createdAt,
            dependencyRevisions = vector,
        )
    }

    /** A match reacts to its discriminator, not to dependencies of unselected cases. */
    private fun activationDependencies(register: CompiledRegister): List<String> =
        register.match?.let { expressionDependencies(it.discriminator) } ?: register.dependencies

    private fun expressionDependencies(expression: Expression): List<String> = when (expression) {
        is Expression.Ref -> if (expression.root !in LEXICAL_ROOTS) listOf(expression.root) else emptyList()
        is Expression.Literal -> emptyList()
        is Expression.ObjectValue -> expression.fields.values.flatMap(::expressionDependencies)
        is Expression.ArrayValue -> expression.items.flatMap(::expressionDependencies)
        is Expression.Concat -> expression.parts.flatMap(::expressionDependencies)
        is Expression.Equals -> expressionDependencies(expression.left) + expressionDependencies(expression.right)
        is Expression.Present -> expressionDependencies(expression.value)
        is Expression.And -> expression.predicates.flatMap(::expressionDependencies)
        is Expression.Or -> expression.predicates.flatMap(::expressionDependencies)
        is Expression.Not -> expressionDependencies(expression.predicate)
    }.distinct()

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
    val providerEvents: List<ProviderLifecycleEvent>
        get() = journal.providerEvents().filter { it.executionId == executionId }

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
    private val idSource: IdSource = UuidIdSource(),
    val journal: InMemoryJournalStore = InMemoryJournalStore(clock, idSource),
    private val planner: ActivationPlanner = ExpressionActivationPlanner(),
    private val providerRegistry: ProviderRegistry = compiler.providerRegistry,
    private val workerCount: Int = DEFAULT_WORKER_COUNT,
) {
    private val assignmentCommitLock = Any()

    constructor(providerRegistry: ProviderRegistry) : this(
        compiler = WorkflowCompiler(providerRegistry),
        providerRegistry = providerRegistry,
    )

    constructor(providerRegistry: ProviderRegistry, workerCount: Int) : this(
        compiler = WorkflowCompiler(providerRegistry),
        providerRegistry = providerRegistry,
        workerCount = workerCount,
    )

    init {
        require(workerCount > 0) { "provider worker count must be positive" }
    }

    fun run(
        yamlText: String,
        parameters: Map<String, Value> = emptyMap(),
        executionId: ExecutionId = nextExecutionId(),
        beforeActivation: (ActivationIntent) -> Unit = {},
    ): WorkflowRunResult {
        val compilation = compile(yamlText)
        if (!compilation.isValid) throw WorkflowExecutionException(compilation.diagnostics.joinToString("\n"))
        return execute(compilation.ir!!, parameters, executionId, beforeActivation)
    }

    private fun compile(yamlText: String) = if (providerRegistry !== compiler.providerRegistry && compiler.providerRegistry.isEmpty()) {
        WorkflowCompiler(providerRegistry).compile(yamlText)
    } else {
        compiler.compile(yamlText)
    }

    fun execute(
        workflow: WorkflowIrDocument,
        parameters: Map<String, Value> = emptyMap(),
        executionId: ExecutionId = nextExecutionId(),
        beforeActivation: (ActivationIntent) -> Unit = {},
    ): WorkflowRunResult {
        validateParameters(workflow, parameters)
        val boundParameters = journal.bindExecution(
            executionId,
            workflow.workflowVersionId,
            workflow.contentHash,
            parameters,
        )
        val contextId = ContextId(ANONYMOUS_CONTEXT)
        journal.persistActivationIntents(
            planner.initial(workflow, executionId, contextId, JournalBatchId(STARTUP_BATCH), clock.now()),
        )
        journal.wakeDeferredActivations(executionId)

        val failures = mutableListOf<String>()
        runWorkers(workflow, boundParameters, executionId, beforeActivation, failures)

        val outputs = workflow.outputs.mapNotNull { outputName ->
            val register = workflow.registers.first { it.name == outputName }
            val assignment = journal.current(RegisterKey(executionId, contextId, register.registerId))
            if (assignment == null && failures.isEmpty() && register.compiledProducer is CompiledProducer.Expression) {
                throw WorkflowExecutionException("output '$outputName' received no assignment")
            }
            assignment?.let { outputName to PublishedOutput(it.value, it.revision) }
        }.toMap()
        return WorkflowRunResult(executionId, outputs, journal, failures)
    }

    /** Run ready activations on a bounded coroutine dispatcher until the queue quiesces. */
    private fun runWorkers(
        workflow: WorkflowIrDocument,
        parameters: Map<String, Value>,
        executionId: ExecutionId,
        beforeActivation: (ActivationIntent) -> Unit,
        failures: MutableList<String>,
    ) {
        val executor = Executors.newFixedThreadPool(workerCount)
        val dispatcher = executor.asCoroutineDispatcher()
        try {
            runBlocking {
                withContext(dispatcher) {
                    val active = AtomicInteger(0)
                    val workers = List(workerCount) {
                        launch {
                            workerLoop(workflow, parameters, executionId, beforeActivation, failures, active)
                        }
                    }
                    workers.joinAll()
                }
            }
        } finally {
            dispatcher.close()
            executor.shutdownNow()
        }
    }

    private suspend fun workerLoop(
        workflow: WorkflowIrDocument,
        parameters: Map<String, Value>,
        executionId: ExecutionId,
        beforeActivation: (ActivationIntent) -> Unit,
        failures: MutableList<String>,
        active: AtomicInteger,
    ) {
        while (true) {
            val intent = journal.claimNextActivation(executionId)
            if (intent == null) {
                if (active.get() == 0 && !journal.hasPendingActivations(executionId)) return
                yield()
                continue
            }
            active.incrementAndGet()
            try {
                // Keep this hook outside activation failure handling. Its existing
                // contract is a worker-crash simulation and must leave the claim retryable.
                try {
                    beforeActivation(intent)
                } catch (failure: Throwable) {
                    journal.releaseActivation(intent.id)
                    throw failure
                }
                processActivation(workflow, parameters, intent)?.let { failure ->
                    synchronized(failures) { failures += failure }
                }
            } finally {
                active.decrementAndGet()
            }
        }
    }

    private fun processActivation(
        workflow: WorkflowIrDocument,
        parameters: Map<String, Value>,
        intent: ActivationIntent,
    ): String? {
        val register = resolveProducer(workflow, intent.producerId)
            ?: throw WorkflowExecutionException("activation refers to unknown producer ${intent.producerId.value}")
        val started = clock.now()
        return try {
            val context = capturedContext(intent)
            if (register.match != null) {
                if (!activateMatch(workflow, parameters, intent, register, context)) return null
                recordActivation(intent, ActivationRecord.Status.COMPLETED, started, null, null, null)
                journal.completeActivation(intent.id)
                null
            } else if (register.provider == null) {
                val evaluated = evaluate(register.producer, workflow, parameters, context, intent.lexicalBindings)
                val value = materialize(evaluated)
                if (evaluated !is Evaluated.OptionalResult) validateOutput(register, value)
                commitAssignment(
                    workflow = workflow,
                    intent = intent,
                    register = register,
                    value = value,
                    invocationId = null,
                    emissionId = null,
                    causationId = intent.id.value,
                    contextId = intent.contextId,
                    targetRegisterId = intent.targetRegisterId,
                    parentActivationId = intent.parentActivationId,
                    discriminatorRevision = intent.discriminatorRevision,
                )
                recordActivation(intent, ActivationRecord.Status.COMPLETED, started, null, null, null)
                journal.completeActivation(intent.id)
                null
            } else {
                val result = executeProvider(workflow, parameters, context, intent, register)
                val status = if (result.failure != null) ActivationRecord.Status.FAILED else when (result.status) {
                    ProviderActivationStatus.COMPLETED -> ActivationRecord.Status.COMPLETED
                    ProviderActivationStatus.OPEN -> ActivationRecord.Status.OPEN
                }
                recordActivation(intent, status, started, result.failure, result.invocationId, result.attemptId)
                if (status == ActivationRecord.Status.OPEN) journal.keepActivationOpen(intent.id)
                else journal.completeActivation(intent.id)
                result.failure?.let { "${register.name}: $it" }
            }
        } catch (failure: BindingFailure) {
            recordActivation(intent, ActivationRecord.Status.FAILED, started, failure.message, null, null)
            journal.completeActivation(intent.id)
            "${register.name}: ${failure.message}"
        }
    }

    private fun resolveProducer(workflow: WorkflowIrDocument, producerId: ProducerId): CompiledRegister? {
        fun visit(register: CompiledRegister): CompiledRegister? {
            val producer = register.compiledProducer ?: return null
            if (producer.producerId == producerId) return register
            return when (producer) {
                is CompiledProducer.Match -> producer.value.cases.values.firstNotNullOfOrNull { nested ->
                    visit(nested.asRegister(register))
                }
                is CompiledProducer.Map -> producer.value.context.firstNotNullOfOrNull(::visit)
                else -> null
            }
        }
        return workflow.registers.firstNotNullOfOrNull(::visit)
    }

    private fun CompiledProducer.asRegister(target: CompiledRegister): CompiledRegister = when (this) {
        is CompiledProducer.Expression -> target.copy(
            producerId = producerId,
            producer = value,
            dependencies = dependencies,
            schema = schema,
            provider = null,
            compiledProducer = this,
            match = null,
            map = null,
        )
        is CompiledProducer.Provider -> target.copy(
            producerId = producerId,
            producer = value.input,
            dependencies = dependencies,
            schema = schema,
            provider = value,
            compiledProducer = this,
            match = null,
            map = null,
        )
        is CompiledProducer.Match -> target.copy(
            producerId = producerId,
            producer = value.discriminator,
            dependencies = dependencies,
            schema = schema,
            provider = null,
            compiledProducer = this,
            match = value,
            map = null,
        )
        is CompiledProducer.Map -> target.copy(
            producerId = producerId,
            producer = value.input,
            dependencies = dependencies,
            schema = schema,
            provider = null,
            compiledProducer = this,
            match = null,
            map = value,
        )
    }

    /** Select and durably enqueue one case producer for this captured revision. */
    private fun activateMatch(
        workflow: WorkflowIrDocument,
        parameters: Map<String, Value>,
        intent: ActivationIntent,
        register: CompiledRegister,
        context: Map<RegisterId, AssignmentMutation>,
    ): Boolean {
        val match = register.match ?: error("match binding is missing")
        val discriminator = materialize(
            evaluate(match.discriminator, workflow, parameters, context, intent.lexicalBindings),
        )
        val objectValue = discriminator as? Value.ObjectValue
            ?: throw BindingFailure("MATCH_INVALID_RUNTIME_TAG: discriminator must be a tagged object")
        val tag = objectValue.fields[match.discriminatorSchema.discriminator] as? Value.StringValue
            ?: throw BindingFailure(
                "MATCH_INVALID_RUNTIME_TAG: discriminator field '${match.discriminatorSchema.discriminator}' must be a string",
            )
        val branch = match.cases[tag.value]
            ?: throw BindingFailure("MATCH_UNKNOWN_TAG: '${tag.value}' is not a declared match case")

        var current: Map<RegisterId, AssignmentMutation>
        while (true) {
            current = journal.currentFor(intent.executionId, intent.contextId)
            val missing = requiredContextDependencies(branch).mapNotNullTo(linkedSetOf()) { dependencyName ->
                workflow.registers.firstOrNull { it.name == dependencyName }?.registerId
            }.filterTo(linkedSetOf()) { it !in current }
            if (missing.isEmpty()) break
            val keys = missing.mapTo(linkedSetOf()) { RegisterKey(intent.executionId, intent.contextId, it) }
            if (journal.deferActivationIfMissing(intent.id, keys)) return false
        }

        val branchVector = linkedMapOf<RegisterId, AssignmentId>()
        branchVector.putAll(intent.dependencyRevisions)
        branch.dependencies.forEach { dependencyName ->
            val dependency = workflow.registers.firstOrNull { it.name == dependencyName } ?: return@forEach
            val assignment = context[dependency.registerId] ?: current[dependency.registerId]
            branchVector[dependency.registerId] =
                assignment?.assignmentId ?: AssignmentId(ExpressionActivationPlanner.ABSENT_REVISION)
        }
        val discriminatorRevision = discriminatorRevision(match, workflow, intent)
        val identity = sha256(buildString {
            append(intent.activationId.value).append('|')
            append(branch.producerId.value).append('|')
            append(discriminatorRevision?.value ?: "lexical")
        })
        journal.persistActivationIntents(listOf(ActivationIntent(
            id = ActivationIntentId("intent-match-$identity"),
            activationId = ActivationId("activation-match-$identity"),
            producerId = branch.producerId,
            workflowId = intent.workflowId,
            workflowVersionId = intent.workflowVersionId,
            executionId = intent.executionId,
            contextId = intent.contextId,
            journalBatchId = JournalBatchId("match-$identity"),
            createdAt = clock.now(),
            dependencyRevisions = branchVector.toSortedMap(compareBy { it.value }),
            parentActivationId = intent.activationId,
            targetRegisterId = register.registerId,
            lexicalBindings = intent.lexicalBindings + ("match" to discriminator),
            discriminatorRevision = discriminatorRevision,
            branchTag = tag.value,
        )))
        return true
    }

    private fun requiredContextDependencies(producer: CompiledProducer): Set<String> = when (producer) {
        is CompiledProducer.Expression -> ExpressionActivationPlanner.requiredContextDependencies(producer.value)
        is CompiledProducer.Provider ->
            ExpressionActivationPlanner.requiredContextDependencies(producer.value.input) +
                ExpressionActivationPlanner.requiredContextDependencies(producer.value.config)
        is CompiledProducer.Match -> ExpressionActivationPlanner.requiredContextDependencies(producer.value.discriminator)
        is CompiledProducer.Map -> ExpressionActivationPlanner.requiredContextDependencies(producer.value.input)
    }

    private fun discriminatorRevision(
        match: CompiledMatch,
        workflow: WorkflowIrDocument,
        intent: ActivationIntent,
    ): AssignmentId? {
        val root = (match.discriminator as? Expression.Ref)?.root ?: return intent.discriminatorRevision
        val discriminatorRegister = workflow.registers.firstOrNull { it.name == root }
            ?: return intent.discriminatorRevision
        return intent.dependencyRevisions[discriminatorRegister.registerId]?.takeUnless {
            it.value == ExpressionActivationPlanner.ABSENT_REVISION
        } ?: intent.discriminatorRevision
    }

    private enum class ProviderActivationStatus { COMPLETED, OPEN }

    private data class ProviderExecutionResult(
        val status: ProviderActivationStatus,
        val invocationId: InvocationId,
        val attemptId: AttemptId?,
        val failure: String? = null,
    )

    private fun executeProvider(
        workflow: WorkflowIrDocument,
        parameters: Map<String, Value>,
        context: Map<RegisterId, AssignmentMutation>,
        intent: ActivationIntent,
        register: CompiledRegister,
    ): ProviderExecutionResult {
        val binding = register.provider ?: error("provider binding is missing")
        val registration = providerRegistry.resolve(binding.providerId, binding.version)
            ?: return failedProvider(
                workflow,
                intent,
                register,
                invocationId(intent),
                null,
                "PROVIDER_NOT_FOUND: provider ${binding.providerId}@${binding.version} is not registered",
            )
        val descriptor = registration.descriptor
        val invocationId = invocationId(intent)
        val input = materialize(evaluate(binding.input, workflow, parameters, context, intent.lexicalBindings))
        val config = materialize(evaluate(binding.config, workflow, parameters, context, intent.lexicalBindings))
        validateProviderBinding(descriptor.inputSchema.validate(input), "provider input")
        validateProviderBinding(descriptor.configurationSchema.validate(config), "provider configuration")

        val invocationEvent = recordProviderEvent(
            workflow,
            intent,
            invocationId,
            null,
            ProviderEventType.INVOCATION,
            causationId = intent.id.value,
            register = register,
        )

        val implementation = registration.implementation
            ?: return failedProvider(
                workflow,
                intent,
                register,
                invocationId,
                null,
                "PROVIDER_UNAVAILABLE: provider ${binding.providerId}@${binding.version} has no in-process implementation",
                invocationEvent.eventId,
            )
        val attemptId = AttemptId("attempt-${nextId()}")
        val attemptEvent = recordProviderEvent(
            workflow,
            intent,
            invocationId,
            attemptId,
            ProviderEventType.ATTEMPT_STARTED,
            causationId = invocationEvent.eventId,
            register = register,
        )
        val request = ProviderInvocationRequest(
            providerId = binding.providerId,
            providerVersion = binding.version,
            invocationId = invocationId,
            attemptId = attemptId,
            input = input,
            config = config,
            idempotencyKey = invocationId.value,
            parentActivationId = intent.parentActivationId,
            discriminatorRevision = intent.discriminatorRevision,
        )
        val messages = try {
            implementation.invoke(request)
        } catch (failure: Throwable) {
            val diagnostic = safeProviderThrowable("PROVIDER_EXCEPTION", failure)
            recordProviderEvent(
                workflow,
                intent,
                invocationId,
                attemptId,
                ProviderEventType.FAILED,
                causationId = attemptEvent.eventId,
                register = register,
                diagnostic = diagnostic,
            )
            return ProviderExecutionResult(ProviderActivationStatus.COMPLETED, invocationId, attemptId, diagnostic)
        }

        val seenEmissionIds = mutableSetOf<EmissionId>()
        var terminal: ProviderEventType? = null
        var terminalFailure: String? = null
        try {
            for (message in messages) {
                if (terminal != null) {
                    return protocolFailure(
                        workflow,
                        intent,
                        register,
                        invocationId,
                        attemptId,
                        attemptEvent.eventId,
                        "PROTOCOL_ORDER_VIOLATION: received ${messageName(message)} after ${terminal!!.name.lowercase()}",
                        emission = message as? ProviderLifecycleMessage.Emission,
                    )
                }
                when (message) {
                    is ProviderLifecycleMessage.Emission -> {
                        val received = recordProviderEvent(
                            workflow,
                            intent,
                            invocationId,
                            attemptId,
                            ProviderEventType.EMISSION_RECEIVED,
                            causationId = attemptEvent.eventId,
                            register = register,
                            emissionId = message.emissionId,
                            value = message.value,
                            correlationId = message.correlationId,
                        )
                        val validationFailure = validateEmission(
                            descriptor,
                            register,
                            message,
                            invocationId,
                            attemptId,
                            seenEmissionIds,
                        )
                        if (validationFailure != null) {
                            return refusal(
                                workflow,
                                intent,
                                register,
                                invocationId,
                                attemptId,
                                received.eventId,
                                message,
                                validationFailure,
                            )
                        }
                        seenEmissionIds += message.emissionId
                        val targetContext = message.correlationId?.let { correlation ->
                            if (correlation.isBlank()) {
                                return refusal(
                                    workflow,
                                    intent,
                                    register,
                                    invocationId,
                                    attemptId,
                                    received.eventId,
                                    message,
                                    "PROTOCOL_INVALID_CORRELATION: correlation id must not be blank",
                                )
                            }
                            ContextId(correlation)
                        } ?: intent.contextId
                        commitAssignment(
                            workflow = workflow,
                            intent = intent,
                            register = register,
                            value = message.value,
                            invocationId = invocationId,
                            emissionId = message.emissionId,
                            causationId = received.eventId,
                            contextId = targetContext,
                            targetRegisterId = intent.targetRegisterId,
                            parentActivationId = intent.parentActivationId,
                            discriminatorRevision = intent.discriminatorRevision,
                        )
                        recordProviderEvent(
                            workflow,
                            intent,
                            invocationId,
                            attemptId,
                            ProviderEventType.EMISSION_ACCEPTED,
                            causationId = received.eventId,
                            register = register,
                            emissionId = message.emissionId,
                            value = message.value,
                            correlationId = message.correlationId,
                        )
                    }
                    ProviderLifecycleMessage.Completed -> {
                        if (!descriptor.lifecycle.completes) {
                            return protocolFailure(
                                workflow,
                                intent,
                                register,
                                invocationId,
                                attemptId,
                                attemptEvent.eventId,
                                "PROTOCOL_UNSUPPORTED_COMPLETION: provider descriptor does not allow completion",
                            )
                        }
                        terminal = ProviderEventType.COMPLETED
                        recordProviderEvent(
                            workflow,
                            intent,
                            invocationId,
                            attemptId,
                            ProviderEventType.COMPLETED,
                            causationId = attemptEvent.eventId,
                            register = register,
                        )
                    }
                    ProviderLifecycleMessage.Open -> {
                        if (!descriptor.lifecycle.supportsOpenActivation) {
                            return protocolFailure(
                                workflow,
                                intent,
                                register,
                                invocationId,
                                attemptId,
                                attemptEvent.eventId,
                                "PROTOCOL_UNSUPPORTED_OPEN: provider descriptor does not allow open activation",
                            )
                        }
                        terminal = ProviderEventType.OPEN
                        recordProviderEvent(
                            workflow,
                            intent,
                            invocationId,
                            attemptId,
                            ProviderEventType.OPEN,
                            causationId = attemptEvent.eventId,
                            register = register,
                        )
                    }
                    is ProviderLifecycleMessage.Failed -> {
                        if (!descriptor.lifecycle.fails) {
                            return protocolFailure(
                                workflow,
                                intent,
                                register,
                                invocationId,
                                attemptId,
                                attemptEvent.eventId,
                                "PROTOCOL_UNSUPPORTED_FAILURE: provider descriptor does not allow failure",
                            )
                        }
                        terminal = ProviderEventType.FAILED
                        val errorValidation = descriptor.errorSchema.validate(message.error)
                        val diagnostic = if (errorValidation.isValid) {
                            "PROVIDER_FAILURE: ${CanonicalValueJson.encode(message.error)}"
                        } else {
                            "PROTOCOL_INVALID_ERROR: ${errorValidation.errors.joinToString { "${it.path}: ${it.message}" }}"
                        }
                        recordProviderEvent(
                            workflow,
                            intent,
                            invocationId,
                            attemptId,
                            ProviderEventType.FAILED,
                            causationId = attemptEvent.eventId,
                            register = register,
                            error = message.error,
                            diagnostic = diagnostic,
                        )
                        terminalFailure = diagnostic
                    }
                }
            }
        } catch (failure: Throwable) {
            val diagnostic = safeProviderThrowable("PROVIDER_EXCEPTION", failure)
            recordProviderEvent(
                workflow,
                intent,
                invocationId,
                attemptId,
                ProviderEventType.FAILED,
                causationId = attemptEvent.eventId,
                register = register,
                diagnostic = diagnostic,
            )
            return ProviderExecutionResult(ProviderActivationStatus.COMPLETED, invocationId, attemptId, diagnostic)
        }
        if (terminal == null) {
            return protocolFailure(
                workflow,
                intent,
                register,
                invocationId,
                attemptId,
                attemptEvent.eventId,
                "PROTOCOL_MISSING_TERMINAL: provider stream ended without a terminal lifecycle message",
            )
        }
        return ProviderExecutionResult(
            if (terminal == ProviderEventType.OPEN) ProviderActivationStatus.OPEN else ProviderActivationStatus.COMPLETED,
            invocationId,
            attemptId,
            terminalFailure,
        )
    }

    private fun validateEmission(
        descriptor: io.workflow.provider.ProviderDescriptor,
        register: CompiledRegister,
        emission: ProviderLifecycleMessage.Emission,
        invocationId: InvocationId,
        attemptId: AttemptId,
        seenEmissionIds: Set<EmissionId>,
    ): String? = when {
        !descriptor.lifecycle.emits -> "PROTOCOL_UNSUPPORTED_EMISSION: provider descriptor does not allow emissions"
        emission.invocationId != invocationId -> "PROTOCOL_INVALID_EMISSION: emission invocation id ${emission.invocationId.value} does not match ${invocationId.value}"
        emission.attemptId != attemptId -> "PROTOCOL_INVALID_EMISSION: emission attempt id ${emission.attemptId.value} does not match ${attemptId.value}"
        emission.emissionId.value.isBlank() -> "PROTOCOL_INVALID_EMISSION: emission id must not be blank"
        emission.emissionId in seenEmissionIds -> "PROTOCOL_DUPLICATE_EMISSION: emission id ${emission.emissionId.value} was already received for this invocation"
        !descriptor.emissionSchema.validate(emission.value).isValid -> "INVALID_OUTPUT: ${descriptor.emissionSchema.validate(emission.value).errors.joinToString { "${it.path}: ${it.message}" }}"
        !register.schema.validate(emission.value).isValid -> "INVALID_OUTPUT: ${register.schema.validate(emission.value).errors.joinToString { "${it.path}: ${it.message}" }}"
        else -> null
    }

    private fun failedProvider(
        workflow: WorkflowIrDocument,
        intent: ActivationIntent,
        register: CompiledRegister,
        invocationId: InvocationId,
        attemptId: AttemptId?,
        diagnostic: String,
        causationId: String = intent.id.value,
    ): ProviderExecutionResult {
        recordProviderEvent(
            workflow,
            intent,
            invocationId,
            attemptId,
            ProviderEventType.FAILED,
            causationId = causationId,
            register = register,
            diagnostic = diagnostic,
        )
        return ProviderExecutionResult(
            ProviderActivationStatus.COMPLETED,
            invocationId,
            attemptId,
            diagnostic,
        )
    }

    private fun protocolFailure(
        workflow: WorkflowIrDocument,
        intent: ActivationIntent,
        register: CompiledRegister,
        invocationId: InvocationId,
        attemptId: AttemptId,
        causationId: String,
        diagnostic: String,
        emission: ProviderLifecycleMessage.Emission? = null,
    ): ProviderExecutionResult {
        if (emission != null) {
            val received = recordProviderEvent(
                workflow,
                intent,
                invocationId,
                attemptId,
                ProviderEventType.EMISSION_RECEIVED,
                causationId = causationId,
                register = register,
                emissionId = emission.emissionId,
                value = emission.value,
                correlationId = emission.correlationId,
            )
            recordProviderEvent(
                workflow,
                intent,
                invocationId,
                attemptId,
                ProviderEventType.EMISSION_REFUSED,
                causationId = received.eventId,
                register = register,
                emissionId = emission.emissionId,
                value = emission.value,
                diagnostic = diagnostic,
                correlationId = emission.correlationId,
            )
            // The refusal itself is caused by the received message; the local
            // variable below keeps that link for the failure record as well.
            val refusalCausationId = received.eventId
            recordProviderEvent(
                workflow,
                intent,
                invocationId,
                attemptId,
                ProviderEventType.FAILED,
                causationId = refusalCausationId,
                register = register,
                diagnostic = diagnostic,
            )
            return ProviderExecutionResult(ProviderActivationStatus.COMPLETED, invocationId, attemptId, diagnostic)
        }
        recordProviderEvent(
            workflow,
            intent,
            invocationId,
            attemptId,
            ProviderEventType.FAILED,
            causationId = causationId,
            register = register,
            diagnostic = diagnostic,
        )
        return ProviderExecutionResult(ProviderActivationStatus.COMPLETED, invocationId, attemptId, diagnostic)
    }

    private fun refusal(
        workflow: WorkflowIrDocument,
        intent: ActivationIntent,
        register: CompiledRegister,
        invocationId: InvocationId,
        attemptId: AttemptId,
        causationId: String,
        emission: ProviderLifecycleMessage.Emission,
        diagnostic: String,
    ): ProviderExecutionResult {
        recordProviderEvent(
            workflow,
            intent,
            invocationId,
            attemptId,
            ProviderEventType.EMISSION_REFUSED,
            causationId = causationId,
            register = register,
            emissionId = emission.emissionId,
            value = emission.value,
            diagnostic = diagnostic,
            correlationId = emission.correlationId,
        )
        recordProviderEvent(
            workflow,
            intent,
            invocationId,
            attemptId,
            ProviderEventType.FAILED,
            causationId = causationId,
            register = register,
            emissionId = emission.emissionId,
            diagnostic = diagnostic,
        )
        return ProviderExecutionResult(ProviderActivationStatus.COMPLETED, invocationId, attemptId, diagnostic)
    }

    private fun recordProviderEvent(
        workflow: WorkflowIrDocument,
        intent: ActivationIntent,
        invocationId: InvocationId,
        attemptId: AttemptId?,
        type: ProviderEventType,
        causationId: String?,
        register: CompiledRegister,
        emissionId: EmissionId? = null,
        value: Value? = null,
        error: Value? = null,
        diagnostic: String? = null,
        correlationId: String? = null,
    ): ProviderLifecycleEvent {
        val event = ProviderLifecycleEvent(
            eventId = "provider-event-${nextId()}",
            type = type,
            workflowId = WorkflowId(workflow.workflowId),
            workflowVersionId = workflow.workflowVersionId,
            executionId = intent.executionId,
            contextId = intent.contextId,
            producerId = register.producerId,
            activationId = intent.activationId,
            intentId = intent.id,
            invocationId = invocationId,
            attemptId = attemptId,
            emissionId = emissionId,
            causationId = causationId,
            value = value,
            error = error,
            diagnostic = diagnostic,
            correlationId = correlationId,
            occurredAt = clock.now(),
            providerId = register.provider?.providerId,
            providerVersion = register.provider?.version,
            parentActivationId = intent.parentActivationId,
            discriminatorRevision = intent.discriminatorRevision,
        )
        journal.recordProviderEvent(event)
        return event
    }

    private fun safeProviderThrowable(prefix: String, failure: Throwable): String =
        "$prefix: ${failure::class.simpleName ?: "ProviderError"}: ${failure.message ?: "provider invocation failed"}"

    private fun messageName(message: ProviderLifecycleMessage): String = when (message) {
        is ProviderLifecycleMessage.Emission -> "emission"
        ProviderLifecycleMessage.Completed -> "completion"
        ProviderLifecycleMessage.Open -> "open"
        is ProviderLifecycleMessage.Failed -> "failure"
    }

    private fun invocationId(intent: ActivationIntent): InvocationId = InvocationId(
        "invocation-${stableIdentity(intent.executionId, intent.contextId, intent.producerId, intent.dependencyRevisions)}",
    )

    private fun nextId(): String = synchronized(idSource) { idSource.nextId() }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private fun validateProviderBinding(result: io.workflow.core.ValidationResult, label: String) {
        if (!result.isValid) throw BindingFailure("$label is invalid: ${result.errors.joinToString { "${it.path}: ${it.message}" }}")
    }

    private fun validateOutput(register: CompiledRegister, value: Value) {
        val validation = register.schema.validate(value)
        if (!validation.isValid) throw BindingFailure(validation.errors.joinToString { "${it.path}: ${it.message}" })
    }

    private fun commitAssignment(
        workflow: WorkflowIrDocument,
        intent: ActivationIntent,
        register: CompiledRegister,
        value: Value,
        invocationId: InvocationId?,
        emissionId: EmissionId?,
        causationId: String,
        contextId: ContextId,
        targetRegisterId: RegisterId? = null,
        parentActivationId: ActivationId? = null,
        discriminatorRevision: AssignmentId? = null,
    ) {
        val assignmentId = AssignmentId("assignment-${nextId()}")
        val batchId = JournalBatchId("batch-${nextId()}")
        val candidate = AssignmentMutation(
            assignmentId = assignmentId,
            workflowId = WorkflowId(workflow.workflowId),
            workflowVersionId = workflow.workflowVersionId,
            executionId = intent.executionId,
            contextId = contextId,
            registerId = targetRegisterId ?: register.registerId,
            value = value,
            producerId = register.producerId,
            activationId = intent.activationId,
            dependencyRevisions = intent.dependencyRevisions,
            invocationId = invocationId,
            emissionId = emissionId,
            causationId = causationId,
            parentActivationId = parentActivationId,
            discriminatorRevision = discriminatorRevision,
            occurredAt = clock.now(),
            mutationOrdinal = 0,
        )
        // Journal order is serialized here so the planner sees every earlier
        // commit and the assignment becomes visible atomically with all intents
        // derived from that exact view. In particular, concurrent roots cannot
        // expose the second half of a join before its runnable intent exists.
        synchronized(assignmentCommitLock) {
            val currentWithCandidate = journal.allCurrent() +
                (RegisterKey(intent.executionId, contextId, candidate.registerId) to candidate)
            val downstream = planner.afterAssignment(
                workflow,
                intent.executionId,
                contextId,
                candidate,
                currentWithCandidate,
                batchId,
                clock.now(),
            )
            journal.commit(JournalBatch(batchId, listOf(candidate), clock.now()), downstream)
        }
    }

    private fun recordActivation(
        intent: ActivationIntent,
        status: ActivationRecord.Status,
        startedAt: Instant,
        failure: String?,
        invocationId: InvocationId?,
        attemptId: AttemptId?,
    ) {
        journal.recordActivation(
            ActivationRecord(
                activationId = intent.activationId,
                intentId = intent.id,
                workflowId = intent.workflowId,
                workflowVersionId = intent.workflowVersionId,
                executionId = intent.executionId,
                producerId = intent.producerId,
                contextId = intent.contextId,
                dependencyRevisions = intent.dependencyRevisions,
                status = status,
                startedAt = startedAt,
                completedAt = if (status == ActivationRecord.Status.OPEN) null else clock.now(),
                failure = failure,
                invocationId = invocationId,
                attemptId = attemptId,
                parentActivationId = intent.parentActivationId,
                discriminatorRevision = intent.discriminatorRevision,
                branchTag = intent.branchTag,
            ),
        )
    }

    private fun validateParameters(workflow: WorkflowIrDocument, parameters: Map<String, Value>) {
        val unknown = parameters.keys - workflow.parameters.keys
        if (unknown.isNotEmpty()) throw WorkflowExecutionException("unknown parameters: ${unknown.sorted().joinToString()}")
        workflow.parameters.forEach { (name, schema) ->
            parameters[name]?.let { value ->
                val result = schema.validate(value)
                if (!result.isValid) throw WorkflowExecutionException(
                    result.errors.joinToString { "parameter '$name' ${it.path}: ${it.message}" },
                )
            }
        }
    }

    private fun capturedContext(intent: ActivationIntent): Map<RegisterId, AssignmentMutation> =
        intent.dependencyRevisions.mapNotNull { (registerId, assignmentId) ->
            if (assignmentId.value == ExpressionActivationPlanner.ABSENT_REVISION) return@mapNotNull null
            val assignment = journal.assignment(assignmentId)
                ?: throw WorkflowExecutionException("captured assignment '${assignmentId.value}' is missing")
            require(assignment.executionId == intent.executionId && assignment.contextId == intent.contextId && assignment.registerId == registerId) {
                "captured assignment does not match activation provenance"
            }
            registerId to assignment
        }.toMap()

    private fun evaluate(
        expression: Expression,
        workflow: WorkflowIrDocument,
        parameters: Map<String, Value>,
        context: Map<RegisterId, AssignmentMutation>,
        lexicalBindings: Map<String, Value> = emptyMap(),
    ): Evaluated = when (expression) {
        is Expression.Literal -> Evaluated.ValueResult(expression.value)
        is Expression.Ref -> {
            val root = when {
                expression.root == "parameters" -> Value.ObjectValue(parameters)
                expression.root == "item" || expression.root == "key" || expression.root == "match" ->
                    lexicalBindings[expression.root] ?: Value.Null
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
        is Expression.ObjectValue -> Evaluated.ValueResult(Value.ObjectValue(expression.fields.mapValues {
            materialize(evaluate(it.value, workflow, parameters, context, lexicalBindings))
        }))
        is Expression.ArrayValue -> Evaluated.ValueResult(Value.ArrayValue(expression.items.map {
            materialize(evaluate(it, workflow, parameters, context, lexicalBindings))
        }))
        is Expression.Concat -> Evaluated.ValueResult(Value.StringValue(expression.parts.joinToString("") {
            (materialize(evaluate(it, workflow, parameters, context, lexicalBindings)) as? Value.StringValue)?.value
                ?: throw BindingFailure("concat operand is not a string")
        }))
        is Expression.Equals -> Evaluated.ValueResult(
            Value.BooleanValue(equivalent(
                evaluate(expression.left, workflow, parameters, context, lexicalBindings),
                evaluate(expression.right, workflow, parameters, context, lexicalBindings),
            )),
        )
        is Expression.Present -> {
            val value = evaluate(expression.value, workflow, parameters, context, lexicalBindings)
            Evaluated.ValueResult(Value.BooleanValue(value is Evaluated.OptionalResult && value.present))
        }
        is Expression.And -> Evaluated.ValueResult(Value.BooleanValue(expression.predicates.all {
            boolValue(evaluate(it, workflow, parameters, context, lexicalBindings))
        }))
        is Expression.Or -> Evaluated.ValueResult(Value.BooleanValue(expression.predicates.any {
            boolValue(evaluate(it, workflow, parameters, context, lexicalBindings))
        }))
        is Expression.Not -> Evaluated.ValueResult(Value.BooleanValue(
            !boolValue(evaluate(expression.predicate, workflow, parameters, context, lexicalBindings)),
        ))
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
        const val DEFAULT_WORKER_COUNT = 4
    }
}

typealias WorkflowRunner = InMemoryWorkflowRunner

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

private object WorkflowInspection {
    fun toJson(result: WorkflowRunResult): String {
        val journal = result.journal
        val batches = journal.batches().filter { it.assignment.executionId == result.executionId }.map { batch -> JsonObject(linkedMapOf(
            "journalBatchId" to JsonPrimitive(batch.journalBatchId.value),
            "formatVersion" to JsonPrimitive(batch.formatVersion),
            "committedAt" to JsonPrimitive(batch.committedAt.toString()),
            "mutations" to JsonArray(batch.mutations.map(::assignmentJson)),
        )) }
        val intents = journal.activationIntents().filter { it.executionId == result.executionId }.map { intent ->
            val state = when {
                journal.isCompleted(intent.id) -> "completed"
                journal.isOpen(intent.id) -> "open"
                else -> "pending"
            }
            val fields = linkedMapOf<String, JsonElement>(
                "id" to JsonPrimitive(intent.id.value),
                "activationId" to JsonPrimitive(intent.activationId.value),
                "workflowId" to JsonPrimitive(intent.workflowId.value),
                "workflowVersionId" to JsonPrimitive(intent.workflowVersionId.value),
                "executionId" to JsonPrimitive(intent.executionId.value),
                "producerId" to JsonPrimitive(intent.producerId.value),
                "contextId" to JsonPrimitive(intent.contextId.value),
                "journalBatchId" to JsonPrimitive(intent.journalBatchId.value),
                "dependencyRevisions" to revisionVector(intent.dependencyRevisions),
                "createdAt" to JsonPrimitive(intent.createdAt.toString()),
                "state" to JsonPrimitive(state),
            )
            intent.parentActivationId?.let { fields["parentActivationId"] = JsonPrimitive(it.value) }
            intent.targetRegisterId?.let { fields["targetRegisterId"] = JsonPrimitive(it.value) }
            intent.discriminatorRevision?.let { fields["discriminatorRevision"] = JsonPrimitive(it.value) }
            intent.branchTag?.let { fields["branchTag"] = JsonPrimitive(it) }
            JsonObject(fields)
        }
        val activations = journal.activations().filter { it.executionId == result.executionId }.map { activation ->
            val fields = linkedMapOf<String, JsonElement>(
                "activationId" to JsonPrimitive(activation.activationId.value),
                "intentId" to JsonPrimitive(activation.intentId.value),
                "workflowId" to JsonPrimitive(activation.workflowId.value),
                "workflowVersionId" to JsonPrimitive(activation.workflowVersionId.value),
                "executionId" to JsonPrimitive(activation.executionId.value),
                "producerId" to JsonPrimitive(activation.producerId.value),
                "contextId" to JsonPrimitive(activation.contextId.value),
                "dependencyRevisions" to revisionVector(activation.dependencyRevisions),
                "status" to JsonPrimitive(activation.status.name.lowercase()),
                "startedAt" to JsonPrimitive(activation.startedAt.toString()),
            )
            activation.completedAt?.let { fields["completedAt"] = JsonPrimitive(it.toString()) }
            activation.failure?.let { fields["failure"] = JsonPrimitive(it) }
            activation.invocationId?.let { fields["invocationId"] = JsonPrimitive(it.value) }
            activation.attemptId?.let { fields["attemptId"] = JsonPrimitive(it.value) }
            activation.parentActivationId?.let { fields["parentActivationId"] = JsonPrimitive(it.value) }
            activation.discriminatorRevision?.let { fields["discriminatorRevision"] = JsonPrimitive(it.value) }
            activation.branchTag?.let { fields["branchTag"] = JsonPrimitive(it) }
            JsonObject(fields)
        }
        val outputObject = Json.parseToJsonElement(result.outputsJson()) as JsonObject
        return JsonObject(linkedMapOf(
            "executionId" to JsonPrimitive(result.executionId.value),
            "outputs" to outputObject["outputs"]!!,
            "batches" to JsonArray(batches),
            "assignments" to JsonArray(journal.assignments().filter { it.executionId == result.executionId }.map(::assignmentJson)),
            "activationIntents" to JsonArray(intents),
            "activations" to JsonArray(activations),
            "providerEvents" to JsonArray(journal.providerEvents().filter { it.executionId == result.executionId }.map(::providerEventJson)),
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
            "formatVersion" to JsonPrimitive(assignment.formatVersion),
        )
        assignment.activationId?.let { fields["activationId"] = JsonPrimitive(it.value) }
        assignment.invocationId?.let { fields["invocationId"] = JsonPrimitive(it.value) }
        assignment.emissionId?.let { fields["emissionId"] = JsonPrimitive(it.value) }
        assignment.causationId?.let { fields["causationId"] = JsonPrimitive(it) }
        assignment.parentActivationId?.let { fields["parentActivationId"] = JsonPrimitive(it.value) }
        assignment.discriminatorRevision?.let { fields["discriminatorRevision"] = JsonPrimitive(it.value) }
        return JsonObject(fields)
    }

    private fun revisionVector(vector: Map<RegisterId, AssignmentId>): JsonObject =
        JsonObject(vector.toSortedMap(compareBy { it.value }).mapKeys { it.key.value }.mapValues { JsonPrimitive(it.value.value) })

    private fun providerEventJson(event: ProviderLifecycleEvent): JsonElement {
        val fields = linkedMapOf<String, JsonElement>(
            "eventId" to JsonPrimitive(event.eventId),
            "type" to JsonPrimitive(event.type.name.lowercase()),
            "workflowId" to JsonPrimitive(event.workflowId.value),
            "workflowVersionId" to JsonPrimitive(event.workflowVersionId.value),
            "executionId" to JsonPrimitive(event.executionId.value),
            "contextId" to JsonPrimitive(event.contextId.value),
            "producerId" to JsonPrimitive(event.producerId.value),
            "activationId" to JsonPrimitive(event.activationId.value),
            "intentId" to JsonPrimitive(event.intentId.value),
            "invocationId" to JsonPrimitive(event.invocationId.value),
            "occurredAt" to JsonPrimitive(event.occurredAt.toString()),
        )
        event.providerId?.let { fields["providerId"] = JsonPrimitive(it) }
        event.providerVersion?.let { fields["providerVersion"] = JsonPrimitive(it) }
        event.attemptId?.let { fields["attemptId"] = JsonPrimitive(it.value) }
        event.emissionId?.let { fields["emissionId"] = JsonPrimitive(it.value) }
        event.causationId?.let { fields["causationId"] = JsonPrimitive(it) }
        event.value?.let { fields["value"] = Json.parseToJsonElement(CanonicalValueJson.encode(it)) }
        event.error?.let { fields["error"] = Json.parseToJsonElement(CanonicalValueJson.encode(it)) }
        event.diagnostic?.let { fields["diagnostic"] = JsonPrimitive(it) }
        event.correlationId?.let { fields["correlationId"] = JsonPrimitive(it) }
        event.parentActivationId?.let { fields["parentActivationId"] = JsonPrimitive(it.value) }
        event.discriminatorRevision?.let { fields["discriminatorRevision"] = JsonPrimitive(it.value) }
        return JsonObject(fields)
    }
}
