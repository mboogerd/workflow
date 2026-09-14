package io.workflow.runtime

import io.workflow.core.AssignmentId
import io.workflow.core.CanonicalValueJson
import io.workflow.core.ExecutionId
import io.workflow.core.InvocationId
import io.workflow.core.Value
import io.workflow.provider.AgentBudget
import io.workflow.provider.AgentBudgetMeter
import io.workflow.provider.AgentCapabilityGate
import io.workflow.provider.AgentOperationResult
import java.time.Instant

/** The only recovery actions an agent may propose. There is intentionally no free-form code variant. */
sealed interface RecoveryProposal {
    data class Retry(val adjustment: Value = Value.Null) : RecoveryProposal
    data class Reconcile(val query: Value) : RecoveryProposal
    data class Compensate(val operation: String, val input: Value = Value.Null) : RecoveryProposal
    data class Substitute(val providerId: String, val inputAdjustment: Value = Value.Null) : RecoveryProposal
    data class Emit(val value: Value) : RecoveryProposal
    data class RequestHuman(val question: String, val choices: List<Value>) : RecoveryProposal {
        init { require(question.isNotBlank()); require(choices.isNotEmpty()) }
    }
    data class Skip(val justification: String) : RecoveryProposal { init { require(justification.isNotBlank()) } }
    data class Abort(val reason: String) : RecoveryProposal { init { require(reason.isNotBlank()) } }
}

enum class RecoveryAction { RETRY, RECONCILE, COMPENSATE, SUBSTITUTE, EMIT, REQUEST_HUMAN, SKIP, ABORT }
fun RecoveryProposal.action(): RecoveryAction = when (this) {
    is RecoveryProposal.Retry -> RecoveryAction.RETRY
    is RecoveryProposal.Reconcile -> RecoveryAction.RECONCILE
    is RecoveryProposal.Compensate -> RecoveryAction.COMPENSATE
    is RecoveryProposal.Substitute -> RecoveryAction.SUBSTITUTE
    is RecoveryProposal.Emit -> RecoveryAction.EMIT
    is RecoveryProposal.RequestHuman -> RecoveryAction.REQUEST_HUMAN
    is RecoveryProposal.Skip -> RecoveryAction.SKIP
    is RecoveryProposal.Abort -> RecoveryAction.ABORT
}

/** Explicit policy also documents recovery-only authority: it never silently inherits everything. */
data class RecoveryPolicy(
    val allowed: Set<RecoveryAction>,
    val failedOperationCapabilities: Set<String>,
    val recoveryOnlyCapabilities: Set<String> = emptySet(),
    val budget: AgentBudget = AgentBudget(),
    /** Capability requirements attached to the action itself, independent of the recovery provider. */
    val actionCapabilities: Map<RecoveryAction, Set<String>> = emptyMap(),
)

data class RecoveryRequest(
    val activationId: String,
    val invocationId: InvocationId,
    val failure: Value,
    val dependencyRevisions: Map<String, AssignmentId>,
    val currentDependencyRevisions: Map<String, AssignmentId>,
    val requiredCapabilities: Set<String> = emptySet(),
    val executionId: ExecutionId = ExecutionId("unscoped"),
)

data class RecoveryValidation(val accepted: Boolean, val reason: String? = null)

/** Pure validator: callers persist the result before executing an accepted action. */
object RecoveryValidator {
    fun validate(
        proposal: RecoveryProposal,
        request: RecoveryRequest,
        policy: RecoveryPolicy,
        recoveryProviderCapabilities: Set<String>,
        meter: AgentBudgetMeter,
        gate: AgentCapabilityGate,
        now: Instant,
    ): RecoveryValidation {
        if (proposal.action() !in policy.allowed) return RecoveryValidation(false, "RECOVERY_ACTION_NOT_ALLOWED")
        if (request.dependencyRevisions != request.currentDependencyRevisions) return RecoveryValidation(false, "STALE_DEPENDENCY_REVISIONS")
        val authority = policy.failedOperationCapabilities + policy.recoveryOnlyCapabilities
        val requiredAuthority = request.requiredCapabilities + policy.actionCapabilities[proposal.action()].orEmpty()
        if (!authority.containsAll(requiredAuthority)) return RecoveryValidation(false, "RECOVERY_AUTHORITY_EXCEEDED")
        if (!authority.containsAll(recoveryProviderCapabilities)) return RecoveryValidation(false, "RECOVERY_PROVIDER_CAPABILITY_ESCALATION")
        // A recovery decision is a bounded agent iteration even if it does not call a tool.
        if (meter.consume(iterations = 1, now = now) is AgentOperationResult.Refused) return RecoveryValidation(false, "RECOVERY_BUDGET_EXHAUSTED")
        val gateResult = gate.useTool("recovery:${proposal.action().name.lowercase()}")
        if (gateResult is AgentOperationResult.Refused) return RecoveryValidation(false, gateResult.code)
        return RecoveryValidation(true)
    }
}

enum class HumanInterventionState { PAUSED, ANSWERED, CANCELLED }
data class HumanIntervention(
    val id: String,
    val activationId: String,
    val question: String,
    val choices: List<Value>,
    val state: HumanInterventionState = HumanInterventionState.PAUSED,
    val answer: Value? = null,
) {
    fun answer(value: Value): HumanIntervention {
        require(state == HumanInterventionState.PAUSED) { "intervention is not paused" }
        require(value in choices) { "answer is not one of the typed choices" }
        return copy(state = HumanInterventionState.ANSWERED, answer = value)
    }
}

/** Small durable-facing boundary; journal implementations can store these records without exposing secrets. */
interface RecoveryLedger {
    fun recordProposal(request: RecoveryRequest, proposal: RecoveryProposal)
    fun recordDecision(request: RecoveryRequest, proposal: RecoveryProposal, validation: RecoveryValidation)
    fun pause(intervention: HumanIntervention)
    fun submitAnswer(id: String, answer: Value): HumanIntervention
    fun intervention(id: String): HumanIntervention?
    fun interventionHistory(id: String): List<HumanIntervention>
    fun recordedProposal(invocationId: InvocationId): RecoveryProposalRecord?
    fun recordedDecision(invocationId: InvocationId): RecoveryDecisionRecord?
}

data class RecoveryProposalRecord(val request: RecoveryRequest, val proposal: RecoveryProposal)

data class RecoveryDecisionRecord(
    val request: RecoveryRequest,
    val proposal: RecoveryProposal,
    val validation: RecoveryValidation,
)

class InMemoryRecoveryLedger : RecoveryLedger {
    val proposals = mutableListOf<Pair<RecoveryRequest, RecoveryProposal>>()
    val decisions = mutableListOf<RecoveryDecisionRecord>()
    private val interventions = linkedMapOf<String, HumanIntervention>()
    private val interventionEvents = linkedMapOf<String, MutableList<HumanIntervention>>()
    override fun recordProposal(request: RecoveryRequest, proposal: RecoveryProposal) { proposals += request to proposal }
    override fun recordDecision(request: RecoveryRequest, proposal: RecoveryProposal, validation: RecoveryValidation) { decisions += RecoveryDecisionRecord(request, proposal, validation) }
    override fun pause(intervention: HumanIntervention) {
        require(intervention.id !in interventions); interventions[intervention.id] = intervention
        interventionEvents.getOrPut(intervention.id, ::mutableListOf) += intervention
    }
    override fun submitAnswer(id: String, answer: Value): HumanIntervention {
        val updated = requireNotNull(interventions[id]) { "unknown intervention '$id'" }.answer(answer)
        interventions[id] = updated
        interventionEvents.getOrPut(id, ::mutableListOf) += updated
        return updated
    }
    override fun intervention(id: String): HumanIntervention? = interventions[id]
    override fun interventionHistory(id: String): List<HumanIntervention> = interventionEvents[id].orEmpty().toList()
    override fun recordedProposal(invocationId: InvocationId): RecoveryProposalRecord? = proposals.lastOrNull { it.first.invocationId == invocationId }
        ?.let { RecoveryProposalRecord(it.first, it.second) }
    override fun recordedDecision(invocationId: InvocationId): RecoveryDecisionRecord? = decisions.lastOrNull { it.request.invocationId == invocationId }
}

sealed interface RecoveryOutcome {
    data class Executed(val proposal: RecoveryProposal, val replayed: Boolean) : RecoveryOutcome
    data class Paused(val intervention: HumanIntervention, val replayed: Boolean) : RecoveryOutcome
    data class HumanAnswered(val intervention: HumanIntervention, val replayed: Boolean) : RecoveryOutcome
    data class Refused(val proposal: RecoveryProposal, val validation: RecoveryValidation, val replayed: Boolean) : RecoveryOutcome
}

/**
 * Durable recovery boundary. A recorded decision is authoritative after restart:
 * the proposal supplier (and therefore the recovery agent) is never called again.
 * The executor is entered only after an accepted decision has been persisted.
 */
class RecoveryCoordinator(
    private val ledger: RecoveryLedger,
    private val now: () -> Instant,
) {
    fun recover(
        request: RecoveryRequest,
        policy: RecoveryPolicy,
        recoveryProviderCapabilities: Set<String>,
        descriptor: io.workflow.provider.AgenticDescriptor,
        grantedCapabilities: Set<String>,
        deterministic: () -> RecoveryProposal? = { null },
        propose: () -> RecoveryProposal,
        execute: (RecoveryProposal) -> Unit,
    ): RecoveryOutcome {
        ledger.recordedDecision(request.invocationId)?.let { recorded ->
            return apply(recorded.proposal, recorded.validation, request, execute, replayed = true)
        }
        // Configured deterministic fallback/compensation is always considered
        // before crossing the nondeterministic recovery-provider boundary.
        val recordedProposal = ledger.recordedProposal(request.invocationId)
        val proposal = recordedProposal?.proposal ?: (deterministic() ?: propose()).also { ledger.recordProposal(request, it) }
        val validation = RecoveryValidator.validate(
            proposal,
            request,
            policy,
            recoveryProviderCapabilities,
            AgentBudgetMeter(policy.budget, now()),
            AgentCapabilityGate(descriptor, grantedCapabilities),
            now(),
        )
        // The decision is durably visible before any accepted action is executed.
        ledger.recordDecision(request, proposal, validation)
        return apply(proposal, validation, request, execute, replayed = false)
    }

    private fun apply(
        proposal: RecoveryProposal,
        validation: RecoveryValidation,
        request: RecoveryRequest,
        execute: (RecoveryProposal) -> Unit,
        replayed: Boolean,
    ): RecoveryOutcome {
        if (!validation.accepted) return RecoveryOutcome.Refused(proposal, validation, replayed)
        if (proposal is RecoveryProposal.RequestHuman) {
            val existing = ledger.intervention(interventionId(request.invocationId))
            val intervention = existing ?: HumanIntervention(
                interventionId(request.invocationId), request.activationId, proposal.question, proposal.choices,
            ).also(ledger::pause)
            return if (intervention.state == HumanInterventionState.ANSWERED) {
                RecoveryOutcome.HumanAnswered(intervention, replayed)
            } else RecoveryOutcome.Paused(intervention, replayed)
        }
        execute(proposal)
        return RecoveryOutcome.Executed(proposal, replayed)
    }

    private fun interventionId(invocationId: InvocationId): String = "human-${invocationId.value}"
}

internal fun encodeRecoveryRequest(request: RecoveryRequest): String = CanonicalValueJson.encode(Value.ObjectValue(mapOf(
    "executionId" to Value.StringValue(request.executionId.value),
    "activationId" to Value.StringValue(request.activationId),
    "invocationId" to Value.StringValue(request.invocationId.value),
    "failure" to request.failure,
    "dependencyRevisions" to revisionValue(request.dependencyRevisions),
    "currentDependencyRevisions" to revisionValue(request.currentDependencyRevisions),
    "requiredCapabilities" to Value.ArrayValue(request.requiredCapabilities.sorted().map(Value::StringValue)),
)))

internal fun decodeRecoveryRequest(encoded: String): RecoveryRequest {
    val fields = (CanonicalValueJson.decode(encoded) as Value.ObjectValue).fields
    fun revisions(name: String): Map<String, AssignmentId> =
        ((fields.getValue(name) as Value.ObjectValue).fields).mapValues { AssignmentId((it.value as Value.StringValue).value) }
    return RecoveryRequest(
        activationId = (fields.getValue("activationId") as Value.StringValue).value,
        invocationId = InvocationId((fields.getValue("invocationId") as Value.StringValue).value),
        failure = fields.getValue("failure"),
        dependencyRevisions = revisions("dependencyRevisions"),
        currentDependencyRevisions = revisions("currentDependencyRevisions"),
        requiredCapabilities = (fields.getValue("requiredCapabilities") as Value.ArrayValue).values.map { (it as Value.StringValue).value }.toSet(),
        executionId = ExecutionId((fields.getValue("executionId") as Value.StringValue).value),
    )
}

internal fun encodeRecoveryProposal(proposal: RecoveryProposal): String = CanonicalValueJson.encode(Value.ObjectValue(buildMap {
    put("action", Value.StringValue(proposal.action().name))
    when (proposal) {
        is RecoveryProposal.Retry -> put("adjustment", proposal.adjustment)
        is RecoveryProposal.Reconcile -> put("query", proposal.query)
        is RecoveryProposal.Compensate -> { put("operation", Value.StringValue(proposal.operation)); put("input", proposal.input) }
        is RecoveryProposal.Substitute -> { put("providerId", Value.StringValue(proposal.providerId)); put("inputAdjustment", proposal.inputAdjustment) }
        is RecoveryProposal.Emit -> put("value", proposal.value)
        is RecoveryProposal.RequestHuman -> { put("question", Value.StringValue(proposal.question)); put("choices", Value.ArrayValue(proposal.choices)) }
        is RecoveryProposal.Skip -> put("justification", Value.StringValue(proposal.justification))
        is RecoveryProposal.Abort -> put("reason", Value.StringValue(proposal.reason))
    }
}))

internal fun decodeRecoveryProposal(encoded: String): RecoveryProposal {
    val fields = (CanonicalValueJson.decode(encoded) as Value.ObjectValue).fields
    fun string(name: String) = (fields.getValue(name) as Value.StringValue).value
    return when (RecoveryAction.valueOf(string("action"))) {
        RecoveryAction.RETRY -> RecoveryProposal.Retry(fields.getValue("adjustment"))
        RecoveryAction.RECONCILE -> RecoveryProposal.Reconcile(fields.getValue("query"))
        RecoveryAction.COMPENSATE -> RecoveryProposal.Compensate(string("operation"), fields.getValue("input"))
        RecoveryAction.SUBSTITUTE -> RecoveryProposal.Substitute(string("providerId"), fields.getValue("inputAdjustment"))
        RecoveryAction.EMIT -> RecoveryProposal.Emit(fields.getValue("value"))
        RecoveryAction.REQUEST_HUMAN -> RecoveryProposal.RequestHuman(string("question"), (fields.getValue("choices") as Value.ArrayValue).values)
        RecoveryAction.SKIP -> RecoveryProposal.Skip(string("justification"))
        RecoveryAction.ABORT -> RecoveryProposal.Abort(string("reason"))
    }
}

internal fun encodeHumanIntervention(intervention: HumanIntervention): String = CanonicalValueJson.encode(Value.ObjectValue(mapOf(
    "id" to Value.StringValue(intervention.id),
    "activationId" to Value.StringValue(intervention.activationId),
    "question" to Value.StringValue(intervention.question),
    "choices" to Value.ArrayValue(intervention.choices),
    "state" to Value.StringValue(intervention.state.name),
    "answer" to (intervention.answer ?: Value.Null),
)))

internal fun decodeHumanIntervention(encoded: String): HumanIntervention {
    val fields = (CanonicalValueJson.decode(encoded) as Value.ObjectValue).fields
    fun string(name: String) = (fields.getValue(name) as Value.StringValue).value
    return HumanIntervention(
        id = string("id"), activationId = string("activationId"), question = string("question"),
        choices = (fields.getValue("choices") as Value.ArrayValue).values,
        state = HumanInterventionState.valueOf(string("state")),
        answer = fields.getValue("answer").takeUnless { it == Value.Null },
    )
}

private fun revisionValue(revisions: Map<String, AssignmentId>): Value.ObjectValue = Value.ObjectValue(
    revisions.toSortedMap().mapValues { Value.StringValue(it.value.value) },
)
