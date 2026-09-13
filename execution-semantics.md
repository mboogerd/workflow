# Execution semantics

## Startup

Starting a workflow creates one execution with an anonymous context. Every
producer definition with no context references is activated once there. A
constant expression emits once. A provider may emit once and finish, emit
repeatedly, remain open, or fail.

Root producers are not restarted merely because a provider emission creates a
correlated context.

## Assignment journal

Every accepted producer output becomes an immutable assignment record containing
at least:

- workflow execution and context identity;
- register name and monotonically increasing revision;
- assigned value;
- producer and activation identity;
- captured dependency revisions;
- provider invocation and emission identity when applicable;
- journal position and timestamp metadata.

The current context view selects the latest assignment for each name. Assigning
an equal payload still creates a revision and is observable as a new push.

## Activation

When an assignment commits, every directly dependent definition is reconsidered.
It activates when all required references have current values. The activation
captures one immutable input snapshot identified by its dependency-revision
vector.

An expression emits one assignment for each activation. A provider may emit zero
or more values. Each emission independently appends an assignment and can trigger
further work.

Declaration order has no semantic effect. Independent activations may execute in
parallel.

## Correlation routing

A provider emission is routed before it becomes an assignment:

- explicit correlation id: target that keyed context, creating it if absent;
- omitted correlation id: inherit the activation's context;
- omitted id from an anonymous root: remain in the anonymous context.

Routing to a new context copies no other values. Reusing an existing correlation
id appends another revision to the target register and triggers its downstream
dependencies again. Correlation is not deduplication.

## Concurrent completion

The journal serializes accepted assignments. Its commit order determines the
current value when concurrent providers emit to the same execution. Each result
retains its input-revision provenance, including results that complete after a
newer input activation has begun.

The first milestone does not yet define latest-only suppression, activation
coalescing, or cancellation of stale work. The selected policy is an open design
decision and must be fixed before effectful concurrent activations are considered
production-safe.

## Conditional execution

`match` reevaluates when its discriminator is assigned. It activates the selected
branch for that discriminator revision. A later discriminator assignment may
select another branch; it does not retract assignments or effects produced by an
earlier branch.

Branches declare compatible output schemas. Branch activation and any provider
effects carry the discriminator revision in their provenance.

## Scatter/gather

`map` takes a finite collection from the captured input revision and activates
one nested graph per element. Item identities derive from the map node and input
index or key. Elements may run concurrently.

The gathered assignment is emitted when every item activation for that input
revision reaches its map policy's completion condition. Array results preserve
index order; keyed results use canonical key order. A later collection assignment
creates a new map activation rather than mutating the previous one.

## Quiescence and completion

An execution is quiescent when it currently has no runnable activations, but an
open provider may emit again later. Quiescence is not completion.

The first milestone has no implicit workflow completion rule. Closing providers,
contexts, executions, and their retention is deferred policy.

## Replay

Replay rebuilds current views and activation history from the ordered assignment,
provider-attempt, decision, and recovery journals. It consumes recorded provider
emissions and outcomes rather than repeating effects or model calls.

Determinism is defined relative to this recorded order. A deliberate re-execution
of providers creates a new workflow execution or explicitly authorized attempt;
it is not replay.
