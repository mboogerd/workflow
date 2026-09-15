---
id: WFL-201
milestone: M2
title: Add versioned provider descriptors and an in-process registry
implementation_model: gpt-5.6-luna
implementation_effort: high
review_model: gpt-5.6-sol
review_effort: high
depends_on: [WFL-103]
---

# Outcome

Workflow YAML can bind a versioned provider whose descriptor is resolved from an
explicit in-process registry and validated without executing provider code.

# Required reading

- `providers-and-effects.md`
- `agentic-steps.md`
- `authoring-and-ir.md`
- `schemas.md`
- `evolution-seams.md`
- Existing compiler and runtime service boundaries

# Scope

- Define the provider descriptor specified in `providers-and-effects.md`, with
  input, emission, configuration, and error schemas; effect class; capabilities;
  lifecycle declarations; idempotency/reconciliation metadata; implementation
  binding; and protocol format version.
- Define a language-neutral invocation request and streamed lifecycle response
  model. The in-process Kotlin adapter must implement that model rather than
  bypass it with ad hoc function calls.
- Implement an explicit registry keyed by exact provider id and version. Reject
  ambiguous, missing, and incompatible registrations.
- Extend YAML and canonical IR compilation for provider context entries,
  separating deployment-static `config` from activation-bound `with` values.
- Infer dependencies only from `with` references. Reject context references in
  static configuration when the descriptor marks the field deployment-static.
- Validate config, bound-input, emission, and declared register schemas using
  the v1 schema compatibility rules.
- Preserve provider descriptors as deployment artifacts separate from canonical
  workflow IR; IR records only the stable provider identity/version and validated
  bindings/policy.

# Acceptance criteria

- Compiler tests resolve an exact descriptor and reject unknown provider IDs,
  unknown versions, schema mismatches, undeclared capabilities, and invalid
  context references in static configuration.
- Registering two implementations for the same provider id/version fails with a
  stable diagnostic.
- The protocol model can represent zero, one, or many emissions followed by
  completion or failure, and an activation that remains open.
- Provider compilation invokes no implementation code.
- Existing expression workflows remain byte-for-byte canonical and executable.

# Non-goals

- Provider execution, remote transport, dynamic package discovery, retry,
  timeout, cancellation, reconciliation, or agent tool execution.

# Verification

```bash
./gradlew test --rerun-tasks
./gradlew run --args='validate examples/hello-expression.yaml'
```

# Handoff

Report descriptor and protocol types, registry behavior, compiler changes,
compatibility diagnostics, and tests executed.
