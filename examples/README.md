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
