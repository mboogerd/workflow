package io.workflow.core

sealed interface ValueSchema {
    data object Any : ValueSchema
    data object Null : ValueSchema
    data object Boolean : ValueSchema
    data object String : ValueSchema
    data object Integer : ValueSchema
    data object Decimal : ValueSchema
    data class Array(val items: ValueSchema) : ValueSchema
    data class Object(val fields: Map<kotlin.String, Field>, val additionalFields: kotlin.Boolean = false) : ValueSchema {
        data class Field(val schema: ValueSchema, val required: kotlin.Boolean = true)
    }
    data class TaggedUnion(val discriminator: kotlin.String, val variants: Map<kotlin.String, Object>) : ValueSchema
}

data class ValidationError(val path: String, val message: String)
data class ValidationResult(val errors: List<ValidationError>) {
    val isValid get() = errors.isEmpty()
}

fun ValueSchema.validate(value: Value, path: String = "$"): ValidationResult {
    fun fail(message: String) = ValidationResult(listOf(ValidationError(path, message)))
    fun recurse(schema: ValueSchema, v: Value, p: String): List<ValidationError> = when (schema) {
        ValueSchema.Any -> emptyList()
        ValueSchema.Null -> if (v is Value.Null) emptyList() else listOf(ValidationError(p, "expected null"))
        ValueSchema.Boolean -> if (v is Value.BooleanValue) emptyList() else listOf(ValidationError(p, "expected boolean"))
        ValueSchema.String -> if (v is Value.StringValue) emptyList() else listOf(ValidationError(p, "expected string"))
        ValueSchema.Integer -> if (v is Value.IntegerValue) emptyList() else listOf(ValidationError(p, "expected integer"))
        ValueSchema.Decimal -> if (v is Value.DecimalValue) emptyList() else listOf(ValidationError(p, "expected decimal"))
        is ValueSchema.Array -> if (v !is Value.ArrayValue) listOf(ValidationError(p, "expected array")) else v.values.flatMapIndexed { i, x -> recurse(schema.items, x, "$p[$i]") }
        is ValueSchema.Object -> if (v !is Value.ObjectValue) listOf(ValidationError(p, "expected object")) else buildList {
            schema.fields.forEach { (name, field) ->
                if (name !in v.fields) { if (field.required) add(ValidationError("$p.$name", "required field is missing")) }
                else addAll(recurse(field.schema, v.fields.getValue(name), "$p.$name"))
            }
            if (!schema.additionalFields) v.fields.keys.filterNot(schema.fields::containsKey).forEach { add(ValidationError("$p.$it", "additional field is not allowed")) }
        }
        is ValueSchema.TaggedUnion -> if (v !is Value.ObjectValue) listOf(ValidationError(p, "expected tagged object")) else {
            val discriminator = v.fields[schema.discriminator]
            if (discriminator !is Value.StringValue) listOf(ValidationError("$p.${schema.discriminator}", "discriminator must be a string"))
            else schema.variants[discriminator.value]?.let { recurse(it, v, p) } ?: listOf(ValidationError(p, "unknown variant '${discriminator.value}'"))
        }
    }
    return ValidationResult(recurse(this, value, path))
}

fun ValueSchema.isCompatibleWith(expected: ValueSchema): kotlin.Boolean = expected == ValueSchema.Any || (this == expected)
