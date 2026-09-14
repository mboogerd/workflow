package io.workflow.compiler

import io.workflow.core.ValueSchema
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CompilerMatchMapTest {
    @Test
    fun `match compiles exhaustive tagged cases and canonicalizes case order`() {
        val first = compiler.compile(matchYaml(cases = """
          Succeeded: {${'$'}ref: '${'$'}.match.value.description'}
          Failed: {${'$'}ref: '${'$'}.match.error'}
        """))
        val reordered = compiler.compile(matchYaml(cases = """
          Failed: {${'$'}ref: '${'$'}.match.error'}
          Succeeded: {${'$'}ref: '${'$'}.match.value.description'}
        """))

        assertTrue(first.isValid, first.diagnostics.joinToString())
        assertTrue(reordered.isValid, reordered.diagnostics.joinToString())
        assertEquals(first.ir!!.contentHash, reordered.ir!!.contentHash)
        val register = first.ir.registers.single { it.name == "handled" }
        val match = register.match
        assertNotNull(match)
        assertEquals(ValueSchema.String, register.schema)
        assertEquals(listOf("Failed", "Succeeded"), match!!.cases.keys.toList())
        assertEquals(emptyList<String>(), register.dependencies)
        assertTrue(first.ir.canonicalJson().contains("\"cases\""))
    }

    @Test
    fun `map compiles nested lexical body and stable array ordering`() {
        val result = compiler.compile(
            """
            workflow:
              id: mapped
              version: 1
              parameters:
                values:
                  schema: {type: array, items: string}
              context:
                mapped:
                  map:
                    over: {${'$'}ref: '${'$'}.parameters.values'}
                    context:
                      value: {${'$'}ref: '${'$'}.item'}
                    output: value
              outputs: [mapped]
            """.trimIndent(),
        )

        assertTrue(result.isValid, result.diagnostics.joinToString())
        val register = result.ir!!.registers.single()
        val map = register.map
        assertNotNull(map)
        assertEquals(MapResultOrdering.ARRAY_INDEX, map!!.ordering)
        assertEquals(MapItemIdentityPolicy.ARRAY_INDEX, map.itemIdentityPolicy)
        assertEquals("value", map.output)
        assertEquals(ValueSchema.Array(ValueSchema.String), register.schema)
        assertEquals(listOf("mapped"), result.ir.outputs)
        assertEquals("mapped@1/producer/mapped/map/register/value", map.context.single().registerId.value)
    }

    @Test
    fun `keyed map canonicalizes literal input presentation order`() {
        fun workflow(input: String) = """
            workflow:
              id: keyed
              version: 1
              context:
                mapped:
                  map:
                    over: $input
                    context:
                      value: {${'$'}ref: '${'$'}.item'}
                    output: value
              outputs: [mapped]
        """.trimIndent()
        val first = compiler.compile(workflow("{b: two, a: one}"))
        val second = compiler.compile(workflow("{a: one, b: two}"))

        assertTrue(first.isValid, first.diagnostics.joinToString())
        assertTrue(second.isValid, second.diagnostics.joinToString())
        assertEquals(first.ir!!.contentHash, second.ir!!.contentHash)
        assertEquals(MapResultOrdering.OBJECT_KEY, first.ir.registers.single().map!!.ordering)
        assertEquals(MapItemIdentityPolicy.OBJECT_KEY, first.ir.registers.single().map!!.itemIdentityPolicy)
    }

    @Test
    fun `match and map validation reports source located diagnostics`() {
        val badMatch = compiler.compile(
            matchYaml(
                cases = """
                  Succeeded: 1
                """,
            ),
        )
        assertFalse(badMatch.isValid)
        assertTrue(badMatch.diagnostics.any { it.message.contains("not exhaustive") || it.message.contains("not present") })
        assertTrue(badMatch.diagnostics.all { it.location.line > 0 && it.semanticPath.startsWith("$") })

        val badMap = compiler.compile(
            """
            workflow:
              id: invalid-map
              version: 1
              context:
                source: text
                mapped:
                  map:
                    over: {${'$'}ref: '${'$'}.source'}
                    context:
                      source: {${'$'}ref: '${'$'}.item'}
                    output: missing
              outputs: [mapped]
            """.trimIndent(),
        )
        assertFalse(badMap.isValid)
        assertTrue(badMap.diagnostics.any { it.message.contains("finite array or object") }, badMap.diagnostics.joinToString())
        assertTrue(badMap.diagnostics.any { it.message.contains("shadows") || it.message.contains("not defined") })
        assertTrue(badMap.diagnostics.all { it.location.line > 0 && it.semanticPath.startsWith("$") })
    }

    @Test
    fun `map item and key are lexical only inside the body`() {
        val result = compiler.compile(
            """
            workflow:
              id: lexical
              version: 1
              context:
                invalid: {${'$'}ref: '${'$'}.item'}
              outputs: [invalid]
            """.trimIndent(),
        )
        assertFalse(result.isValid)
        assertTrue(result.diagnostics.any { it.message.contains("unknown reference root 'item'") })
    }

    @Test
    fun `map key has a string schema for keyed input and outer registers remain lexical`() {
        val result = compiler.compile(
            """
            workflow:
              id: keyed-scope
              version: 1
              context:
                prefix: p
                mapped:
                  map:
                    over: {a: one, b: two}
                    context:
                      value:
                        ${'$'}concat: [{${'$'}ref: '${'$'}.prefix'}, {${'$'}ref: '${'$'}.key'}, {${'$'}ref: '${'$'}.item'}]
                    output: value
              outputs: [mapped]
            """.trimIndent(),
        )
        assertTrue(result.isValid, result.diagnostics.joinToString())
        assertEquals(ValueSchema.Object(mapOf("a" to ValueSchema.Object.Field(ValueSchema.String), "b" to ValueSchema.Object.Field(ValueSchema.String))), result.ir!!.registers.single { it.name == "mapped" }.schema)
    }

    @Test
    fun `map nested in match retains match lexical scope`() {
        val result = compiler.compile(
            """
            workflow:
              id: nested-match-map
              version: 1
              parameters:
                result:
                  schema:
                    type: tagged-union
                    discriminator: kind
                    variants:
                      Ready:
                        type: object
                        fields:
                          kind: {schema: string}
                          prefix: {schema: string}
                          items: {schema: {type: array, items: string}}
              context:
                handled:
                  match:
                    value: {${'$'}ref: '${'$'}.parameters.result'}
                    cases:
                      Ready:
                        map:
                          over: {${'$'}ref: '${'$'}.match.items'}
                          context:
                            value:
                              ${'$'}concat: [{${'$'}ref: '${'$'}.match.prefix'}, {${'$'}ref: '${'$'}.item'}]
                          output: value
              outputs: [handled]
            """.trimIndent(),
        )

        assertTrue(result.isValid, result.diagnostics.joinToString())
        assertEquals(ValueSchema.Array(ValueSchema.String), result.ir!!.registers.single().schema)
    }

    private val compiler = WorkflowCompiler()

    private fun matchYaml(cases: String): String = """
        workflow:
          id: matched
          version: 1
          parameters:
            result:
              schema:
                type: tagged-union
                discriminator: kind
                variants:
                  Succeeded:
                    type: object
                    fields:
                      kind: {schema: string}
                      value:
                        schema:
                          type: object
                          fields: {description: {schema: string}}
                  Failed:
                    type: object
                    fields:
                      kind: {schema: string}
                      error: {schema: string}
          context:
            handled:
              schema: string
              match:
                value: {${'$'}ref: '${'$'}.parameters.result'}
                cases:
        ${cases.prependIndent("          ")}
          outputs: [handled]
    """.trimIndent()
}
