package io.workflow.demo

import io.workflow.core.EmissionId
import io.workflow.core.Value
import io.workflow.core.ValueSchema
import io.workflow.provider.EffectClass
import io.workflow.provider.ProviderDescriptor
import io.workflow.provider.ProviderLifecycleMessage
import io.workflow.provider.ProviderRegistry
import java.math.BigInteger

/** Deterministic, offline providers used by the single-repository example. */
object DemoProviders {
    const val READER = "demo.repository-reader"
    const val BUILDER = "demo.model-builder"

    private val string = ValueSchema.String
    private val sourceFile = ValueSchema.Object(mapOf(
        "path" to ValueSchema.Object.Field(string),
        "language" to ValueSchema.Object.Field(string),
        "lines" to ValueSchema.Object.Field(ValueSchema.Integer),
    ))
    private val snapshot = ValueSchema.Object(mapOf(
        "repository" to ValueSchema.Object.Field(string),
        "commit" to ValueSchema.Object.Field(string),
        "files" to ValueSchema.Object.Field(ValueSchema.Array(sourceFile)),
    ))
    private val evidence = ValueSchema.Object(mapOf(
        "sourcePath" to ValueSchema.Object.Field(string),
        "startLine" to ValueSchema.Object.Field(ValueSchema.Integer),
        "endLine" to ValueSchema.Object.Field(ValueSchema.Integer),
    ))
    private val entity = ValueSchema.Object(mapOf(
        "id" to ValueSchema.Object.Field(string),
        "kind" to ValueSchema.Object.Field(string),
        "name" to ValueSchema.Object.Field(string),
        "evidence" to ValueSchema.Object.Field(ValueSchema.Array(evidence)),
    ))
    val modelSchema = ValueSchema.Object(mapOf(
        "repository" to ValueSchema.Object.Field(string),
        "commit" to ValueSchema.Object.Field(string),
        "summary" to ValueSchema.Object.Field(string),
        "entities" to ValueSchema.Object.Field(ValueSchema.Array(entity)),
    ))

    fun registry(): ProviderRegistry = ProviderRegistry().also { registry ->
        registry.register(
            ProviderDescriptor(READER, 1, readerInput, snapshot, effectClass = EffectClass.READ),
        ) { request ->
            val input = request.input as Value.ObjectValue
            val repository = (input.fields.getValue("repository") as Value.StringValue).value
            val commit = (input.fields.getValue("commit") as Value.StringValue).value
            val files = fixture(repository, commit)
            listOf(
                ProviderLifecycleMessage.Emission(Value.ObjectValue(mapOf(
                    "repository" to Value.StringValue(repository),
                    "commit" to Value.StringValue(commit),
                    "files" to Value.ArrayValue(files),
                )), EmissionId("reader-${repository}-${commit}"), request.invocationId, request.attemptId),
                ProviderLifecycleMessage.Completed,
            )
        }
        registry.register(
            ProviderDescriptor(BUILDER, 1, snapshot, modelSchema, effectClass = EffectClass.AGENTIC),
        ) { request ->
            val input = request.input as Value.ObjectValue
            val repository = (input.fields.getValue("repository") as Value.StringValue).value
            val commit = (input.fields.getValue("commit") as Value.StringValue).value
            val files = input.fields.getValue("files") as Value.ArrayValue
            val entities = files.values.map { file ->
                val fields = (file as Value.ObjectValue).fields
                val path = (fields.getValue("path") as Value.StringValue).value
                val lines = (fields.getValue("lines") as Value.IntegerValue).value
                Value.ObjectValue(mapOf(
                    "id" to Value.StringValue("file:${repository}:${path}"),
                    "kind" to Value.StringValue("source-file"),
                    "name" to Value.StringValue(path),
                    "evidence" to Value.ArrayValue(listOf(Value.ObjectValue(mapOf(
                        "sourcePath" to Value.StringValue(path),
                        "startLine" to Value.IntegerValue(BigInteger.ONE),
                        "endLine" to Value.IntegerValue(lines),
                    )))),
                ))
            }
            val model = Value.ObjectValue(mapOf(
                "repository" to Value.StringValue(repository),
                "commit" to Value.StringValue(commit),
                "summary" to Value.StringValue("${entities.size} source files in $repository at $commit"),
                "entities" to Value.ArrayValue(entities),
            ))
            listOf(ProviderLifecycleMessage.Emission(model, EmissionId("model-${repository}-${commit}"), request.invocationId, request.attemptId), ProviderLifecycleMessage.Completed)
        }
    }

    private val readerInput = ValueSchema.Object(mapOf(
        "repository" to ValueSchema.Object.Field(string),
        "commit" to ValueSchema.Object.Field(string),
    ))

    private fun fixture(repository: String, commit: String): List<Value> {
        val suffix = if (commit.endsWith("2")) "src/changed.kt" else "src/Main.kt"
        return listOf(
            Value.ObjectValue(mapOf("path" to Value.StringValue(suffix), "language" to Value.StringValue("kotlin"), "lines" to Value.IntegerValue(if (commit.endsWith("2")) 24 else 18))),
            Value.ObjectValue(mapOf("path" to Value.StringValue("README.md"), "language" to Value.StringValue("markdown"), "lines" to Value.IntegerValue(10))),
        )
    }
}
