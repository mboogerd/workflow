---
id: WFL-202
milestone: M2
title: Execute provider activations with durable provenance records
implementation_model: gpt-5.6-luna
implementation_effort: xhigh
review_model: gpt-5.6-sol
review_effort: xhigh
depends_on: [WFL-201]
---

# Outcome

The in-memory runtime can schedule provider producers, validate and commit each
emission, propagate downstream work, and record the complete invocation,
attempt, emission, completion, and failure lifecycle.

# Required reading

- `providers-and-effects.md`
- `execution-semantics.md`
- `execution-backend.md`
- `failure-and-recovery.md`
- `agentic-steps.md`
- WFL-201 protocol and registry implementation

# Scope

- Implement provider activation through the provider-invocation boundary using
  Kotlin coroutines without coupling scheduler internals to provider classes.
- Construct stable logical invocation IDs from execution, context, producer, and
  dependency-revision vector; assign distinct physical attempt IDs.
- Bind the immutable captured activation snapshot into a validated invocation
  request.
- Accept zero or more ordered lifecycle messages. Validate emission identity and
  schema before committing each emission through the existing singleton journal
  batch path.
- Record invocation, attempt-start, emission-received/accepted/refused,
  completion, and failure events with causation links.
- Treat invalid output, duplicate emission identity within an invocation, and
  protocol-order violations as typed activation failures without fabricating an
  assignment.
- Execute independent ready providers in parallel using a bounded worker pool.
  Ensure journal order, rather than coroutine completion assumptions, controls
  observable assignment order.
- Preserve every activation; do not add latest-only, coalescing, or cancellation
  behavior.

# Acceptance criteria

- Test providers cover zero, one, and many emissions; normal completion; typed
  failure; invalid output; duplicate emission; and protocol-order violation.
- Every accepted provider emission creates its own singleton journal batch and
  can activate downstream expressions/providers.
- Re-running a worker claim for an already completed activation does not invoke
  the provider again.
- Parallel-provider tests use deterministic barriers rather than sleeps and show
  that journal commit order is authoritative.
- Failure records retain safe diagnostics while keeping arbitrary provider stack
  traces outside context values.
- Existing M1 behavior stays green.

# Non-goals

- Retry, timeout, cancellation, reconciliation, remote providers, long-lived
  root streams, or SQLite durability.

# Verification

```bash
./gradlew test --rerun-tasks
```

# Handoff

Report lifecycle state transitions, worker-pool behavior, identity/dedup rules,
failure cases, and tests executed.
