---
id: WFL-302
milestone: M3
title: Execute reactive match branches per discriminator revision
implementation_model: gpt-5.6-luna
implementation_effort: high
review_model: gpt-5.6-sol
review_effort: high
depends_on: [WFL-301]
---

# Outcome

The runtime executes exactly the selected `match` branch for each discriminator
revision and preserves all earlier branch assignments and effects as history.

# Required reading

- `execution-semantics.md`, conditional execution
- `values-and-bindings.md`, provider result variants
- `failure-and-recovery.md`
- WFL-301 match IR and compiler tests

# Scope

- Plan a match activation whenever its discriminator receives an assignment and
  required bindings are ready.
- Select a case from the captured discriminator revision, create stable nested
  activation identities, and execute only that case's producer graph.
- Carry discriminator revision and enclosing activation provenance into every
  nested assignment and provider invocation.
- Assign the match register only from the selected branch output after that
  branch produces a value.
- Treat an unrecognized runtime tag as a typed activation failure even though
  static validation should normally prevent it.
- On a later discriminator revision, execute the newly selected branch without
  retracting or rewriting earlier branch results.
- Make restart-safe activation identity choices even though durable restart is
  implemented later.

# Acceptance criteria

- Tests cover every branch, repeated discriminator revisions selecting the same
  and different branches, nested provider failure, malformed runtime tags, and
  provenance.
- An unselected branch performs no provider invocation.
- Equal discriminator payload assignments still create distinct activations.
- Earlier branch assignments remain queryable after later revisions.
- Parallel WFL-303 integration requires no changes to public match semantics.

# Non-goals

- Branch retraction, skipped-terminal facts, latest-only policy, recovery
  proposals, or `map` runtime behavior.

# Verification

```bash
./gradlew test --tests '*Match*Test' --rerun-tasks
./gradlew test --rerun-tasks
```

# Handoff

Report branch identity/provenance rules, negative-path coverage, tests executed,
and any integration-sensitive files changed.
