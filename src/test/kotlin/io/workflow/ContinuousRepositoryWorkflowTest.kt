package io.workflow

import io.workflow.compiler.WorkflowCompiler
import io.workflow.core.CanonicalValueJson
import io.workflow.core.DeterministicIdSource
import io.workflow.core.EmissionId
import io.workflow.core.ExecutionId
import io.workflow.core.FixedClock
import io.workflow.core.Value
import io.workflow.demo.DemoProviders
import io.workflow.runtime.InMemoryWorkflowRunner
import io.workflow.runtime.SqliteJournalStore
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.createTempFile
import kotlin.io.path.deleteIfExists
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ContinuousRepositoryWorkflowTest {
    private val workflowText = Path.of("examples", "continuous-repositories.yaml").toFile().readText()
    private val executionId = ExecutionId("continuous-test")

    @Test
    fun `durable commit events repeatedly rebuild anonymous architecture and retain provenance across restart`() {
        val database = createTempFile("continuous-repositories", ".db")
        database.deleteIfExists()
        try {
            val firstArchitectureRevision: Long
            SqliteJournalStore(database, FixedClock(Instant.EPOCH), DeterministicIdSource("first-")) .use { store ->
                val providers = DemoProviders.registry()
                val runner = runner(store, providers, "first-")
                val host = runner.start(workflowText, executionId = executionId)
                assertEquals(1, host.openProviders().size)
                val first = host.emit(host.openProviders().single().invocationId, mainEvent("delivery-a", "trigger-a"), EmissionId("event-a"))
                firstArchitectureRevision = first.outputs.getValue("architecture").revision
                assertEquals(listOf("app/service", "lib/core", "platform/base"), repositories(first.outputs.getValue("architecture").value))
                assertEquals(3, providerInvocations(store, DemoProviders.REPOSITORY_MODEL))
                assertTrue(first.isQuiescent)
            }

            // Closing the database without calling stop is the controlled process crash:
            // the commit source remains open and must be reconstructed from the journal.
            SqliteJournalStore(database, FixedClock(Instant.EPOCH), DeterministicIdSource("second-")) .use { store ->
                val providers = DemoProviders.registry()
                val runner = runner(store, providers, "second-")
                val resumed = runner.resume(executionId)
                assertEquals(1, resumed.openProviders().size)
                assertEquals(3, providerInvocations(store, DemoProviders.REPOSITORY_MODEL))
                assertEquals(firstArchitectureRevision, resumed.result().outputs.getValue("architecture").revision)

                val second = resumed.emit(resumed.openProviders().single().invocationId, mainEvent("delivery-b", "trigger-b"), EmissionId("event-b"))
                assertEquals(firstArchitectureRevision + 1, second.outputs.getValue("architecture").revision)
                assertEquals(listOf("app/service", "new/repository", "platform/base"), repositories(second.outputs.getValue("architecture").value))

                // Equal event payloads are intentionally not delivery-deduplicated.
                val repeated = resumed.emit(resumed.openProviders().single().invocationId, mainEvent("delivery-b", "trigger-b"), EmissionId("event-b-repeat"))
                assertEquals(second.outputs.getValue("architecture").revision + 1, repeated.outputs.getValue("architecture").revision)
                assertEquals(repositories(second.outputs.getValue("architecture").value), repositories(repeated.outputs.getValue("architecture").value))

                // The configured provider filters non-main fixtures before the global pipeline.
                val filtered = resumed.emit(resumed.openProviders().single().invocationId, featureEvent(), EmissionId("event-feature"))
                assertEquals(emptyList<String>(), repositories(filtered.outputs.getValue("architecture").value))

                val assignments = store.assignments().filter { it.executionId == executionId }
                val architectures = assignments.filter { it.registerId.value.endsWith("/register/architecture") }
                assertEquals(4, architectures.size)
                architectures.forEach { architecture ->
                    val topology = assignments.single { it.assignmentId == architecture.dependencyRevisions.values.single() }
                    assertTrue(topology.registerId.value.endsWith("/register/topology"))
                    val models = assignments.single { it.assignmentId == topology.dependencyRevisions.values.single() }
                    assertTrue(models.registerId.value.endsWith("/register/models"))
                    val inventory = assignments.single { it.assignmentId == models.dependencyRevisions.values.single() }
                    assertTrue(inventory.registerId.value.endsWith("/register/inventory"))
                    val event = assignments.single { it.assignmentId == inventory.dependencyRevisions.values.single() }
                    assertTrue(event.registerId.value.endsWith("/register/event"))
                }
                val inspection = repeated.inspectionJson()
                assertTrue(inspection.contains("\"dependencyRevisions\""))
                assertTrue(inspection.contains("\"correlationId\":\"app/service\""))
                assertFalse(assignments.any { it.contextId.value == "app/service" &&
                    (it.registerId.value.endsWith("/register/topology") || it.registerId.value.endsWith("/register/architecture")) })
            }
        } finally {
            database.deleteIfExists()
        }
    }

    @Test
    fun `crash after accepting event retains pending inventory intent and resumes without replaying completed model work`() {
        val database = createTempFile("continuous-pending", ".db")
        database.deleteIfExists()
        try {
            SqliteJournalStore(database, FixedClock(Instant.EPOCH), DeterministicIdSource("crash-")) .use { store ->
                val providers = DemoProviders.registry()
                val runner = runner(store, providers, "crash-")
                val host = runner.start(workflowText, executionId = executionId)
                assertThrows(RuntimeException::class.java) {
                    // The source assignment is committed before this hook interrupts its inventory activation.
                    runner.start(workflowText, executionId = executionId) { intent ->
                        if (intent.producerId.value.endsWith("/producer/inventory")) throw RuntimeException("controlled crash")
                    }.emit(host.openProviders().single().invocationId, mainEvent("delivery-a", "trigger-a"), EmissionId("crash-event"))
                }
                assertTrue(store.activationIntents().any { it.producerId.value.endsWith("/producer/inventory") })
                assertEquals(0, providerInvocations(store, DemoProviders.REPOSITORY_MODEL))
            }
            SqliteJournalStore(database, FixedClock(Instant.EPOCH), DeterministicIdSource("recover-")) .use { store ->
                val providers = DemoProviders.registry()
                val resumed = runner(store, providers, "recover-").resume(executionId)
                assertEquals(listOf("app/service", "lib/core", "platform/base"), repositories(resumed.result().outputs.getValue("architecture").value))
                assertEquals(3, providerInvocations(store, DemoProviders.REPOSITORY_MODEL))
                assertTrue(store.activationIntents().none { it.producerId.value.endsWith("/producer/inventory") && !store.isCompleted(it.id) })
            }
        } finally {
            database.deleteIfExists()
        }
    }

    @Test
    fun `cli survives a real process restart without duplicating completed repository work`() {
        val database = createTempFile("continuous-process", ".db")
        database.deleteIfExists()
        try {
            val databaseArgument = database.toAbsolutePath().toString()
            runCli("demo", "continuous-repositories", "start", "--database", databaseArgument)
            val snapshotA = runCli(
                "demo", "continuous-repositories", "emit", "--database", databaseArgument,
                "--event", eventJson("delivery-process-a", "trigger-a"),
            )
            CanonicalValueJson.decode(snapshotA)
            SqliteJournalStore(database).use { store ->
                assertEquals(1, architectureAssignments(store))
                assertEquals(3, providerInvocations(store, DemoProviders.REPOSITORY_MODEL))
            }

            // The source remains open when the first JVM exits. A separate JVM
            // reconstructs it from SQLite without repeating completed event-A work.
            CanonicalValueJson.decode(runCli("demo", "continuous-repositories", "resume", "--database", databaseArgument))
            SqliteJournalStore(database).use { store ->
                assertEquals(1, architectureAssignments(store))
                assertEquals(3, providerInvocations(store, DemoProviders.REPOSITORY_MODEL))
            }

            val snapshotB = runCli(
                "demo", "continuous-repositories", "emit", "--database", databaseArgument,
                "--event", eventJson("delivery-process-b", "trigger-b"),
            )
            CanonicalValueJson.decode(snapshotB)
            SqliteJournalStore(database).use { store ->
                assertEquals(2, architectureAssignments(store))
                assertEquals(6, providerInvocations(store, DemoProviders.REPOSITORY_MODEL))
            }
        } finally {
            database.deleteIfExists()
        }
    }

    private fun runner(store: SqliteJournalStore, providers: io.workflow.provider.ProviderRegistry, ids: String) =
        InMemoryWorkflowRunner(
            compiler = WorkflowCompiler(providers), journal = store, providerRegistry = providers,
            idSource = DeterministicIdSource(ids), clock = FixedClock(Instant.EPOCH), workerCount = 1,
        )

    private fun providerInvocations(store: SqliteJournalStore, provider: String): Int =
        store.providerInvocations().count { it.providerId == provider }

    private fun architectureAssignments(store: SqliteJournalStore): Int =
        store.assignments().count { it.registerId.value.endsWith("/register/architecture") }

    private fun runCli(vararg arguments: String): String {
        val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val command = listOf(java, "-cp", System.getProperty("java.class.path"), "io.workflow.ApplicationKt") + arguments
        val process = ProcessBuilder(command).directory(Path.of("").toAbsolutePath().toFile()).start()
        val stdout = process.inputStream.bufferedReader().readText().trim()
        val stderr = process.errorStream.bufferedReader().readText().trim()
        assertEquals(0, process.waitFor(), stderr)
        assertTrue(stdout.startsWith("{"), stdout.take(200))
        return stdout
    }

    private fun eventJson(delivery: String, commit: String): String =
        CanonicalValueJson.encode(mainEvent(delivery, commit))

    private fun mainEvent(delivery: String, commit: String) = Value.ObjectValue(mapOf(
        "repository" to Value.StringValue("app/service"),
        "branch" to Value.StringValue("main"),
        "deliveryId" to Value.StringValue(delivery),
        "commit" to Value.StringValue(commit),
    ))

    private fun featureEvent() = Value.ObjectValue(mapOf(
        "repository" to Value.StringValue("app/service"),
        "branch" to Value.StringValue("feature/no-global-rebuild"),
        "deliveryId" to Value.StringValue("delivery-feature"),
        "commit" to Value.StringValue("trigger-b"),
    ))

    private fun repositories(value: Value): List<String> =
        ((value as Value.ObjectValue).fields.getValue("repositories") as Value.ArrayValue).values
            .map { (it as Value.StringValue).value }
}
