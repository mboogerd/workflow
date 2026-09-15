# Workflow v1 conformance traceability

The machine-checked index is [`traceability.json`](traceability.json). It maps
all ten README cross-cutting invariants and every level-two section of the twelve
normative v1 topic documents to one or more executable scenario ids or to an
explicit compile-time/deferred-feature explanation.

The corpus currently contains 16 scenarios covering startup, expressions,
schemas, scripted providers, runtime failure, retry, repeated assignments,
correlation, match, finite map/gather, effect reconciliation, agentic metadata
and constrained recovery, real SQLite reopen/resume, continuous execution,
provider-free replay, and an author-bound effect idempotency key.

`ConformanceCorpusTest` rejects duplicate scenario ids, missing or unknown
scenario mappings, uncovered invariants or normative sections, empty
explanations, malformed direct requirement references, and an empty corpus.
