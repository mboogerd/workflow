package io.workflow.runtime

import io.workflow.compiler.WorkflowCompiler
import io.workflow.core.DeterministicIdSource
import io.workflow.core.EmissionId
import io.workflow.core.ExecutionId
import io.workflow.core.FixedClock
import io.workflow.core.Value
import io.workflow.core.ValueSchema
import io.workflow.provider.EffectClass
import io.workflow.provider.IdempotencyContract
import io.workflow.provider.ProviderDescriptor
import io.workflow.provider.ProviderImplementation
import io.workflow.provider.ProviderInvocationRequest
import io.workflow.provider.ProviderLifecycleMessage
import io.workflow.provider.ProviderRegistry
import io.workflow.provider.ReconciliationMode
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** WFL-505: an author-bound `idempotency-key` becomes the provider's
 * reconciliation identity while invocation identity is untouched. */
class IdempotencyKeyRuntimeTest {
    private val requests = mutableListOf<ProviderInvocationRequest>()

    private fun implementation() = ProviderImplementation { request ->
        requests += request
        listOf(
            ProviderLifecycleMessage.Emission(Value.StringValue("sent"), EmissionId("emission-${request.attemptId.value}"), request.invocationId, request.attemptId),
            ProviderLifecycleMessage.Completed,
        )
    }

    private fun registry(): ProviderRegistry = ProviderRegistry().also {
        it.register(
            ProviderDescriptor(
                providerId = "notifier",
                version = 1,
                inputSchema = ValueSchema.String,
                emissionSchema = ValueSchema.String,
                effectClass = EffectClass.EFFECT,
                idempotency = IdempotencyContract(ReconciliationMode.IDEMPOTENT_BY_INVOCATION),
            ),
            implementation(),
        )
    }

    private fun boundYaml() = """
        workflow:
          id: idempotency-key-runtime
          version: 1
          parameters:
            tag: {schema: string}
          context:
            sent:
              provider: notifier
              version: 1
              with: {${'$'}ref: '${'$'}.parameters.tag'}
              idempotency-key: {${'$'}concat: ['ext-', {${'$'}ref: '${'$'}.parameters.tag'}]}
          outputs: [sent]
    """.trimIndent()

    private fun runner(prefix: String, providers: ProviderRegistry = registry()) = InMemoryWorkflowRunner(
        compiler = WorkflowCompiler(providers), providerRegistry = providers,
        idSource = DeterministicIdSource(prefix), clock = FixedClock(Instant.EPOCH), workerCount = 1,
    )

    @Test
    fun `two separate activations with the same bound key converge on one idempotency key but keep distinct invocation ids`() {
        val providers = registry()
        val first = runner("run-a-", providers).run(
            boundYaml(), parameters = mapOf("tag" to Value.StringValue("widget")), executionId = ExecutionId("execution-a"),
        )
        val second = runner("run-b-", providers).run(
            boundYaml(), parameters = mapOf("tag" to Value.StringValue("widget")), executionId = ExecutionId("execution-b"),
        )

        assertTrue(first.isSuccessful, first.failures.joinToString())
        assertTrue(second.isSuccessful, second.failures.joinToString())
        assertEquals(2, requests.size)
        val (firstRequest, secondRequest) = requests
        assertEquals("ext-widget", firstRequest.idempotencyKey)
        assertEquals("ext-widget", secondRequest.idempotencyKey)
        assertNotEquals(firstRequest.invocationId, secondRequest.invocationId)

        // The bound key is also recorded on the invocation event for each execution.
        assertTrue(first.journal.providerInvocations().any { it.value == Value.StringValue("ext-widget") })
        assertTrue(second.journal.providerInvocations().any { it.value == Value.StringValue("ext-widget") })
    }

    @Test
    fun `the bound key changes when its referenced input changes`() {
        val providers = registry()
        val widget = runner("run-widget-", providers).run(
            boundYaml(), parameters = mapOf("tag" to Value.StringValue("widget")), executionId = ExecutionId("execution-widget"),
        )
        val gadget = runner("run-gadget-", providers).run(
            boundYaml(), parameters = mapOf("tag" to Value.StringValue("gadget")), executionId = ExecutionId("execution-gadget"),
        )

        assertTrue(widget.isSuccessful, widget.failures.joinToString())
        assertTrue(gadget.isSuccessful, gadget.failures.joinToString())
        assertEquals(2, requests.size)
        assertEquals("ext-widget", requests[0].idempotencyKey)
        assertEquals("ext-gadget", requests[1].idempotencyKey)
    }

    @Test
    fun `a provider without idempotency-key still defaults to the logical invocation id`() {
        val providers = ProviderRegistry().also {
            it.register(
                ProviderDescriptor(
                    providerId = "unbound-notifier",
                    version = 1,
                    inputSchema = ValueSchema.Any,
                    emissionSchema = ValueSchema.String,
                    effectClass = EffectClass.PURE,
                ),
                implementation(),
            )
        }
        val yaml = """
            workflow:
              id: idempotency-key-absent
              version: 1
              context:
                sent: {provider: unbound-notifier, version: 1}
              outputs: [sent]
        """.trimIndent()
        val result = runner("run-default-", providers).run(yaml, executionId = ExecutionId("execution-default"))

        assertTrue(result.isSuccessful, result.failures.joinToString())
        val request = requests.single()
        assertEquals(request.invocationId.value, request.idempotencyKey)
        assertTrue(result.journal.providerInvocations().single().value == Value.StringValue(request.invocationId.value))
    }
}
