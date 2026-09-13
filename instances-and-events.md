# Instances and events

## Identity

A workflow instance has the logical identity:

`(workflow_id, workflow_version, correlation_key)`

It also has an engine-assigned `run_id`. Each accepted source event has a stable
`event_id`. Provider invocations and attempts derive their identity from these
values plus node and map-item identity.

The correlation key is produced by a source-specific, portable binding. Events
without a valid key are rejected or routed to an explicit dead-letter policy;
they never create an accidental global instance.

## Source declarations

A source declares:

- provider and event schema;
- optional acceptance predicate;
- correlation-key binding;
- cardinality: `one` or `many`;
- duplicate identity and handling;
- ordering key when order matters;
- opening and closure policy;
- late-event policy.

Multiple sources may contribute facts to the same instance. A `one` source
assigns one fact. A `many` source contributes to a declared collection with
stable item keys and merge semantics.

## Opening and closure

The first accepted event normally opens an instance. Nodes with no unresolved
dependencies may run immediately after opening; source-dependent nodes wait for
their facts.

Any input whose absence or completeness matters must eventually be sealed. A
source or collection closure policy may use an explicit close event, expected
count, event-time window plus watermark, or administrative action. Wall-clock
silence alone is not logical proof of absence unless the declared policy makes
it so and records the decision.

## Duplicate and late events

Duplicate `event_id` values are idempotently ignored after the first accepted
record. Conflicting payloads under one event id are protocol errors.

Late events follow a source policy: reject, append while the instance remains
open, or open a new run/revision. A completed fact is never silently rewritten.

## Lifecycle

An instance progresses monotonically through:

`open → sealed → completed | failed | cancelled`

`sealed` means no new source contributions are accepted under the current run's
closure rules. It does not imply that all provider calls have finished.

Completion requires every exported fact to be terminal and every required
effect/recovery obligation to be resolved. Runtime retention and archival are
separate from logical completion.

