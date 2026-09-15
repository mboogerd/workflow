package io.workflow.compiler

import io.workflow.core.Value
import io.workflow.core.ValueSchema
import io.workflow.provider.EffectClass
import io.workflow.provider.IdempotencyContract
import io.workflow.provider.ProviderDescriptor
import io.workflow.provider.ProviderInvocationRequest
import io.workflow.provider.ProviderLifecycle
import io.workflow.provider.ProviderLifecycleMessage
import io.workflow.provider.ProviderRegistry
import io.workflow.provider.ProviderCompatibility
import io.workflow.provider.ReconciliationMode
import io.workflow.core.AttemptId
import io.workflow.core.EmissionId
import io.workflow.core.InvocationId
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

    /** A registry with both a pure `greeter` and an effect `notifier` provider, the
     * latter satisfying the pre-existing descriptor-level idempotency requirement
     * so that only the new `idempotency-key` binding is under test. */
    private fun effectRegistry(): ProviderRegistry = registry().also {
        it.register(
            ProviderDescriptor(
                providerId = "notifier",
                version = 1,
                inputSchema = ValueSchema.String,
                emissionSchema = ValueSchema.String,
                effectClass = EffectClass.EFFECT,
                idempotency = IdempotencyContract(ReconciliationMode.IDEMPOTENT_BY_INVOCATION),
            ),
        )
    }

    @Test
    fun `effect provider accepts a string idempotency-key bound to a reference`() {
        val result = WorkflowCompiler(providerRegistry = effectRegistry()).compile(
            """
            workflow:
              id: idempotency-key-accepted
              version: 1
              parameters:
                tag: {schema: string}
              context:
                sent:
                  provider: notifier
                  version: 1
                  with: {${'$'}ref: '${'$'}.parameters.tag'}
                  idempotency-key: {${'$'}concat: ['tag-', {${'$'}ref: '${'$'}.parameters.tag'}]}
              outputs: [sent]
            """.trimIndent(),
        )
        assertTrue(result.isValid, result.diagnostics.joinToString())
        val register = result.ir!!.registers.single { it.name == "sent" }
        assertNotNull(register.provider!!.idempotencyKey)
        assertTrue(register.canonicalProviderJson().contains("idempotency-key"))
    }

    @Test
    fun `effect provider without idempotency-key is a compile diagnostic`() {
        val result = WorkflowCompiler(providerRegistry = effectRegistry()).compile(
            """
            workflow:
              id: idempotency-key-missing
              version: 1
              context:
                sent: {provider: notifier, version: 1, with: hello}
              outputs: [sent]
            """.trimIndent(),
        )
        assertFalse(result.isValid)
        assertTrue(result.diagnostics.any { it.message.contains("must declare idempotency-key") })
    }

    @Test
    fun `non-string idempotency-key is a compile diagnostic for a literal and a referenced value`() {
        val literal = WorkflowCompiler(providerRegistry = effectRegistry()).compile(
            """
            workflow:
              id: idempotency-key-literal-wrong-type
              version: 1
              context:
                sent: {provider: notifier, version: 1, with: hello, idempotency-key: 7}
              outputs: [sent]
            """.trimIndent(),
        )
        assertFalse(literal.isValid)
        assertTrue(literal.diagnostics.any { it.message.contains("idempotency-key") && it.message.contains("incompatible") })

        val referenced = WorkflowCompiler(providerRegistry = effectRegistry()).compile(
            """
            workflow:
              id: idempotency-key-referenced-wrong-type
              version: 1
              parameters:
                tag: {schema: integer}
              context:
                sent:
                  provider: notifier
                  version: 1
                  with: hello
                  idempotency-key: {${'$'}ref: '${'$'}.parameters.tag'}
              outputs: [sent]
            """.trimIndent(),
        )
        assertFalse(referenced.isValid)
        assertTrue(referenced.diagnostics.any { it.message.contains("idempotency-key") && it.message.contains("incompatible") })
    }

    @Test
    fun `idempotency-key is optional for pure read and agentic providers`() {
        val result = WorkflowCompiler(providerRegistry = registry()).compile(
            """
            workflow:
              id: idempotency-key-optional
              version: 1
              context:
                name: Ada
                greeting:
                  provider: greeter
                  version: 1
                  config: {region: eu}
                  with: {name: {${'$'}ref: '${'$'}.name'}}
              outputs: [greeting]
            """.trimIndent(),
        )
        assertTrue(result.isValid, result.diagnostics.joinToString())
        assertEquals(null, result.ir!!.registers.single { it.name == "greeting" }.provider!!.idempotencyKey)
    }

    @Test
    fun `idempotency-key binds inside a match branch and contributes its dependency`() {
        val decisionSchema = ValueSchema.TaggedUnion(
            discriminator = "kind",
            variants = mapOf(
                "ok" to ValueSchema.Object(mapOf("kind" to ValueSchema.Object.Field(ValueSchema.String))),
            ),
        )
        val registry = effectRegistry().also {
            it.register(ProviderDescriptor("decisions", 1, ValueSchema.Any, decisionSchema))
        }
        val result = WorkflowCompiler(providerRegistry = registry).compile(
            """
            workflow:
              id: idempotency-key-match-branch
              version: 1
              parameters:
                tag: {schema: string}
              context:
                decisions: {provider: decisions, version: 1}
                selected:
                  schema: string
                  match:
                    value: {${'$'}ref: '${'$'}.decisions'}
                    cases:
                      ok:
                        provider: notifier
                        version: 1
                        with: hello
                        idempotency-key: {${'$'}ref: '${'$'}.parameters.tag'}
              outputs: [selected]
            """.trimIndent(),
        )
        assertTrue(result.isValid, result.diagnostics.joinToString())
        val match = result.ir!!.registers.single { it.name == "selected" }.match!!
        val branch = match.cases.getValue("ok") as CompiledProducer.Provider
        assertNotNull(branch.value.idempotencyKey)

        val missing = WorkflowCompiler(providerRegistry = registry).compile(
            """
            workflow:
              id: idempotency-key-match-branch-missing
              version: 1
              context:
                decisions: {provider: decisions, version: 1}
                selected:
                  schema: string
                  match:
                    value: {${'$'}ref: '${'$'}.decisions'}
                    cases:
                      ok: {provider: notifier, version: 1, with: hello}
              outputs: [selected]
            """.trimIndent(),
        )
        assertFalse(missing.isValid)
        assertTrue(missing.diagnostics.any { it.message.contains("must declare idempotency-key") })
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
    fun `static config accepts parameter bindings and does not infer them as context dependencies`() {
        val result = WorkflowCompiler(providerRegistry = registry()).compile("""
            workflow:
              id: parameter-config
              version: 1
              parameters:
                region: {schema: string}
              context:
                greeting:
                  provider: greeter
                  version: 1
                  config: {region: {${'$'}ref: '${'$'}.parameters.region'}}
                  with: {name: Ada}
              outputs: [greeting]
        """.trimIndent())
        assertTrue(result.isValid, result.diagnostics.joinToString())
        assertTrue(result.ir!!.registers.single().dependencies.isEmpty())
        assertTrue(result.ir!!.canonicalJson().contains("\"root\":\"parameters\""))
    }

    @Test
    fun `provider emission schema feeds downstream binding validation`() {
        val result = WorkflowCompiler(providerRegistry = registry()).compile("""
            workflow:
              id: chained-providers
              version: 1
              context:
                first:
                  provider: greeter
                  version: 1
                  config: {region: eu}
                  with: {name: Ada}
                second:
                  provider: greeter
                  version: 1
                  config: {region: eu}
                  with: {name: {${'$'}ref: '${'$'}.first.message'}}
              outputs: [second]
        """.trimIndent())
        assertTrue(result.isValid, result.diagnostics.joinToString())
        assertEquals(listOf("first"), result.ir!!.registers.single { it.name == "second" }.dependencies)
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
        val incompatible = ProviderDescriptor(
            "incompatible",
            1,
            ValueSchema.Any,
            ValueSchema.Any,
            compatibility = ProviderCompatibility(setOf(2)),
        )
        val incompatibleFailure = assertThrows(IllegalArgumentException::class.java) { registry.register(incompatible) }
        assertTrue(incompatibleFailure.message!!.contains("incompatible with supported IR"))
    }

    @Test
    fun `protocol models zero many and open lifecycle streams`() {
        val messages: List<ProviderLifecycleMessage> = listOf(
            ProviderLifecycleMessage.Emission(Value.StringValue("a"), EmissionId("e1"), InvocationId("i"), AttemptId("a")),
            ProviderLifecycleMessage.Emission(Value.StringValue("b"), EmissionId("e2"), InvocationId("i"), AttemptId("a"), "correlation"),
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
