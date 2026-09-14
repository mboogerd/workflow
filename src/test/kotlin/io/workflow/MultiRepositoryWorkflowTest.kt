package io.workflow

import io.workflow.core.CanonicalValueJson
import io.workflow.core.DeterministicIdSource
import io.workflow.core.EmissionId
import io.workflow.core.ExecutionId
import io.workflow.core.FixedClock
import io.workflow.core.Value
import io.workflow.demo.DemoProviders
import io.workflow.provider.ProviderDescriptor
import io.workflow.provider.ProviderLifecycleMessage
import io.workflow.runtime.InMemoryWorkflowRunner
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MultiRepositoryWorkflowTest {
    private val root = Path.of("examples")

    @Test
    fun `public CLI gathers finite inventory and rebuilds only the captured snapshot`() {
        val first = inspectWithParameters(
            """{"repositories":[
              {"repository":"app/service","commit":"svc-001","dependencies":["lib/core"]},
              {"repository":"lib/core","commit":"core-001","dependencies":["platform/base"]},
              {"repository":"platform/base","commit":"base-001","dependencies":[]},
              {"repository":"cycle/left","commit":"left-001","dependencies":["cycle/right"]},
              {"repository":"cycle/right","commit":"right-001","dependencies":["cycle/left"]}
            ]}""".replace("\n", ""),
        )
        val second = inspectWithParameters(
            """{"repositories":[
              {"repository":"app/service","commit":"svc-002","dependencies":["platform/base"]},
              {"repository":"platform/base","commit":"base-001","dependencies":[]},
              {"repository":"new/repository","commit":"new-001","dependencies":[]}
            ]}""".replace("\n", ""),
        )

        val firstArchitecture = first.objectAt("outputs").objectAt("architecture").objectAt("value")
        val secondArchitecture = second.objectAt("outputs").objectAt("architecture").objectAt("value")
        assertEquals(
            listOf("app/service", "cycle/left", "cycle/right", "lib/core", "platform/base"),
            firstArchitecture.strings("repositories"),
        )
        assertEquals(listOf("app/service", "new/repository", "platform/base"), secondArchitecture.strings("repositories"))
        assertFalse(secondArchitecture.strings("repositories").contains("lib/core"))
        assertFalse(secondArchitecture.strings("repositories").contains("cycle/left"))
        assertEquals(
            listOf("app/service@svc-002", "new/repository@new-001", "platform/base@base-001"),
            secondArchitecture.strings("gatheredModelRevisions"),
        )

        val firstTopology = first.objectAt("outputs").objectAt("topology").objectAt("value")
        val edges = firstTopology.objects("edges")
        assertEquals(
            listOf("app/service->lib/core", "cycle/left->cycle/right", "cycle/right->cycle/left", "lib/core->platform/base"),
            edges.map { "${it.string("from")}->${it.string("to")}" },
        )
        assertEquals(4, firstTopology.objects("outgoing").flatMap { it.objects("dependencies") }.size)
        assertEquals(4, firstTopology.objects("incoming").flatMap { it.objects("dependencies") }.size)
        assertTrue(firstTopology.objects("edges").any { it.string("from") == "cycle/left" && it.string("to") == "cycle/right" })
        assertTrue(firstTopology.objects("edges").any { it.string("from") == "cycle/right" && it.string("to") == "cycle/left" })

        fun assignment(rootName: String, inspection: Value.ObjectValue): Value.ObjectValue =
            inspection.objects("assignments").single { it.string("registerId").endsWith("/$rootName") }
        val firstModels = assignment("models", first)
        val firstTopologyAssignment = assignment("topology", first)
        val firstArchitectureAssignment = assignment("architecture", first)
        assertEquals(firstModels.string("assignmentId"), firstTopologyAssignment.objectAt("dependencyRevisions").string(firstModels.string("registerId")))
        assertEquals(firstTopologyAssignment.string("assignmentId"), firstArchitectureAssignment.objectAt("dependencyRevisions").string(firstTopologyAssignment.string("registerId")))
    }

    @Test
    fun `map model work may complete out of order while gather remains canonical`() {
        val entered = CountDownLatch(4)
        val registry = DemoProviders.registry().also { providers ->
            providers.register(
                ProviderDescriptor("test.repository-model", 1, DemoProviders.repositoryInputSchema, DemoProviders.repositoryModelSchema),
            ) { request ->
                val input = request.input as Value.ObjectValue
                val repository = input.string("repository")
                if (repository == "app/service") {
                    assertTrue(entered.await(5, TimeUnit.SECONDS))
                } else {
                    entered.countDown()
                }
                val commit = input.string("commit")
                val model = Value.ObjectValue(mapOf(
                    "repository" to Value.StringValue(repository),
                    "commit" to Value.StringValue(commit),
                    "revision" to Value.StringValue("$repository@$commit"),
                    "summary" to Value.StringValue("test model"),
                    "dependencies" to Value.ArrayValue(input.strings("dependencies").sorted().map(Value::StringValue)),
                    "entities" to Value.ArrayValue(emptyList()),
                ))
                listOf(
                    ProviderLifecycleMessage.Emission(model, EmissionId("test-$repository"), request.invocationId, request.attemptId),
                    ProviderLifecycleMessage.Completed,
                )
            }
        }
        val result = InMemoryWorkflowRunner(
            compiler = io.workflow.compiler.WorkflowCompiler(registry),
            providerRegistry = registry,
            idSource = DeterministicIdSource("multi-order-"),
            clock = FixedClock(Instant.EPOCH),
            workerCount = 5,
        ).run(
            root.resolve("multi-repository.yaml").toFile().readText().replace(DemoProviders.REPOSITORY_MODEL, "test.repository-model"),
            mapOf("repositories" to CanonicalValueJson.decode(root.resolve("multi-repository-parameters.json").toFile().readText()).let { (it as Value.ObjectValue).fields.getValue("repositories") }),
            ExecutionId("out-of-order-multi"),
        )
        assertTrue(result.isSuccessful, result.failures.joinToString())
        val modelAssignments = result.journal.assignments().filter { it.mapItemId != null && it.registerId.value.endsWith("/register/model") }
        assertEquals(5, modelAssignments.size)
        assertTrue(modelAssignments.map { it.value.asObject().string("repository") } != modelAssignments.map { it.value.asObject().string("repository") }.sorted())
        assertEquals(
            listOf("app/service", "cycle/left", "cycle/right", "lib/core", "platform/base"),
            result.outputs.getValue("architecture").value.asObject().strings("repositories"),
        )
    }

    private fun inspectWithParameters(parameters: String): Value.ObjectValue {
        val parameterFile = kotlin.io.path.createTempFile("multi-repository", ".json")
        parameterFile.toFile().writeText(parameters)
        return try {
            CanonicalValueJson.decode(capture {
                main(arrayOf("inspect", root.resolve("multi-repository.yaml").toString(), "--parameters", parameterFile.toString(), "--providers", "demo"))
            }.trim()) as Value.ObjectValue
        } finally {
            parameterFile.toFile().delete()
        }
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
private fun Value.ObjectValue.objects(name: String): List<Value.ObjectValue> = (fields.getValue(name) as Value.ArrayValue).values.map { it as Value.ObjectValue }
private fun Value.ObjectValue.string(name: String): String = (fields.getValue(name) as Value.StringValue).value
private fun Value.ObjectValue.strings(name: String): List<String> = (fields.getValue(name) as Value.ArrayValue).values.map { (it as Value.StringValue).value }
private fun Value.asObject(): Value.ObjectValue = this as Value.ObjectValue
