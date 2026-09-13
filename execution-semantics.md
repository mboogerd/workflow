# Execution semantics

## Readiness

A node is ready when:

1. all references in its selected definition are terminal;
2. its branch, if any, has been selected;
3. its provider and required capabilities are available; and
4. its instance is in a state that permits new work.

All ready nodes may start concurrently. Declaration order has no semantic
effect. A join is ready only when every required input is terminal.

## Node lifecycle

Each node progresses through a monotone state machine:

`unresolved → ready → running → terminal`

Terminal is one of:

- value assigned for `source`, `let`, `match`, `map`, and `output`;
- terminal `Outcome` assigned for `call`;
- skipped because an enclosing branch was not selected;
- cancelled by instance policy.

The logical terminal assignment is written once. A provider call may have
multiple append-only attempts before its one terminal `Outcome` is committed.

## Determinism

Given the same workflow IR, correlated source events, execution-policy version,
and recorded provider outcomes, the engine must derive the same:

- dependencies and readiness transitions;
- selected branches;
- provider invocation identities;
- map expansion identities and gathered ordering;
- exported workflow result.

Wall-clock interleaving may differ. Determinism concerns observable workflow
decisions, not serial execution.

When simultaneously available external inputs require ordering, the engine uses
a specified stable ordering key recorded with the events. It does not depend on
thread scheduling or message arrival races.

## Conditional execution

`match` waits for a terminal discriminator, selects exactly one exhaustive
branch, and seals all unselected branches as skipped. References inside an
unselected branch do not block the workflow.

Branch outputs have one declared schema. Every selected branch must produce a
compatible value or outcome.

## Scatter/gather

`map` consumes a finite, sealed collection. It expands one nested graph per
element, with stable item identity. Elements may run concurrently.

The gathered value becomes terminal when every item is terminal. Array input
preserves index order regardless of completion order. Object or keyed-set input
uses canonical key order. Per-item failure behavior is an explicit map policy:
collect outcomes, fail fast, or require all successes.

## Replay and resume

Execution decisions and provider attempt outcomes are durably recorded before
their downstream consequences become externally visible. Resume continues from
the recorded state. Replay uses recorded provider outcomes and does not repeat
model calls or effects.

A deliberate rerun is a new run or a new explicitly authorized attempt, not a
replay of the old run.

