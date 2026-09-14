package io.workflow.provider

import io.workflow.core.ActivationId
import io.workflow.core.AssignmentId
import io.workflow.core.AttemptId
import io.workflow.core.EmissionId
import io.workflow.core.InvocationId
import io.workflow.core.Value
import io.workflow.core.ValueSchema

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

/** Portable reconciliation protocol for an effect whose reply was lost. */
data class ReconciliationRequest(
    val formatVersion: Int = 1,
    val providerId: String,
    val providerVersion: Int,
    val invocationId: InvocationId,
    val idempotencyKey: String = invocationId.value,
    val attemptId: AttemptId? = null,
) {
    init { require(formatVersion == 1) { "unsupported reconciliation protocol format $formatVersion" } }
}

enum class ReconciliationDisposition {
    DEFINITELY_NOT_APPLIED,
    DEFINITELY_APPLIED,
    STILL_UNKNOWN,
    PROTOCOL_FAILURE,
}

data class ReconciliationResult(
    val formatVersion: Int = 1,
    val disposition: ReconciliationDisposition,
    /** The provider's recorded output when the effect definitely occurred. */
    val recordedResult: Value? = null,
    val diagnostic: String? = null,
) {
    init {
        require(formatVersion == 1) { "unsupported reconciliation protocol format $formatVersion" }
        require(disposition != ReconciliationDisposition.DEFINITELY_APPLIED || recordedResult != null) {
            "a definitely-applied reconciliation result must include the recorded provider result"
        }
    }
}

/** Versions of portable artifacts with which this descriptor is compatible. */
data class ProviderCompatibility(
    val irFormatVersions: Set<Int> = setOf(1),
) {
    init {
        require(irFormatVersions.isNotEmpty()) { "provider compatibility must declare at least one IR format version" }
        require(irFormatVersions.all { it > 0 }) { "provider IR format versions must be positive" }
    }
}

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
    /**
     * Required for effectful/agentic providers.  It remains nullable solely so
     * the compiler can give a source-location diagnostic for legacy descriptors.
     */
    val idempotency: IdempotencyContract? = IdempotencyContract(ReconciliationMode.HUMAN_INTERVENTION),
    val implementationBinding: String = "in-process",
    val protocolFormatVersion: Int = 1,
    val compatibility: ProviderCompatibility = ProviderCompatibility(),
    /** Configuration fields which must be known at deployment time. */
    val deploymentStaticConfigFields: Set<String> = emptySet(),
    /** Descriptors may further constrain policy fields; v1 baseline fields remain portable. */
    val policySchema: ValueSchema = ValueSchema.Object(emptyMap(), additionalFields = true),
) {
    init {
        require(providerId.isNotBlank()) { "provider id must not be blank" }
        require(version > 0) { "provider version must be positive" }
        require(protocolFormatVersion > 0) { "provider protocol format version must be positive" }
        require(implementationBinding.isNotBlank()) { "provider implementation binding must not be blank" }
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
    /** Present when this provider is the selected producer of a match. */
    val parentActivationId: ActivationId? = null,
    val discriminatorRevision: AssignmentId? = null,
) {
    val key: ProviderKey get() = ProviderKey(providerId, providerVersion)
    val version: Int get() = providerVersion
}

/** Ordered messages returned by one invocation. */
sealed interface ProviderLifecycleMessage {
    data class Emission(
        val value: Value,
        val emissionId: EmissionId,
        val invocationId: InvocationId,
        val attemptId: AttemptId,
        val correlationId: String? = null,
    ) : ProviderLifecycleMessage

    data object Completed : ProviderLifecycleMessage
    data object Open : ProviderLifecycleMessage
    data class Failed(val error: Value) : ProviderLifecycleMessage
}

fun interface ProviderImplementation {
    fun invoke(request: ProviderInvocationRequest): Iterable<ProviderLifecycleMessage>
}

/** Optional cooperative control channel for a provider that remains open. */
interface CancellableProviderImplementation : ProviderImplementation {
    /** Returns false when the implementation observed but could not honour cancellation. */
    fun cancel(request: ProviderInvocationRequest): Boolean
}

/** Implemented only by providers declaring QUERY_BY_INVOCATION support. */
interface ReconciliationProviderImplementation : ProviderImplementation {
    fun reconcile(request: ReconciliationRequest): ReconciliationResult
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
    private val supportedIrFormatVersions: Set<Int> = setOf(1),
) {
    private val registrations = linkedMapOf<ProviderKey, RegisteredProvider>()

    @Synchronized
    fun register(descriptor: ProviderDescriptor, implementation: ProviderImplementation? = null): RegisteredProvider {
        require(descriptor.protocolFormatVersion in supportedProtocolFormatVersions) {
            "provider ${descriptor.providerId}@${descriptor.version} uses unsupported protocol format version ${descriptor.protocolFormatVersion}"
        }
        require(descriptor.compatibility.irFormatVersions.any(supportedIrFormatVersions::contains)) {
            "provider ${descriptor.providerId}@${descriptor.version} is incompatible with supported IR format versions ${supportedIrFormatVersions.sorted()}"
        }
        require(implementation == null || descriptor.implementationBinding == "in-process") {
            "provider ${descriptor.providerId}@${descriptor.version} cannot register an in-process implementation for binding '${descriptor.implementationBinding}'"
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

    @Synchronized
    fun isEmpty(): Boolean = registrations.isEmpty()

    companion object {
        fun empty(): ProviderRegistry = ProviderRegistry()
    }
}

typealias ProviderContract = ProviderDescriptor
typealias InvocationRequest = ProviderInvocationRequest
typealias LifecycleMessage = ProviderLifecycleMessage
