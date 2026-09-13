# Providers and effects rationales

This is a chronological, append-only decision log. It explains the design in
[Providers and effects](providers-and-effects.md) but is not itself normative.

## 2026-09-13 — PROVIDER-001: Make integrations declarative provider contracts

**Status:** Active

The central usability goal is to remove imperative integration plumbing from a
workflow definition. A provider encapsulates that plumbing behind schemas,
configuration, effects, and execution policy. The workflow then describes only
the data relationship and integration parameters.

Systems such as [Kestra](https://kestra.io/docs/workflow-components/tasks) show
the value of a large catalog of declarative task plugins. The proposed language
differs by making named data definitions, rather than an ordered task list, the
primary graph surface.

## 2026-09-13 — PROVIDER-002: Allow providers in arbitrary implementation languages

**Status:** Active

Requiring every integration to share the engine language would constrain the
ecosystem and undermine separation between definition and execution. A canonical
value protocol and versioned descriptors permit local Kotlin cells, Python
workers, or remote services behind one semantic contract.

The cost is that schemas, compatibility, cancellation, and errors must be
specified at the protocol boundary rather than left to host-language conventions.

## 2026-09-13 — PROVIDER-003: Declare effect behavior explicitly

**Status:** Active

The engine cannot infer whether a call is safe to cache or retry. Pure, read,
effectful, and agentic calls have materially different replay and authorization
requirements, so effect class belongs in the provider descriptor.

## 2026-09-13 — PROVIDER-004: Treat ambiguous completion as a reconciliation problem

**Status:** Active

A timeout after an external write leaves two possible worlds: the write did not
happen, or it happened and the response was lost. Blind retry can duplicate an
effect. Stable logical invocation ids plus provider reconciliation are therefore
part of the minimum safe effect contract.

This is also why “exactly once” is not promised solely by the workflow runtime.
External idempotency or queryable operation identity is required.

## 2026-09-13 — PROVIDER-005: Use one provider construct for sources and calls

**Status:** Active

Both former constructs bind an integration and wait for it to produce values.
Whether it listens indefinitely, emits once, emits repeatedly, or completes is
provider protocol behavior. Dependencies determine when a provider is activated;
an optional correlation id on each emission determines where its value is
assigned.

The runtime still observes emission, completion, and failure messages, but the
workflow language does not require a source/call category or declared cardinality.
