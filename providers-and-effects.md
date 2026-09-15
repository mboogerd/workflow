# Providers and effects

## Uniform provider contract

A provider is a producer implementation addressed by stable provider id and
version. Event listeners, one-shot lookups, transformations, external mutations,
and agentic work all use this construct.

Its descriptor declares:

- input, emitted-value, configuration, and error schemas;
- effect class;
- required capabilities and secrets;
- completion, failure, timeout, and cancellation behavior;
- idempotency and reconciliation support;
- compatibility metadata;
- an implementation endpoint or engine-native binding.

A provider context definition contains provider identity, validated
configuration, bound inputs, and execution policy. It contains no integration
implementation code.

## Activation and emission

A provider without context dependencies is activated once in the anonymous
context when the workflow starts. A provider with dependencies receives a new
activation when its captured dependency-revision vector changes through an
assignment.

An activation may emit zero, one, or many values and may then complete, fail, or
remain open. The language does not declare provider cardinality in the first
milestone. The runtime protocol nevertheless communicates emissions and
activation lifecycle events.

Each emission contains:

- a value conforming to the provider's emission schema;
- an optional correlation id;
- stable provider emission identity;
- invocation and attempt provenance.

Lifecycle messages from one invocation form an ordered protocol stream. The
runtime observes that stream in provider order. Messages from different
invocations have no provider-level relative order; their accepted assignments
are ordered by the workflow execution journal.

The engine routes the emission and appends it to the register named by the
provider definition.

## Effect classes

The provider descriptor distinguishes:

- **pure**: emissions depend only on bound input and pinned implementation;
- **read**: observes external state without intentional mutation;
- **effect**: may mutate an external system;
- **agentic**: may be nondeterministic and use declared tools, including
  effectful tools.

Effect class informs caching, retry, replay, authorization, and audit. It is not
inferred from whether a provider has dependencies or emits repeatedly.

## Invocation identity

Every activation receives a stable logical invocation id derived from workflow
execution, context, producer, and dependency-revision vector. Each physical
attempt has an append-only attempt id, and each emission has an id unique within
the invocation.

An effect provider uses the logical invocation id as an idempotency key when the
external system supports one. Repeated assignments intentionally create distinct
activations and therefore distinct effect identities.

An effect provider producer may bind `idempotency-key` to an expression using
the same reference machinery as `with` and `config`. Compilation requires it to
be string-valued and requires effect providers to declare it; it is optional for
pure, read, and agentic providers. When declared, the bound value is evaluated
alongside `input` and `config` from the same dependency-revision snapshot and is
used as the invocation's idempotency key instead of the logical invocation id.
This is the only way to make two distinct activations — from separate runs, or
from separate correlated contexts — converge on one external reconciliation
identity; the key is recorded on the invocation event for replay, inspection,
and audit. Invocation identity itself is unaffected: two activations with the
same bound idempotency key still have distinct invocation ids.

## Failure and emitted error values

Provider lifecycle failure is recorded against the activation and does not erase
a previous register value. A provider may instead emit a typed application error
as data when downstream `match` handling is part of its contract.

Protocol errors—invalid output, schema mismatch, lost worker, or unsupported
cancellation—become engine-defined activation failures. They do not fabricate a
register assignment.

## Reconciliation

An effect provider declares one of:

- effects are idempotent under logical invocation id;
- the provider can query/reconcile outcome by invocation id;
- ambiguous completion is not safely retryable and requires policy or human
  intervention.

Compensation is a separate provider operation, not an assumed inverse.

## Portability

Provider implementations may use any language. Calls cross the runtime boundary
through the canonical value and provider protocols. Small portable
transformations remain expressions; complex or language-specific transformations
are providers even when deployed in-process.
