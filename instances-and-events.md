# Instances and events

## Execution and context identity

Starting a workflow creates a `workflow_execution_id` and one anonymous workflow
instance. An instance and its context have the identity:

`(workflow_execution_id, correlation_id?)`

The anonymous context uses the absent correlation id. A correlated context is
created lazily when the first emission targets its key.

The workflow definition id, version, and content hash are immutable metadata of
the execution.

## Anonymous startup

All producer definitions without context dependencies activate once in the
anonymous context. This uniform rule covers constant expressions, event
subscriptions, timers, one-shot reads, generated seeds, and other roots without
classifying them as sources or calls.

A correlated instance does not start another copy of the root producers. It
receives work through routed assignments and their downstream dependencies.

## Emission routing

Every provider emission may carry a correlation id:

- a new id creates a context and assigns the provider's named register there;
- an existing id appends another assignment to that context and register;
- no id inherits the provider activation's context;
- no id from an anonymous root writes to the anonymous context.

Routing a value to a new context does not copy values from the originating
context. Providers must emit or derive every value that context needs.

## Repeated correlation

Correlation identifies a destination; it does not provide once-only semantics.
The same provider—or different activations of it—may emit the same correlation id
multiple times. Every accepted emission appends a revision and triggers
downstream dependencies again, including when the payload is equal to the
current value.

The first milestone has no source cardinality declaration and performs no
automatic deduplication, debounce, coalescing, or “already executed” check based
on correlation id. These may become explicit policies if practical workflows
need them.

Providers may carry their own external event ids in emission metadata for audit
or provider-level deduplication, but the workflow language does not assign those
ids semantic behavior initially.

## Lifecycle

A keyed context is active after its first assignment and may later be quiescent.
An open provider can reactivate it at any time. Neither quiescence nor provider
closure implicitly completes the context or workflow execution.

Explicit close, seal, retention, and garbage-collection policies are deferred.
Until such policies exist, the runtime retains enough history to reconstruct the
current view and replay observed activations.
