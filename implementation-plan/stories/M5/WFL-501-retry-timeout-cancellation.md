---
id: WFL-501
milestone: M5
title: Implement deterministic retry, timeout, and cancellation policy
implementation_model: gpt-5.6-luna
implementation_effort: high
review_model: gpt-5.6-sol
review_effort: high
depends_on: [WFL-404]
---

# Outcome

Provider activations obey versioned retry, timeout, and cancellation policies;
all physical attempts and decisions are durable and resume safely.

# Required reading

- `failure-and-recovery.md`
- `providers-and-effects.md`
- `execution-semantics.md`, concurrent completion
- `agentic-steps.md`, cancellation behavior
- Existing restart/replay and provider lifecycle implementation

# Scope

- Define the exact v1 YAML/IR policy fields for maximum attempts, retryable error
  classes, deterministic backoff schedule, attempt timeout, activation deadline,
  and cancellation grace period. Document them in the normative topic files and
  append matching rationale entries.
- Validate policies during compilation against provider descriptor lifecycle and
  effect declarations.
- Persist retry decisions and their due times; resume must not reset a budget or
  recompute an already recorded backoff choice.
- Implement attempt timeouts and cooperative cancellation through the provider
  protocol. Record providers that ignore or cannot support cancellation.
- Keep one stable logical invocation ID across retry attempts and allocate a new
  physical attempt ID for each attempt.
- Ensure retries of pure/read providers are safe. Hold ambiguous effect/agentic
  attempts for WFL-502 reconciliation rather than blindly retrying them.
- Use an injectable scheduler/clock for deterministic tests; no wall-clock sleeps.

# Acceptance criteria

- Tests cover success after retry, exhausted budget, non-retryable failure,
  deterministic backoff, attempt timeout, activation deadline, cooperative and
  ignored cancellation, crash during backoff, and resume.
- Attempt numbers and IDs remain stable and auditable across restart.
- Replay consumes recorded retry/timeout/cancellation decisions without waiting
  or invoking providers.
- Invalid unsafe policy combinations fail compilation.
- Existing continuous workflow behavior remains green when no policy is present.

# Non-goals

- Effect reconciliation implementation, compensation, agentic recovery proposal,
  latest-only cancellation, or human interaction.

# Verification

```bash
./gradlew test --tests '*Retry*Test' --rerun-tasks
./gradlew test --tests '*Timeout*Test' --rerun-tasks
./gradlew test --rerun-tasks
```

# Handoff

Report normative syntax changes and rationale IDs, retry state machine, restart
behavior, cancellation limitations, unsafe combinations, and tests executed.
