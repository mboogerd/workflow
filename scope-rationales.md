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

**Status:** Refined by SCOPE-005

General loops and a general expression language would force the first runtime to
solve termination, sandboxing, cross-language equivalence, hidden dependencies,
and much more extensive static analysis. Finite graphs, finite mapping, and a
small binding algebra cover the motivating example while keeping execution and
replay semantics inspectable.

The scope can expand from observed pressure in real workflow definitions rather
than anticipated language completeness.

## 2026-09-13 — SCOPE-004: Optimize the first milestone for language neutrality

**Status:** Active

IDE feedback from an embedded Python DSL would be useful, but the first milestone
benefits more from having the authored form closely mirror the IR. YAML plus a
small structural expression language keeps the compiler and runtime independent
of a host language and makes the semantic surface difficult to expand
accidentally.

This deliberately accepts weaker expression-level editor feedback. Schema-aware
YAML completion and validation can improve later without changing workflow
semantics.

## 2026-09-13 — SCOPE-005: Prefer unrestricted push mechanics over early policy syntax

**Status:** Active

Repeated emissions to the same correlation id existed even when event inputs had
special `source` syntax; the distinction merely made the case less visible. The
first milestone now accepts every push, records it as a revision, and reactivates
downstream producers.

Once-only, cardinality, deduplication, and completion controls are deferred until
their required behavior is demonstrated. This keeps the initial language small
without destroying the history needed to add those policies later.

## 2026-09-13 — SCOPE-006: Build the first runtime independently of ComputeNet

**Status:** Active

ComputeNet was the original intended substrate, but mapping unsettled workflow
semantics directly onto its broader runtime would couple discovery of the
language to integration work. A purpose-built first backend can implement the
small journal, register, correlation, and activation model directly.

The IR remains backend-neutral so a ComputeNet backend can be reconsidered after
the semantics have been exercised.

## 2026-09-13 — SCOPE-007: Close v1 policy choices without adding policy syntax

**Status:** Active

An implementation plan cannot safely delegate choices such as stale-result
handling, retention, provider discovery, or context completion to independent
workers. V1 therefore fixes the simplest behavior: execute every activation,
retain every record, register providers explicitly, and require administrative
stop rather than semantic closure.

These decisions do not add language surface. Later versions may add explicit
alternative policies while preserving the v1 interpretation.
