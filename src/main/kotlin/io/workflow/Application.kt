package io.workflow

import io.workflow.core.CanonicalValueJson
import io.workflow.core.Value
import io.workflow.compiler.WorkflowCompiler
import io.workflow.runtime.InMemoryWorkflowRunner
import io.workflow.runtime.SqliteJournalStore
import io.workflow.runtime.WorkflowReplay
import io.workflow.core.ExecutionId
import io.workflow.runtime.WorkflowExecutionException
import io.workflow.demo.DemoProviders
import java.nio.file.Files
import java.nio.file.Path

fun main(args: Array<String>) {
    if (args.isEmpty() || args.first() == "--help") {
        println("No command was given. Use validate, compile, run, inspect, stop, resume, or replay.")
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
        else -> {
            System.err.println("unknown command '${args.first()}'; use validate, compile, run, inspect, stop, resume, or replay")
            kotlin.system.exitProcess(2)
        }
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
