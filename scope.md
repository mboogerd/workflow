# Scope

## Objective

Provide the smallest declarative language that makes reactive workflow dataflow
and reusable integrations visible while delegating implementation details to
providers and an execution backend.

The primary use case is predictable orchestration containing potentially
nondeterministic agentic providers and constrained agentic recovery.

## Initial version

The initial version includes:

- YAML authoring that maps closely to a portable, serializable IR;
- named, versioned context registers backed by append-only assignment logs;
- latest-assignment references and dependency-derived activation;
- immediate parallel execution of independent activated nodes;
- expressions as the default context definition;
- one uniform provider construct for event sources, integrations, pure
  functions, and agentic work;
- anonymous workflow startup and optional correlation routing on emissions;
- repeated assignment and downstream reactivation;
- a small YAML expression and predicate algebra, without arbitrary code;
- provider attempts, failure, retry, timeout, cancellation, and reconciliation;
- reactive conditional branches;
- finite scatter/gather through `map`;
- durable records sufficient for deterministic replay of observed execution;
- a purpose-built, backend-independent first runtime.

## Explicit non-goals for the initial version

- A general-purpose programming language.
- Separate `source`, `call`, or `let` syntax.
- Arbitrary host-language execution inside the portable workflow graph.
- Implicit dependency discovery from provider implementation behavior.
- In-place mutation without an assignment revision.
- Once-only execution per correlation id.
- Declared provider cardinality, automatic deduplication, debouncing, or
  coalescing.
- A natural terminal state for continuously emitting workflows.
- Unbounded loops, recursive workflows, or general cyclic graphs.
- Exactly-once external effects without cooperation from the external system.
- Exposing an agent framework's internal graph as workflow nodes by default.
- Distributed scheduling.
- A dependency on ComputeNet for the first runtime.

## Deferred extensions

- Per-node policies such as once, distinct-until-changed, debounce, coalesce,
  latest-only, or explicit cardinality.
- Context sealing, completion, retention, and event-window policies.
- Bounded iteration or fixpoint as a first-class graph construct.
- A richer portable expression language, if real workflows demonstrate the
  need.
- Lightweight packaging of local transform providers.
- Recursive or streaming collections with explicit frontiers.
- Subworkflows, reusable graph fragments, and higher-order workflow templates.
- Generated typed authoring frontends.
- A ComputeNet execution backend.

## Current open decisions

1. The exact schema system and compatibility rules at provider boundaries.
2. Scheduling semantics when a dependency changes while its consumer is still
   running, including stale result handling.
3. Whether provider emissions are globally ordered per context or only ordered
   per named register.
4. The provider packaging, discovery, and deployment protocol.
5. How effectful downstream providers expose and control repeated activation.
6. The exact reactive semantics of `match` and `map` under repeated updates.
7. Which execution records are retained indefinitely versus compacted.
8. When a workflow execution or keyed context may be closed and collected.

