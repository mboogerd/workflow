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

## Initial-version closure decisions

- Schemas use the closed portable algebra in [Schemas](schemas.md).
- Every dependency-revision vector creates a distinct activation. A newer
  activation does not cancel, coalesce, or invalidate an older one in v1.
- Accepted assignments have one authoritative total journal order per workflow
  execution. Provider-local production order is preserved only where the
  provider protocol declares it.
- Providers are registered explicitly with the runtime. Dynamic package
  discovery and remote deployment are outside v1, although invocation remains
  behind a language-neutral protocol boundary.
- Effectful providers run once for every activation and must satisfy the
  idempotency or reconciliation contract. V1 supplies no repeated-activation
  suppression policy.
- `match` and `map` create independent work for every triggering input revision,
  as defined in [Execution semantics](execution-semantics.md).
- V1 performs no journal compaction. All semantic and operational records are
  retained.
- Contexts and executions have no semantic close operation. A host may stop an
  execution administratively, but that does not synthesize completion facts.

Questions about alternative policies remain valid extension work, but do not
leave v1 runtime behavior unspecified.
