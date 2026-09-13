# Failure and recovery

## Failure model

Provider activations have an operational lifecycle independent of register
assignments. An activation may:

- emit zero or more values;
- complete normally;
- fail;
- time out;
- be cancelled;
- remain open.

Lifecycle events and attempts are append-only records. A failure does not erase
the register's current value and does not create a replacement assignment unless
the provider explicitly emits an application-level error value.

Errors contain a stable type, human-readable message, retry classification,
causal details safe for workflow processing, and references to protected
diagnostics. Secrets and unrestricted logs are not copied into context values.

## Recovery order

Recovery proceeds through this policy:

1. Validate and classify the failure.
2. Apply deterministic retry for declared transient failures within budget.
3. Reconcile any external effect whose completion is ambiguous.
4. Apply an explicitly configured deterministic fallback or compensation.
5. If allowed, ask an agentic recovery provider for a constrained proposal.
6. Validate the proposal against policy, capabilities, budget, provenance, and
   current register revisions.
7. Execute the accepted action or require human intervention / stop the
   activation.

An agent must not guess whether an ambiguous effect occurred. Reconciliation
precedes retry or compensation.

## Recovery decisions

An agentic recovery provider emits one of:

- `Retry(adjustment)`
- `Reconcile(query)`
- `Compensate(operation)`
- `Substitute(provider, input_adjustment)`
- `Emit(value)`
- `RequestHuman(question, choices)`
- `Skip(justification)`
- `Abort(reason)`

The available variants may be narrowed per provider. The runtime rejects
proposals outside policy or requiring undeclared capabilities. Recovery authority
cannot exceed the failed operation's authority plus explicitly declared
recovery-only capabilities.

## Reactive consequences

A successful retry or accepted `Emit` appends a new register assignment and
therefore activates downstream definitions. `Skip` ends only the failed
activation; it does not assign or remove a current value.

If dependencies have newer revisions by the time recovery completes, the result
still carries its original dependency provenance. Suppression or latest-only
handling is deferred execution policy rather than implicit behavior.

## Determinism and audit

The failure, recovery request, proposal, validation result, human answer, new
attempt, and emissions are durable events. Replay follows recorded accepted
decisions and does not ask a recovery model again.

Compensation is an explicit provider operation because real effects are rarely
perfectly reversible.

