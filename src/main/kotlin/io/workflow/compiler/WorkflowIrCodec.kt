package io.workflow.compiler

import io.workflow.core.CanonicalValueJson
import io.workflow.core.ProducerId
import io.workflow.core.RegisterId
import io.workflow.core.ValueSchema
import io.workflow.core.WorkflowVersionId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Decoder for the canonical deployed IR persisted by the durable backend. */
object WorkflowIrCodec {
    private val json = Json { ignoreUnknownKeys = false }
    private val source = SourceLocation(1, 1)

    fun decode(content: String, verifyHash: Boolean = true): WorkflowIrDocument {
        val document = json.parseToJsonElement(content).asObject("IR document")
        val decoded = WorkflowIrDocument(
            workflowId = document.requiredString("workflowId"),
            version = document.requiredInt("version"),
            workflowVersionId = WorkflowVersionId(document.requiredString("workflowVersionId")),
            parameters = document.requiredObject("parameters").mapValues { schema(it.value) },
            registers = document.requiredArray("registers").map { register(it.asObject("register")) },
            outputs = document.requiredArray("outputs").map { it.asPrimitive("output").content },
            contentHash = document.requiredString("contentHash"),
            irVersion = document.requiredInt("irVersion"),
        )
        require(!verifyHash || decoded.hasValidContentHash()) {
            "workflow IR content hash does not match canonical content"
        }
        return decoded
    }

    private fun register(value: JsonObject): CompiledRegister {
        val expression = expression(value.required("producer"))
        val provider = value["provider"]?.let { provider(it.asObject("provider")) }
        val match = value["match"]?.let { match(it.asObject("match")) }
        val map = value["map"]?.let { map(it.asObject("map")) }
        val producerId = ProducerId(value.requiredString("producerId"))
        val schema = schema(value.required("schema"))
        val dependencies = value.requiredArray("dependencies").map { it.asPrimitive("dependency").content }
        val compiledProducer = when {
            match != null -> CompiledProducer.Match(match, producerId, schema, dependencies, source)
            map != null -> CompiledProducer.Map(map, producerId, schema, dependencies, source)
            provider != null -> CompiledProducer.Provider(provider, producerId, schema, dependencies, source)
            else -> CompiledProducer.Expression(expression, producerId, schema, dependencies, source)
        }
        return CompiledRegister(
            name = value.requiredString("name"),
            registerId = RegisterId(value.requiredString("registerId")),
            producerId = producerId,
            producer = expression,
            dependencies = dependencies,
            schema = schema,
            source = source,
            provider = provider,
            compiledProducer = compiledProducer,
            match = match,
            map = map,
        )
    }

    private fun compiledProducer(value: JsonObject): CompiledProducer {
        val kind = value.requiredString("kind")
        val producerId = ProducerId(value.requiredString("producerId"))
        val schema = schema(value.required("schema"))
        val dependencies = value.requiredArray("dependencies").map { it.asPrimitive("dependency").content }
        return when (kind) {
            "expression" -> CompiledProducer.Expression(expression(value.required("expression")), producerId, schema, dependencies, source)
            "provider" -> CompiledProducer.Provider(provider(value.requiredObject("provider")), producerId, schema, dependencies, source)
            "match" -> CompiledProducer.Match(match(value.requiredObject("match")), producerId, schema, dependencies, source)
            "map" -> CompiledProducer.Map(map(value.requiredObject("map")), producerId, schema, dependencies, source)
            else -> error("unsupported compiled producer kind '$kind'")
        }
    }

    private fun provider(value: JsonObject): CompiledProvider = CompiledProvider(
        providerId = value.requiredString("providerId"),
        version = value.requiredInt("version"),
        config = expression(value.required("config")),
        input = expression(value.required("input")),
        capabilities = value.requiredArray("capabilities").map { it.asPrimitive("capability").content }.toSet(),
        policy = io.workflow.core.CanonicalValueJson.decode(value.required("policy").toString()),
    )

    private fun match(value: JsonObject): CompiledMatch = CompiledMatch(
        discriminator = expression(value.required("discriminator")),
        discriminatorSchema = schema(value.required("discriminatorSchema")) as? ValueSchema.TaggedUnion
            ?: error("match discriminatorSchema must be tagged-union"),
        cases = value.requiredObject("cases").mapValues { compiledProducer(it.value.asObject("case producer")) },
        outputSchema = schema(value.required("outputSchema")),
    )

    private fun map(value: JsonObject): CompiledMap = CompiledMap(
        input = expression(value.required("input")),
        context = value.requiredArray("context").map { register(it.asObject("map register")) },
        output = value.requiredString("output"),
        outputSchema = schema(value.required("outputSchema")),
        ordering = when (value.requiredString("ordering")) {
            "array_index" -> MapResultOrdering.ARRAY_INDEX
            "object_key" -> MapResultOrdering.OBJECT_KEY
            else -> error("unsupported map ordering")
        },
        itemIdentityPolicy = when (value.requiredString("itemIdentityPolicy")) {
            "array_index" -> MapItemIdentityPolicy.ARRAY_INDEX
            "object_key" -> MapItemIdentityPolicy.OBJECT_KEY
            else -> error("unsupported map item identity policy")
        },
    )

    private fun expression(element: JsonElement): Expression {
        val value = element.asObject("expression")
        return when (val kind = value.requiredString("kind")) {
            "literal" -> Expression.Literal(CanonicalValueJson.decode(value.required("value").toString()))
            "ref" -> Expression.Ref(
                root = value.requiredString("root"),
                path = value.requiredArray("path").map { pathStep(it.asObject("path step")) },
                requirement = when (value.requiredString("requirement")) {
                    "required" -> Requirement.REQUIRED
                    "optional" -> Requirement.OPTIONAL
                    else -> error("unsupported reference requirement")
                },
            )
            "object" -> Expression.ObjectValue(value.requiredObject("fields").mapValues { expression(it.value) })
            "array" -> Expression.ArrayValue(value.requiredArray("items").map(::expression))
            "concat" -> Expression.Concat(value.requiredArray("parts").map(::expression))
            "equals" -> Expression.Equals(expression(value.required("left")), expression(value.required("right")))
            "present" -> Expression.Present(expression(value.required("value")))
            "and" -> Expression.And(value.requiredArray("predicates").map(::expression))
            "or" -> Expression.Or(value.requiredArray("predicates").map(::expression))
            "not" -> Expression.Not(expression(value.required("predicate")))
            else -> error("unsupported expression kind '$kind'")
        }
    }

    private fun pathStep(value: JsonObject): PathStep = when (value.requiredString("kind")) {
        "field" -> PathStep.Field(value.requiredString("name"))
        "index" -> PathStep.Index(value.requiredInt("index"))
        else -> error("unsupported path step")
    }

    private fun schema(element: JsonElement): ValueSchema {
        val primitive = element as? JsonPrimitive
        if (primitive != null) return when (primitive.content) {
            "any" -> ValueSchema.Any
            "null" -> ValueSchema.Null
            "boolean" -> ValueSchema.Boolean
            "string" -> ValueSchema.String
            "integer" -> ValueSchema.Integer
            "decimal" -> ValueSchema.Decimal
            else -> error("unsupported schema '${primitive.content}'")
        }
        val value = element.asObject("schema")
        return when (value.requiredString("type")) {
            "array" -> ValueSchema.Array(schema(value.required("items")))
            "object" -> ValueSchema.Object(
                value.requiredObject("fields").mapValues { field ->
                    val fieldValue = field.value.asObject("object field")
                    ValueSchema.Object.Field(schema(fieldValue.required("schema")), fieldValue.requiredBoolean("required"))
                },
                value["additional-fields"]?.asPrimitive("additional-fields")?.content?.toBooleanStrictOrNull() ?: false,
            )
            "tagged-union" -> ValueSchema.TaggedUnion(
                discriminator = value.requiredString("discriminator"),
                variants = value.requiredObject("variants").mapValues {
                    schema(it.value) as? ValueSchema.Object ?: error("tagged-union variants must be objects")
                },
            )
            else -> error("unsupported schema type")
        }
    }

    private fun JsonObject.required(name: String): JsonElement = get(name) ?: error("missing '$name'")
    private fun JsonObject.requiredString(name: String): String = required(name).asPrimitive(name).content
    private fun JsonObject.requiredInt(name: String): Int = requiredString(name).toInt()
    private fun JsonObject.requiredBoolean(name: String): Boolean = required(name).asPrimitive(name).content.toBooleanStrict()
    private fun JsonObject.requiredArray(name: String): JsonArray = required(name) as? JsonArray ?: error("'$name' must be an array")
    private fun JsonObject.requiredObject(name: String): JsonObject = required(name).asObject(name)
    private fun JsonElement.asObject(label: String): JsonObject = this as? JsonObject ?: error("$label must be an object")
    private fun JsonElement.asPrimitive(label: String): JsonPrimitive = this as? JsonPrimitive ?: error("$label must be a primitive")
}
