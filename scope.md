# Scope

## Objective

Provide the smallest declarative language that makes workflow dataflow,
integration, and lifecycle visible while delegating implementation details to
reusable providers and execution engines.

The primary use case is a deterministic workflow containing potentially
nondeterministic agentic steps and constrained agentic recovery.

## Initial version

The initial version includes:

- staged Python authoring and a portable, serializable IR;
- event sources and explicit workflow-instance correlation;
- named single-assignment facts and dependency-derived scheduling;
- immediate parallel execution of ready nodes;
- a small binding and predicate algebra, without arbitrary expressions;
- typed provider calls, including external and agentic providers;
- explicit terminal outcomes, attempts, retry, timeout, and cancellation;
- finite conditional branches;
- finite scatter/gather through `map`;
- durable execution records sufficient for deterministic replay;
- stable effect identity and reconciliation of ambiguous effects.

## Explicit non-goals for the initial version

- A general-purpose programming language.
- Arbitrary Python execution inside the portable workflow graph.
- Implicit dependency discovery from arbitrary context reads.
- Mutable shared state.
- Unbounded `while` loops, recursive workflows, or general cyclic graphs.
- Full streaming query semantics over never-ending collections.
- Exactly-once external effects without cooperation from the external system.
- Exposing an agent framework's internal graph as workflow nodes by default.
- Transparent migration of in-flight instances between incompatible workflow
  versions.
- Distributed scheduling as a language-level concern.

## Deferred extensions

- Bounded iteration or fixpoint as a first-class graph construct.
- A richer portable expression language, if real workflows demonstrate the
  need.
- Auto-packaged local transform providers for small pieces of arbitrary code.
- Recursive or streaming monotone collections with explicit frontiers.
- Subworkflows, reusable graph fragments, and higher-order workflow templates.
- Workflow-version migration protocols.

## Current open decisions

1. Whether singular references use RFC 9535 JSONPath syntax, generated typed
   accessors, or both as frontends to one path IR.
2. The exact schema system and compatibility rules at provider boundaries.
3. Whether the first implementation supports declared growing collections or
   only finite `map` inputs.
4. The provider packaging, discovery, and deployment protocol.
5. Which execution records are retained indefinitely versus compacted.
6. The exact boundary between ComputeNet-native provider cells and remote
   provider workers.
7. Whether bounded iteration belongs in the first usable release or the first
   extension.

