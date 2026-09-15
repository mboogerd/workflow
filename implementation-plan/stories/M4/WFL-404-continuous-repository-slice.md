---
id: WFL-404
milestone: M4
title: Deliver the continuous durable repository workflow
implementation_model: gpt-5.6-luna
implementation_effort: high
review_model: gpt-5.6-sol
review_effort: high
depends_on: [WFL-401, WFL-403]
---

# Outcome

Release v0.4: a durable demonstration consumes repeated main-branch commit
events and publishes a new complete repository/topology/system snapshot for each
event, surviving a process restart with inspectable provenance.

# Required reading

- `instances-and-events.md`
- `execution-semantics.md`
- `evolution-seams.md`
- The M3 multi-repository workflow and all M4 runtime tests

# Scope

- Add a deterministic controllable commit-event provider emitting repository ID,
  branch, delivery ID, and commit SHA. It must be usable across a real process
  restart without network services.
- Add a provider activated by each main-branch event that returns the current
  authoritative finite repository inventory fixture. Feed that snapshot through
  the existing M3 map/resolver/system-model pipeline.
- Keep the global pipeline in the anonymous context. Add a separate small
  correlated projection in the example to demonstrate per-repository routing
  without implying that global aggregation reads across contexts.
- Add SQLite-backed `start`, `resume`, `inspect`, and administrative `stop`
  demonstration commands or documented equivalents.
- Include a process-level acceptance test: emit event A, produce system snapshot
  A, stop after a controlled crash point, resume, emit event B, and produce
  snapshot B without duplicating completed work.
- Inspection must show each system-model revision's triggering event and complete
  dependency-revision provenance.
- Document that this release recomputes all repository models after every event,
  performs no delivery deduplication, and may accept stale completion order under
  the specified v1 policy.

# Acceptance criteria

- Repeated events create repeated system-model revisions even for equal payloads.
- Non-main branch fixtures are filtered by explicit provider configuration or
  provider behavior documented in the example.
- Restart does not repeat completed fake agent invocations and does not lose
  pending activation intents.
- Correlated repository projections remain isolated and are not used as hidden
  inputs to the global pipeline.
- CLI inspection produces a stable JSON representation suitable for assertions.
- The named milestone test and full test suite execute and pass.

# Non-goals

- Real GitHub webhooks, real LLM calls, incremental repository models,
  delivery-ID deduplication, latest-only scheduling, or atomic multi-repository
  change sets.

# Verification

```bash
./gradlew test --tests '*ContinuousRepositoryWorkflowTest' --rerun-tasks
./gradlew test --rerun-tasks
./gradlew run --args='demo continuous-repositories --database build/demo-workflow.db'
```

# Handoff

Report the process-level scenario, CLI commands and inspected provenance,
restart evidence, explicit v0.4 limitations, and tests executed.
