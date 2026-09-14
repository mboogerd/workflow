# Workflow v1 conformance traceability

Each item is exercised by the named corpus scenario; compiler-only rules are
represented by an expected diagnostic rather than a fabricated runtime event.

| Requirement | Scenario |
| --- | --- |
| Invariant 1 | startup, schemas |
| Invariant 2 | repeated-assignments |
| Invariant 3 | repeated-assignments |
| Invariant 4 | startup, map-gather |
| Invariant 5 | correlation |
| Invariant 6 | failure, schemas |
| Invariant 7 | effects |
| Invariant 8 | agentic |
| Invariant 9 | replay |
| Invariant 10 | repeated-assignments |
| core-model | startup, repeated-assignments, correlation |
| scope | continuous |
| values-and-bindings | expressions, failure |
| schemas | schemas |
| authoring-and-ir | expressions, match, map-gather |
| execution-semantics | startup, repeated-assignments, match, map-gather, replay |
| providers-and-effects | providers, retry, effects, replay |
| instances-and-events | correlation, continuous |
| failure-and-recovery | failure, retry, effects, agentic |
| agentic-steps | agentic |
| execution-backend | providers, restart |
| evolution-seams | restart |

Compile-time-only explanations: parser rejection, schema incompatibility,
acyclic graph validation, lexical-scope validation, and unsafe policy validation
are deliberately represented as diagnostic scenarios because no runtime may run
an invalid canonical IR.
