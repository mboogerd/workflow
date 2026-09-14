package io.workflow.runtime

import io.workflow.core.AssignmentId
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
)

data class RecoveryRequest(
    val activationId: String,
    val invocationId: InvocationId,
    val failure: Value,
    val dependencyRevisions: Map<String, AssignmentId>,
    val currentDependencyRevisions: Map<String, AssignmentId>,
    val requiredCapabilities: Set<String> = emptySet(),
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
        if (!authority.containsAll(request.requiredCapabilities)) return RecoveryValidation(false, "RECOVERY_AUTHORITY_EXCEEDED")
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
}

class InMemoryRecoveryLedger : RecoveryLedger {
    data class Decision(val request: RecoveryRequest, val proposal: RecoveryProposal, val validation: RecoveryValidation)
    val proposals = mutableListOf<Pair<RecoveryRequest, RecoveryProposal>>()
    val decisions = mutableListOf<Decision>()
    private val interventions = linkedMapOf<String, HumanIntervention>()
    override fun recordProposal(request: RecoveryRequest, proposal: RecoveryProposal) { proposals += request to proposal }
    override fun recordDecision(request: RecoveryRequest, proposal: RecoveryProposal, validation: RecoveryValidation) { decisions += Decision(request, proposal, validation) }
    override fun pause(intervention: HumanIntervention) { require(intervention.id !in interventions); interventions[intervention.id] = intervention }
    override fun submitAnswer(id: String, answer: Value): HumanIntervention {
        val updated = requireNotNull(interventions[id]) { "unknown intervention '$id'" }.answer(answer)
        interventions[id] = updated
        return updated
    }
    override fun intervention(id: String): HumanIntervention? = interventions[id]
}
