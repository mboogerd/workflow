package io.workflow.core

import java.time.Instant
import java.util.UUID

interface Clock { fun now(): Instant }
object SystemClock : Clock { override fun now() = Instant.now() }
class FixedClock(private val instant: Instant) : Clock { override fun now() = instant }
interface IdSource { fun nextId(): String }
class DeterministicIdSource(private val prefix: String = "id-") : IdSource {
    private var n = 0L
    @Synchronized
    override fun nextId() = "$prefix${++n}"
}
class UuidIdSource : IdSource { override fun nextId(): String = UUID.randomUUID().toString() }

data class AssignmentMutation(
    val assignmentId: AssignmentId,
    val workflowId: WorkflowId,
    val workflowVersionId: WorkflowVersionId,
    val executionId: ExecutionId,
    val contextId: ContextId,
    val registerId: RegisterId,
    val value: Value,
    val producerId: ProducerId,
    val activationId: ActivationId? = null,
    val dependencyRevisions: Map<RegisterId, AssignmentId> = emptyMap(),
    val invocationId: InvocationId? = null,
    val emissionId: EmissionId? = null,
    val causationId: String? = null,
    val revision: Long = 1,
    val occurredAt: Instant,
    /** Provenance for an assignment produced by one finite-map item. */
    val mapActivationId: ActivationId? = null,
    val mapItemId: String? = null,
    val mapItemIndex: Int? = null,
    val mapItemKey: String? = null,
    val mapInputRevision: AssignmentId? = null,
    val formatVersion: Int = 1,
    val mutationOrdinal: Int = 0,
) {
    val parentMapActivationId: ActivationId? get() = mapActivationId
    val itemIdentity: String? get() = mapItemId
    val parentInputRevision: AssignmentId? get() = mapInputRevision
}
data class JournalBatch(
    val journalBatchId: JournalBatchId,
    val mutations: List<AssignmentMutation>,
    val committedAt: Instant,
    val formatVersion: Int = 1,
) {
    init {
        require(mutations.size == 1) { "v1 journal batch must contain exactly one assignment mutation" }
        require(mutations.single().mutationOrdinal == 0) { "the only v1 mutation must have ordinal zero" }
    }

    val assignment: AssignmentMutation get() = mutations.single()
}
data class ActivationIntent(
    val id: ActivationIntentId, val activationId: ActivationId, val producerId: ProducerId,
    val workflowId: WorkflowId, val workflowVersionId: WorkflowVersionId,
    val executionId: ExecutionId, val contextId: ContextId,
    val journalBatchId: JournalBatchId, val createdAt: Instant,
    /** The immutable register-revision snapshot captured by this activation. */
    val dependencyRevisions: Map<RegisterId, AssignmentId> = emptyMap(),
    /** Optional finite-map item provenance and immutable lexical snapshot. */
    val mapActivationId: ActivationId? = null,
    val mapItemId: String? = null,
    val mapItemIndex: Int? = null,
    val mapItemKey: String? = null,
    val mapInputRevision: AssignmentId? = null,
    val parentContextId: ContextId? = null,
    val lexicalBindings: Map<String, Value> = emptyMap(),
) {
    val intentId: ActivationIntentId get() = id
    val parentMapActivationId: ActivationId? get() = mapActivationId
    val itemIdentity: String? get() = mapItemId
    val parentInputRevision: AssignmentId? get() = mapInputRevision
}
