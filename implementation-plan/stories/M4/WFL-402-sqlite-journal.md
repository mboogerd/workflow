---
id: WFL-402
milestone: M4
title: Implement the transactional SQLite journal and projections
implementation_model: gpt-5.6-luna
implementation_effort: xhigh
review_model: gpt-5.6-sol
review_effort: xhigh
depends_on: [WFL-304]
---

# Outcome

The runtime can use SQLite as a durable implementation of its existing storage
interfaces, atomically preserving singleton journal batches, current views, and
activation intents across process failure.

# Required reading

- `execution-backend.md`
- `execution-semantics.md`, assignment journal
- `evolution-seams.md`
- `failure-and-recovery.md`, lifecycle records
- Existing in-memory storage interfaces and record serialization

# Scope

- Add a pinned SQLite JDBC dependency and a SQLite-backed storage implementation.
- Introduce an explicit database format version and ordered, transactional
  migrations starting at version 1.
- Persist workflow definitions/content hashes, executions, contexts, journal
  batches, assignments, current register projections, activation intents,
  activations, provider invocations, attempts, emissions, failures, and decisions
  already supported by the runtime.
- Enforce one assignment mutation per v1 journal batch in both application and
  database constraints where practical.
- In one SQLite transaction: allocate journal/register revisions, append the
  batch and assignment, update the current view, derive/persist activation
  intents from the resulting view, then commit.
- Make activation-intent claiming crash-safe with explicit pending/claimed/done
  states and lease or owner metadata. A process crash may cause a claim to be
  retried but must not lose the intent.
- Add rebuild logic that discards/recreates projections from authoritative
  journal records in a test database.
- Parameterize database paths and prevent tests from sharing global state.

# Acceptance criteria

- Transaction-fault tests at every commit step prove there is no visible partial
  batch, updated view without assignment, or assignment without activation
  intent.
- Reopening a database preserves exact IDs, revisions, journal order, and pending
  work.
- Projection rebuild produces the same current views and runnable intents as the
  live database.
- Concurrent writers cannot allocate duplicate journal positions or register
  revisions.
- Migration tests cover new, current, and unsupported-newer database versions.
- The in-memory store remains usable for fast tests and passes the same storage
  contract suite.

# Non-goals

- Multi-process scheduling, distributed transactions, multi-assignment batches,
  compaction, or transition compare-and-swap semantics.

# Verification

```bash
./gradlew test --tests '*StoreContractTest' --rerun-tasks
./gradlew test --tests '*Sqlite*Test' --rerun-tasks
./gradlew test --rerun-tasks
```

# Handoff

Report schema/migrations, atomic transaction boundary, intent-claim recovery,
fault-injection matrix, storage-contract parity, and tests executed.
