# Execution backend

## First implementation

The first runtime is purpose-built for this workflow model. It does not depend
on ComputeNet. Starting with a small standalone implementation allows the
register, correlation, activation, and provider semantics to stabilize before
mapping them onto a more general dataflow substrate.

The language and IR remain backend-independent. ComputeNet or another engine may
be supported later by implementing the same observable semantics.

## Minimal runtime components

The first backend contains:

- an IR loader and compatibility validator;
- a durable ordered journal for assignments, activations, provider attempts,
  emissions, failures, and recovery decisions;
- a materialized latest-value view per `(execution, instance, register)`;
- a dependency index and reactive activation scheduler;
- correlation routing and lazy keyed-context creation;
- a provider registry and local/remote provider protocol;
- timeout, cancellation, retry, reconciliation, and recovery handling;
- replay, inspection, and provenance queries.

It may initially run as one process with one durable database. Distribution,
replication, and horizontal scheduling are not first-milestone requirements.

## Processing boundary

The backend processes provider emissions through one logical commit path:

1. validate emission identity and schema;
2. resolve its target context from optional correlation;
3. append the assignment and provenance to the journal;
4. update the materialized current-value view;
5. discover affected dependents;
6. append or schedule their activations with captured input revisions.

The journal order is authoritative for replay and current-value selection. The
physical implementation may batch work but must preserve the same observable
records.

## Provider isolation

Providers receive declared bound input and invocation metadata rather than the
whole workflow context. They communicate through a versioned protocol and may be
in-process, subprocess, or remote implementations.

Provider failures cannot partially mutate the assignment journal. An emission
becomes visible only after validation and durable commit.

## Effects

Durable orchestration cannot by itself atomically commit an external mutation.
Effect providers therefore receive stable invocation ids and declare idempotency
or reconciliation behavior. Replay never repeats a recorded external effect.

## Future ComputeNet backend

ComputeNet remains a plausible later substrate because keyed contexts,
dependency propagation, and repeated assignments are dataflow concerns. That
mapping is deliberately deferred. The first runtime should reveal the required
semantics before they are expressed in ComputeNet cells, ports, links, ownership,
and durability mechanisms.
