# Agentic steps

## Position in the model

An agentic step is a provider call with stronger nondeterminism, capability,
budget, persistence, and observability metadata. It does not introduce a second
workflow semantics.

The outer workflow remains deterministic around a recorded agentic outcome. An
agent may reason, loop, call tools, or use a graph framework internally; the
outer engine sees one versioned invocation with attempts and one terminal
outcome.

## Required descriptor fields

In addition to the normal provider contract, an agentic provider declares:

- model selection policy and pinned strategy/prompt version;
- typed task input and required output schema;
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

Frameworks such as Koog or LangGraph are suitable implementations of an
agentic provider. Their internal nodes remain private when they are merely an
implementation strategy.

An internal action becomes an outer workflow node only when it needs one or more
of the following:

- independent durability or replay;
- external visibility or approval;
- separate authorization or secrets;
- independent retry, timeout, or compensation policy;
- data dependencies shared with other workflow nodes.

This boundary prevents agent-framework control flow from leaking into the
minimal workflow language while allowing important effects to remain explicit.

## Output discipline

Agentic providers return schema-valid structured output. Free-form text may be
a field in that structure, but it is not a substitute for a typed outcome.
Validation failure is a typed provider failure and may enter the recovery
policy.

Agent tools receive stable invocation and attempt context. Effectful tool calls
must follow the same idempotency and reconciliation rules as any other provider
effect.

## Agentic recovery

Recovery agents are separate provider roles. They inspect a bounded failure
record and return a typed recovery proposal. They do not directly mutate the
workflow graph, grant themselves capabilities, or conceal the original failure.
The rules are specified in [Failure and recovery](failure-and-recovery.md).

