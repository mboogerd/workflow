package io.workflow

import io.workflow.compiler.WorkflowCompiler
import java.nio.file.Files
import java.nio.file.Path

fun main(args: Array<String>) {
    if (args.isEmpty() || args.first() == "--help") {
        println("No command was given. Workflow runtime commands are not available yet.")
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
        else -> {
            System.err.println("unknown command '${args.first()}'; use validate or compile")
            kotlin.system.exitProcess(2)
        }
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
    WorkflowCompiler().compile(Files.readString(path))
}
