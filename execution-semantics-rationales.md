# Execution semantics rationales

This is a chronological, append-only decision log. It explains the design in
[Execution semantics](execution-semantics.md) but is not itself normative.

## 2026-09-13 — EXEC-001: Schedule eagerly from data readiness

**Status:** Active

The desired behavior is reactive: all roots may start together, and every
dependent starts as soon as its actual inputs are terminal. A central ordered
task list would obscure parallelism and make declaration order accidentally
semantic.

## 2026-09-13 — EXEC-002: Define determinism around recorded boundaries

**Status:** Active

Bit-for-bit repeatability of an LLM call or an external read is not generally
available. The useful guarantee is that the orchestrator makes the same
decisions from the same inputs and recorded provider outcomes. This isolates
nondeterminism without pretending it does not exist.

Durable workflow systems make a similar separation between deterministic
workflow decisions and recorded activity results. LangGraph likewise warns that
replay resumes from recorded checkpoints and requires deterministic handling of
side effects; see its [durable execution guidance](https://docs.langchain.com/oss/python/langgraph/functional-api).

## 2026-09-13 — EXEC-003: Separate attempts from terminal fact assignment

**Status:** Active

Retries appear to conflict with single assignment only if every attempt is
treated as a new value. Recording attempts as append-only operational facts and
committing one terminal node outcome preserves both retry history and monotone
dataflow semantics.

## 2026-09-13 — EXEC-004: Seal unselected branches

**Status:** Active

If unselected branches simply remain unresolved, downstream completeness cannot
be distinguished from a stuck workflow. Explicit skipped terminals make branch
closure durable and ensure dependencies in unreachable branches do not block
completion.

## 2026-09-13 — EXEC-005: Gather by stable input identity, not completion order

**Status:** Active

Parallel map items may finish in any order. Gathering by input index or canonical
key makes downstream values independent of timing and gives retries stable child
identities.

