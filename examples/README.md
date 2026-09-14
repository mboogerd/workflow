# Workflow examples

## Offline repository model

The M2 example runs an entire repository-to-model workflow without network
access, credentials, a checkout, or an LLM. Use the explicit `demo` provider
profile:

```bash
./gradlew run --args='run examples/single-repository.yaml --parameters examples/single-repository-parameters.json --providers demo'
```

The output contains `model` (repository, commit, a summary, and stable source
file entities with path/line evidence) and `summary`. The repository-reader
maps the parameter pair to a small fixture; the model-builder is declared
`agentic` to exercise that boundary but is deterministic scaffolding, not an
LLM integration. Provider profiles are opt-in, so an unknown provider is never
supplied by a global singleton.

## Offline multi-repository architecture

The M3 example takes one finite repository inventory snapshot, scatters a model
build over every entry, gathers models in deterministic repository order, and
then resolves both outgoing and incoming dependency views before generating the
system architecture:

```bash
./gradlew run --args='run examples/multi-repository.yaml --parameters examples/multi-repository-parameters.json --providers demo'
```

The inventory demonstrates an independent repository, a dependency chain, and a
repository-domain cycle. The cycle is data handled by the topology provider; it
does not create a cycle in the workflow graph. Each model contains its
repository and commit identity, while topology and architecture contain the
gathered model revisions used to derive them. Every triggering inventory
snapshot rebuilds every repository model; this is a full recomputation, not
incremental processing.

## Continuous repository demonstration

`continuous-repositories.yaml` is an offline, SQLite-backed workflow that
accepts repeated commit-event fixtures and creates one new complete
repository/topology/system-model revision for each accepted event.

Start an execution, then inject fixture events, inspect it, resume it after a
process restart, or stop it administratively:

```bash
./gradlew run --args='demo continuous-repositories start --database build/demo-workflow.db'
./gradlew run --args="demo continuous-repositories emit --database build/demo-workflow.db --event '{\"repository\":\"app/service\",\"branch\":\"main\",\"deliveryId\":\"delivery-a\",\"commit\":\"trigger-a\"}'"
./gradlew run --args='demo continuous-repositories inspect --database build/demo-workflow.db'
./gradlew run --args='demo continuous-repositories resume --database build/demo-workflow.db'
./gradlew run --args='demo continuous-repositories stop --database build/demo-workflow.db'
```

The source is an open in-process provider, so events survive a real restart
through the journal and require neither a network service nor a process-local
queue. Inspection emits stable JSON with each architecture revision's full
dependency-revision chain back to its triggering event. The event's repository
projection is routed to a distinct correlated context; the topology and system
pipeline only reads anonymous-context inventory/map outputs and never performs
cross-context aggregation.

This v0.4 demo deliberately rebuilds every repository model for every accepted
main-branch event. It does not deduplicate delivery IDs, perform incremental or
latest-only scheduling, or prevent a stale completion from being committed after
a newer activation under the v1 policy. Non-main fixtures are explicitly
filtered by the continuous inventory provider and produce an empty global
snapshot.
