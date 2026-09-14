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
        /** Accept both kebab-case YAML fields and the canonical camel-case IR fields. */
        fun from(value: Value): ProviderExecutionPolicy {
            val fields = (value as? Value.ObjectValue)?.fields ?: return ProviderExecutionPolicy()
            fun field(vararg names: String) = names.firstNotNullOfOrNull(fields::get)
            fun integer(vararg names: String): Long? = (field(*names) as? Value.IntegerValue)?.value?.toLong()
            fun duration(vararg names: String): Duration? = integer(*names)?.let(Duration::ofMillis)
            fun strings(vararg names: String): Set<String> = ((field(*names) as? Value.ArrayValue)?.values ?: emptyList())
                .mapNotNull { (it as? Value.StringValue)?.value }.toSet()
            fun durations(vararg names: String): List<Duration> = ((field(*names) as? Value.ArrayValue)?.values ?: emptyList())
                .mapNotNull { (it as? Value.IntegerValue)?.value?.toLong()?.let(Duration::ofMillis) }
            return ProviderExecutionPolicy(
                maximumAttempts = (integer("maximumAttempts", "maximum-attempts") ?: 1L).toInt(),
                retryableErrorClasses = strings("retryableErrorClasses", "retryable-error-classes"),
                backoffSchedule = durations("backoffScheduleMillis", "backoff-schedule-millis", "backoffSchedule", "backoff-schedule"),
                attemptTimeout = duration("attemptTimeoutMillis", "attempt-timeout-millis"),
                activationDeadline = duration("activationDeadlineMillis", "activation-deadline-millis"),
                cancellationGrace = duration("cancellationGraceMillis", "cancellation-grace-millis"),
            )
        }
    }
}

fun Value.errorClass(): String? = (this as? Value.ObjectValue)?.fields?.let { fields ->
    ((fields["class"] ?: fields["errorClass"] ?: fields["error-class"]) as? Value.StringValue)?.value
}
