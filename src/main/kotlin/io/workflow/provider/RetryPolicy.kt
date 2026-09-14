package io.workflow.provider

import io.workflow.core.Value
import java.time.Duration

/**
 * The v1 execution policy carried by a provider binding.  All durations are
 * integer milliseconds so a recorded decision can be replayed exactly.
 */
data class ProviderExecutionPolicy(
    val maximumAttempts: Int = 1,
    val retryableErrorClasses: Set<String> = emptySet(),
    val backoffSchedule: List<Duration> = emptyList(),
    val attemptTimeout: Duration? = null,
    val activationDeadline: Duration? = null,
    val cancellationGrace: Duration? = null,
) {
    init {
        require(maximumAttempts >= 1) { "maximumAttempts must be at least one" }
        require(backoffSchedule.all { !it.isNegative }) { "backoff schedule durations must not be negative" }
        require(attemptTimeout?.isNegative != true) { "attempt timeout must not be negative" }
        require(activationDeadline?.isNegative != true) { "activation deadline must not be negative" }
        require(cancellationGrace?.isNegative != true) { "cancellation grace must not be negative" }
    }

    fun backoffForRetry(attemptNumber: Int): Duration =
        backoffSchedule.getOrElse(attemptNumber - 1) { Duration.ZERO }

    companion object {
        val fieldNames = setOf(
            "maximum-attempts", "retryable-error-classes", "backoff-schedule-millis",
            "attempt-timeout-millis", "activation-deadline-millis", "cancellation-grace-millis",
        )

        /** Parse the exact v1 policy shape retained unchanged in canonical IR. */
        fun from(value: Value): ProviderExecutionPolicy {
            val fields = (value as? Value.ObjectValue)?.fields
                ?: throw IllegalArgumentException("policy must be an object")
            val unknown = fields.keys - fieldNames
            require(unknown.isEmpty()) { "unknown fields: ${unknown.sorted().joinToString()}" }
            fun integer(name: String): Long? = fields[name]?.let { field ->
                require(field is Value.IntegerValue) { "$name must be an integer" }
                try { field.value.longValueExact() } catch (_: ArithmeticException) {
                    throw IllegalArgumentException("$name is outside the 64-bit integer range")
                }
            }
            fun duration(name: String): Duration? = integer(name)?.let(Duration::ofMillis)
            fun strings(name: String): Set<String> = fields[name]?.let { field ->
                require(field is Value.ArrayValue) { "$name must be an array of strings" }
                field.values.map { entry ->
                    require(entry is Value.StringValue && entry.value.isNotBlank()) { "$name must contain non-blank strings" }
                    entry.value
                }.toSet()
            } ?: emptySet()
            fun durations(name: String): List<Duration> = fields[name]?.let { field ->
                require(field is Value.ArrayValue) { "$name must be an array of integer milliseconds" }
                field.values.map { entry ->
                    require(entry is Value.IntegerValue) { "$name must be an array of integer milliseconds" }
                    val millis = try { entry.value.longValueExact() } catch (_: ArithmeticException) {
                        throw IllegalArgumentException("$name contains a value outside the 64-bit integer range")
                    }
                    Duration.ofMillis(millis)
                }
            } ?: emptyList()
            return ProviderExecutionPolicy(
                maximumAttempts = (integer("maximum-attempts") ?: 1L).also {
                    require(it <= Int.MAX_VALUE) { "maximum-attempts is too large" }
                }.toInt(),
                retryableErrorClasses = strings("retryable-error-classes"),
                backoffSchedule = durations("backoff-schedule-millis"),
                attemptTimeout = duration("attempt-timeout-millis"),
                activationDeadline = duration("activation-deadline-millis"),
                cancellationGrace = duration("cancellation-grace-millis"),
            )
        }
    }
}

fun Value.errorClass(): String? = (this as? Value.ObjectValue)?.fields?.let { fields ->
    ((fields["class"] ?: fields["errorClass"] ?: fields["error-class"]) as? Value.StringValue)?.value
}
