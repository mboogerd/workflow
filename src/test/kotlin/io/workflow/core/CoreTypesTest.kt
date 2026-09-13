package io.workflow.core

import java.math.BigDecimal
import java.math.BigInteger
import java.time.Instant
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class CoreTypesTest {
    @Test fun `canonical values preserve every kind and numeric distinction`() {
        val value = Value.ObjectValue(mapOf(
            "null" to Value.Null, "bool" to Value.BooleanValue(true), "text" to Value.StringValue("x"),
            "integer" to Value.IntegerValue(BigInteger("999999999999999999999999")),
            "decimal" to Value.DecimalValue(BigDecimal("12.3400")),
            "scaleZeroDecimal" to Value.DecimalValue(BigDecimal("1")),
            "negativeScaleDecimal" to Value.DecimalValue(BigDecimal("1E+3")),
            "array" to Value.ArrayValue(listOf(Value.IntegerValue(1), Value.DecimalValue("1.0"), Value.ObjectValue(emptyMap()))),
            "tag" to Value.TaggedValue("ok", Value.StringValue("done")),
            "tagShapedObject" to Value.ObjectValue(mapOf("\$tag" to Value.StringValue("ordinary"), "value" to Value.Null)),
            "escapeShapedObject" to Value.ObjectValue(mapOf("\$object" to Value.ArrayValue(emptyList()))),
        ))
        val encoded = CanonicalValueJson.encode(value)
        assertTrue(encoded.contains("999999999999999999999999"))
        assertTrue(encoded.contains("12.3400"))
        assertEquals(value, CanonicalValueJson.decode(encoded))
    }

    @Test fun `schemas validate every form without coercion`() {
        val examples = listOf(
            ValueSchema.Null to Value.Null,
            ValueSchema.Boolean to Value.BooleanValue(true),
            ValueSchema.String to Value.StringValue("text"),
            ValueSchema.Integer to Value.IntegerValue(1),
            ValueSchema.Decimal to Value.DecimalValue("1.0"),
        )
        examples.forEach { (schema, value) ->
            assertTrue(schema.validate(value).isValid)
            assertTrue(ValueSchema.Any.validate(value).isValid)
        }
        assertFalse(ValueSchema.Decimal.validate(Value.IntegerValue(1)).isValid)
        assertFalse(ValueSchema.Integer.validate(Value.DecimalValue("1.0")).isValid)
        assertFalse(ValueSchema.Boolean.validate(Value.StringValue("true")).isValid)
    }

    @Test fun `schemas validate closed objects optional values arrays and tagged unions`() {
        val schema = ValueSchema.Object(mapOf(
            "id" to ValueSchema.Object.Field(ValueSchema.String),
            "note" to ValueSchema.Object.Field(ValueSchema.String, required = false),
            "items" to ValueSchema.Object.Field(ValueSchema.Array(ValueSchema.Integer)),
        ))
        assertTrue(schema.validate(Value.ObjectValue(mapOf("id" to Value.StringValue("a"), "items" to Value.ArrayValue(listOf(Value.IntegerValue(2)))))).isValid)
        assertFalse(schema.validate(Value.ObjectValue(mapOf("items" to Value.ArrayValue(emptyList()), "extra" to Value.Null))).isValid)
        val union = ValueSchema.TaggedUnion("kind", mapOf("ok" to ValueSchema.Object(mapOf("kind" to ValueSchema.Object.Field(ValueSchema.String), "value" to ValueSchema.Object.Field(ValueSchema.Integer)))))
        assertTrue(union.validate(Value.ObjectValue(mapOf("kind" to Value.StringValue("ok"), "value" to Value.IntegerValue(1)))).isValid)
        assertFalse(union.validate(Value.ObjectValue(mapOf("kind" to Value.StringValue("bad")))).isValid)
    }

    @Test fun `schema compatibility is deliberately conservative`() {
        assertTrue(ValueSchema.String.isCompatibleWith(ValueSchema.String))
        assertTrue(ValueSchema.String.isCompatibleWith(ValueSchema.Any))
        assertTrue(ValueSchema.Any.isCompatibleWith(ValueSchema.Any))
        assertFalse(ValueSchema.Any.isCompatibleWith(ValueSchema.String))
        assertFalse(ValueSchema.Integer.isCompatibleWith(ValueSchema.Decimal))
        assertFalse(
            ValueSchema.Object(mapOf("id" to ValueSchema.Object.Field(ValueSchema.String)))
                .isCompatibleWith(ValueSchema.Object(emptyMap())),
        )
    }

    @Test fun `v1 journal batch has exactly one mutation`() {
        val mutation = AssignmentMutation(
            assignmentId = AssignmentId("a"),
            workflowId = WorkflowId("workflow"),
            workflowVersionId = WorkflowVersionId("version"),
            executionId = ExecutionId("execution"),
            contextId = ContextId("context"),
            registerId = RegisterId("register"),
            value = Value.Null,
            producerId = ProducerId("producer"),
            occurredAt = Instant.EPOCH,
        )
        assertEquals(mutation, JournalBatch(JournalBatchId("j"), listOf(mutation), Instant.EPOCH).assignment)
        assertThrows<IllegalArgumentException> { JournalBatch(JournalBatchId("j"), emptyList(), Instant.EPOCH) }
        assertThrows<IllegalArgumentException> { JournalBatch(JournalBatchId("j"), listOf(mutation, mutation), Instant.EPOCH) }
        assertThrows<IllegalArgumentException> {
            JournalBatch(JournalBatchId("j"), listOf(mutation.copy(mutationOrdinal = 1)), Instant.EPOCH)
        }
    }
}
