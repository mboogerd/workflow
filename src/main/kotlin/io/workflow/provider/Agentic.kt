package io.workflow.provider

import io.workflow.core.Value
import io.workflow.core.ValueSchema
import java.time.Duration
import java.time.Instant

/** Immutable, auditable description of the strategy used by an agentic provider. */
data class AgenticDescriptor(
    val modelSelectionPolicy: String,
    val strategyVersion: String,
    val promptVersion: String,
    val tools: Map<String, AgentToolDescriptor>,
    val requiredCapabilities: Set<String> = emptySet(),
    val requiredSecrets: Set<String> = emptySet(),
    val budgets: AgentBudget = AgentBudget(),
    val outputAcceptance: AgentOutputAcceptance = AgentOutputAcceptance(),
    val persistence: AgentPersistence = AgentPersistence(),
    val cancellation: AgentCancellation = AgentCancellation(),
) {
    init {
        require(modelSelectionPolicy.isNotBlank()) { "agentic model selection policy must not be blank" }
        require(strategyVersion.isNotBlank()) { "agentic strategy version must not be blank" }
        require(promptVersion.isNotBlank()) { "agentic prompt version must not be blank" }
        require(tools.keys.none(String::isBlank)) { "agentic tool names must not be blank" }
    }
}

/** Useful for deterministic/fake agents; production descriptors should use a meaningful pinned version. */
fun deterministicAgenticDescriptor(strategyVersion: String = "deterministic-v1"): AgenticDescriptor = AgenticDescriptor(
    modelSelectionPolicy = "pinned",
    strategyVersion = strategyVersion,
    promptVersion = strategyVersion,
    tools = emptyMap(),
)

data class AgentToolDescriptor(
    val effectClass: EffectClass,
    val requiredCapabilities: Set<String> = emptySet(),
)

data class AgentBudget(
    val maxTokens: Long = Long.MAX_VALUE,
    val maxCostMicros: Long = Long.MAX_VALUE,
    val maxDuration: Duration = Duration.ofMillis(Long.MAX_VALUE),
    val maxToolCalls: Int = Int.MAX_VALUE,
    val maxIterations: Int = Int.MAX_VALUE,
) {
    init {
        require(maxTokens >= 0 && maxCostMicros >= 0 && !maxDuration.isNegative && maxToolCalls >= 0 && maxIterations >= 0) {
            "agentic budgets must be non-negative"
        }
    }
}

data class AgentOutputAcceptance(
    /** An optional stricter output contract in addition to the provider emission schema. */
    val schema: ValueSchema? = null,
    val requireNonNull: Boolean = true,
)

data class AgentPersistence(
    val granularity: AgentPersistenceGranularity = AgentPersistenceGranularity.ATTEMPT,
    val resumable: Boolean = true,
    val retainTrace: Boolean = true,
    val retainArtifacts: Boolean = false,
)

enum class AgentPersistenceGranularity { ACTIVATION, ATTEMPT, ITERATION }

data class AgentCancellation(val cooperative: Boolean = true, val retainPartialTrace: Boolean = true)

/** Runtime-pinned facts; deliberately excludes secret values. */
data class ResolvedAgentMetadata(
    val modelId: String,
    val settings: Map<String, String> = emptyMap(),
    val resolvedAt: Instant,
) {
    init { require(modelId.isNotBlank()) { "resolved agent model id must not be blank" } }

    fun safeSummary(): String = buildString {
        append("model=").append(modelId)
        settings.toSortedMap().forEach { (key, value) ->
            if (!key.contains("secret", ignoreCase = true) && !key.contains("token", ignoreCase = true)) append("; ").append(key).append('=').append(value)
        }
    }
}

sealed interface AgentOperationResult {
    data object Accepted : AgentOperationResult
    data class Refused(val code: String, val message: String) : AgentOperationResult
}

/** Provider-facing meter. A refused operation never consumes a partially valid budget. */
class AgentBudgetMeter(private val limit: AgentBudget, private val startedAt: Instant, private val observer: (AgentOperationResult) -> Unit = {}) {
    var tokens: Long = 0; private set
    var costMicros: Long = 0; private set
    var toolCalls: Int = 0; private set
    var iterations: Int = 0; private set

    fun consume(tokens: Long = 0, costMicros: Long = 0, toolCalls: Int = 0, iterations: Int = 0, now: Instant): AgentOperationResult {
        val result = when {
            tokens < 0 || costMicros < 0 || toolCalls < 0 || iterations < 0 -> AgentOperationResult.Refused("INVALID_BUDGET_DELTA", "budget deltas must be non-negative")
            Duration.between(startedAt, now) > limit.maxDuration -> AgentOperationResult.Refused("TIME_BUDGET_EXHAUSTED", "agent time budget is exhausted")
            this.tokens + tokens > limit.maxTokens -> AgentOperationResult.Refused("TOKEN_BUDGET_EXHAUSTED", "agent token budget is exhausted")
            this.costMicros + costMicros > limit.maxCostMicros -> AgentOperationResult.Refused("COST_BUDGET_EXHAUSTED", "agent cost budget is exhausted")
            this.toolCalls + toolCalls > limit.maxToolCalls -> AgentOperationResult.Refused("TOOL_CALL_BUDGET_EXHAUSTED", "agent tool-call budget is exhausted")
            this.iterations + iterations > limit.maxIterations -> AgentOperationResult.Refused("ITERATION_BUDGET_EXHAUSTED", "agent iteration budget is exhausted")
            else -> {
                this.tokens += tokens; this.costMicros += costMicros; this.toolCalls += toolCalls; this.iterations += iterations
                AgentOperationResult.Accepted
            }
        }
        observer(result)
        return result
    }
}

class AgentCapabilityGate(private val descriptor: AgenticDescriptor, grantedCapabilities: Set<String>, private val observer: (AgentOperationResult) -> Unit = {}) {
    private val granted = grantedCapabilities.toSet()

    fun useTool(name: String): AgentOperationResult {
        val tool = descriptor.tools[name]
        val result = if (tool == null) AgentOperationResult.Refused("UNDECLARED_TOOL", "tool '$name' is not declared")
        else {
            val missing = tool.requiredCapabilities + descriptor.requiredCapabilities - granted
            if (missing.isEmpty()) AgentOperationResult.Accepted else AgentOperationResult.Refused("CAPABILITY_REFUSED", "tool '$name' requires ${missing.sorted().joinToString()}")
        }
        observer(result)
        return result
    }
}

data class AgenticInvocationEnvironment(
    val descriptor: AgenticDescriptor,
    val metadata: ResolvedAgentMetadata,
    val budget: AgentBudgetMeter,
    val capabilities: AgentCapabilityGate,
)

/** A fake or real agent can use this explicit boundary; it remains one provider activation. */
interface AgenticProviderImplementation : ProviderImplementation {
    fun resolveMetadata(request: ProviderInvocationRequest, now: Instant): ResolvedAgentMetadata
    fun invokeAgentic(request: ProviderInvocationRequest, environment: AgenticInvocationEnvironment): Iterable<ProviderLifecycleMessage>
    override fun invoke(request: ProviderInvocationRequest): Iterable<ProviderLifecycleMessage> =
        error("agentic providers must be invoked through the agentic runtime boundary")
}
