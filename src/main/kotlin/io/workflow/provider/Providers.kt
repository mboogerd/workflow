package io.workflow.provider

import io.workflow.core.Value
import io.workflow.core.ValueSchema
import io.workflow.core.InvocationId
import io.workflow.core.AttemptId

/** The effect classification used by a provider descriptor. */
enum class EffectClass { PURE, READ, EFFECT, AGENTIC }

/** The lifecycle capabilities advertised by a provider implementation. */
data class ProviderLifecycle(
    val emits: Boolean = true,
    val completes: Boolean = true,
    val fails: Boolean = true,
    val supportsOpenActivation: Boolean = true,
    val supportsTimeout: Boolean = false,
    val supportsCancellation: Boolean = false,
)

enum class ReconciliationMode {
    IDEMPOTENT_BY_INVOCATION,
    QUERY_BY_INVOCATION,
    HUMAN_INTERVENTION,
}

data class IdempotencyContract(
    val mode: ReconciliationMode = ReconciliationMode.IDEMPOTENT_BY_INVOCATION,
    val key: String = "logical-invocation-id",
)

/** A descriptor points at an implementation but contains no implementation code. */
data class ProviderDescriptor(
    val providerId: String,
    val version: Int,
    val inputSchema: ValueSchema,
    val emissionSchema: ValueSchema,
    val configurationSchema: ValueSchema = ValueSchema.Object(emptyMap()),
    val errorSchema: ValueSchema = ValueSchema.Any,
    val effectClass: EffectClass = EffectClass.PURE,
    val capabilities: Set<String> = emptySet(),
    val secrets: Set<String> = emptySet(),
    val lifecycle: ProviderLifecycle = ProviderLifecycle(),
    val idempotency: IdempotencyContract = IdempotencyContract(),
    val implementationBinding: String = "in-process",
    val protocolFormatVersion: Int = 1,
    /** Configuration fields which must be known at deployment time. */
    val deploymentStaticConfigFields: Set<String> = emptySet(),
    val policySchema: ValueSchema = ValueSchema.Object(emptyMap()),
) {
    init {
        require(providerId.isNotBlank()) { "provider id must not be blank" }
        require(version > 0) { "provider version must be positive" }
        require(protocolFormatVersion > 0) { "provider protocol format version must be positive" }
    }

    val id: String get() = providerId

    /** Empty means that every declared configuration field is deployment-static. */
    fun isDeploymentStatic(field: String): Boolean =
        deploymentStaticConfigFields.isEmpty() || field in deploymentStaticConfigFields
}

data class ProviderKey(val providerId: String, val version: Int) {
    override fun toString(): String = "$providerId@$version"
}

/** The language-neutral request crossing the provider invocation boundary. */
data class ProviderInvocationRequest(
    val providerId: String,
    val providerVersion: Int,
    val invocationId: InvocationId,
    val attemptId: AttemptId,
    val input: Value,
    val config: Value,
    val idempotencyKey: String = invocationId.value,
) {
    val key: ProviderKey get() = ProviderKey(providerId, providerVersion)
    val version: Int get() = providerVersion
}

/** Ordered messages returned by one invocation. */
sealed interface ProviderLifecycleMessage {
    data class Emission(
        val value: Value,
        val emissionId: String,
        val correlationId: String? = null,
    ) : ProviderLifecycleMessage

    data object Completed : ProviderLifecycleMessage
    data object Open : ProviderLifecycleMessage
    data class Failed(val error: Value) : ProviderLifecycleMessage
}

fun interface ProviderImplementation {
    fun invoke(request: ProviderInvocationRequest): Iterable<ProviderLifecycleMessage>
}

data class RegisteredProvider(
    val descriptor: ProviderDescriptor,
    val implementation: ProviderImplementation? = null,
) {
    val key: ProviderKey get() = ProviderKey(descriptor.providerId, descriptor.version)
}

/** Explicit, exact-match registry. It deliberately has no global fallback. */
class ProviderRegistry(
    private val supportedProtocolFormatVersions: Set<Int> = setOf(1),
) {
    private val registrations = linkedMapOf<ProviderKey, RegisteredProvider>()

    @Synchronized
    fun register(descriptor: ProviderDescriptor, implementation: ProviderImplementation? = null): RegisteredProvider {
        require(descriptor.protocolFormatVersion in supportedProtocolFormatVersions) {
            "provider ${descriptor.providerId}@${descriptor.version} uses unsupported protocol format version ${descriptor.protocolFormatVersion}"
        }
        val key = ProviderKey(descriptor.providerId, descriptor.version)
        require(key !in registrations) { "provider ${key} is already registered" }
        return RegisteredProvider(descriptor, implementation).also { registrations[key] = it }
    }

    @Synchronized
    fun resolve(providerId: String, version: Int): RegisteredProvider? =
        registrations[ProviderKey(providerId, version)]

    fun resolve(key: ProviderKey): RegisteredProvider? = resolve(key.providerId, key.version)

    @Synchronized
    fun contains(providerId: String, version: Int): Boolean = ProviderKey(providerId, version) in registrations

    @Synchronized
    fun descriptors(): List<ProviderDescriptor> = registrations.values.map { it.descriptor }

    companion object {
        fun empty(): ProviderRegistry = ProviderRegistry()
    }
}

typealias ProviderContract = ProviderDescriptor
typealias InvocationRequest = ProviderInvocationRequest
typealias LifecycleMessage = ProviderLifecycleMessage
