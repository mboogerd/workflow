---
id: WFL-503
milestone: M5
title: Enforce agentic metadata, budgets, and constrained recovery
implementation_model: gpt-5.6-luna
implementation_effort: xhigh
review_model: gpt-5.6-sol
review_effort: xhigh
depends_on: [WFL-501]
---

# Outcome

Agentic providers execute through the ordinary provider boundary with pinned
strategy metadata, enforceable budgets and capabilities, validated typed output,
and auditable constrained recovery proposals.

# Required reading

- `agentic-steps.md`
- `agentic-steps-rationales.md`
- `failure-and-recovery.md`
- `providers-and-effects.md`
- WFL-501 lifecycle-policy implementation

# Scope

- Implement all required agentic descriptor fields: model-selection policy,
  prompt/strategy version, tools and effect classes, capabilities/secrets,
  token/cost/time/tool-call/iteration budgets, output acceptance, persistence,
  resumability, trace retention, and cancellation behavior.
- Record the runtime-resolved model identifier and materially relevant settings
  on every agentic attempt.
- Add a provider-facing budget meter and capability gate. Fake agent providers
  must demonstrate accepted and refused tool/budget operations without calling a
  real model.
- Validate every agentic emission against its typed schema and acceptance policy
  before assignment.
- Implement deterministic fallback/compensation configuration followed by the
  constrained recovery-proposal variants in `failure-and-recovery.md`.
- Validate recovery authority, capabilities, budget, provenance, and current
  dependency revisions. Persist proposals and decisions; execute only accepted
  actions.
- Represent `RequestHuman` as a durable paused/intervention state with an API/CLI
  surface to submit a typed answer. Do not build a graphical interface.
- Ensure replay uses recorded resolved metadata, output, proposal, validation,
  and human answer without invoking an agent again.

# Acceptance criteria

- Tests cover every recovery proposal variant, accepted/refused authority,
  budget exhaustion, undeclared tool use, invalid output, stale dependency
  revisions, human pause/resume, crash/restart, and replay.
- Recovery authority never exceeds the failed operation plus explicitly declared
  recovery-only capabilities.
- Agent trace diagnostics do not leak secrets into context values or ordinary
  inspection output.
- A fake agent's internal multi-step loop remains one outer provider activation
  unless an action meets the promotion criteria in `agentic-steps.md`.
- Existing deterministic providers do not need agent metadata.

# Non-goals

- Integration with a specific LLM vendor or agent framework, free-form recovery
  code, hidden capability escalation, or transition/delta semantics.

# Verification

```bash
./gradlew test --tests '*Agentic*Test' --rerun-tasks
./gradlew test --tests '*Recovery*Test' --rerun-tasks
./gradlew test --rerun-tasks
```

# Handoff

Report descriptor enforcement, budget/capability boundaries, recovery decision
matrix, pause/resume interface, replay evidence, and tests executed.
