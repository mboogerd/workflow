---
id: WFL-101
milestone: M1
title: Scaffold the standalone runtime and core portable types
implementation_model: gpt-5.6-luna
implementation_effort: medium
review_model: gpt-5.6-sol
review_effort: medium
depends_on: []
---

# Outcome

Create a buildable Kotlin/JVM 21 application with the portable value, schema,
identity, and journal-record types needed by the first vertical slice. There is
no compiler or executor yet, but the project has executable test and application
entry points for subsequent stories.

# Required reading

- `README.md`
- `core-model.md`
- `values-and-bindings.md`
- `schemas.md`
- `evolution-seams.md`
- Optional toolchain reference: `../computenet/gradle/libs.versions.toml`

# Scope

- Add a Gradle wrapper and Kotlin application build using Kotlin 2.1.21 and a
  Java 21 toolchain.
- Add pinned Kaml, kotlinx-serialization, and JUnit 5 dependencies. Do not add a
  dependency on the ComputeNet project.
- Define a lossless portable `Value` model for null, Boolean, string, integer,
  arbitrary-precision decimal, array, object, and tagged values. Canonical JSON
  serialization must preserve integer versus decimal.
- Define the complete v1 `ValueSchema` algebra and value validation from
  `schemas.md`.
- Define typed IDs for workflows, versions, executions, contexts, registers,
  producers, journal batches, assignments, activation intents, activations,
  invocations, attempts, and emissions.
- Define immutable record DTOs needed for journal batches and assignments,
  including format version, mutation ordinal, causation, and timestamps.
- Provide injectable clock and ID-source interfaces with production and
  deterministic-test implementations.
- Establish packages that keep model/schema types independent of compiler,
  runtime, provider implementations, CLI, and persistence.

# Acceptance criteria

- `./gradlew test --rerun-tasks` succeeds and executes tests.
- Canonical value round-trip tests cover all value kinds, large integers,
  scale-preserving decimals, nested data, and tagged values.
- Schema tests cover valid and invalid values, closed objects, optional fields,
  arrays, tagged unions, and the conservative compatibility rules.
- A journal-batch constructor rejects zero or multiple assignment mutations in
  v1.
- Core types do not import compiler, runtime, provider, CLI, SQLite, or
  ComputeNet classes.
- The application main entry point runs and reports that no command was given.

# Non-goals

- YAML parsing, expression ASTs, graph validation, execution, persistence, or
  provider invocation.
- General schema subtyping or JSON Schema support.
- Future multi-mutation behavior beyond representing and rejecting invalid v1
  batch cardinality.

# Verification

```bash
./gradlew test --rerun-tasks
./gradlew run --args='--help'
```

# Handoff

Report the package map, dependency versions, public core types, tests executed,
and any unavoidable deviation from the specified schema or value model.
