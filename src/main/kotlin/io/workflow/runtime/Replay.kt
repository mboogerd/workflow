package io.workflow.runtime

import io.workflow.compiler.WorkflowIrDocument
import io.workflow.core.AssignmentMutation
import io.workflow.core.ContextId
import io.workflow.core.ExecutionId
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A provider-free reconstruction of one durable execution's observable state. */
data class ReplayResult(
    val executionId: ExecutionId,
    val current: Map<RegisterKey, AssignmentMutation>,
    val assignments: List<AssignmentMutation>,
    val activationIntents: List<io.workflow.core.ActivationIntent>,
    val activations: List<ActivationRecord>,
    val providerEvents: List<ProviderLifecycleEvent>,
) {
    fun outputs(workflow: WorkflowIrDocument): Map<String, PublishedOutput> {
        require(workflow.irVersion == 1) { "unsupported workflow IR version ${workflow.irVersion}" }
        require(workflow.hasValidContentHash()) { "workflow IR content hash does not match canonical content" }
        val anonymous = ContextId(InMemoryWorkflowRunner.ANONYMOUS_CONTEXT)
        return workflow.outputs.mapNotNull { name ->
            val register = workflow.registers.firstOrNull { it.name == name } ?: return@mapNotNull null
            current[RegisterKey(executionId, anonymous, register.registerId)]?.let { name to PublishedOutput(it.value, it.revision) }
        }.toMap()
    }

    fun outputsJson(workflow: WorkflowIrDocument? = null): String {
        val outputMap = workflow?.let { outputs(it) }.orEmpty()
        return buildJsonObject {
            put("executionId", executionId.value)
            put("mode", "replay")
            put("assignments", assignments.size)
            put("activationIntents", activationIntents.size)
            put("activations", activations.size)
            put("providerEvents", providerEvents.size)
            put("outputs", JsonObject(outputMap.toSortedMap().mapValues { (_, output) ->
                JsonObject(mapOf(
                    "value" to Json.parseToJsonElement(io.workflow.core.CanonicalValueJson.encode(output.value)),
                    "revision" to JsonPrimitive(output.revision),
                ))
            }))
        }.toString()
    }
}

/** Rebuilds projections from authoritative records without touching providers. */
object WorkflowReplay {
    fun replay(
        journal: WorkflowJournalStore,
        executionId: ExecutionId? = null,
        workflow: WorkflowIrDocument? = null,
    ): List<ReplayResult> {
        journal.validateCompatibility()
        workflow?.let {
            require(it.irVersion == 1) { "unsupported workflow IR version ${it.irVersion}" }
            require(it.hasValidContentHash()) { "workflow IR content hash does not match canonical content" }
        }
        val ids = executionId?.let(::listOf) ?: journal.executionIds().ifEmpty {
            journal.assignments().map { it.executionId }.distinct()
        }
        return ids.map { id ->
            workflow?.let { expected ->
                val binding = journal.executionBinding(id)
                require(binding == null || (binding.workflowVersionId == expected.workflowVersionId && binding.contentHash == expected.contentHash)) {
                    "execution '${id.value}' is bound to incompatible workflow content"
                }
            }
            replayExecution(journal, id)
        }
    }

    fun replayOne(journal: WorkflowJournalStore, executionId: ExecutionId): ReplayResult =
        replayExecution(journal, executionId)

    private fun replayExecution(journal: WorkflowJournalStore, executionId: ExecutionId): ReplayResult {
        val assignments = journal.assignments().filter { it.executionId == executionId }
        val rebuilt = CurrentViews.rebuild(assignments.map { assignment ->
            io.workflow.core.JournalBatch(
                io.workflow.core.JournalBatchId("replay-${assignment.assignmentId.value}"),
                listOf(assignment),
                assignment.occurredAt,
            )
        })
        return ReplayResult(
            executionId = executionId,
            current = rebuilt,
            assignments = assignments,
            activationIntents = journal.activationIntents().filter { it.executionId == executionId },
            activations = journal.activations().filter { it.executionId == executionId },
            providerEvents = journal.providerEvents().filter { it.executionId == executionId },
        )
    }
}

typealias ReplayEngine = WorkflowReplay
