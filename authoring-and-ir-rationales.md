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

**Status:** Superseded by AUTHOR-006

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

## 2026-09-13 — AUTHOR-006: Adopt YAML as the first authoring surface

**Status:** Active

The first milestone values simplicity, language neutrality, and closeness to the
IR more than host-language expression feedback. A constrained YAML document can
be parsed by the compiler and inspected by operators without executing authoring
code or reproducing a Python environment.

The limited expression language is represented structurally with reserved YAML
operators, so it is already an AST rather than a string program. This gives up
some IDE type feedback compared with Python, but reduces staging, portability,
security, and source-to-IR complexity at the point where the language is still
being discovered.

Typed embedded DSLs remain possible later as generators for the same IR. They
are no longer part of the first milestone.

## 2026-09-13 — AUTHOR-007: Remove source, call, and let from YAML

**Status:** Active

The previous syntax exposed distinctions the runtime can derive from one uniform
producer model. A provider is a provider whether it waits on an event, performs a
lookup, or runs an agent. A context entry containing an expression is already
clearly a derivation, so `let` adds no information.

The smaller surface makes YAML track the IR more directly: each register has an
expression, provider, match, or map producer, and references provide all graph
dependencies.

## 2026-09-13 — AUTHOR-008: Fix the first match and map YAML shapes

**Status:** Active

Leaving branch-local bindings and nested map structure illustrative would force
the compiler implementation to invent durable syntax. V1 uses one captured
`$.match` value for cases and an explicit `over`/nested `context`/named `output`
map form. Both lower directly to the existing producer graph and keep collection
cardinality visible.
