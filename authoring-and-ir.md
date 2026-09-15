# Authoring and IR

## Initial authoring surface

The initial authoring surface is YAML 1.2 parsed as data with the core schema.
The language permits no custom YAML tags or executable constructors. Parsers
place bounded limits on aliases, nesting, and document size.

The document has one `workflow` object:

```yaml
workflow:
  id: issue-to-pr
  version: 1

  parameters:
    agent:
      schema: string

  context:
    event:
      provider: jira.issue.created
      version: 1
      config:
        assignee: {$ref: "$.parameters.agent"}

    repo:
      $ref: "$.event.issue.component"

    pr:
      provider: agentic-developer
      version: 1
      with:
        prompt: {$ref: "$.event.issue.description"}
        repo: {$ref: "$.repo"}
        agent-id:
          $concat:
            - agentic-builder-
            - {$ref: "$.event.issue.id"}

  outputs:
    - pr
```

Each `context` entry defines one named versioned register. Its definition is:

- an expression directly;
- a mapping containing `provider`;
- a mapping containing `match`; or
- a mapping containing `map`.

A producer mapping may contain an optional `schema` field for its assigned
value. A provider mapping additionally contains optional deployment-static
`config`, activation-bound `with`, and execution-policy fields defined by the
provider descriptor and runtime policy schema.

The exact v1 conditional shape is:

```yaml
context:
  handled:
    schema: string
    match:
      value: {$ref: "$.result"}
      cases:
        Succeeded:
          $ref: "$.match.value.description"
        Failed:
          provider: report-failure
          version: 1
          with:
            error: {$ref: "$.match.error"}
```

The discriminator schema is a tagged union. Each case value is one producer
definition and every variant must have exactly one case. Inside a case,
`$.match` denotes the entire captured discriminator value; ordinary context and
parameter references remain available. Every case producer must have the same
canonical output schema as the match register.

The exact v1 finite-map shape is:

```yaml
context:
  models:
    map:
      over: {$ref: "$.repositories"}
      context:
        snapshot:
          provider: repository-read
          version: 1
          with:
            repository: {$ref: "$.item"}
        model:
          provider: repository-model
          version: 1
          with:
            snapshot: {$ref: "$.snapshot"}
      output: model
```

`over` must produce a finite array or object. The body is a nested context graph
and `output` names exactly one body register. The body additionally exposes
`$.item` and `$.key`; array keys are zero-based integer indexes and object keys
are strings in canonical key order. Body register names may not shadow an outer
register or reserved lexical root. Body definitions may use the same four
producer forms recursively, subject to finite acyclic validation.

There are no `source`, `call`, or `let` wrappers. Entry order is not semantic.
References in expressions, provider inputs, branches, and map bodies define
dependencies.

Plain YAML values and reserved `$` operators are specified in
[Values and bindings](values-and-bindings.md). Unknown semantic fields and
operators are compile errors rather than implicit extension points.

YAML anchors and aliases may be accepted for presentation-level reuse. They are
expanded before semantic validation and have no identity or runtime meaning.

## Compilation pipeline

1. Parse one YAML document using the constrained YAML profile.
2. Decode register producers and expression ASTs.
3. Resolve provider descriptors and schemas.
4. Infer dependencies from references.
5. Validate the complete producer graph.
6. Normalize and serialize canonical IR with YAML source locations.
7. Assign or verify workflow version and content hash.
8. Deploy the IR and separately packaged providers to an execution backend.

## Static validation

Compilation rejects:

- duplicate or unstable register identities;
- multiple definitions for one context name;
- cycles outside supported nested constructs;
- references to unknown registers or non-singular paths;
- missing required bindings and schema incompatibilities;
- non-exhaustive or output-incompatible branches;
- unresolved provider versions;
- undeclared capabilities or effects;
- unsafe retry/reconciliation combinations;
- provider configuration containing context references where the provider
  requires deployment-static configuration;
- outputs that cannot ever receive an assignment under any reachable state.

The compiler does not reject repeated provider emissions, repeated correlation
ids, or repeated assignments of equal payloads. Those are normal runtime events.

## Portable IR

The IR contains at least:

- workflow identity, version, content hash, and policy versions;
- stable register/producer ids and source locations;
- producer kinds, dependencies, and nested graph structure;
- expression and predicate ASTs;
- input, emission, and error schemas;
- provider ids, versions, configurations, effects, capabilities, and bound
  effect idempotency keys;
- assignment, correlation, activation, and provenance semantics;
- retry, timeout, cancellation, recovery, and map policies;
- journal-batch, activation-intent, and causation semantics;
- exported register names.

The IR is language-neutral and versioned independently from the YAML surface
schema. A backend either supports its IR version or rejects it before execution.

## Other authoring forms

Generated typed builders or embedded DSLs may later target the same IR. They
must not introduce semantics unavailable to YAML and must emit an inspectable
artifact independent of the host language.

Complex glue uses a provider. Future tooling may package a local transform as a
provider automatically, but the resulting IR must still expose a versioned
provider boundary.
