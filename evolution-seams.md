# Evolution seams

## Intent

V1 does not implement state transitions, differential collections, atomic
multi-register writes, cross-context aggregation, or workflow-to-workflow
materialized views. It does preserve the identities, records, and component
boundaries those features will need.

These are implementation constraints for v1, not additional YAML constructs.

## Journal batches

All mutations enter the runtime through a versioned journal-batch proposal. A v1
batch contains exactly one assignment mutation and receives a stable
`journal_batch_id`. The assignment retains its own producer, activation,
emission, context, register, and revision identity.

The storage transaction also persists current-view projection changes and every
activation intent derived from the committed assignment. Workers consume only
committed intents.

This makes single assignments safe today while leaving a direct extension path
to an atomic batch containing several keyed mutations. V1 validation must reject
a batch with cardinality other than one.

## Reconstructible views

Assignment, activation, attempt, emission, decision, and recovery journals are
authoritative. Current register values, runnable-work indexes, and inspection
views are projections that can be rebuilt from durable records.

V1 retains all records and uses explicit format versions and migrations from the
first persistent schema. Implementations must not encode semantic state solely
in process memory or provider instances.

## Stable causation and identity

Records use typed stable identifiers and explicit links for:

- workflow definition, version, and execution;
- correlation context;
- journal batch and assignment revision;
- producer, activation, dependency-revision vector, and activation intent;
- provider invocation, physical attempt, and emission;
- causal predecessor or external event identity where supplied.

Clocks and identifier generators are injectable so tests can reproduce ordering
and crash boundaries. Application payloads may contain commit ranges, patches,
or other deltas, but v1 gives those values no privileged runtime meaning.

## Runtime boundaries

The purpose-built runtime separates these responsibilities even when they are
implemented in one module and process:

- IR loading and compilation;
- provider descriptor resolution;
- journal-batch commit;
- current-view projection;
- activation planning and work claiming;
- provider invocation;
- exported-output publication and inspection.

The boundaries need not be remote APIs. They exist so a later transition
producer, keyed materialized view, alternative backend, or cross-workflow output
can replace one responsibility without rewriting the workflow compiler.

## Forward mapping

The intended extension path is:

| Future capability | V1 seam it extends |
| --- | --- |
| Previous-state transition with compare-and-swap | Assignment history, captured dependency revisions, journal-batch validation |
| Atomic repository/topology change set | Relax one-mutation batch cardinality; retain one batch identity |
| Keyed materialized collection | Stable correlation/item keys plus reconstructible projections |
| Delta-triggered activation | Durable activation planner and explicit causation |
| Per-key latest-only or coalescing | Durable intents and dependency-revision provenance |
| Cross-workflow materialized output | Exported-output publication boundary and versioned schemas |
| ComputeNet backend | Portable IR and backend service boundaries |

None of these mappings promises that the future feature is already present. In
particular, v1 has no self-reference, compare-and-swap state transition,
multi-assignment transaction, wildcard context reference, collection frontier,
or output subscription.
