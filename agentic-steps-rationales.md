# Agentic steps rationales

This is a chronological, append-only decision log. It explains the design in
[Agentic steps](agentic-steps.md) but is not itself normative.

## 2026-09-13 — AGENT-001: Treat an agent as a provider, not as the outer language

**Status:** Active

The workflow should remain easy to audit and reason about even when one step is
open-ended. A provider boundary gives the agent typed input, bounded authority,
one durable outcome, and explicit effect metadata while preserving freedom in
its internal control loop.

## 2026-09-13 — AGENT-002: Reuse agent graph frameworks inside providers

**Status:** Active

[Koog graph strategies](https://docs.koog.ai/agents/graph-based-agents/) and
[LangGraph workflows and agents](https://docs.langchain.com/oss/python/langgraph/workflows-agents)
already model model/tool loops, routing, persistence, and human interruption.
Reimplementing those facilities in the outer DSL would expand its scope and
couple it to fast-moving agent abstractions.

They are therefore implementation options for an agentic provider rather than
syntax dependencies of the workflow language.

## 2026-09-13 — AGENT-003: Promote only operationally significant internal steps

**Status:** Active

Keeping every internal agent action opaque would hide effects and approval
boundaries. Exposing every prompt and tool hop would overwhelm the workflow
graph. Independent durability, authority, policy, or shared data dependency is
the criterion for promoting an internal action to an outer node.

## 2026-09-13 — AGENT-004: Persist resolved model and strategy metadata

**Status:** Active

Aliases such as a provider's “latest” model can change while a workflow version
does not. Reproducibility and audit therefore require recording the actual model,
prompt/strategy version, tool policy, and budgets used by each attempt.

