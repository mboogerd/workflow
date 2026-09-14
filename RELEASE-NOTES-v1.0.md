# Workflow v1.0 release notes

Workflow v1.0 provides YAML compilation to a versioned portable IR, durable
SQLite execution, inspection, restart/resume, and deterministic replay. The
checked-in conformance corpus is run with `./gradlew conformance`; the clean
distribution gate is `./gradlew distributionSmokeTest`.

The offline repository demonstration includes full snapshot reconstruction.
Safety tests inject transient model-builder failure and a fake publication whose
reply is lost after the external write; the runtime reconciles the stable
invocation identity and publishes the recovered result once, without a duplicate
external effect.

## Explicitly absent from v1

- Incremental transitions / compare-and-swap state transitions.
- Keyed materialized collections.
- Atomic multi-assignment changes (a v1 journal batch contains one mutation).
- Cross-context aggregation.
- Cross-workflow subscriptions and materialized outputs.

These are intentionally deferred seams, not partial features. See
`evolution-seams.md` for the preserved identities and boundaries.
