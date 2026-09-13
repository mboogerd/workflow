# Providers and effects

## Provider contract

A provider is addressed by stable provider id and version. Its descriptor
declares:

- input and output schemas;
- configuration schema;
- effect class;
- required capabilities and secrets;
- timeout and cancellation behavior;
- idempotency and reconciliation support;
- compatibility metadata;
- an implementation endpoint or engine-native binding.

A `call` contains only provider identity, validated configuration, bound input,
and execution policy. Workflow definitions do not contain integration code.

## Effect classes

The initial model distinguishes:

- **pure**: output depends only on input and pinned implementation version;
- **read**: observes external state but does not intentionally mutate it;
- **effect**: may mutate an external system;
- **agentic**: may be nondeterministic and may use declared tools, some of
  which can be effects.

Effect class informs caching, retry, replay, authorization, and audit behavior.
It is part of the provider contract, not an engine guess.

## Invocation identity

Every call receives a stable logical invocation id derived from workflow
instance, node identity, and map item identity. Each physical attempt also has
an append-only attempt id.

An effect provider must use the logical invocation id as an idempotency key when
the external system supports one. The engine never equates a transport timeout
with proof that no effect occurred.

## Outcomes

Providers return a terminal `Outcome<Success, Error>` with a typed success value
or typed failure information. Timeout and cancellation are explicit variants or
standard typed failures, as fixed by the common outcome schema.

Provider protocol errors—invalid output, schema mismatch, lost worker, or
unsupported cancellation—are converted to engine-defined typed failures. They
do not leave a fact unresolved forever.

## Reconciliation

An effect provider declares one of:

- effect is idempotent under logical invocation id;
- provider can query/reconcile the outcome by invocation id;
- effect is not safely retryable and ambiguous completion requires human or
  policy intervention.

Compensation is a separate declared provider operation. It is not assumed to be
an automatic inverse.

## Provider portability

Provider implementations may be written in any language. Calls cross the engine
boundary through the canonical value model and provider protocol. Very small,
portable transformations should remain `let` bindings; complex or
language-specific transformations are providers even when deployed in-process.

