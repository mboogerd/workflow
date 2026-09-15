# Workflow v1 vertical implementation plan

## Outcome

This plan builds the specified workflow language as a standalone Kotlin/JVM
runtime in five usable releases. Every milestone ends with an executable
vertical slice, not merely a completed infrastructure layer.

The first implementation intentionally does not depend on ComputeNet. It uses
the same Java 21 and Kotlin 2.1.21 baseline as the adjacent ComputeNet project so
a later backend adapter does not begin with a toolchain migration.

## Execution profile

- Repository: this `workflow` repository.
- Runtime: Kotlin 2.1.21 on Java 21, built with the Gradle wrapper.
- Authoring: YAML 1.2 through Kaml; canonical values and IR use
  `kotlinx.serialization` with explicit handling for decimal numbers.
- Persistent store: SQLite behind runtime storage interfaces, introduced in M4.
- Test framework: JUnit 5 plus Kotlin test assertions. Prefer deterministic
  fixtures and injectable clocks/identifier sources.
- Implementers: `gpt-5.6-luna`, at the per-story reasoning effort in
  `manifest.yaml` and the story frontmatter.
- Review/correction: `gpt-5.6-sol`, at the per-story review effort. The reviewer
  inspects the complete story diff, runs the stated verification, and may make
  corrections within story scope before reporting the result.

The model names and efforts are orchestration metadata, not runtime
configuration for the workflow engine.

## Milestones

| Milestone | Working release | User-visible proof |
| --- | --- | --- |
| M1 — Compile and run | v0.1 | Validate, compile, and execute a one-shot expression workflow from YAML |
| M2 — One repository | v0.2 | Invoke typed providers to build a model for one repository at one commit |
| M3 — System snapshot | v0.3 | Map a finite repository inventory, resolve bidirectional dependencies, and emit a system model |
| M4 — Continuous and durable | v0.4 | Consume repeated commit events, rebuild full snapshots, restart safely, and inspect provenance |
| M5 — Safe agentic v1 | v1.0 | Apply retry, timeout, effect reconciliation, agent budgets, recovery, and the full conformance gate |

M3 is the first complete form of the motivating repository-to-system workflow.
M4 makes that workflow continuously usable, using full recomputation rather
than pretending that incremental transitions exist. M5 completes the operational
safety promised by the v1 specification.

## Scheduling

`manifest.yaml` is the canonical dependency graph. A story is ready when every
`depends_on` story has been implemented, reviewed, corrected, and merged into
the orchestrator's integration baseline.

Within a milestone, ready stories with the same or different `parallel_group`
may run concurrently only when they start from that same baseline. The group is
an integration hint, not permission to ignore file overlap. Later stories must
consume merged code rather than recreate a dependency locally.

The milestone release gate runs only after every story in the milestone passes
individual review. Do not begin the next milestone from an unreviewed partial
state.

## Implementer contract

Each Luna implementer should:

1. Read its complete story and every required design reference.
2. Inspect the current implementation and nearest tests before choosing types or
   file locations.
3. Stay within the story's explicit scope and non-goals.
4. Preserve public semantics from the topic files; rationale files explain but
   do not override them.
5. Add tests that fail without the implemented behavior.
6. Run the narrow checks first and the story's full verification before handoff.
7. Report changed files, tests actually executed, decisions made, and any
   unresolved specification conflict.

Agents must not implement deferred transition, differential-collection,
multi-assignment, cross-context query, or cross-workflow subscription semantics.
The v1 extension seams are boundaries to preserve, not features to anticipate in
code beyond what the assigned story requires.

## Reviewer/corrector contract

Each Sol reviewer should:

1. Re-read the same story and its required design references.
2. Verify the diff implements the acceptance criteria without expanding scope.
3. Check semantic failure paths, durable identity/provenance, and tests—not just
   the happy path.
4. Run the stated commands with execution forced when cached test tasks could
   conceal a zero-test run.
5. Correct defects in the story worktree when the fix remains within scope.
6. Report pass, corrected-pass, or blocked with concrete evidence.

## Release gates

Every milestone must leave all prior examples and tests green. From M1 onward:

```bash
./gradlew test --rerun-tasks
```

Each milestone additionally has a named end-to-end test and CLI demonstration in
its final story. M5 adds a clean-build and distribution smoke test.

## Plan map

- Machine-readable ordering and agent configuration: [manifest.yaml](manifest.yaml)
- M1 stories: [stories/M1](stories/M1)
- M2 stories: [stories/M2](stories/M2)
- M3 stories: [stories/M3](stories/M3)
- M4 stories: [stories/M4](stories/M4)
- M5 stories: [stories/M5](stories/M5)
