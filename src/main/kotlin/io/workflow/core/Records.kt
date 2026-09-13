package io.workflow.core

import java.time.Instant
import java.util.UUID

interface Clock { fun now(): Instant }
object SystemClock : Clock { override fun now() = Instant.now() }
class FixedClock(private val instant: Instant) : Clock { override fun now() = instant }
interface IdSource { fun nextId(): String }
class DeterministicIdSource(private val prefix: String = "id-") : IdSource {
    private var n = 0L
    override fun nextId() = "$prefix${++n}"
}
class UuidIdSource : IdSource { override fun nextId(): String = UUID.randomUUID().toString() }

data class AssignmentMutation(
    val assignmentId: AssignmentId, val contextId: ContextId, val registerId: RegisterId,
    val value: Value, val producerId: ProducerId, val activationId: ActivationId? = null,
    val causationId: String? = null, val revision: Long = 1, val occurredAt: Instant,
    val formatVersion: Int = 1, val mutationOrdinal: Int = 0
)
data class JournalBatch(
    val journalBatchId: JournalBatchId, val formatVersion: Int = 1,
    val mutationOrdinal: Int = 0, val assignment: AssignmentMutation,
    val committedAt: Instant
) {
    companion object {
        fun create(id: JournalBatchId, mutations: List<AssignmentMutation>, committedAt: Instant, formatVersion: Int = 1): JournalBatch {
            require(mutations.size == 1) { "v1 journal batch must contain exactly one assignment mutation" }
            return JournalBatch(id, formatVersion, 0, mutations.single(), committedAt)
        }
    }
}
data class ActivationIntent(
    val id: ActivationIntentId, val activationId: ActivationId, val producerId: ProducerId,
    val contextId: ContextId, val journalBatchId: JournalBatchId, val createdAt: Instant
)
