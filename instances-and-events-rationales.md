# Instances and events rationales

This is a chronological, append-only decision log. It explains the design in
[Instances and events](instances-and-events.md) but is not itself normative.

## 2026-09-13 — INSTANCE-001: Separate correlation identity from run identity

**Status:** Active

Multiple upstream events may need to enrich the same context. A correlation key
answers which logical instance receives an event, while a run id distinguishes
new executions, retries at workflow scope, or later revisions. Conflating them
would either prevent aggregation or make reruns overwrite history.

Workflow version participates in identity so that an event is never silently
interpreted under a newly deployed, incompatible graph.

## 2026-09-13 — INSTANCE-002: Declare source cardinality

**Status:** Active

A source expected exactly once has different duplicate and completion semantics
from an accumulating source. Declaring `one` versus `many` makes conflicts,
deduplication, and collection identity statically visible.

## 2026-09-13 — INSTANCE-003: Require explicit closure for negative conclusions

**Status:** Active

An open event stream can always produce another item, so neither “all items
arrived” nor “no matching event exists” follows from current state alone. A
close event, count, or event-time frontier turns that open-world input into a
finite fact the workflow may safely inspect.

This is the event-lifecycle counterpart of the language's monotonicity rule.

## 2026-09-13 — INSTANCE-004: Make duplicate handling identity-based

**Status:** Active

At-least-once delivery is normal for integrations. Stable event ids make
deduplication deterministic. Conflicting content under the same id is surfaced
as corruption or protocol error rather than resolved by arrival order.

