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
            main(arrayOf("inspect", root.resolve("single-repository.yaml").toString(), "--parameters", root.resolve("single-repository-parameters.json").toString(), "--providers", "demo"))
        }
        val inspection = CanonicalValueJson.decode(output.trim()) as Value.ObjectValue
        val outputs = inspection.objectAt("outputs")
        val model = ((outputs.fields.getValue("model") as Value.ObjectValue).fields.getValue("value") as Value.ObjectValue)
        assertEquals(Value.StringValue("demo/workflow"), model.fields.getValue("repository"))
        assertEquals(Value.StringValue("abc123"), model.fields.getValue("commit"))
        val entities = model.fields.getValue("entities") as Value.ArrayValue
        assertTrue(entities.values.isNotEmpty())
        val evidence = (((entities.values.first() as Value.ObjectValue).fields.getValue("evidence") as Value.ArrayValue).values.first() as Value.ObjectValue)
        assertTrue(evidence.fields.containsKey("sourcePath"))

        val assignments = inspection.objectsAt("assignments")
        val snapshot = assignments.single { it.stringAt("registerId").endsWith("/snapshot") }
        val modelAssignment = assignments.single { it.stringAt("registerId").endsWith("/model") }
        val snapshotAssignmentId = snapshot.stringAt("assignmentId")
        val modelInvocationId = modelAssignment.stringAt("invocationId")
        val modelActivationId = modelAssignment.stringAt("activationId")
        assertEquals("abc123", snapshot.objectAt("value").stringAt("commit"))
        assertEquals(
            snapshotAssignmentId,
            modelAssignment.objectAt("dependencyRevisions").stringAt(snapshot.stringAt("registerId")),
        )

        val received = inspection.objectsAt("providerEvents").single {
            it.stringAt("type") == "emission_received" && it.stringAt("emissionId") == modelAssignment.stringAt("emissionId")
        }
        assertEquals(received.stringAt("eventId"), modelAssignment.stringAt("causationId"))
        assertEquals(modelInvocationId, received.stringAt("invocationId"))
        assertTrue(inspection.objectsAt("providerEvents").any {
            it.stringAt("type") == "invocation" && it.stringAt("invocationId") == modelInvocationId
        })
        val activation = inspection.objectsAt("activations").single { it.stringAt("activationId") == modelActivationId }
        assertEquals(modelInvocationId, activation.stringAt("invocationId"))
        assertEquals(snapshotAssignmentId, activation.objectAt("dependencyRevisions").stringAt(snapshot.stringAt("registerId")))
    }

    @Test
    fun `different commit fixture produces distinct model history`() {
        val first = inspectWithParameters("{\"repository\":\"demo/workflow\",\"commit\":\"abc123\"}")
        val second = inspectWithParameters("{\"repository\":\"demo/workflow\",\"commit\":\"abc122\"}")
        assertHistory(first, "abc123", "src/Main.kt")
        assertHistory(second, "abc122", "src/changed.kt")
    }

    private fun inspectWithParameters(parameters: String): Value.ObjectValue {
        val parameterFile = kotlin.io.path.createTempFile("single-repository", ".json")
        parameterFile.toFile().writeText(parameters)
        return try {
            CanonicalValueJson.decode(capture {
                main(arrayOf("inspect", root.resolve("single-repository.yaml").toString(), "--parameters", parameterFile.toString(), "--providers", "demo"))
            }.trim()) as Value.ObjectValue
        }
        finally { parameterFile.toFile().delete() }
    }

    private fun assertHistory(inspection: Value.ObjectValue, commit: String, sourcePath: String) {
        val assignments = inspection.objectsAt("assignments")
        val snapshot = assignments.single { it.stringAt("registerId").endsWith("/snapshot") }
        val model = assignments.single { it.stringAt("registerId").endsWith("/model") }
        val snapshotValue = snapshot.objectAt("value")
        assertEquals(commit, snapshotValue.stringAt("commit"))
        assertEquals(sourcePath, snapshotValue.objectsAt("files").first().stringAt("path"))
        assertEquals(
            snapshot.stringAt("assignmentId"),
            model.objectAt("dependencyRevisions").stringAt(snapshot.stringAt("registerId")),
        )
    }

    private fun capture(block: () -> Unit): String {
        val bytes = ByteArrayOutputStream()
        val previous = System.out
        System.setOut(PrintStream(bytes))
        try { block() } finally { System.setOut(previous) }
        return bytes.toString()
    }
}

private fun Value.ObjectValue.objectAt(name: String): Value.ObjectValue = fields.getValue(name) as Value.ObjectValue
private fun Value.ObjectValue.objectsAt(name: String): List<Value.ObjectValue> =
    (fields.getValue(name) as Value.ArrayValue).values.map { it as Value.ObjectValue }
private fun Value.ObjectValue.stringAt(name: String): String = (fields.getValue(name) as Value.StringValue).value
