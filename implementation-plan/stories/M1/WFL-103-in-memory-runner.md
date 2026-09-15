---
id: WFL-103
milestone: M1
title: Execute an expression workflow through the in-memory journal
implementation_model: gpt-5.6-luna
implementation_effort: xhigh
review_model: gpt-5.6-sol
review_effort: high
depends_on: [WFL-102]
---

# Outcome

Release v0.1: a user can run a YAML workflow containing parameters and
expressions to quiescence and receive its declared outputs plus inspectable
assignment and activation provenance.

# Required reading

- `core-model.md`
- `execution-semantics.md`
- `execution-backend.md`
- `evolution-seams.md`
- `failure-and-recovery.md` for record-shape awareness only
- WFL-101 and WFL-102 implementation and tests

# Scope

- Define interfaces for journal-batch commit, current-view projection,
  activation planning/claiming, provider invocation, and output publication.
  Only the first three need concrete behavior in this story.
- Implement an in-memory store whose single lock/transaction atomically commits
  one assignment, updates the current view, and persists all resulting activation
  intents.
- Reject non-singleton v1 batch proposals at the commit boundary, even if a test
  constructs one below the public model constructor.
- Activate dependency-free expression producers once at execution startup.
- After each assignment, create exactly one activation intent for each newly
  eligible producer and captured dependency-revision vector.
- Evaluate expression activations from their immutable captured input snapshots,
  append assignments, and continue until quiescent.
- Make activation identity deterministic from execution, context, producer, and
  dependency-revision vector. Restarting the same in-memory test fixture must not
  duplicate a previously persisted intent.
- Implement `run <yaml> --parameters <json>`, printing declared output values and
  their revisions as JSON.
- Add an inspection option that prints ordered batches, assignments, activation
  intents, and activations without exposing internal object addresses.

# Acceptance criteria

- `examples/hello-expression.yaml` executes end to end from the CLI.
- Independent roots are declaration-order independent; a multi-input expression
  activates only after every required input is assigned.
- Optional references distinguish absent, missing, and assigned null.
- An equal value assigned in a direct store-level test still creates a new
  revision and a new dependent activation vector.
- A failure injected between planning and worker execution leaves committed
  activation intent available to claim; there is no assignment-without-intent
  crash gap.
- Current views can be rebuilt from journal records in a test and match the live
  projection.
- The milestone release gate and CLI demonstration pass.

# Non-goals

- Providers, long-lived executions, correlation, `match`, `map`, SQLite, retry,
  or recovery.
- Latest-only or stale-result suppression.
- Multi-assignment batches.

# Verification

```bash
./gradlew test --rerun-tasks
./gradlew run --args='run examples/hello-expression.yaml --parameters examples/hello-parameters.json'
```

# Handoff

Report the service boundaries, atomicity mechanism, activation identity rule,
CLI output, milestone test evidence, and remaining limitations.
