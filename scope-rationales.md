# Scope rationales

This is a chronological, append-only decision log. It explains the design in
[Scope](scope.md) but is not itself normative.

## 2026-09-13 — SCOPE-001: Center the language on dataflow and integrations

**Status:** Active

The motivating problem is not general computation; Python and provider
implementations already solve that. The missing layer is a concise description
of what data each step needs, what it produces, and how reusable integrations
are parameterized.

Consequently, the language should spend its syntax budget on dependencies,
outcomes, effects, lifecycle, and composition rather than imperative control
flow.

## 2026-09-13 — SCOPE-002: Keep deterministic orchestration around agentic islands

**Status:** Active

The primary workload combines predictable business workflows with steps whose
implementation uses an LLM or agent loop. Treating the entire workflow as an
agent graph would weaken replay, auditability, and effect control. Treating the
agent as a provider preserves a deterministic outer shell while allowing rich
internal behavior.

This follows the useful distinction made by agent literature between predefined
workflows and agents that dynamically direct their own process. See Anthropic's
[Building effective agents](https://www.anthropic.com/engineering/building-effective-agents).

## 2026-09-13 — SCOPE-003: Start with finite workflows and a constrained glue layer

**Status:** Active

General loops and a general expression language would force the first runtime to
solve termination, sandboxing, cross-language equivalence, hidden dependencies,
and much more extensive static analysis. Finite graphs, finite mapping, and a
small binding algebra cover the motivating example while keeping execution and
replay semantics inspectable.

The scope can expand from observed pressure in real workflow definitions rather
than anticipated language completeness.

