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
