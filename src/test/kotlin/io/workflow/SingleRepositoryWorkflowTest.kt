package io.workflow

import io.workflow.core.CanonicalValueJson
import io.workflow.core.Value
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SingleRepositoryWorkflowTest {
    private val root = Path.of("examples")

    @Test
    fun `public CLI builds typed repository model with complete provenance`() {
        val output = capture {
            main(arrayOf("run", root.resolve("single-repository.yaml").toString(), "--parameters", root.resolve("single-repository-parameters.json").toString(), "--providers", "demo"))
        }
        val value = CanonicalValueJson.decode(output.trim()) as Value.ObjectValue
        val outputs = value.fields.getValue("outputs") as Value.ObjectValue
        val model = ((outputs.fields.getValue("model") as Value.ObjectValue).fields.getValue("value") as Value.ObjectValue)
        assertEquals(Value.StringValue("demo/workflow"), model.fields.getValue("repository"))
        assertEquals(Value.StringValue("abc123"), model.fields.getValue("commit"))
        val entities = model.fields.getValue("entities") as Value.ArrayValue
        assertTrue(entities.values.isNotEmpty())
        val evidence = (((entities.values.first() as Value.ObjectValue).fields.getValue("evidence") as Value.ArrayValue).values.first() as Value.ObjectValue)
        assertTrue(evidence.fields.containsKey("sourcePath"))
    }

    @Test
    fun `different commit fixture produces distinct model history`() {
        val first = runWithParameters("{\"repository\":\"demo/workflow\",\"commit\":\"abc123\"}")
        val second = runWithParameters("{\"repository\":\"demo/workflow\",\"commit\":\"abc122\"}")
        assertTrue(first != second)
        assertTrue(first.contains("abc123"))
        assertTrue(second.contains("abc122"))
    }

    private fun runWithParameters(parameters: String): String {
        val parameterFile = kotlin.io.path.createTempFile("single-repository", ".json")
        parameterFile.toFile().writeText(parameters)
        return try { capture { main(arrayOf("run", root.resolve("single-repository.yaml").toString(), "--parameters", parameterFile.toString(), "--providers", "demo")) } }
        finally { parameterFile.toFile().delete() }
    }

    private fun capture(block: () -> Unit): String {
        val bytes = ByteArrayOutputStream()
        val previous = System.out
        System.setOut(PrintStream(bytes))
        try { block() } finally { System.setOut(previous) }
        return bytes.toString()
    }
}
