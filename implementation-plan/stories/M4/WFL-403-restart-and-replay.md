---
id: WFL-403
milestone: M4
title: Resume interrupted executions and replay recorded outcomes
implementation_model: gpt-5.6-luna
implementation_effort: xhigh
review_model: gpt-5.6-sol
review_effort: xhigh
depends_on: [WFL-402]
---

# Outcome

After process restart, the runtime reconstructs execution state, resumes pending
work without duplicating completed provider effects, and can replay recorded
execution history without invoking providers.

# Required reading

- `execution-semantics.md`, replay
- `failure-and-recovery.md`
- `providers-and-effects.md`, invocation identity and effects
- `execution-backend.md`
- WFL-402 SQLite schema, claim lifecycle, and fault-injection tests

# Scope

- Add startup reconstruction for deployed workflow IR, execution/context state,
  projections, pending/expired activation claims, and open/terminal provider
  attempts.
- Define deterministic treatment for attempts interrupted without a terminal
  record: pure/read attempts may be scheduled as a new physical attempt under
  the same logical invocation; effect/agentic attempts become ambiguous and are
  held for later reconciliation support.
- Never invoke a provider for an activation already recorded as complete.
- Implement replay as a separate mode that consumes journaled assignments,
  provider emissions/outcomes, branch choices, and lifecycle records to rebuild
  views and activation history without executing provider code.
- Verify content hash and IR/database format compatibility before resume or
  replay.
- Add `resume <database>` and `replay <database> [--execution <id>]` CLI
  commands with machine-readable summaries.
- Add process-boundary tests using a child JVM or equivalent real reopen, not
  merely reconstructing objects in the same runtime instance.

# Acceptance criteria

- Crash/reopen tests cover pending intent, claimed activation, provider after
  emission but before completion, pure/read interrupted attempt, and ambiguous
  effect attempt.
- Completed invocations are never called during resume or replay.
- Replay with provider implementations that throw on invocation still succeeds,
  proving providers were not called.
- Replayed current outputs and provenance equal the recorded execution.
- An incompatible workflow content hash or newer storage/IR version fails before
  work is claimed.
- All SQLite and in-memory storage contract tests remain green.

# Non-goals

- Automatic reconciliation, compensation, retry budgets, journal compaction, or
  distributed recovery.

# Verification

```bash
./gradlew test --tests '*Restart*Test' --rerun-tasks
./gradlew test --tests '*Replay*Test' --rerun-tasks
./gradlew test --rerun-tasks
```

# Handoff

Report restart classification, replay boundary, process-level scenarios,
provider non-invocation evidence, CLI behavior, and tests executed.
