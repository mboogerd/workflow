package io.workflow.conformance

import io.workflow.compiler.WorkflowCompiler
import io.workflow.core.*
import io.workflow.demo.DemoProviders
import io.workflow.provider.*
import io.workflow.runtime.*
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.json.*

data class ConformanceSummary(val executed: Int, val passed: Int, val failures: List<String>)

fun runConformance(corpus: Path = Path.of("conformance", "v1")): ConformanceSummary {
    val scenarios = Files.list(corpus).use { paths -> paths.filter {
        it.fileName.toString().endsWith(".json") && it.fileName.toString() !in setOf("schema.json", "traceability.json")
    }.sorted().toList() }
    require(scenarios.isNotEmpty()) { "conformance corpus contains zero scenarios" }
    val failures = mutableListOf<String>()
    scenarios.forEach { file -> runCatching { execute(file) }.onFailure { failures += "${file.fileName}: ${it.message}" } }
    return ConformanceSummary(scenarios.size, scenarios.size - failures.size, failures)
}

private fun execute(file: Path) {
    val scenario = Json.parseToJsonElement(Files.readString(file)).jsonObject
    validateScenario(scenario)
    require(scenario.int("formatVersion") == 1) { "unsupported scenario format" }
    require(scenario["requirementReferences"]?.jsonArray?.isNotEmpty() == true) { "missing normative requirement references" }
    val yaml = scenario["workflowYaml"]?.jsonPrimitive?.content ?: scenario["workflow"]?.jsonPrimitive?.content?.let { Files.readString(Path.of(it)) }
    if (yaml == null) {
        require(scenario["recovery"] != null) { "scenario has neither workflow nor recovery operation" }
        executeRecovery(scenario)
        return
    }
    val initialRegistry = registry(scenario)
    val compiled = WorkflowCompiler(initialRegistry).compile(yaml)
    scenario["expectedFailure"]?.jsonPrimitive?.contentOrNull?.let { expected ->
        require(!compiled.isValid && (expected == "any" || compiled.diagnostics.joinToString().contains(expected))) { "expected compile failure $expected" }
        return
    }
    require(compiled.isValid) { compiled.diagnostics.joinToString() }
    val parameters = scenario["parameters"]?.let { CanonicalValueJson.decode(it.toString()) as Value.ObjectValue }?.fields ?: emptyMap()
    val executionId = ExecutionId("corpus-${scenario.string("id")}")
    if (scenario["restart"]?.jsonPrimitive?.booleanOrNull == true) {
        val database = Files.createTempFile("workflow-conformance", ".db")
        Files.deleteIfExists(database)
        try {
            SqliteJournalStore(database, FixedClock(Instant.EPOCH), DeterministicIdSource("store-")) .use { store ->
                runner(initialRegistry, store, scenario.string("id")).start(compiled.ir!!, parameters, executionId)
            }
            SqliteJournalStore(database, FixedClock(Instant.EPOCH), DeterministicIdSource("resume-store-")) .use { store ->
                val resumedRegistry = registry(scenario)
                val host = runner(resumedRegistry, store, "${scenario.string("id")}-resume").resume(executionId)
                emitEvents(host, scenario)
                assertExecution(host.result(), store, scenario, compiled.ir!!)
            }
        } finally { Files.deleteIfExists(database) }
    } else {
        val store = InMemoryJournalStore(FixedClock(Instant.EPOCH), DeterministicIdSource("store-"))
        val host = runner(initialRegistry, store, scenario.string("id")).start(compiled.ir!!, parameters, executionId)
        emitEvents(host, scenario)
        assertExecution(host.result(), store, scenario, compiled.ir!!)
    }
    if (scenario["recovery"] != null) executeRecovery(scenario)
}

private fun validateScenario(scenario: JsonObject) {
    val allowed = setOf(
        "id", "formatVersion", "requirementReferences", "workflow", "workflowYaml", "parameters", "providerScripts",
        "events", "expectedOutputs", "expectedValues", "expectedRecords", "expectedFailure", "expectSuccessful", "restart", "recovery", "replay",
    )
    require(scenario.keys.all { it in allowed }) { "unknown scenario fields ${scenario.keys - allowed}" }
    require(scenario["id"]?.jsonPrimitive?.contentOrNull?.isNotBlank() == true) { "scenario id is required" }
    require(scenario["formatVersion"]?.jsonPrimitive?.intOrNull == 1) { "unsupported scenario format" }
    require(scenario["requirementReferences"]?.jsonArray?.isNotEmpty() == true) { "requirementReferences must be nonempty" }
    require(listOf("workflow", "workflowYaml", "recovery").any(scenario::containsKey)) { "scenario must declare workflow, workflowYaml, or recovery" }
    scenario["providerScripts"]?.jsonArray?.forEach { element ->
        val script = element.jsonObject
        require(script.keys.all { it in setOf("id", "effectClass", "emissionSchema", "attempts") }) { "unknown provider script field" }
        require(script["id"]?.jsonPrimitive?.contentOrNull?.isNotBlank() == true && script["attempts"]?.jsonArray?.isNotEmpty() == true) {
            "provider script requires id and nonempty attempts"
        }
    }
}

private fun runner(registry: ProviderRegistry, store: WorkflowJournalStore, id: String) = InMemoryWorkflowRunner(
    compiler = WorkflowCompiler(registry), providerRegistry = registry, journal = store,
    idSource = DeterministicIdSource("corpus-$id-"), clock = FixedClock(Instant.EPOCH), workerCount = 1,
)

private fun emitEvents(host: HostedWorkflowExecution, scenario: JsonObject) {
    scenario["events"]?.jsonArray?.forEachIndexed { index, event ->
        host.emit(host.openProviders().single().invocationId, CanonicalValueJson.decode(event.toString()), EmissionId("corpus-event-$index"))
    }
}

private fun assertExecution(result: WorkflowRunResult, journal: WorkflowJournalStore, scenario: JsonObject, workflow: io.workflow.compiler.WorkflowIrDocument) {
    val expectedSuccess = scenario["expectSuccessful"]?.jsonPrimitive?.booleanOrNull ?: true
    require(result.isSuccessful == expectedSuccess) { "success=${result.isSuccessful}, failures=${result.failures.joinToString()}" }
    scenario["expectedOutputs"]?.jsonObject?.forEach { (name, value) ->
        require(result.outputs[name]?.value == CanonicalValueJson.decode(value.toString())) { "output $name did not match" }
    }
    scenario["expectedValues"]?.jsonArray?.forEach { element ->
        val expectation = element.jsonObject
        val path = expectation.string("path").split('.')
        var actual = requireNotNull(result.outputs[path.first()]?.value) { "missing output ${path.first()}" }
        path.drop(1).forEach { actual = (actual as Value.ObjectValue).fields.getValue(it) }
        require(actual == CanonicalValueJson.decode(expectation.getValue("value").toString())) { "value ${expectation.string("path")} did not match" }
    }
    val records = scenario["expectedRecords"]?.jsonObject
    records?.get("minimumAssignments")?.jsonPrimitive?.intOrNull?.let { require(journal.assignments().size >= it) }
    records?.get("providerEventTypes")?.jsonArray?.forEach { type ->
        require(journal.providerEvents().any { it.type.name == type.jsonPrimitive.content }) { "missing provider event ${type.jsonPrimitive.content}" }
    }
    records?.get("providerAttemptCount")?.jsonPrimitive?.intOrNull?.let { require(journal.providerAttempts().size == it) }
    if (scenario["replay"]?.jsonPrimitive?.booleanOrNull == true) {
        val replay = WorkflowReplay.replayOne(journal, result.executionId).outputs(workflow)
        scenario["expectedOutputs"]?.jsonObject?.forEach { (name, value) ->
            require(replay.getValue(name).value == CanonicalValueJson.decode(value.toString())) { "replay output $name did not match" }
        }
    }
}

private fun registry(scenario: JsonObject): ProviderRegistry = DemoProviders.registry().also { providers ->
    scenario["providerScripts"]?.jsonArray?.forEach { element ->
        val script = element.jsonObject
        val attempts = script.getValue("attempts").jsonArray
        val calls = AtomicInteger()
        val implementation = ProviderImplementation { request ->
            val step = attempts[minOf(calls.getAndIncrement(), attempts.lastIndex)].jsonObject
            step["failureClass"]?.jsonPrimitive?.content?.let { failure ->
                listOf(ProviderLifecycleMessage.Failed(Value.ObjectValue(mapOf("class" to Value.StringValue(failure)))))
            } ?: listOf(
                ProviderLifecycleMessage.Emission(CanonicalValueJson.decode(step.getValue("emit").toString()), EmissionId("script-${script.string("id")}-${calls.get()}"), request.invocationId, request.attemptId),
                ProviderLifecycleMessage.Completed,
            )
        }
        val effectClass = EffectClass.valueOf(script["effectClass"]?.jsonPrimitive?.content ?: "PURE")
        providers.register(
            ProviderDescriptor(
                script.string("id"), 1, ValueSchema.Any, schema(script["emissionSchema"]?.jsonPrimitive?.content ?: "string"),
                configurationSchema = ValueSchema.Any, effectClass = effectClass,
                idempotency = if (effectClass in setOf(EffectClass.EFFECT, EffectClass.AGENTIC)) IdempotencyContract(ReconciliationMode.IDEMPOTENT_BY_INVOCATION) else null,
            ), implementation,
        )
    }
}

private fun schema(name: String): ValueSchema = when (name) {
    "any" -> ValueSchema.Any
    "string" -> ValueSchema.String
    "integer" -> ValueSchema.Integer
    "boolean" -> ValueSchema.Boolean
    else -> error("unsupported scripted schema '$name'")
}

private fun executeRecovery(scenario: JsonObject) {
    val recovery = scenario.getValue("recovery").jsonObject
    val action = RecoveryAction.valueOf(recovery.string("action"))
    val proposal = when (action) {
        RecoveryAction.SKIP -> RecoveryProposal.Skip("corpus")
        RecoveryAction.ABORT -> RecoveryProposal.Abort("corpus")
        RecoveryAction.EMIT -> RecoveryProposal.Emit(Value.StringValue("recovered"))
        else -> error("corpus recovery action $action is not configured")
    }
    val invocationId = InvocationId("corpus-recovery-${scenario.string("id")}")
    val request = RecoveryRequest("activation", invocationId, Value.StringValue("failure"), emptyMap(), emptyMap())
    val descriptor = AgenticDescriptor("pinned", "corpus-v1", "corpus-v1", mapOf("recovery:${action.name.lowercase()}" to AgentToolDescriptor(EffectClass.PURE)))
    val ledger = InMemoryRecoveryLedger()
    var proposals = 0
    val outcome = RecoveryCoordinator(ledger) { Instant.EPOCH }.recover(
        request, RecoveryPolicy(setOf(action), emptySet()), emptySet(), descriptor, emptySet(),
        propose = { proposals++; proposal }, execute = {},
    )
    require(outcome is RecoveryOutcome.Executed && proposals == 1) { "recovery proposal was not accepted" }
    val replayed = RecoveryCoordinator(ledger) { Instant.EPOCH }.recover(
        request, RecoveryPolicy(setOf(action), emptySet()), emptySet(), descriptor, emptySet(),
        propose = { error("replay invoked recovery agent") }, execute = {},
    )
    require(replayed is RecoveryOutcome.Executed && replayed.replayed) { "recovery decision was not replayed" }
}

private fun JsonObject.string(name: String) = getValue(name).jsonPrimitive.content
private fun JsonObject.int(name: String) = getValue(name).jsonPrimitive.int

fun main(args: Array<String>) {
    val summary = runConformance(Path.of(args.firstOrNull() ?: "conformance/v1"))
    println("conformance executed=${summary.executed} passed=${summary.passed} failed=${summary.failures.size}")
    summary.failures.forEach { System.err.println(it) }
    check(summary.failures.isEmpty()) { "conformance failures" }
}
