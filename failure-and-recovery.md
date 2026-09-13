# Failure and recovery

## Failure model

Every provider call reaches a terminal outcome. The common outcome algebra is:

- `Succeeded(value, metadata)`
- `Failed(error, metadata)`
- `TimedOut(error, metadata)`
- `Cancelled(reason, metadata)`

Errors contain a stable type, human-readable message, retry classification,
causal details safe for the workflow, and references to protected diagnostics.
Secrets and unrestricted logs are not copied into workflow values.

Attempts are append-only records. The node outcome is assigned once after the
policy stops creating attempts.

## Recovery order

Recovery proceeds through the following ordered policy:

1. Validate and classify the failure.
2. Apply deterministic retry for declared transient failures within budget.
3. Reconcile any external effect whose completion is ambiguous.
4. Apply an explicitly configured deterministic fallback or compensation.
5. If allowed, ask an agentic recovery provider for a constrained proposal.
6. Validate that proposal against policy, capabilities, budget, and current
   execution state.
7. Execute the accepted action or require human intervention / terminate.

An agent must not guess whether an ambiguous effect occurred. Reconciliation
precedes retry or compensation.

## Recovery decisions

An agentic recovery provider returns one of these typed proposals:

- `Retry(adjustment)`
- `Reconcile(query)`
- `Compensate(operation)`
- `Substitute(provider, input_adjustment)`
- `RequestHuman(question, choices)`
- `Skip(justification)`
- `Abort(reason)`

The available variants may be narrowed per node. The engine rejects proposals
outside the node's policy or requiring undeclared capabilities. Recovery
authority can never exceed the authority of the failed operation plus explicitly
declared recovery-only capabilities.

## Determinism and audit

The recovery request, proposal, validation result, human answer, and resulting
attempt are durable events. Replay follows the recorded accepted decision; it
does not ask the recovery model again.

Retry adjustments and substitutions become part of the new attempt input and
are therefore visible to downstream audit and reproducibility tooling.

## Workflow-level handling

Normal domain failures are data and should be handled with `match`. Unhandled
failures follow workflow policy: fail, wait for intervention, or emit a declared
partial result. Skipping is never implicit and must satisfy downstream schema
and completeness requirements.

