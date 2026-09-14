# Workflow language design

This directory describes a small, backend-independent workflow language. The
present direction is YAML with a deliberately limited expression vocabulary
that lowers almost directly to a portable workflow IR.

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

A workflow declares named context values. Each name is a versioned register
whose assignments form an append-only log. An ordinary reference reads its
latest assignment.

A context value is produced by a limited expression, a provider, `match`, or
`map`. References in a definition form its dependencies. An assignment to any
name activates downstream definitions that reference it, even when the newly
assigned value equals the previous value. Declaration order has no semantic
effect.

There are no separate `source`, `call`, or `let` constructs. Every producer
without context dependencies activates once in the anonymous context when the
workflow starts. A producer with dependencies is activated by assignments to
those dependencies. A provider may emit zero, one, or many values.

Each provider emission optionally carries a correlation id. An explicit id
routes the assignment to that keyed workflow instance and context, creating it
when necessary. An
omitted id inherits the invocation's context. Repeated emissions with the same
correlation id append new assignments and reactivate downstream work; the first
milestone does not impose once-only or cardinality rules.

The first version has no general-purpose expression language. YAML expression
positions admit a small, portable algebra for literals, singular references,
object/list construction, string concatenation, simple predicates, and outcome
matching. Complex transformation remains possible through providers.

Provider behavior—especially agentic behavior—may be nondeterministic, but
assignments, activations, attempts, and outcomes are recorded. Replay consumes
those records instead of repeating effects or model calls.

YAML is the initial authoring and interchange surface. Its structure stays close
to the language-neutral IR. The first runtime will be purpose-built for these
semantics rather than implemented on ComputeNet; ComputeNet remains a possible
future backend.

## Topic map

| Current design | Decision history | Subject |
| --- | --- | --- |
| [Scope](scope.md) | [Rationales](scope-rationales.md) | Initial boundary, non-goals, unresolved decisions |
| [Core model](core-model.md) | [Rationales](core-model-rationales.md) | Versioned registers, producers, and dependencies |
| [Values and bindings](values-and-bindings.md) | [Rationales](values-and-bindings-rationales.md) | Data model and deliberately small expression language |
| [Execution semantics](execution-semantics.md) | [Rationales](execution-semantics-rationales.md) | Assignments, activation, reactivity, and replay |
| [Providers and effects](providers-and-effects.md) | [Rationales](providers-and-effects-rationales.md) | Provider emissions and side-effect safety |
| [Agentic steps](agentic-steps.md) | [Rationales](agentic-steps-rationales.md) | Bounded nondeterminism and agent-framework integration |
| [Failure and recovery](failure-and-recovery.md) | [Rationales](failure-and-recovery-rationales.md) | Attempts, failures, retry, reconciliation, and recovery |
| [Instances and events](instances-and-events.md) | [Rationales](instances-and-events-rationales.md) | Anonymous startup, correlation routing, keyed contexts |
| [Authoring and IR](authoring-and-ir.md) | [Rationales](authoring-and-ir-rationales.md) | YAML syntax, compiler, validation, portable artifact |
| [Execution backend](execution-backend.md) | [Rationales](execution-backend-rationales.md) | Purpose-built first runtime and backend boundary |

## Suggested reading paths

- Language semantics: core model → values and bindings → execution semantics.
- Integrations: providers and effects → failure and recovery.
- Agentic workflows: agentic steps → failure and recovery → execution semantics.
- Implementation: authoring and IR → execution backend → instances and events.

## Cross-cutting invariants

1. Dependencies are explicit in the compiled IR and derivable without running a
   provider.
2. Every accepted assignment appends a revision; “current value” means the
   latest revision for one context and name.
3. Every assignment is an event, even when its payload equals the previous one.
4. Every activation records the dependency revisions from which it was derived.
5. Correlation routes an emission; it does not silently copy another context.
6. Missing, `null`, provider failure, and no assignment yet remain distinct.
7. Effects use stable invocation identity and can be reconciled after ambiguous
   failure.
8. Agentic recovery proposes a typed action; the runtime decides whether it is
   authorized.
9. The canonical IR, not YAML presentation details or a particular backend, is
   the durable workflow definition.

## Offline repository example

The M2 example runs an entire repository-to-model workflow without network
access, credentials, a checkout, or an LLM. Use the explicit `demo` provider
profile:

```bash
./gradlew run --args='run examples/single-repository.yaml --parameters examples/single-repository-parameters.json --providers demo'
```

The output contains `model` (repository, commit, a summary, and stable source
file entities with path/line evidence) and `summary`. The repository-reader
maps the parameter pair to a small fixture; the model-builder is declared
`agentic` to exercise that boundary but is deterministic scaffolding, not an
LLM integration. Provider profiles are opt-in, so an unknown provider is never
supplied by a global singleton.
