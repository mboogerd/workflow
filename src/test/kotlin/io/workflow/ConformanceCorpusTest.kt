package io.workflow

import io.workflow.conformance.runConformance
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ConformanceCorpusTest {
    @Test fun `v1 corpus is nonempty and passes through public runtime`() {
        val result = runConformance()
        assertTrue(result.executed >= 15)
        assertEquals(result.executed, result.passed, result.failures.joinToString())
    }

    @Test fun `traceability index covers every normative v1 section and README invariant`() {
        val corpus = Path.of("conformance/v1")
        val scenarioFiles = Files.list(corpus).use { paths -> paths.filter {
            it.fileName.toString().endsWith(".json") && it.fileName.toString() !in setOf("schema.json", "traceability.json")
        }.toList() }
        val scenarios = scenarioFiles.associate { file ->
            val value = Json.parseToJsonElement(Files.readString(file)).jsonObject
            value.getValue("id").jsonPrimitive.content to value
        }
        assertEquals(scenarioFiles.size, scenarios.size, "scenario ids must be unique")

        val index = Json.parseToJsonElement(Files.readString(corpus.resolve("traceability.json"))).jsonObject
            .getValue("requirements").jsonObject
        (1..10).forEach { assertTrue("README.md#invariant-$it" in index, "missing README invariant $it") }
        val expectedSections = mapOf(
            "scope.md" to listOf("objective", "initial-version", "explicit-non-goals-for-the-initial-version", "deferred-extensions", "initial-version-closure-decisions"),
            "core-model.md" to listOf("concepts", "minimal-surface-constructs", "assignment", "activation", "correlation", "graph-shape"),
            "values-and-bindings.md" to listOf("boundary-data-model", "reference-semantics", "initial-expression-algebra", "yaml-representation", "no-general-purpose-expressions", "illustrative-authoring-shape"),
            "schemas.md" to listOf("purpose", "schema-forms", "yaml-representation", "validation-and-compatibility", "versioning"),
            "execution-semantics.md" to listOf("startup", "assignment-journal", "activation", "correlation-routing", "concurrent-completion", "conditional-execution", "scatter-gather", "quiescence-and-completion", "replay"),
            "providers-and-effects.md" to listOf("uniform-provider-contract", "activation-and-emission", "effect-classes", "invocation-identity", "failure-and-emitted-error-values", "reconciliation", "portability"),
            "agentic-steps.md" to listOf("position-in-the-model", "required-descriptor-fields", "internal-agent-graphs", "emission-discipline", "agentic-recovery"),
            "failure-and-recovery.md" to listOf("failure-model", "recovery-order", "recovery-decisions", "reactive-consequences", "determinism-and-audit"),
            "instances-and-events.md" to listOf("execution-and-context-identity", "anonymous-startup", "emission-routing", "repeated-correlation", "lifecycle"),
            "authoring-and-ir.md" to listOf("initial-authoring-surface", "compilation-pipeline", "static-validation", "portable-ir", "other-authoring-forms"),
            "execution-backend.md" to listOf("first-implementation", "minimal-runtime-components", "processing-boundary", "provider-isolation", "effects", "future-computenet-backend"),
            "evolution-seams.md" to listOf("intent", "journal-batches", "reconstructible-views", "stable-causation-and-identity", "runtime-boundaries", "forward-mapping"),
        )
        expectedSections.forEach { (topic, sections) -> sections.forEach { section ->
            val key = "$topic#$section"
            val mapping = index[key]?.jsonObject
            assertTrue(mapping != null, "uncovered normative section $key")
            val mappedScenarios = mapping?.get("scenarios")?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
            mappedScenarios.forEach { assertTrue(it in scenarios, "$key references unknown scenario $it") }
            val explanation = mapping?.get("explanation")?.jsonPrimitive?.content
            assertTrue(mappedScenarios.isNotEmpty() || !explanation.isNullOrBlank(), "$key has neither scenarios nor reviewed explanation")
        } }

        scenarios.forEach { (id, scenario) ->
            val references = scenario.getValue("requirementReferences").jsonArray.map { it.jsonPrimitive.content }
            assertTrue(references.isNotEmpty(), "$id has no direct requirement references")
            references.forEach { assertTrue('#' in it && it.substringBefore('#').endsWith(".md"), "$id has dangling reference syntax $it") }
        }
    }

    @Test fun `zero scenario corpus fails closed`() {
        val empty = Files.createTempDirectory("empty-corpus")
        try { assertThrows(IllegalArgumentException::class.java) { runConformance(empty) } } finally { Files.deleteIfExists(empty) }
    }
}
