package io.workflow.conformance

import io.workflow.compiler.WorkflowCompiler
import io.workflow.core.CanonicalValueJson
import io.workflow.core.DeterministicIdSource
import io.workflow.core.EmissionId
import io.workflow.core.ExecutionId
import io.workflow.core.FixedClock
import io.workflow.core.Value
import io.workflow.demo.DemoProviders
import io.workflow.runtime.InMemoryWorkflowRunner
import io.workflow.runtime.WorkflowReplay
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Public-runtime runner for the checked-in, implementation-neutral v1 corpus. */
data class ConformanceSummary(val executed: Int, val passed: Int, val failures: List<String>)

fun runConformance(corpus: Path = Path.of("conformance", "v1")): ConformanceSummary {
    val scenarios = Files.list(corpus).use { paths -> paths.filter { it.fileName.toString().endsWith(".json") && it.fileName.toString() != "schema.json" }.sorted().toList() }
    require(scenarios.isNotEmpty()) { "conformance corpus contains zero scenarios" }
    val failures = mutableListOf<String>()
    scenarios.forEach { file -> runCatching { execute(file) }.onFailure { failures += "${file.fileName}: ${it.message}" } }
    return ConformanceSummary(scenarios.size, scenarios.size - failures.size, failures)
}

private fun execute(file: Path) {
    val scenario = Json.parseToJsonElement(Files.readString(file)).jsonObject
    require(scenario.string("formatVersion") == "1") { "unsupported scenario format" }
    require(scenario["requirementReferences"]?.jsonArray?.isNotEmpty() == true) { "missing normative requirement references" }
    val registry = DemoProviders.registry()
    val compiler = WorkflowCompiler(registry)
    val yaml = scenario["workflowYaml"]?.jsonPrimitive?.content ?: Files.readString(Path.of(scenario.string("workflow")))
    val compiled = compiler.compile(yaml)
    val expectedFailure = scenario["expectedFailure"]?.jsonPrimitive?.contentOrNull
    if (expectedFailure != null) {
        require(!compiled.isValid && (expectedFailure == "any" || compiled.diagnostics.joinToString().contains(expectedFailure))) { "expected compile failure $expectedFailure" }
        return
    }
    require(compiled.isValid) { compiled.diagnostics.joinToString() }
    val parameters = scenario["parameters"]?.let { CanonicalValueJson.decode(it.toString()) as Value.ObjectValue }?.fields ?: emptyMap()
    val host = InMemoryWorkflowRunner(compiler, providerRegistry = registry, idSource = DeterministicIdSource("corpus-${scenario.string("id")}-"), clock = FixedClock(Instant.EPOCH), workerCount = 1)
        .start(compiled.ir!!, parameters, ExecutionId("corpus-${scenario.string("id")}"))
    scenario["events"]?.jsonArray?.forEachIndexed { index, event ->
        val value = CanonicalValueJson.decode(event.toString())
        host.emit(host.openProviders().single().invocationId, value, EmissionId("corpus-event-$index"))
    }
    val result = host.result()
    require(result.isSuccessful) { result.failures.joinToString() }
    scenario["expectedOutputs"]?.jsonObject?.forEach { (name, value) ->
        require(result.outputs[name]?.value == CanonicalValueJson.decode(value.toString())) { "output $name did not match" }
    }
    val records = scenario["expectedRecords"]?.jsonObject
    records?.get("minimumAssignments")?.jsonPrimitive?.intOrNull?.let { require(result.journal.assignments().size >= it) }
    records?.get("providerEventTypes")?.jsonArray?.map { it.jsonPrimitive.content }?.forEach { type ->
        require(result.journal.providerEvents().any { it.type.name == type }) { "missing provider event $type" }
    }
    if (scenario["replay"]?.jsonPrimitive?.booleanOrNull == true) {
        val replay = WorkflowReplay.replayOne(result.journal, result.executionId).outputs(compiled.ir!!)
        scenario["expectedOutputs"]?.jsonObject?.forEach { (name, value) ->
            require(replay.getValue(name).value == CanonicalValueJson.decode(value.toString())) { "replay output $name did not match" }
        }
    }
}

private fun JsonObject.string(name: String) = getValue(name).jsonPrimitive.content

fun main(args: Array<String>) {
    val summary = runConformance(Path.of(args.firstOrNull() ?: "conformance/v1"))
    println("conformance executed=${summary.executed} passed=${summary.passed} failed=${summary.failures.size}")
    summary.failures.forEach { System.err.println(it) }
    check(summary.failures.isEmpty()) { "conformance failures" }
}
