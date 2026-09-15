---
id: WFL-502
milestone: M5
title: Enforce effect idempotency and reconciliation
implementation_model: gpt-5.6-luna
implementation_effort: xhigh
review_model: gpt-5.6-sol
review_effort: xhigh
depends_on: [WFL-501]
---

# Outcome

Effectful provider attempts use stable idempotency identity, and an ambiguous
completion is reconciled before the runtime retries, compensates, or exposes a
terminal intervention requirement.

# Required reading

- `providers-and-effects.md`, effect classes, invocation identity, reconciliation
- `failure-and-recovery.md`, recovery order
- `execution-backend.md`, effects
- WFL-501 retry state machine and restart classification

# Scope

- Pass the logical invocation ID as the provider protocol idempotency key and
  preserve it across retries and restart.
- Require every effectful descriptor to declare idempotent-by-key,
  reconcile-by-key, or unsafe-ambiguous behavior. Reject missing declarations.
- Add a versioned reconciliation request/result protocol capable of reporting
  definitely-not-applied, definitely-applied with recorded result,
  still-unknown, and protocol failure.
- Persist reconciliation request, attempt, result, and decision records.
- On ambiguous completion, reconcile before any retry. Reuse a confirmed applied
  result without repeating the effect; permit retry only after confirmed
  not-applied or when the descriptor's idempotency contract makes it safe.
- Leave still-unknown/unsafe cases in a durable intervention-required state.
- Define compensation only as a separately registered provider operation; do not
  infer or automatically execute an inverse in this story.
- Add deterministic fake external systems that detect duplicate writes and can
  simulate lost replies around the effect boundary.

# Acceptance criteria

- Fault tests cover effect applied/reply lost, effect not applied/reply lost,
  duplicate retry, reconciliation unavailable, reconciliation crash/restart, and
  idempotent-key replay.
- No scenario blindly repeats an ambiguous non-idempotent effect.
- A confirmed applied result flows downstream once with provenance linking the
  original invocation and reconciliation decision.
- Replay performs neither effect nor reconciliation calls.
- Compiler tests reject unsafe descriptor/policy combinations.

# Non-goals

- Domain-specific compensation logic, distributed transactions, exactly-once
  guarantees without provider cooperation, or human-interface implementation.

# Verification

```bash
./gradlew test --tests '*Effect*Test' --rerun-tasks
./gradlew test --tests '*Reconciliation*Test' --rerun-tasks
./gradlew test --rerun-tasks
```

# Handoff

Report effect state machine, provider contracts, fault matrix, evidence that
ambiguous effects are never blindly retried, and tests executed.
