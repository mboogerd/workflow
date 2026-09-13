# Workflow language design

This directory describes a small declarative workflow language intended to run
on ComputeNet. The present direction is a staged Python DSL that compiles to a
portable workflow IR. The workflow is a dataflow graph of single-assignment
facts; providers perform pure computation, external integration, or bounded
agentic work.

This is a design, not yet an implementation contract.

## Document contract

- A topic file, such as `execution-semantics.md`, contains only the current
  design for that topic.
- Its `-rationales.md` companion is a chronological, append-only decision log.
  It may contain rejected or superseded positions.
- If the two disagree, the topic file is the current proposal. An active,
  newer rationale entry explains why it changed.
- Open decisions are marked explicitly. They must not be inferred from old
  rationale entries.
- Examples illustrate semantics; they do not establish concrete syntax unless
  a document says otherwise.

When changing a decision, update the topic file and append a rationale entry in
the same change. Mark the displaced rationale entry `Superseded by ...`; do not
rewrite its historical content.

## Design in one page

A workflow definition declares named facts. A fact is produced by a source, a
small built-in value construction, a provider call, a conditional branch, or a
finite mapping operation. References between definitions form the dependency
graph, so sequence, parallelism, and joins need no separate syntax.

The context is a logical view of facts known for one workflow instance, not a
mutable dictionary passed between tasks. Ordinary facts are assigned once.
Collections may grow only through an explicitly declared monotone collection
or finite map construct with a closure rule.

The orchestration layer is deterministic. Provider calls—especially agentic
ones—may be nondeterministic, but their attempts and terminal outcomes are
recorded. Replay consumes those records instead of repeating effects or model
calls.

The first version has no general-purpose expression language. It has a small,
portable binding algebra for literals, singular references, object/list
construction, string concatenation, simple predicates, and outcome matching.
Complex transformation remains possible through explicit providers.

Python is the initial authoring surface, not the execution semantics. Running a
definition builds and validates a language-neutral IR. A Kotlin/ComputeNet
runtime can execute that IR without evaluating Python.

## Topic map

| Current design | Decision history | Subject |
| --- | --- | --- |
| [Scope](scope.md) | [Rationales](scope-rationales.md) | Initial boundary, non-goals, unresolved decisions |
| [Core model](core-model.md) | [Rationales](core-model-rationales.md) | Facts, dependencies, and graph constructs |
| [Values and bindings](values-and-bindings.md) | [Rationales](values-and-bindings-rationales.md) | Data model and deliberately small glue language |
| [Execution semantics](execution-semantics.md) | [Rationales](execution-semantics-rationales.md) | Readiness, parallelism, branching, mapping, replay |
| [Providers and effects](providers-and-effects.md) | [Rationales](providers-and-effects-rationales.md) | Integration contracts and side-effect safety |
| [Agentic steps](agentic-steps.md) | [Rationales](agentic-steps-rationales.md) | Bounded nondeterminism and agent-framework integration |
| [Failure and recovery](failure-and-recovery.md) | [Rationales](failure-and-recovery-rationales.md) | Outcomes, retry, reconciliation, and agentic recovery |
| [Instances and events](instances-and-events.md) | [Rationales](instances-and-events-rationales.md) | Correlation, multi-event input, closure, lifecycle |
| [Authoring and IR](authoring-and-ir.md) | [Rationales](authoring-and-ir-rationales.md) | Python DSL, compiler, validation, portable artifact |
| [ComputeNet execution](computenet-execution.md) | [Rationales](computenet-execution-rationales.md) | Mapping the language onto ComputeNet without coupling it |

## Suggested reading paths

- Language semantics: core model → values and bindings → execution semantics.
- Integrations: providers and effects → failure and recovery.
- Agentic workflows: agentic steps → failure and recovery → execution semantics.
- Implementation: authoring and IR → ComputeNet execution → instances and events.

## Cross-cutting invariants

1. Dependencies are explicit in the compiled IR and derivable without running a
   provider.
2. A fact has one terminal assignment; retries create attempts, not new fact
   values.
3. Missing, `null`, failure, and an open input scope are distinct states.
4. Negative conclusions require a terminal outcome or a sealed input scope.
5. Effects use stable invocation identity and can be reconciled after ambiguous
   failure.
6. Agentic recovery proposes a typed action; the deterministic runtime decides
   whether it is authorized.
7. The IR, not Python source or a particular engine, is the durable workflow
   definition.

