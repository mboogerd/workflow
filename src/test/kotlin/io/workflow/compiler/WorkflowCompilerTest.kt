package io.workflow.compiler

import io.workflow.core.ValueSchema
import io.workflow.core.Value
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WorkflowCompilerTest {
    private val compiler = WorkflowCompiler()

    @Test
    fun `all expression operators compile`() {
        val result = compiler.compile(
            """
            workflow:
              id: operators
              version: 1
              parameters:
                name: {schema: string}
                missing: {schema: string}
              context:
                required: {${'$'}ref: "${'$'}.parameters.name"}
                optional: {${'$'}optional: "${'$'}.parameters.missing"}
                joined: {${'$'}concat: ["a", {${'$'}ref: "${'$'}.required"}]}
                equal: {${'$'}eq: [{${'$'}ref: "${'$'}.required"}, "a"]}
                present: {${'$'}present: {${'$'}optional: "${'$'}.parameters.name"}}
                both: {${'$'}and: [{${'$'}present: {${'$'}optional: "${'$'}.parameters.name"}}, true]}
                either: {${'$'}or: [false, {${'$'}ref: "${'$'}.equal"}]}
                negated: {${'$'}not: {${'$'}ref: "${'$'}.equal"}}
                escaped: {${'$'}literal: {${'$'}tag: ordinary, value: 1}}
              outputs: [negated]
            """.trimIndent(),
        )
        assertTrue(result.isValid, result.diagnostics.joinToString())
        val ir = result.ir!!
        val second = compiler.compile(
            """
            workflow:
              version: 1
              id: operators
              parameters: {name: {schema: string}, missing: {schema: string}}
              context:
                required: {${'$'}ref: "${'$'}.parameters.name"}
                optional: {${'$'}optional: "${'$'}.parameters.missing"}
                joined: {${'$'}concat: ["a", {${'$'}ref: "${'$'}.required"}]}
                equal: {${'$'}eq: [{${'$'}ref: "${'$'}.required"}, "a"]}
                present: {${'$'}present: {${'$'}optional: "${'$'}.parameters.name"}}
                both: {${'$'}and: [{${'$'}present: {${'$'}optional: "${'$'}.parameters.name"}}, true]}
                either: {${'$'}or: [false, {${'$'}ref: "${'$'}.equal"}]}
                negated: {${'$'}not: {${'$'}ref: "${'$'}.equal"}}
                escaped: {${'$'}literal: {${'$'}tag: ordinary, value: 1}}
              outputs: [negated]
            """.trimIndent(),
        )
        assertTrue(second.isValid, second.diagnostics.joinToString())
        assertEquals(ir.contentHash, second.ir!!.contentHash)
        assertEquals(ir.canonicalJson(), second.ir!!.canonicalJson())
    }

    @Test
    fun `forward references and dependency paths are canonicalized`() {
        val first = compiler.compile(
            """
            workflow:
              id: stable
              version: 1
              context:
                result: {${'$'}ref: "${'$'}.source.value"}
                source: {value: x}
              outputs: [result]
            """.trimIndent(),
        )
        val reordered = compiler.compile(
            """
            workflow:
              context: {source: {value: x}, result: {${'$'}ref: "${'$'}.source.value"}}
              outputs: [result]
              version: 1
              id: stable
            """.trimIndent(),
        )
        assertTrue(first.isValid, first.diagnostics.joinToString())
        assertTrue(reordered.isValid, reordered.diagnostics.joinToString())
        assertEquals(first.ir!!.contentHash, reordered.ir!!.contentHash)
        assertEquals(first.ir!!.canonicalJson(), reordered.ir!!.canonicalJson())
        assertEquals("stable@1", first.ir!!.workflowVersionId.value)
        assertEquals("stable@1/register/result", first.ir!!.registers.first { it.name == "result" }.registerId.value)
        assertTrue(first.ir!!.canonicalJson().contains("\"kind\":\"field\",\"name\":\"value\""))
    }

    @Test
    fun `bad paths cycles and unknown fields are diagnostics with locations`() {
        val result = compiler.compile(
            """
            workflow:
              id: bad
              version: 1
              mystery: true
              context:
                a: {${'$'}ref: "${'$'}.b"}
                b: {${'$'}ref: "${'$'}.a"}
                wildcard: {${'$'}ref: "${'$'}.a[*]"}
                missing: {${'$'}ref: "${'$'}.never.value"}
                unknown: {${'$'}wat: 1}
              outputs: [never-defined]
            """.trimIndent(),
        )
        assertFalse(result.isValid)
        assertTrue(result.diagnostics.any { it.message.contains("cycle") })
        assertTrue(result.diagnostics.any { it.message.contains("singular") })
        assertTrue(result.diagnostics.any { it.message.contains("unknown reference root") })
        assertTrue(result.diagnostics.any { it.message.contains("unknown expression operator") })
        assertTrue(result.diagnostics.any { it.message.contains("unreachable") })
        assertTrue(result.diagnostics.all { it.location.line > 0 && it.semanticPath.startsWith("$") })
    }

    @Test
    fun `non-expression producer forms are rejected in expression-only milestone`() {
        listOf("provider", "match", "map").forEach { form ->
            val result = compiler.compile(
                """
                workflow:
                  id: $form
                  version: 1
                  context:
                    value:
                      $form: something
                  outputs: [value]
                """.trimIndent(),
            )
            assertFalse(result.isValid)
            assertTrue(result.diagnostics.any { it.message.contains("not supported") })
        }
    }

    @Test
    fun `quoted scalar remains a string`() {
        val result = compiler.compile(
            """
            workflow:
              id: quoted
              version: 1
              context:
                value: "123"
              outputs: [value]
            """.trimIndent(),
        )
        assertTrue(result.isValid, result.diagnostics.joinToString())
        assertEquals(ValueSchema.String, result.ir!!.registers.single().schema)
    }

    @Test
    fun `YAML scalar types are respected for identity version and null schema`() {
        val valid = compiler.compile(
            """
            workflow:
              id: scalar-types
              version: 1
              parameters: {nothing: {schema: null}}
              context: {value: null}
              outputs: [value]
            """.trimIndent(),
        )
        assertTrue(valid.isValid, valid.diagnostics.joinToString())
        assertEquals(ValueSchema.Null, valid.ir!!.parameters.getValue("nothing"))

        val coreScalars = compiler.compile(
            """
            workflow:
              id: core-scalars
              version: 1
              context:
                upper-null: NULL
                upper-true: TRUE
                hex: 0xFF
                octal: -0o10
                exponent: 1e3
                quoted-bool: "true"
                block-number: |-
                  123
              outputs: [upper-null, upper-true, hex, octal, exponent, quoted-bool, block-number]
            """.trimIndent(),
        )
        assertTrue(coreScalars.isValid, coreScalars.diagnostics.joinToString())
        assertEquals(
            listOf(
                ValueSchema.String,
                ValueSchema.Decimal,
                ValueSchema.Integer,
                ValueSchema.Integer,
                ValueSchema.String,
                ValueSchema.Null,
                ValueSchema.Boolean,
            ),
            coreScalars.ir!!.registers.map { it.schema },
        )

        val nonFinite = compiler.compile(
            """
            workflow:
              id: non-finite
              version: 1
              context: {value: .inf}
              outputs: [value]
            """.trimIndent(),
        )
        assertFalse(nonFinite.isValid)
        assertTrue(nonFinite.diagnostics.any { it.message.contains("non-finite") })

        listOf("id: 7\n              version: 1", "id: quoted-version\n              version: \"1\"").forEach { identity ->
            val invalid = compiler.compile(
                """
                workflow:
                  $identity
                  context: {value: 1}
                  outputs: [value]
                """.trimIndent(),
            )
            assertFalse(invalid.isValid)
        }
    }

    @Test
    fun `literal escape preserves scalar kinds and reserved object shape`() {
        val result = compiler.compile(
            """
            workflow:
              id: literal
              version: 1
              context:
                value: {${'$'}literal: {${'$'}tag: ordinary, number: 1, enabled: true, absent: null}}
              outputs: [value]
            """.trimIndent(),
        )
        assertTrue(result.isValid, result.diagnostics.joinToString())
        val literal = result.ir!!.registers.single().producer as Expression.Literal
        val fields = (literal.value as Value.ObjectValue).fields
        assertTrue(fields.getValue("number") is Value.IntegerValue)
        assertTrue(fields.getValue("enabled") is Value.BooleanValue)
        assertEquals(Value.Null, fields.getValue("absent"))
    }

    @Test
    fun `every expression operator rejects an invalid shape or operand`() {
        val invalidExpressions = listOf(
            "{${'$'}ref: [not-a-path]}",
            "{${'$'}optional: 7}",
            "{${'$'}concat: not-a-list}",
            "{${'$'}eq: [one]}",
            "{${'$'}present: {${'$'}ref: '${'$'}.parameters.text'}}",
            "{${'$'}and: [1]}",
            "{${'$'}or: [false, text]}",
            "{${'$'}not: 1}",
            "{${'$'}literal: 1, extra: 2}",
        )
        invalidExpressions.forEachIndexed { index, expression ->
            val result = compiler.compile(
                """
                workflow:
                  id: invalid-$index
                  version: 1
                  parameters: {text: {schema: string}}
                  context: {value: $expression}
                  outputs: [value]
                """.trimIndent(),
            )
            assertFalse(result.isValid, "expression unexpectedly compiled: $expression")
            assertTrue(result.diagnostics.all { it.location.line > 0 && it.semanticPath.startsWith("$") })
        }
    }

    @Test
    fun `explicit schemas validate options tagged unions and heterogeneous arrays`() {
        val tagged = compiler.compile(
            """
            workflow:
              id: tagged
              version: 1
              parameters:
                result:
                  schema:
                    type: tagged-union
                    discriminator: kind
                    variants:
                      ok:
                        type: object
                        fields:
                          kind: {schema: string}
                          value: {schema: integer}
              context:
                copied: {${'$'}ref: '${'$'}.parameters.result'}
              outputs: [copied]
            """.trimIndent(),
        )
        assertTrue(tagged.isValid, tagged.diagnostics.joinToString())
        assertTrue(tagged.ir!!.parameters.getValue("result") is ValueSchema.TaggedUnion)

        val badBoolean = compiler.compile(
            """
            workflow:
              id: invalid-schema
              version: 1
              parameters:
                input:
                  schema:
                    type: object
                    fields: {name: {schema: string, required: maybe}}
                    additional-fields: perhaps
              context: {value: 1}
              outputs: [value]
            """.trimIndent(),
        )
        assertFalse(badBoolean.isValid)
        assertTrue(badBoolean.diagnostics.count { it.message.contains("Boolean") } == 2)

        val heterogeneous = compiler.compile(
            """
            workflow:
              id: heterogeneous
              version: 1
              context:
                value:
                  schema: {type: array, items: integer}
                  ${'$'}literal: [1, text]
              outputs: [value]
            """.trimIndent(),
        )
        assertFalse(heterogeneous.isValid)
        assertTrue(heterogeneous.diagnostics.any { it.message.contains("incompatible") })
    }

    @Test
    fun `YAML profile rejects unsafe forms and accepts bounded aliases`() {
        val boundedAlias = WorkflowCompiler(maxAliases = 1).compile(
            """
            workflow:
              id: aliases
              version: 1
              context:
                source: &shared hello
                copy: *shared
              outputs: [copy]
            """.trimIndent(),
        )
        assertTrue(boundedAlias.isValid, boundedAlias.diagnostics.joinToString())

        val rejectedDocuments = listOf(
            """
            workflow:
              id: duplicate
              id: again
              version: 1
              context: {value: 1}
              outputs: [value]
            """.trimIndent(),
            """
            workflow:
              id: tagged
              version: 1
              context: {value: !custom data}
              outputs: [value]
            """.trimIndent(),
            """
            workflow: {id: first, version: 1, context: {value: 1}, outputs: [value]}
            ---
            workflow: {id: second, version: 1, context: {value: 2}, outputs: [value]}
            """.trimIndent(),
            """
            unexpected: true
            workflow: {id: root-field, version: 1, context: {value: 1}, outputs: [value]}
            """.trimIndent(),
            """
            workflow:
              id: aliases
              version: 1
              context:
                source: &shared hello
                one: *shared
                two: *shared
              outputs: [one]
            """.trimIndent(),
        )
        rejectedDocuments.forEachIndexed { index, yaml ->
            val result = WorkflowCompiler(maxAliases = 1).compile(yaml)
            assertFalse(result.isValid, "unsafe YAML case $index unexpectedly compiled")
            assertTrue(
                result.diagnostics.all { it.location.line > 0 && it.location.column > 0 },
                "unsafe YAML case $index had invalid locations: ${result.diagnostics.joinToString()}",
            )
        }

        val tooDeep = WorkflowCompiler(maxNesting = 5).compile(
            """
            workflow:
              id: deep
              version: 1
              context: {value: [[[[[[1]]]]]]}
              outputs: [value]
            """.trimIndent(),
        )
        assertFalse(tooDeep.isValid)
        assertTrue(tooDeep.diagnostics.any { it.message.contains("nesting") })
    }

    @Test
    fun `reserved roots and impossible typed paths are rejected`() {
        val result = compiler.compile(
            """
            workflow:
              id: roots
              version: 1
              parameters: {text: {schema: string}}
              context:
                parameters: 1
                impossible: {${'$'}ref: '${'$'}.parameters.text.field'}
                huge-index: {${'$'}ref: '${'$'}.impossible[999999999999999999999]'}
              outputs: [impossible]
            """.trimIndent(),
        )
        assertFalse(result.isValid)
        assertTrue(result.diagnostics.any { it.message.contains("reserved") })
        assertTrue(result.diagnostics.any { it.message.contains("field selector") })
        assertTrue(result.diagnostics.any { it.message.contains("too large") })
    }
}
