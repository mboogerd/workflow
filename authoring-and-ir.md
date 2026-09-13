# Authoring and IR

## Initial authoring surface

The initial authoring surface is an embedded Python DSL. Python code runs at
compile time to construct a workflow definition. It is not shipped as the
workflow's runtime semantics.

The DSL exposes typed builders for workflow constructs, references, bindings,
predicates, schemas, and provider descriptors. IDE type checking, completion,
navigation, and syntax highlighting apply to ordinary Python authoring code.

Arbitrary Python values may help assemble a definition, but any value entering
the graph must become a supported IR node. Python lambdas or functions are not
serialized as hidden expressions.

## Definition-time discipline

A definition must be reproducible from its declared source and dependencies.
Definition code must not make workflow structure depend on current time,
randomness, network responses, ambient secrets, or mutable local state.

The compiler should run definition construction in a controlled process and
record source dependencies. The generated canonical IR and its hash are the
artifact used for deployment and execution.

## Compilation pipeline

1. Import and execute the workflow-definition module in definition mode.
2. Build the graph and binding ASTs.
3. Resolve provider descriptors and schemas.
4. Infer dependencies from references.
5. Validate the complete graph.
6. Normalize and serialize canonical IR with source locations.
7. Assign or verify workflow version and content hash.
8. Deploy the IR and separately packaged providers to an execution engine.

## Static validation

Compilation rejects:

- duplicate or unstable node identities;
- cycles outside supported nested constructs;
- references to unknown facts or non-singular paths;
- missing required bindings and schema incompatibilities;
- non-exhaustive or output-incompatible branches;
- unbounded or unsealed map inputs;
- unresolved provider versions;
- undeclared capabilities or effects;
- unsafe retry/reconciliation combinations;
- absence checks over open inputs;
- outputs that can remain unresolved under a reachable branch.

## Portable IR

The IR contains at least:

- workflow identity, semantic version, content hash, and policy versions;
- stable node ids and source locations;
- node kinds, dependencies, and nested graph structure;
- binding and predicate ASTs;
- input, output, and error schemas;
- provider ids, versions, configurations, effects, and capabilities;
- source correlation/cardinality/closure policies;
- retry, timeout, cancellation, recovery, and map policies;
- exported outputs.

The IR is language-neutral and versioned independently from the Python package.
An engine either supports its IR version or rejects it before execution.

## Other authoring forms

YAML may later provide a direct authoring or interchange syntax for the same IR.
It must not introduce semantics unavailable to the Python DSL. Conversely,
Python convenience APIs must lower to IR rather than becoming Python-only
runtime behavior.

Complex glue uses an explicit provider. A future convenience feature may
package a Python transform as a provider automatically, but the resulting IR
must still show a versioned provider boundary.

