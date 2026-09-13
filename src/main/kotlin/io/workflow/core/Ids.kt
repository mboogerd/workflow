package io.workflow.core

@JvmInline value class WorkflowId(val value: String)
@JvmInline value class WorkflowVersionId(val value: String)
typealias VersionId = WorkflowVersionId
@JvmInline value class ExecutionId(val value: String)
@JvmInline value class ContextId(val value: String)
typealias CorrelationContextId = ContextId
@JvmInline value class RegisterId(val value: String)
@JvmInline value class ProducerId(val value: String)
@JvmInline value class JournalBatchId(val value: String)
@JvmInline value class AssignmentId(val value: String)
@JvmInline value class ActivationIntentId(val value: String)
@JvmInline value class ActivationId(val value: String)
@JvmInline value class InvocationId(val value: String)
@JvmInline value class AttemptId(val value: String)
@JvmInline value class EmissionId(val value: String)
