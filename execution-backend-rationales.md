# Execution backend rationales

This is a chronological, append-only decision log. It explains the design in
[Execution backend](execution-backend.md) but is not itself normative.

## 2026-09-13 — COMPUTENET-001: Separate workflow definition from execution engine

**Status:** Active

The language should describe workflow meaning without embedding Kotlin runtime
objects. This allows Python authoring, validation tools, alternate executors, and
long-lived durable definitions while ComputeNet remains free to evolve its
physical execution strategy.

## 2026-09-13 — COMPUTENET-002: Compile dependencies to explicit links

**Status:** Deferred by BACKEND-007

ComputeNet already treats cell and port identity, links, message context, and
dispatch as semantic. Mapping workflow references onto those explicit
relationships preserves both models. Calling local provider functions directly
would bypass important runtime invariants.

## 2026-09-13 — COMPUTENET-003: Treat context as a graph projection

**Status:** Superseded by CORE-005 and BACKEND-007

A mutable context map would duplicate ComputeNet's graph state and introduce a
second consistency mechanism. Facts should remain cells/materialized records;
“context” is the author-facing projection of their terminal values for one
instance.

## 2026-09-13 — COMPUTENET-004: Map scatter to keyed families with explicit sealing

**Status:** Deferred by BACKEND-007

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

**Status:** Deferred by BACKEND-007

Workflow compilation, provider registries, Python interop, and agent frameworks
are policy and integration layers. Only generally reusable dataflow mechanisms
should move into the ComputeNet kernel, preserving its transport-neutral and
language-neutral core.

## 2026-09-13 — BACKEND-007: Build a purpose-specific first runtime

**Status:** Active

ComputeNet is no longer a dependency of the first version. The workflow model is
still changing—from terminal facts to reactive versioned registers in the same
design sequence—and implementing those semantics directly is a smaller and more
informative first step than adapting a general dataflow runtime concurrently.

The first backend therefore owns a straightforward journal, current-value view,
dependency scheduler, correlation router, and provider protocol. The IR remains
backend-neutral, preserving the option to implement a ComputeNet backend after
the observable semantics have stabilized.

The earlier external-effect conclusion remains active: neither a standalone
journal nor ComputeNet can make an external mutation exactly once without
idempotency or reconciliation at the provider boundary.
