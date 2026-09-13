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
        is Value.DecimalValue -> numericLiteral(value.value.toPlainString())
        is Value.ArrayValue -> JsonArray(value.values.map(::encodeElement))
        is Value.ObjectValue -> JsonObject(value.fields.toSortedMap().mapValues { encodeElement(it.value) })
        is Value.TaggedValue -> JsonObject(mapOf("\$tag" to JsonPrimitive(value.tag), "value" to encodeElement(value.value)))
    }

    private fun decodeElement(element: JsonElement): Value = when (element) {
        JsonNull -> Value.Null
        is JsonArray -> Value.ArrayValue(element.map(::decodeElement))
        is JsonObject -> if (element.containsKey("\$tag") && element.size == 2) {
            Value.TaggedValue(element["\$tag"]!!.jsonPrimitive.content, decodeElement(element["value"]!!))
        } else Value.ObjectValue(element.mapValues { decodeElement(it.value) })
        is JsonPrimitive -> when {
            element.isString -> Value.StringValue(element.content)
            element.content == "true" -> Value.BooleanValue(true)
            element.content == "false" -> Value.BooleanValue(false)
            element.content.contains('.') || element.content.contains('e', true) -> Value.DecimalValue(element.content)
            else -> Value.IntegerValue(BigInteger(element.content))
        }
    }
}
