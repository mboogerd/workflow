---
id: WFL-304
milestone: M3
title: Deliver the finite multi-repository system-model slice
implementation_model: gpt-5.6-luna
implementation_effort: high
review_model: gpt-5.6-sol
review_effort: high
depends_on: [WFL-302, WFL-303]
---

# Outcome

Release v0.3: one YAML workflow reads a finite repository inventory, builds each
repository model concurrently, gathers the models, resolves outgoing and incoming
dependencies, and emits a system-wide architectural model.

# Required reading

- `README.md`
- `execution-semantics.md`, conditional execution and scatter/gather
- `agentic-steps.md`
- `evolution-seams.md`, especially application deltas versus runtime semantics
- All M3 compiler/runtime tests

# Scope

- Add deterministic demo schemas for repository inventory, repository model,
  dependency edge/evidence, resolved topology, and system architecture.
- Add fake providers for inventory, per-repository context building,
  bidirectional dependency resolution, and system-model generation. The resolver
  computes reverse edges from the entire gathered snapshot in one acyclic step.
- Add a YAML workflow using finite `map` for repository models, followed by one
  topology resolver and one system-model provider.
- Include a repository with no dependencies, a dependency chain, and a cycle in
  repository-domain data to demonstrate that domain cycles do not become
  workflow graph cycles.
- Add an end-to-end test proving per-repository work can complete out of order
  while gather and final output remain deterministic.
- Run the workflow twice with two authoritative inventory snapshots, one adding
  and removing repositories, and prove each run reflects only its captured finite
  input. Do not describe this as incremental processing.
- Document the exact limitation: every triggering snapshot rebuilds every
  repository model.

# Acceptance criteria

- The example validates, compiles, and runs through the public CLI with the demo
  provider profile.
- Every repository model records repository and commit identity; topology and
  system outputs record the gathered model revisions from which they derive.
- Incoming and outgoing dependency views agree and have deterministic ordering.
- Repository-domain cycles are represented successfully.
- Changed inventory produces a new full result without mutating the previous
  activation history.
- The named milestone test and full test gate execute and pass.

# Non-goals

- Real Git/LLM calls, commit streams, persistence, correlated cross-context
  aggregation, incremental model updates, or atomic multi-repository deltas.

# Verification

```bash
./gradlew test --tests '*MultiRepositoryWorkflowTest' --rerun-tasks
./gradlew test --rerun-tasks
./gradlew run --args='run examples/multi-repository.yaml --parameters examples/multi-repository-parameters.json --providers demo'
```

# Handoff

Report the workflow shape, demo command/output, deterministic gather evidence,
domain-cycle evidence, full-recompute limitation, and tests executed.
