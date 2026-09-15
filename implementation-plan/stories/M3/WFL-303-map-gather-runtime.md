---
id: WFL-303
milestone: M3
title: Execute finite map and deterministic gather
implementation_model: gpt-5.6-luna
implementation_effort: xhigh
review_model: gpt-5.6-sol
review_effort: xhigh
depends_on: [WFL-301]
---

# Outcome

The runtime scatters one captured finite collection revision into stable nested
item graphs, runs items concurrently, and emits deterministically ordered
gathered assignments once every item has produced an output.

# Required reading

- `core-model.md`, graph shape
- `execution-semantics.md`, scatter/gather and provider lifecycle
- `values-and-bindings.md`, map lexical roots
- `evolution-seams.md`
- WFL-301 map IR and compiler tests

# Scope

- Create one independent map activation for each input collection assignment.
- Derive item identities from map producer plus array index or canonical object
  key and preserve the parent input revision in every nested record.
- Bind `$.item` and `$.key` into immutable nested activation snapshots.
- Execute items concurrently through the existing scheduler and provider
  boundaries.
- Emit the first gather when every selected item-output register has at least one
  assignment. After readiness, emit a new gather for every later selected item
  output using all items' current revisions. A failed or completed-without-output
  item fails the map activation; it must not hang silently.
- Gather arrays in input-index order and keyed objects in canonical key order,
  independent of completion order.
- Empty collections gather immediately to the corresponding empty collection.
- A later collection assignment starts a distinct map activation and never
  mutates or reuses the earlier item family.

# Acceptance criteria

- Tests cover empty, single, array, keyed-object, out-of-order completion,
  repeated item output after readiness, item failure before and after readiness,
  no-output completion, repeated collection revisions, and nested provenance.
- A deterministic barrier test proves gather order does not follow completion
  order.
- Every nested item/output has stable identity across deterministic replay of the
  same execution fixture.
- Each gathered result is committed through one singleton journal batch.
- No streaming, incremental, or cross-context collection behavior is implied.

# Non-goals

- Recursive maps, streaming collections, map-item reuse between revisions,
  frontiers, partial gathers, or multi-assignment batches.

# Verification

```bash
./gradlew test --tests '*Map*Test' --rerun-tasks
./gradlew test --rerun-tasks
```

# Handoff

Report item and gather identity rules, completion/failure behavior, deterministic
ordering evidence, tests executed, and integration-sensitive files changed.
