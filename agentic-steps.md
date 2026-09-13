# Agentic steps

## Position in the model

An agentic step is a provider with stronger nondeterminism, capability, budget,
persistence, and observability metadata. It does not introduce separate workflow
semantics.

Each dependency update may create a new agent activation. The activation receives
an immutable input snapshot and records its dependency revisions. An agent may
reason, loop, call tools, or use a graph framework internally; the outer runtime
observes its emissions, attempts, lifecycle, and effects.

## Required descriptor fields

In addition to the normal provider contract, an agentic provider declares:

- model selection policy and pinned strategy/prompt version;
- typed activation input and emitted-value schema;
- allowed tools and the effect class of each tool;
- capability and secret requirements;
- token, cost, time, tool-call, and iteration budgets;
- output validation and acceptance policy;
- persistence granularity and resumability;
- trace and artifact retention policy;
- cancellation behavior.

Runtime-resolved model identifiers and all materially relevant settings are
recorded with each attempt.

## Internal agent graphs

Frameworks such as Koog or LangGraph are suitable provider implementations.
Their internal nodes remain private when they are merely an implementation
strategy.

An internal action becomes an outer workflow value only when it needs one or
more of:

- independent durability or replay;
- external visibility or approval;
- separate authorization or secrets;
- independent retry, timeout, or compensation policy;
- data dependencies shared with other workflow producers.

## Emission discipline

Agentic emissions conform to a declared schema. Free-form text may be a field in
that structure but is not a substitute for typed protocol behavior.

An agent normally emits once per activation, but the language does not require
that cardinality. Every emission is independently logged and may trigger
downstream work. Effectful agent tools follow the same invocation identity and
reconciliation rules as other provider effects.

## Agentic recovery

Recovery agents are separate provider roles. They inspect a bounded failure and
provenance record and emit a typed recovery proposal. They do not directly
rewrite context history, grant themselves capabilities, or conceal the original
failure. See [Failure and recovery](failure-and-recovery.md).

