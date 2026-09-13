# Core model

## Concepts

- **Workflow definition**: an immutable, versioned producer graph.
- **Workflow execution**: one activation of that definition and its durable
  journals.
- **Workflow instance**: the reactive state associated with one optional
  correlation key inside an execution.
- **Context**: the current register values of one workflow instance.
- **Register**: one named context value backed by an append-only assignment log.
- **Assignment**: one revision written to a register.
- **Current value**: the latest assignment to a register.
- **Producer**: an expression, provider, `match`, or `map` that can assign one
  named register.
- **Reference**: a statically visible dependency on the current value of another
  register or a singular path within it.
- **Activation**: one evaluation caused by a specific dependency-revision vector.
- **Emission**: a value produced by a provider, optionally routed with a
  correlation id.

The context is a materialized view over assignment logs, not a mutable dictionary
whose history is discarded.

## Minimal surface constructs

Each `context` entry defines one named register using one of:

| Form | Meaning |
| --- | --- |
| expression | Recompute a value from referenced current values. This is the default form. |
| `provider` | Activate an integration component and append each emitted value. |
| `match` | Select a producer branch from the current discriminator. |
| `map` | Apply a nested producer graph to a finite collection. |

The workflow's top-level `outputs` list selects the registers exposed outside
the graph; it is not itself a producer.

There are no `source`, `call`, or `let` constructs. Event listeners and one-shot
functions are both providers. A plain expression at a context entry needs no
wrapper.

References define incoming edges. There is no separate `sequence`, `parallel`,
or `join` construct:

- one dependency creates causal sequencing;
- independent activations may execute in parallel;
- multiple references form a reactive join over their current values.

## Assignment

Every accepted push appends a new revision to exactly one `(context, name)`
register. It becomes that register's current value. Payload equality does not
suppress an assignment or its downstream activations.

Each register has one definition but its producer may assign it repeatedly.
Multiple definitions for the same name are compile errors.

Each assignment records producer identity, correlation context, cause,
dependency revisions, and provider invocation/emission identity where applicable.

## Activation

At workflow startup, every producer without context dependencies activates once
in the anonymous context. Creating a correlated workflow instance does not
restart those root producers.

After a register is assigned, every directly dependent producer becomes eligible
for activation when all of its required references have current values. An
activation captures those current dependency revisions as its input snapshot.

Expressions evaluate once per activation. Providers may emit zero, one, or many
assignments during an activation and may complete, fail, or remain open.

## Correlation

An emission with a correlation id targets the context with that key, creating it
if necessary. An emission without one inherits its invocation's context; a root
provider therefore writes to the anonymous context when it omits correlation.

Creating a context copies no values from the originating context. Only the
emitted named value is assigned there.

## Graph shape

The first version is acyclic after treating `map` bodies as nested graph
boundaries. Repeated execution comes from new assignments, not graph cycles.

Dynamic graph expansion is limited to finite `map`. Each expanded producer has
a stable identity derived from the map node and item key or index.
