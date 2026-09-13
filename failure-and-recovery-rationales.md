# Failure and recovery rationales

This is a chronological, append-only decision log. It explains the design in
[Failure and recovery](failure-and-recovery.md) but is not itself normative.

## 2026-09-13 — RECOVERY-001: Make failure a terminal value

**Status:** Superseded by RECOVERY-006

A failed provider that emits no value would leave dependents waiting forever and
make absence timing-dependent. A typed terminal outcome lets ordinary graph
constructs handle expected failure and gives the engine a complete state.

## 2026-09-13 — RECOVERY-002: Use deterministic mechanisms before agentic recovery

**Status:** Active

Known transient errors, declared fallbacks, and reconciliation have narrower,
more predictable behavior than asking a model. They should run first. Agentic
recovery is valuable specifically for unforeseen semantic situations after safe
mechanical options are exhausted.

Retry policy is versioned workflow behavior. Systems such as Temporal similarly
treat retries as explicit activity policy; see the
[Temporal retry policy documentation](https://github.com/temporalio/documentation/blob/main/docs/encyclopedia/retry-policies.mdx).

## 2026-09-13 — RECOVERY-003: Recovery agents propose; the engine disposes

**Status:** Active

Allowing a recovery model to execute arbitrary corrective actions would make
authorization and replay nondeterministic. A closed `RecoveryDecision` algebra
lets the model apply judgment while a deterministic policy layer verifies
capabilities, budgets, effect safety, and current state.

## 2026-09-13 — RECOVERY-004: Preserve the original failure and every decision

**Status:** Active

Recovery must add information, not rewrite history. Original attempts, the
recovery request and proposal, validation, human input, and follow-up attempt
remain append-only. This supports audit, debugging, and deterministic replay.

## 2026-09-13 — RECOVERY-005: Model compensation as an operation, not an inverse

**Status:** Active

Many real effects are only partially compensable, time-sensitive, or require
new authorization. A declared compensation provider can express those realities;
an automatic “undo” abstraction cannot.

## 2026-09-13 — RECOVERY-006: Separate activation failure from register assignment

**Status:** Active

A streaming or repeatedly activated provider may fail after having emitted valid
values. Replacing its current register value with every operational failure would
mix provider lifecycle with application data and discard a useful last value.

Failures are therefore durable activation records. The current register remains
unchanged unless the provider deliberately emits a typed error value. Recovery
can retry, reconcile, compensate, or emit a replacement, and any resulting
emission follows the normal assignment and downstream-reactivation rules.
