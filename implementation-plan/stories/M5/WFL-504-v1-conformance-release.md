---
id: WFL-504
milestone: M5
title: Establish the v1 conformance corpus and release gate
implementation_model: gpt-5.6-luna
implementation_effort: high
review_model: gpt-5.6-sol
review_effort: xhigh
depends_on: [WFL-502, WFL-503]
---

# Outcome

Release v1.0: the implementation is checked against a machine-readable
conformance corpus, the motivating continuous repository workflow runs with
failure/recovery instrumentation, and a clean distribution can validate, run,
inspect, resume, and replay workflows.

# Required reading

- Every normative topic file in the repository root
- `README.md`, especially document authority and cross-cutting invariants
- `implementation-plan/README.md`
- All milestone end-to-end tests and CLI documentation
- Optional corpus-structure reference only:
  `../computenet/concord/schema/scenario.md`

# Scope

- Create an implementation-neutral YAML or JSON conformance scenario format with
  versioned schema, inputs/provider scripts, expected records/outputs/failures,
  and explicit normative requirement references.
- Populate scenarios for startup, expressions, schemas, providers, repeated
  assignments, correlation, match, map/gather, failure, retry, effects,
  reconciliation, agentic metadata/recovery, restart, and replay.
- Add a runner that executes every corpus scenario against the public runtime
  interfaces and reports executed/passed/failed counts. A zero-scenario run must
  fail.
- Add a traceability index from normative sections/invariants to scenarios and a
  test that rejects dangling references or uncovered v1 invariants.
- Upgrade the continuous repository demo to inject transient model-builder
  failure, safe retry, and one ambiguous fake publication effect reconciled
  without duplication.
- Complete CLI and operator documentation for validate, compile, run, start,
  inspect, stop, resume, replay, database selection, provider profiles, and
  machine-readable output.
- Add a Gradle distribution and a clean-directory smoke test using only generated
  distribution contents plus checked-in examples.
- Audit documentation against implementation. If a real contradiction is found,
  stop and report it rather than silently weakening the conformance expectation.

# Acceptance criteria

- The corpus runner executes a nonzero scenario count and all scenarios pass.
- Every README cross-cutting invariant and every normative v1 construct has a
  traceable scenario or an explicit, reviewed explanation why it is compile-time
  only.
- The full repository workflow survives the injected failures with no duplicated
  fake external effect.
- A clean distribution validates and runs the expression, single-repository,
  multi-repository, and continuous examples.
- Replay succeeds without loading provider implementations.
- `./gradlew clean check --rerun-tasks` and the distribution smoke test pass.
- Release notes explicitly list deferred incremental transitions, keyed
  materialized collections, atomic multi-assignment changes, cross-context
  aggregation, and cross-workflow subscriptions as absent from v1.

# Non-goals

- Real GitHub or LLM provider integrations, distributed scheduling, ComputeNet
  backend implementation, or any deferred incremental feature.

# Verification

```bash
./gradlew clean check --rerun-tasks
./gradlew conformance --rerun-tasks
./gradlew installDist
./gradlew distributionSmokeTest --rerun-tasks
```

# Handoff

Report scenario counts and coverage, full release commands, distribution smoke
evidence, final demo behavior, documentation reconciliation, and the explicit
v1 deferred-feature list.
