# Core model rationales

This is a chronological, append-only decision log. It explains the design in
[Core model](core-model.md) but is not itself normative.

## 2026-09-13 — CORE-001: Model context as monotone facts

**Status:** Active

The original sketch described a monotonically growing shared context. Making
that context a logical projection of immutable facts is more precise than a
shared map: concurrent nodes cannot overwrite each other, readiness is stable,
and replay can reconstruct what became known.

This direction is related to monotonic programming results such as the
[CALM theorem](https://doi.org/10.1145/3369736) and
[LVars](https://doi.org/10.1145/2502323.2502326): monotone accumulation avoids
coordination until a computation needs a negative or final conclusion.

## 2026-09-13 — CORE-002: Derive graph edges from explicit references

**Status:** Active

Order-independent definitions are useful only if dependencies can be recovered
without executing step code. Explicit bindings provide that property. Passing a
whole context object into arbitrary functions would make dependencies depend on
runtime behavior and would impair validation, scheduling, and caching.

This is close to the static dataflow style of
[CWL workflows](https://www.commonwl.org/v1.2/Workflow.html), while integrating
bindings more directly into each value definition.

## 2026-09-13 — CORE-003: Do not add sequence, parallel, or join syntax

**Status:** Active

Those constructs are graph consequences rather than independent semantics.
References already express sequence and joins; the absence of a dependency
already expresses potential parallelism. Separate constructs would create two
ways to describe the same ordering and invite contradictions.

## 2026-09-13 — CORE-004: Represent batch work with finite map, not a general loop

**Status:** Active

Scatter/gather has stable, finite completion semantics and maps naturally to
keyed execution. A general loop introduces feedback, mutable iteration state,
termination, and replay questions. Bounded iteration can later be added as a
separate construct rather than hidden inside `map`.

