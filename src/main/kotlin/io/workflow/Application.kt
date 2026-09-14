package io.workflow

import io.workflow.core.CanonicalValueJson
import io.workflow.core.Value
import io.workflow.compiler.WorkflowCompiler
import io.workflow.runtime.InMemoryWorkflowRunner
import io.workflow.runtime.SqliteJournalStore
import io.workflow.runtime.WorkflowReplay
import io.workflow.core.ExecutionId
import io.workflow.core.EmissionId
import io.workflow.runtime.WorkflowExecutionException
import io.workflow.demo.DemoProviders
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

fun main(args: Array<String>) {
    if (args.isEmpty() || args.first() == "--help") {
        println("No command was given. Use validate, compile, run, inspect, stop, resume, replay, human-answer, or demo.")
        return
    }
    when (args.first()) {
        "validate" -> {
            val result = compileFile(args)
            if (result.isValid) println("valid") else {
                result.diagnostics.forEach { System.err.println(it) }
                kotlin.system.exitProcess(1)
            }
        }
        "compile" -> {
            val outputIndex = args.indexOf("--output")
            if (outputIndex < 0 || outputIndex + 1 >= args.size) {
                System.err.println("compile requires --output <json>")
                kotlin.system.exitProcess(2)
            }
            val result = compileFile(args)
            if (!result.isValid) {
                result.diagnostics.forEach { System.err.println(it) }
                kotlin.system.exitProcess(1)
            }
            Files.createDirectories(Path.of(args[outputIndex + 1]).toAbsolutePath().parent)
            Files.writeString(Path.of(args[outputIndex + 1]), result.ir!!.canonicalJson() + "\n")
            println(args[outputIndex + 1])
        }
        "run", "inspect", "stop" -> runFile(
            args,
            inspectionOnly = args.first() == "inspect" || args.first() == "stop",
            administrativeStop = args.first() == "stop",
        )
        "resume" -> resumeDatabase(args)
        "replay" -> replayDatabase(args)
        "human-answer" -> submitHumanAnswer(args)
        "demo" -> continuousRepositoryDemo(args)
        else -> {
            System.err.println("unknown command '${args.first()}'; use validate, compile, run, inspect, stop, resume, replay, human-answer, or demo")
            kotlin.system.exitProcess(2)
        }
    }
}

private fun submitHumanAnswer(args: Array<String>) {
    val database = databasePath(args, "human-answer") ?: return
    val interventionIndex = args.indexOf("--intervention")
    val answerIndex = args.indexOf("--answer")
    require(interventionIndex >= 0 && interventionIndex + 1 < args.size) { "human-answer requires --intervention <id>" }
    require(answerIndex >= 0 && answerIndex + 1 < args.size) { "human-answer requires --answer <json>" }
    try {
        SqliteJournalStore(database).use { store ->
            val updated = store.submitAnswer(args[interventionIndex + 1], CanonicalValueJson.decode(args[answerIndex + 1]))
            println("{\"interventionId\":\"${updated.id}\",\"state\":\"${updated.state.name.lowercase()}\",\"answer\":${CanonicalValueJson.encode(requireNotNull(updated.answer))}}")
        }
    } catch (failure: Exception) {
        System.err.println("human answer failed: ${failure.message ?: "error"}")
        kotlin.system.exitProcess(1)
    }
}

/**
 * Offline administration surface for the v0.4 durable repository example.
 * The provider itself is an open durable source; this command only supplies
 * explicit fixture emissions and never needs a webhook server.
 */
private fun continuousRepositoryDemo(args: Array<String>) {
    if (args.getOrNull(1) != "continuous-repositories") {
        System.err.println("demo requires 'continuous-repositories'")
        kotlin.system.exitProcess(2)
    }
    val action = args.getOrNull(2)?.takeUnless { it.startsWith("--") } ?: "start"
    if (action !in setOf("start", "resume", "emit", "inspect", "stop")) {
        System.err.println("continuous-repositories actions: start, resume, emit, inspect, stop")
        kotlin.system.exitProcess(2)
    }
    val databaseIndex = args.indexOf("--database")
    if (databaseIndex < 0 || databaseIndex + 1 >= args.size) {
        System.err.println("continuous-repositories requires --database <sqlite path>")
        kotlin.system.exitProcess(2)
    }
    val database = Path.of(args[databaseIndex + 1])
    val execution = args.indexOf("--execution").takeIf { it >= 0 }?.let { index ->
        require(index + 1 < args.size) { "--execution requires an execution id" }
        ExecutionId(args[index + 1])
    } ?: ExecutionId("continuous-repositories")
    val providers = DemoProviders.registry()
    val compiler = WorkflowCompiler(providerRegistry = providers)
    val yamlPath = Path.of("examples", "continuous-repositories.yaml")
    val workflow = compiler.compile(Files.readString(yamlPath)).ir
        ?: error("continuous repository demo workflow did not compile")
    try {
        SqliteJournalStore(database).use { store ->
            val runner = InMemoryWorkflowRunner(compiler = compiler, journal = store, providerRegistry = providers)
            val host = if (store.executionBinding(execution) == null) {
                runner.start(workflow, executionId = execution)
            } else {
                runner.resume(execution)
            }
            when (action) {
                "emit" -> {
                    val eventIndex = args.indexOf("--event")
                    require(eventIndex >= 0 && eventIndex + 1 < args.size) {
                        "emit requires --event <json containing repository, branch, deliveryId, commit>"
                    }
                    val event = CanonicalValueJson.decode(args[eventIndex + 1]) as? Value.ObjectValue
                        ?: throw WorkflowExecutionException("event JSON must be an object")
                    val deliveryId = (event.fields["deliveryId"] as? Value.StringValue)?.value
                        ?: throw WorkflowExecutionException("event.deliveryId must be a string")
                    val handle = host.openProviders().singleOrNull { it.providerId == DemoProviders.COMMIT_EVENTS }
                        ?: throw WorkflowExecutionException("continuous commit-event source is not open")
                    host.emit(handle.invocationId, event, EmissionId("event-$deliveryId-${UUID.randomUUID()}"))
                }
                "stop" -> host.stop()
                else -> host.result()
            }
            // The runtime's canonical inspection is stable JSON and includes assignment,
            // activation, dependency-vector, and provider-event provenance.
            println(host.result().inspectionJson())
        }
    } catch (failure: Exception) {
        System.err.println("continuous repository demo failed: ${failure.message ?: "error"}")
        kotlin.system.exitProcess(1)
    }
}

private fun resumeDatabase(args: Array<String>) {
    val database = databasePath(args, "resume") ?: return
    val providers = providerRegistry(args)
    val selected = selectedExecution(args)
    try {
        SqliteJournalStore(database).use { store ->
            val ids = selected?.let(::listOf) ?: store.executionIds()
            require(ids.isNotEmpty()) { "database contains no executions" }
            val runner = InMemoryWorkflowRunner(
                compiler = WorkflowCompiler(providerRegistry = providers),
                journal = store,
                providerRegistry = providers,
            )
            val results = ids.map { runner.resume(it).result() }
            println("{\"mode\":\"resume\",\"executions\":[${results.joinToString(",") { result ->
                "{\"executionId\":\"${result.executionId.value}\",\"state\":\"${result.executionState.name.lowercase()}\",\"successful\":${result.isSuccessful},\"outputs\":${result.outputsJson()}}"
            }}]}")
        }
    } catch (failure: Exception) {
        System.err.println("workflow resume failed: ${failure.message ?: "resume error"}")
        kotlin.system.exitProcess(1)
    }
}

private fun replayDatabase(args: Array<String>) {
    val database = databasePath(args, "replay") ?: return
    val selected = selectedExecution(args)
    try {
        SqliteJournalStore(database).use { store ->
            val ids = selected?.let(::listOf) ?: store.executionIds()
            require(ids.isNotEmpty()) { "database contains no executions" }
            val summaries = ids.map { id ->
                val binding = store.executionBinding(id)
                val workflow = binding?.let { bound ->
                    store.workflowDefinitionForVersion(bound.workflowVersionId)?.content?.let { io.workflow.compiler.WorkflowIrCodec.decode(it) }
                }
                WorkflowReplay.replay(store, id, workflow).single().outputsJson(workflow)
            }
            println("{\"mode\":\"replay\",\"executions\":[${summaries.joinToString(",")}]}" )
        }
    } catch (failure: Exception) {
        System.err.println("workflow replay failed: ${failure.message ?: "replay error"}")
        kotlin.system.exitProcess(1)
    }
}

private fun databasePath(args: Array<String>, command: String): Path? {
    if (args.size < 2) {
        System.err.println("$command requires a database path")
        kotlin.system.exitProcess(2)
    }
    val path = Path.of(args[1])
    if (!Files.isRegularFile(path)) {
        System.err.println("database file not found: $path")
        kotlin.system.exitProcess(2)
    }
    return path
}

private fun selectedExecution(args: Array<String>): ExecutionId? {
    val index = args.indexOf("--execution")
    return if (index < 0) null else {
        require(index + 1 < args.size) { "--execution requires an execution id" }
        ExecutionId(args[index + 1])
    }
}

private fun compileFile(args: Array<String>) = run {
    if (args.size < 2) {
        System.err.println("${args.first()} requires a YAML path")
        kotlin.system.exitProcess(2)
    }
    val path = Path.of(args[1])
    if (!Files.isRegularFile(path)) {
        System.err.println("YAML file not found: $path")
        kotlin.system.exitProcess(2)
    }
    WorkflowCompiler(providerRegistry = providerRegistry(args)).compile(Files.readString(path))
}

private fun providerRegistry(args: Array<String>): io.workflow.provider.ProviderRegistry {
    val providerIndex = args.indexOf("--providers")
    if (providerIndex < 0 || providerIndex + 1 >= args.size) return io.workflow.provider.ProviderRegistry.empty()
    return when (args[providerIndex + 1]) {
        "demo" -> DemoProviders.registry()
        else -> {
            System.err.println("unknown provider profile '${args[providerIndex + 1]}'")
            kotlin.system.exitProcess(2)
        }
    }
}

private fun runFile(args: Array<String>, inspectionOnly: Boolean, administrativeStop: Boolean = false) {
    if (args.size < 2) {
        System.err.println("${args.first()} requires a YAML path")
        kotlin.system.exitProcess(2)
    }
    val yamlPath = Path.of(args[1])
    if (!Files.isRegularFile(yamlPath)) {
        System.err.println("YAML file not found: $yamlPath")
        kotlin.system.exitProcess(2)
    }
    val providers = providerRegistry(args)
    val parameterIndex = args.indexOf("--parameters")
    if (parameterIndex < 0 || parameterIndex + 1 >= args.size) {
        System.err.println("${args.first()} requires --parameters <json>")
        kotlin.system.exitProcess(2)
    }
    val parameterSource = args[parameterIndex + 1]
    val parameterPath = runCatching { Path.of(parameterSource) }.getOrNull()
    val parameterText = parameterPath?.takeIf(Files::isRegularFile)?.let(Files::readString) ?: parameterSource
    val parameters = try {
        when (val decoded = CanonicalValueJson.decode(parameterText)) {
            is Value.ObjectValue -> decoded.fields
            else -> throw WorkflowExecutionException("parameters JSON must be an object")
        }
    } catch (failure: Exception) {
        System.err.println("invalid parameters JSON: ${failure.message ?: "parse error"}")
        kotlin.system.exitProcess(1)
    }
    val compiler = WorkflowCompiler(providerRegistry = providers)
    val compilation = compiler.compile(Files.readString(yamlPath))
    if (!compilation.isValid) {
        compilation.diagnostics.forEach { System.err.println(it) }
        kotlin.system.exitProcess(1)
    }
    val result = try {
        val host = InMemoryWorkflowRunner(compiler = compiler, providerRegistry = providers)
            .start(compilation.ir!!, parameters)
        if (administrativeStop || args.contains("--stop")) host.stop() else host.result()
    } catch (failure: Exception) {
        System.err.println("workflow execution failed: ${failure.message ?: "execution error"}")
        kotlin.system.exitProcess(1)
    }
    if (!result.isSuccessful) {
        result.failures.forEach { System.err.println("workflow activation failed: $it") }
        kotlin.system.exitProcess(1)
    }
    println(if (inspectionOnly || args.contains("--inspect")) result.inspectionJson() else result.outputsJson())
}
