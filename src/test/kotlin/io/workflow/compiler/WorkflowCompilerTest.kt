package io.workflow.compiler

import io.workflow.core.ValueSchema
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
                unknown: {${'$'}wat: 1}
              outputs: [never-defined]
            """.trimIndent(),
        )
        assertFalse(result.isValid)
        assertTrue(result.diagnostics.any { it.message.contains("cycle") })
        assertTrue(result.diagnostics.any { it.message.contains("singular") })
        assertTrue(result.diagnostics.any { it.message.contains("unknown expression operator") })
        assertTrue(result.diagnostics.any { it.message.contains("unreachable") })
        assertTrue(result.diagnostics.all { it.location.line > 0 && it.semanticPath.startsWith("$") })
    }

    @Test
    fun `provider forms are rejected in expression-only milestone`() {
        val result = compiler.compile(
            """
            workflow:
              id: provider
              version: 1
              context:
                value:
                  provider: something
                  version: 1
              outputs: [value]
            """.trimIndent(),
        )
        assertFalse(result.isValid)
        assertTrue(result.diagnostics.any { it.message.contains("not supported") })
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
}
