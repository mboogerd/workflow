---
id: WFL-401
milestone: M4
title: Support repeated root emissions and correlated contexts
implementation_model: gpt-5.6-luna
implementation_effort: xhigh
review_model: gpt-5.6-sol
review_effort: xhigh
depends_on: [WFL-304]
---

# Outcome

A running execution can host an open root provider, accept repeated emissions,
route each emission to the anonymous or an explicitly correlated context, and
reactivate downstream producers for every accepted assignment.

# Required reading

- `instances-and-events.md`
- `execution-semantics.md`, startup, activation, correlation, and quiescence
- `providers-and-effects.md`, activation and emission
- `scope.md`, v1 closure decisions
- Existing provider scheduler and map/match behavior

# Scope

- Extend execution hosting so root providers may remain open after zero or more
  emissions without being mistaken for completed workflow executions.
- Route provider emissions according to explicit correlation ID or inherited
  activation context and lazily create keyed contexts.
- Do not restart dependency-free root producers when a keyed context is created.
- Do not copy anonymous or originating context values into a newly created keyed
  context.
- Accept repeated correlation IDs and equal payloads as new assignment revisions
  that reactivate downstream work.
- Preserve provider-declared emission order from one invocation while allowing
  different invocations to race through the authoritative journal order.
- Distinguish quiescence from completion in the runtime API and CLI. Add explicit
  administrative stop/cancel for a hosted execution without synthesizing context
  completion.
- Add bounded test controls for driving and stopping fake open providers; do not
  rely on sleep-based timing.

# Acceptance criteria

- Tests cover anonymous emission, new/repeated correlation IDs, inherited
  correlation, no context copying, repeated equal values, open-provider
  quiescence, and administrative stop.
- One root provider instance serves all keyed contexts in its workflow execution.
- Downstream producers activate only when their required values exist in the
  destination context.
- Inspection output clearly distinguishes execution, anonymous context, and
  correlated contexts.
- Existing finite one-shot workflows still terminate through the CLI's
  run-until-quiescent behavior.

# Non-goals

- Cross-context references, deduplication, debounce, latest-only, context
  sealing, persistent restart, or a natural completion rule for open streams.

# Verification

```bash
./gradlew test --tests '*Correlation*Test' --rerun-tasks
./gradlew test --tests '*OpenProvider*Test' --rerun-tasks
./gradlew test --rerun-tasks
```

# Handoff

Report routing/lifecycle rules, deterministic test controls, context-isolation
evidence, administrative-stop behavior, and tests executed.
