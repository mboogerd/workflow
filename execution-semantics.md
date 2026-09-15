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
- journal batch identity and mutation ordinal;
- register name and monotonically increasing revision;
- assigned value;
- producer and activation identity;
- captured dependency revisions;
- provider invocation and emission identity when applicable;
- journal position and timestamp metadata.

The runtime commits through a journal batch abstraction. A v1 batch contains
exactly one assignment mutation. The assignment, its current-view update, and
the durable activation intents caused by that assignment become visible
atomically. This single-mutation restriction is an implementation contract, not
authoring syntax; a later version may permit several mutations without changing
assignment identity or journal provenance.

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

Every distinct dependency-revision vector is retained and executed. If a newer
activation is created while an older activation is running, v1 neither cancels
the older work nor suppresses its eventual result. Completion order therefore
may differ from input order, and provenance is the way consumers distinguish
those results.

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

V1 deliberately supplies no latest-only suppression, activation coalescing, or
stale-work cancellation. Providers that require those policies must serialize
or reconcile them internally. Later language versions may add explicit policies
without changing the v1 meaning.

## Conditional execution

`match` reevaluates when its discriminator is assigned. It activates the selected
branch for that discriminator revision. A later discriminator assignment may
select another branch; it does not retract assignments or effects produced by an
earlier branch.

Branches declare compatible output schemas. Branch activation and any provider
effects carry the discriminator revision in their provenance. Every assignment
from the selected case's output is assigned to the match register. A selected
case that remains open may therefore continue to emit even after a newer
discriminator revision selected another case; v1 retains both with their
provenance and applies no stale-result suppression.

## Scatter/gather

`map` takes a finite collection from the captured input revision and activates
one nested graph per element. Item identities derive from the map node and input
index or key. Elements may run concurrently.

The first gathered assignment is emitted when every item body has assigned its
selected output register at least once. Array results preserve index order;
keyed results use canonical key order. After readiness, every later selected
output assignment from an item emits another gathered assignment using the
latest selected output revision for every item in that map activation. An empty
input emits one empty gathered value immediately.

If an item output producer terminates or fails before its first assignment, the
map activation fails. Failure after a first assignment is recorded but does not
erase the current gathered values. A later collection assignment creates a new
map activation rather than mutating or reusing the previous one.

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
