# Evolution-seam rationales

This is a chronological, append-only decision log. It explains the design in
[Evolution seams](evolution-seams.md) but is not itself normative.

## 2026-09-13 — EVOLUTION-001: Shape durable records before adding new syntax

**Status:** Active

The repository-model use case demonstrates future pressure for previous-state
transitions, keyed materialized collections, and atomic deltas. Adding those
features to v1 would delay the first executable workflow and force premature
policy decisions.

Stable identities, reconstructible journals, and replaceable runtime boundaries
are cheap in v1 and expensive to retrofit. They preserve the option without
pretending to implement it.

## 2026-09-13 — EVOLUTION-002: Commit one assignment through a batch envelope

**Status:** Active

If v1 persists a bare assignment as its transaction unit, atomic multi-key
updates later require changing journal identity, projection, scheduling, and
replay together. A versioned batch envelope containing exactly one mutation has
almost the same initial implementation cost while preserving a natural growth
path.

The cardinality restriction is mandatory in v1 so the envelope does not become
an undocumented multi-write feature.

## 2026-09-13 — EVOLUTION-003: Persist activation intent with its cause

**Status:** Active

Scheduling downstream work only after committing an assignment creates a crash
window in which the value is durable but its consequences are lost. Persisting
activation intents in the same storage transaction closes that window and gives
future coalescing, latest-only, and delta-aware planners durable inputs.

## 2026-09-13 — EVOLUTION-004: Keep application deltas ordinary values in v1

**Status:** Active

Providers can already exchange typed commit ranges, model patches, and topology
change envelopes. Giving such payloads runtime semantics before transition and
atomicity rules are designed would couple application convention to the core
language. V1 records their schema and provenance but otherwise treats them like
any other value.
