---
id: WFL-301
milestone: M3
title: Add match and finite-map constructs to canonical IR
implementation_model: gpt-5.6-luna
implementation_effort: xhigh
review_model: gpt-5.6-sol
review_effort: high
depends_on: [WFL-203]
---

# Outcome

The compiler can represent and validate the complete v1 producer graph:
expressions, providers, reactive `match`, and finite `map` with a nested lexical
scope and stable item identity policy.

# Required reading

- `core-model.md`
- `execution-semantics.md`, especially conditional execution and scatter/gather
- `values-and-bindings.md`
- `authoring-and-ir.md`
- `schemas.md`
- Existing canonical IR and dependency-validation tests

# Scope

- Implement the exact YAML forms for `match` and `map` from
  `authoring-and-ir.md`; do not invent alternative surface syntax.
- Add IR nodes for match discriminator, exhaustive tagged-union cases, compatible
  branch result producers, map input, item/key bindings, nested producer graph,
  output selection, and collection/result ordering policy.
- Resolve ordinary references lexically. A map body may additionally reference
  only `$.item` and `$.key`; nested references must not escape into another
  context or map item.
- Derive dependencies across the outer and nested graphs without executing
  provider code.
- Validate exhaustive/output-compatible match branches, finite array or object
  map inputs, unique stable nested producer IDs, acyclicity across graph
  boundaries, and result schemas.
- Canonicalize branch and keyed-map ordering so YAML presentation order does not
  change the IR hash.
- Add compiler fixtures for valid and invalid nested constructs.

# Acceptance criteria

- Every new YAML/IR form is documented and covered by canonical round-trip tests.
- Reordering match case mappings or keyed input object presentation does not
  destabilize canonical IR identity where semantics are unchanged.
- Invalid branch exhaustiveness, branch schema mismatch, unavailable `$.item` or
  `$.key`, nested duplicate identity, non-collection input, and cross-scope
  references receive source-located diagnostics.
- The compiler still rejects general cycles and arbitrary loops.
- Existing expression and provider IR hashes remain stable.

# Non-goals

- Executing `match` or `map`, streaming collections, collection mutation,
  frontiers, fixpoints, or cross-context aggregation.

# Verification

```bash
./gradlew test --rerun-tasks
./gradlew test --tests '*Compiler*Match*Test' --rerun-tasks
./gradlew test --tests '*Compiler*Map*Test' --rerun-tasks
```

# Handoff

Report the final YAML shapes, IR additions, lexical-scope rules, validation
matrix, compatibility impact, and tests executed.
