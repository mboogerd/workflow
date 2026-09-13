# Authoring and IR rationales

This is a chronological, append-only decision log. It explains the design in
[Authoring and IR](authoring-and-ir.md) but is not itself normative.

## 2026-09-13 — AUTHOR-001: Use YAML only as an initial sketch

**Status:** Superseded by AUTHOR-002 for the first authoring surface

YAML made the desired declarative shape easy to illustrate, but nested provider
configuration, reusable fragments, schemas, and expression-like glue would
quickly require custom validation and editor tooling. YAML may still become a
direct syntax or interchange form if it maps exactly to the IR.

## 2026-09-13 — AUTHOR-002: Start with a Python embedded DSL

**Status:** Active

Python supplies modules, names, functions for definition-time reuse, packaging,
type checkers, completion, and syntax highlighting. It lets the language focus
on workflow constructs rather than recreate a complete authoring ecosystem.

The cost is exposure to Python's dynamic and Turing-complete behavior. Staging
contains that cost: Python constructs a graph, then exits; it is not the durable
workflow runtime.

## 2026-09-13 — AUTHOR-003: Do not infer dependencies from full-context functions

**Status:** Active

Passing a context object into an arbitrary function looks pleasantly duck-typed,
but static dependency discovery would require executing or analyzing Python.
Explicit named bindings and structural schemas preserve the ergonomic benefit
of passing only required data without making behavior-dependent reads part of
the scheduler.

## 2026-09-13 — AUTHOR-004: Make canonical IR authoritative

**Status:** Active

Definition and execution may occur in different languages and at different
times. Persisting Python source or serialized closures would couple runs to a
specific interpreter and environment. A versioned, validated IR provides a
stable contract between authoring, deployment, execution, inspection, and
replay.

## 2026-09-13 — AUTHOR-005: Retain a provider escape hatch for arbitrary glue

**Status:** Active

A closed binding algebra will not express every useful transformation. Requiring
a manually deployed service for a three-line transform may become unwieldy, but
silently serializing that code recreates the expression-runtime problem. The
semantic escape hatch is an explicit provider; packaging automation can make it
lighter later without hiding the boundary.

