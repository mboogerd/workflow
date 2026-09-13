# ComputeNet execution rationales

This is a chronological, append-only decision log. It explains the design in
[ComputeNet execution](computenet-execution.md) but is not itself normative.

## 2026-09-13 — COMPUTENET-001: Separate workflow definition from execution engine

**Status:** Active

The language should describe workflow meaning without embedding Kotlin runtime
objects. This allows Python authoring, validation tools, alternate executors, and
long-lived durable definitions while ComputeNet remains free to evolve its
physical execution strategy.

## 2026-09-13 — COMPUTENET-002: Compile dependencies to explicit links

**Status:** Active

ComputeNet already treats cell and port identity, links, message context, and
dispatch as semantic. Mapping workflow references onto those explicit
relationships preserves both models. Calling local provider functions directly
would bypass important runtime invariants.

## 2026-09-13 — COMPUTENET-003: Treat context as a graph projection

**Status:** Active

A mutable context map would duplicate ComputeNet's graph state and introduce a
second consistency mechanism. Facts should remain cells/materialized records;
“context” is the author-facing projection of their terminal values for one
instance.

## 2026-09-13 — COMPUTENET-004: Map scatter to keyed families with explicit sealing

**Status:** Active

ComputeNet's keyed-cell direction is a natural substrate for map expansion, but
workflow gather additionally needs terminal completeness. A snapshot or current
aggregate is not enough: the runtime needs an explicit sealed input and barrier
before assigning the gathered fact.

## 2026-09-13 — COMPUTENET-005: Keep external effect safety in the provider protocol

**Status:** Active

Journaling a dispatched message does not atomically commit an external Jira or
GitHub mutation. Stable invocation identity, idempotency, and reconciliation
must cross the integration boundary. ComputeNet supplies durable orchestration;
the external system must cooperate for stronger effect guarantees.

## 2026-09-13 — COMPUTENET-006: Keep workflow and agent dependencies out of the kernel

**Status:** Active

Workflow compilation, provider registries, Python interop, and agent frameworks
are policy and integration layers. Only generally reusable dataflow mechanisms
should move into the ComputeNet kernel, preserving its transport-neutral and
language-neutral core.

