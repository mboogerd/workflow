# Core model

## Concepts

- **Workflow definition**: an immutable, versioned graph specification.
- **Workflow instance**: one correlated execution of a workflow definition.
- **Fact**: a named value in an instance. An ordinary fact is assigned once.
- **Node**: a definition that can produce one fact.
- **Reference**: a statically visible dependency on another fact or a singular
  path within it.
- **Provider**: a versioned implementation behind a typed input/output contract.
- **Outcome**: the terminal result of a provider call.

The workflow context is the set of facts currently known for an instance. It is
not a mutable object handed to each node. Its apparent growth comes from facts
reaching terminal assignment.

## Minimal graph constructs

The initial language has these semantic constructs:

| Construct | Meaning |
| --- | --- |
| `workflow` | Declares identity, version, inputs, outputs, and policies. |
| `source` | Accepts external events and contributes correlated facts. |
| `let` | Builds a value with the binding algebra; performs no provider call. |
| `call` | Invokes a provider with declared bindings and produces an `Outcome`. |
| `match` | Selects one finite branch from a terminal value or outcome. |
| `map` | Applies a finite subgraph or provider call to each collection element and gathers the results. |
| `output` | Names the facts exported as the workflow result. |

References in a construct's inputs define its incoming edges. There is no
separate `sequence`, `parallel`, or `join` construct:

- one dependency creates sequencing;
- independent ready nodes create parallelism;
- multiple dependencies create a join.

## Assignment and growth

An ordinary fact transitions from unresolved to exactly one terminal value.
That assignment is immutable.

Growth is allowed only when its algebra and completion rule are declared. A
finite `map` input is sealed before expansion. A future streaming collection
must define key identity, duplicate handling, merge semantics, and a frontier
or seal before absence or completion can be concluded.

## Graph shape

After treating `map` bodies as nested graph boundaries, an initial-version
workflow is finite and acyclic. Providers may run internal loops, but those
loops are not part of the outer workflow semantics.

Dynamic task creation is limited to finite `map` expansion. Each expanded node
receives a stable identity derived from the map node and item key or index.

## Names and identity

Every node has a stable logical identifier independent of source-code line
number or declaration order. Renaming or structurally changing a node changes
the compiled workflow version unless an explicit compatibility mapping is
provided in a future version-migration design.

