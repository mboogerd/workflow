# ComputeNet execution

## Separation of concerns

The workflow language and its IR are independent of ComputeNet. ComputeNet is
the initial execution substrate. The compiler may be Python while the durable
runtime is Kotlin/JVM.

The engine translates validated IR into an explicit ComputeNet graph and
execution records. It must preserve ComputeNet's cell identity, typed ports,
explicit links, message context, ownership rules, and lifecycle semantics.

## Conceptual mapping

| Workflow concept | ComputeNet realization |
| --- | --- |
| Workflow instance | Hosted graph instance plus durable instance state |
| Fact | Typed value cell/port or materialized fact record |
| Reference dependency | Explicit link between producer and consumer ports |
| `let` | Engine-native deterministic binding cell |
| `call` | Provider invocation cell and attempt journal |
| `match` | Deterministic routing plus sealing of unselected outputs |
| `map` | Keyed cell family plus explicit completion/gather barrier |
| Source event | Ingress cell carrying workflow/event context |
| Run metadata | Message context propagated across links and provider calls |
| Replay | Reconstruction from source, decision, and attempt journals |

This is a semantic mapping, not a requirement that each workflow node always
correspond to exactly one physical cell.

## Runtime services

The ComputeNet-backed engine provides:

- IR loading, compatibility validation, and graph materialization;
- instance lookup and source-event correlation;
- durable fact, decision, invocation, and attempt records;
- provider registry and local/remote dispatch;
- readiness scheduling and nested map expansion;
- timeout, cancellation, retry, reconciliation, and recovery policy;
- capability/secret mediation;
- observability and provenance;
- resumption and deterministic replay.

## Context propagation

Workflow, version, correlation, run, event, node, map-item, invocation, and
attempt identifiers travel in explicit message/execution context. Provider
payloads contain declared business input; providers need not receive the whole
workflow context.

This keeps data dependencies statically visible and prevents incidental context
reads from becoming hidden dependencies.

## Effects and ownership

Provider dispatch must not bypass ComputeNet's ownership-aware payload rules.
Failure, cancellation, branch suppression, retries, and dead-letter paths must
all discharge owned or leased values explicitly.

ComputeNet durability alone cannot guarantee exactly-once mutation of an
external system. The provider protocol therefore carries stable invocation ids
and reconciliation semantics as specified in
[Providers and effects](providers-and-effects.md).

## Implementation boundary

Workflow-specific cells and compiler support should live outside the kernel
unless they express generally reusable dataflow semantics. Transport-specific
provider code remains outside the kernel. The kernel must not become dependent
on Python or on an agent framework.

