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
            "array" to Value.ArrayValue(listOf(Value.IntegerValue(1), Value.DecimalValue("1.0"))),
            "tag" to Value.TaggedValue("ok", Value.StringValue("done")),
        ))
        val encoded = CanonicalValueJson.encode(value)
        assertTrue(encoded.contains("999999999999999999999999"))
        assertTrue(encoded.contains("12.3400"))
        assertEquals(value, CanonicalValueJson.decode(encoded))
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

    @Test fun `v1 journal batch has exactly one mutation`() {
        val mutation = AssignmentMutation(AssignmentId("a"), ContextId("c"), RegisterId("r"), Value.Null, ProducerId("p"), occurredAt = Instant.EPOCH)
        assertEquals(mutation, JournalBatch.create(JournalBatchId("j"), listOf(mutation), Instant.EPOCH).assignment)
        assertThrows<IllegalArgumentException> { JournalBatch.create(JournalBatchId("j"), emptyList(), Instant.EPOCH) }
        assertThrows<IllegalArgumentException> { JournalBatch.create(JournalBatchId("j"), listOf(mutation, mutation), Instant.EPOCH) }
    }
}
