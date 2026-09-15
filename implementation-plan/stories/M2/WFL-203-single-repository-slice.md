---
id: WFL-203
milestone: M2
title: Deliver the one-repository modeling vertical slice
implementation_model: gpt-5.6-luna
implementation_effort: medium
review_model: gpt-5.6-sol
review_effort: medium
depends_on: [WFL-202]
---

# Outcome

Release v0.2: a deterministic example workflow accepts one repository and commit
SHA, invokes repository-reader and model-builder providers, and emits a typed
repository architectural model with complete provenance.

# Required reading

- `README.md`
- `agentic-steps.md`
- `providers-and-effects.md`
- All M1 and M2 implementation tests

# Scope

- Add a representative `RepositorySnapshot`, `RepositoryModel`, and evidence
  schema in example/test code. Use stable entity identifiers and explicit source
  references rather than a single prose blob.
- Implement deterministic fake in-process providers:
  - repository reader: repository identity plus commit SHA to a small source-tree
    snapshot fixture;
  - model builder: source snapshot to repository model, declared `agentic` but
    making no external LLM call.
- Add an example YAML workflow wiring parameters to the reader, builder, a
  summary expression, and declared outputs.
- Add a named end-to-end test that compiles and executes through the public CLI
  application boundary, not private runtime helpers.
- Make CLI provider profiles explicit, for example `--providers demo`, so an
  unknown provider is never silently supplied by a global singleton.
- Document the command, output shape, and why the provider is deterministic test
  scaffolding rather than an LLM integration.

# Acceptance criteria

- One command validates and runs the example from YAML to a repository-model
  output.
- Inspection output links the final assignment to the builder emission,
  invocation, activation, reader assignment, and input commit SHA.
- Changing the input fixture or commit SHA produces a distinct captured input
  and assignment history.
- No network, GitHub credential, Git checkout, or LLM API is required.
- `./gradlew test --rerun-tasks` includes and executes the named milestone test.

# Non-goals

- Real repository or LLM providers, multiple repositories, `match`, `map`,
  repeated commit events, persistence, or incremental updates.

# Verification

```bash
./gradlew test --tests '*SingleRepositoryWorkflowTest' --rerun-tasks
./gradlew test --rerun-tasks
./gradlew run --args='run examples/single-repository.yaml --parameters examples/single-repository-parameters.json --providers demo'
```

# Handoff

Report the example command and output, provider profile, end-to-end provenance
chain, test evidence, and known limitations.
