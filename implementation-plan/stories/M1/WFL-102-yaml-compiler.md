---
id: WFL-102
milestone: M1
title: Compile expression-only YAML into canonical IR
implementation_model: gpt-5.6-luna
implementation_effort: high
review_model: gpt-5.6-sol
review_effort: high
depends_on: [WFL-101]
---

# Outcome

The CLI can parse and statically validate an expression-only workflow, emit a
canonical language-neutral IR document, and produce stable workflow identity and
content hash diagnostics.

# Required reading

- `authoring-and-ir.md`
- `values-and-bindings.md`
- `schemas.md`
- `core-model.md`
- `evolution-seams.md`
- WFL-101 implementation and tests

# Scope

- Parse the constrained YAML document shape and reject custom tags, unknown
  semantic fields, duplicate keys, excessive aliasing/nesting, and multiple
  documents.
- Implement IR nodes for literals, required and optional singular references,
  object/list construction, concatenation, equality, presence, Boolean
  composition, and negation.
- Implement RFC 9535 singular-path parsing only for the subset required by
  `values-and-bindings.md`; reject wildcards, filters, slices, and recursive
  descent.
- Parse workflow identity/version, parameters, expression context entries,
  explicit schemas, and outputs.
- Infer dependencies from references and reject unknown references, dependency
  cycles, duplicate register definitions, unavailable lexical roots, and
  impossible outputs.
- Infer expression schemas where possible and enforce v1 compatibility.
- Normalize canonical IR independent of YAML mapping order and calculate a
  SHA-256 content hash over canonical serialization.
- Preserve source locations in diagnostics without including them in the
  semantic content hash.
- Add `validate <yaml>` and `compile <yaml> --output <json>` CLI commands.

# Acceptance criteria

- The example from `authoring-and-ir.md`, reduced to expression-only producers,
  compiles deterministically.
- Reordering YAML context entries leaves canonical IR and content hash unchanged.
- Every reserved expression operator has positive and negative compiler tests.
- Cycle, unknown reference, non-singular path, schema mismatch, unknown field,
  and unreachable-output diagnostics name the source location and semantic path.
- Unknown producer forms are rejected rather than preserved as extensions.
- Compilation performs no provider call or other effect.

# Non-goals

- Provider, `match`, or `map` producers.
- Executing IR.
- Historical references, whole-context access, arbitrary expressions, or custom
  YAML tags.

# Verification

```bash
./gradlew test --rerun-tasks
./gradlew run --args='validate examples/hello-expression.yaml'
./gradlew run --args='compile examples/hello-expression.yaml --output build/hello-expression.ir.json'
```

# Handoff

Report supported YAML/IR forms, canonicalization decisions, diagnostics covered,
the generated example IR path, and tests executed.
