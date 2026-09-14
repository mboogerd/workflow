package io.workflow.compiler

import io.workflow.core.Value
import io.workflow.core.ValueSchema
import io.workflow.provider.EffectClass
import io.workflow.provider.ProviderDescriptor
import io.workflow.provider.ProviderInvocationRequest
import io.workflow.provider.ProviderLifecycle
import io.workflow.provider.ProviderLifecycleMessage
import io.workflow.provider.ProviderRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ProviderCompilerTest {
    private val input = ValueSchema.Object(mapOf("name" to ValueSchema.Object.Field(ValueSchema.String)))
    private val output = ValueSchema.Object(mapOf("message" to ValueSchema.Object.Field(ValueSchema.String)))

    private fun registry(): ProviderRegistry = ProviderRegistry().also {
        it.register(
            ProviderDescriptor(
                providerId = "greeter",
                version = 1,
                inputSchema = input,
                emissionSchema = output,
                configurationSchema = ValueSchema.Object(mapOf("region" to ValueSchema.Object.Field(ValueSchema.String))),
                effectClass = EffectClass.PURE,
                capabilities = setOf("compute"),
                lifecycle = ProviderLifecycle(supportsOpenActivation = true),
            ),
        )
    }

    @Test
    fun `provider identity and bindings are compiled without invoking implementation`() {
        val result = WorkflowCompiler(providerRegistry = registry()).compile(
            """
            workflow:
              id: provider
              version: 1
              context:
                name: Ada
                greeting:
                  provider: greeter
                  version: 1
                  config: {region: eu}
                  with: {name: {${'$'}ref: '${'$'}.name'}}
                  schema: $output
              outputs: [greeting]
            """.trimIndent().replace("schema: $output", "schema: {type: object, fields: {message: {schema: string}}}"),
        )
        assertTrue(result.isValid, result.diagnostics.joinToString())
        val register = result.ir!!.registers.single { it.name == "greeting" }
        assertEquals("greeter", register.provider!!.providerId)
        assertEquals(1, register.provider!!.version)
        assertEquals(listOf("name"), register.dependencies)
        assertTrue(register.canonicalProviderJson().contains("greeter"))
    }

    @Test
    fun `provider diagnostics cover unknown versions schema and capabilities`() {
        val compiler = WorkflowCompiler(providerRegistry = registry())
        val unknown = compiler.compile("""
            workflow: {id: unknown, version: 1, context: {value: {provider: absent, version: 2}}, outputs: [value]}
        """.trimIndent())
        assertFalse(unknown.isValid)
        assertTrue(unknown.diagnostics.any { it.message.contains("unknown provider") })

        val invalid = compiler.compile("""
            workflow:
              id: invalid-provider
              version: 1
              context:
                name: Ada
                greeting:
                  provider: greeter
                  version: 1
                  config: {region: 7}
                  capabilities: [network]
                  with: {name: 7}
              outputs: [greeting]
        """.trimIndent())
        assertFalse(invalid.isValid)
        assertTrue(invalid.diagnostics.any { it.message.contains("configuration") })
        assertTrue(invalid.diagnostics.any { it.message.contains("bound input schema") })
        assertTrue(invalid.diagnostics.any { it.message.contains("not declared") })
    }

    @Test
    fun `static config rejects context references and infers only with dependencies`() {
        val result = WorkflowCompiler(providerRegistry = registry()).compile("""
            workflow:
              id: static-config
              version: 1
              context:
                name: Ada
                greeting:
                  provider: greeter
                  version: 1
                  config: {region: {${'$'}ref: '${'$'}.name'}}
                  with: {name: {${'$'}ref: '${'$'}.name'}}
              outputs: [greeting]
        """.trimIndent())
        assertFalse(result.isValid)
        assertTrue(result.diagnostics.any { it.message.contains("deployment-static") })
        assertTrue(result.diagnostics.none { it.message.contains("unknown reference root") })
    }

    @Test
    fun `registry rejects duplicate exact identity and unsupported protocol`() {
        val registry = ProviderRegistry()
        val descriptor = ProviderDescriptor("same", 1, ValueSchema.Any, ValueSchema.Any)
        registry.register(descriptor)
        val duplicate = assertThrows(IllegalArgumentException::class.java) { registry.register(descriptor) }
        assertEquals("provider same@1 is already registered", duplicate.message)
        val unsupported = ProviderDescriptor("future", 1, ValueSchema.Any, ValueSchema.Any, protocolFormatVersion = 2)
        val failure = assertThrows(IllegalArgumentException::class.java) { registry.register(unsupported) }
        assertTrue(failure.message!!.contains("unsupported protocol format version 2"))
    }

    @Test
    fun `protocol models zero many and open lifecycle streams`() {
        val messages: List<ProviderLifecycleMessage> = listOf(
            ProviderLifecycleMessage.Emission(Value.StringValue("a"), "e1"),
            ProviderLifecycleMessage.Emission(Value.StringValue("b"), "e2", "correlation"),
            ProviderLifecycleMessage.Completed,
        )
        assertEquals(3, messages.size)
        assertNotNull(ProviderLifecycleMessage.Open)
        assertNotNull(ProviderLifecycleMessage.Failed(Value.StringValue("failure")))
        val request = ProviderInvocationRequest("greeter", 1, io.workflow.core.InvocationId("i"), io.workflow.core.AttemptId("a"), Value.Null, Value.Null)
        assertEquals("i", request.idempotencyKey)
    }
}

private fun CompiledRegister.canonicalProviderJson(): String =
    WorkflowIrDocument("test", 1, io.workflow.core.WorkflowVersionId("test@1"), emptyMap(), listOf(this), emptyList(), "").canonicalJson()
