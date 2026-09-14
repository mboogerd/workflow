package io.workflow

import io.workflow.conformance.runConformance
import java.nio.file.Files
import java.nio.file.Path
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

    @Test fun `traceability covers every README invariant and v1 topic`() {
        val index = Files.readString(Path.of("conformance/v1/traceability.md"))
        (1..10).forEach { assertTrue(index.contains("Invariant $it")) }
        listOf("core-model", "scope", "values-and-bindings", "schemas", "authoring-and-ir", "execution-semantics", "providers-and-effects", "instances-and-events", "failure-and-recovery", "agentic-steps", "execution-backend", "evolution-seams").forEach {
            assertTrue(index.contains(it), "missing $it")
        }
        Files.list(Path.of("conformance/v1")).use { files ->
            files.filter { it.fileName.toString().endsWith(".json") && it.fileName.toString() != "schema.json" }.forEach { scenario ->
                val text = Files.readString(scenario)
                assertTrue(text.contains("requirementReferences"))
            }
        }
    }

    @Test fun `zero scenario corpus fails closed`() {
        val empty = Files.createTempDirectory("empty-corpus")
        try { assertThrows(IllegalArgumentException::class.java) { runConformance(empty) } } finally { Files.deleteIfExists(empty) }
    }
}
