package io.workflow.core

import java.math.BigDecimal
import java.math.BigInteger
import kotlinx.serialization.json.*

@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
private fun numericLiteral(text: String): JsonPrimitive = JsonUnquotedLiteral(text)

sealed interface Value {
    data object Null : Value
    data class BooleanValue(val value: Boolean) : Value
    data class StringValue(val value: String) : Value
    data class IntegerValue(val value: BigInteger) : Value {
        constructor(value: Long) : this(BigInteger.valueOf(value))
    }
    data class DecimalValue(val value: BigDecimal) : Value {
        constructor(value: String) : this(BigDecimal(value))
    }
    data class ArrayValue(val values: List<Value>) : Value
    data class ObjectValue(val fields: Map<String, Value>) : Value
    data class TaggedValue(val tag: String, val value: Value) : Value
}

object CanonicalValueJson {
    fun encode(value: Value): String = encodeElement(value).toString()

    fun decode(json: String): Value = decodeElement(Json.parseToJsonElement(json))

    private fun encodeElement(value: Value): JsonElement = when (value) {
        Value.Null -> JsonNull
        is Value.BooleanValue -> JsonPrimitive(value.value)
        is Value.StringValue -> JsonPrimitive(value.value)
        is Value.IntegerValue -> numericLiteral(value.value.toString())
        is Value.DecimalValue -> numericLiteral(value.value.canonicalDecimalText())
        is Value.ArrayValue -> JsonArray(value.values.map(::encodeElement))
        is Value.ObjectValue -> encodeObject(value)
        is Value.TaggedValue -> JsonObject(
            mapOf("\$tag" to JsonPrimitive(value.tag), "value" to encodeElement(value.value)),
        )
    }

    private fun decodeElement(element: JsonElement): Value = when (element) {
        JsonNull -> Value.Null
        is JsonArray -> Value.ArrayValue(element.map(::decodeElement))
        is JsonObject -> decodeObject(element)
        is JsonPrimitive -> when {
            element.isString -> Value.StringValue(element.content)
            element.content == "true" -> Value.BooleanValue(true)
            element.content == "false" -> Value.BooleanValue(false)
            element.content.contains('.') || element.content.contains('e', true) -> Value.DecimalValue(element.content)
            else -> Value.IntegerValue(BigInteger(element.content))
        }
    }

    private fun encodeObject(value: Value.ObjectValue): JsonObject {
        val fields = value.fields.toSortedMap()
        val conflictsWithTag = fields.keys == setOf("\$tag", "value")
        val conflictsWithEscape = fields.keys == setOf("\$object")
        if (!conflictsWithTag && !conflictsWithEscape) {
            return JsonObject(fields.mapValues { encodeElement(it.value) })
        }
        val entries = fields.map { (key, fieldValue) ->
            JsonArray(listOf(JsonPrimitive(key), encodeElement(fieldValue)))
        }
        return JsonObject(mapOf("\$object" to JsonArray(entries)))
    }

    private fun decodeObject(element: JsonObject): Value = when {
        element.keys == setOf("\$object") -> {
            val entries = element["\$object"] as? JsonArray
                ?: error("canonical escaped object must contain an entry array")
            Value.ObjectValue(entries.associate { entry ->
                val pair = entry as? JsonArray
                    ?: error("canonical escaped object entry must be an array")
                require(pair.size == 2) { "canonical escaped object entry must contain a key and value" }
                pair[0].jsonPrimitive.content to decodeElement(pair[1])
            })
        }
        element.keys == setOf("\$tag", "value") -> {
            val tag = element["\$tag"] as? JsonPrimitive
                ?: error("canonical tagged value must contain a string tag")
            require(tag.isString) { "canonical tagged value must contain a string tag" }
            Value.TaggedValue(tag.content, decodeElement(element.getValue("value")))
        }
        else -> Value.ObjectValue(element.mapValues { decodeElement(it.value) })
    }

    private fun BigDecimal.canonicalDecimalText(): String {
        val text = toString()
        return if ('.' in text || 'e' in text.lowercase()) text else "${text}E+0"
    }
}
