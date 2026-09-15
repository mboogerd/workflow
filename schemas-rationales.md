# Schema rationales

This is a chronological, append-only decision log. It explains the design in
[Schemas](schemas.md) but is not itself normative.

## 2026-09-13 — SCHEMA-001: Start with a closed structural schema algebra

**Status:** Active

Provider validation, branch compatibility, tagged outcomes, and portable IR all
need a schema contract in the first executable version. Depending on a complete
JSON Schema evaluator would add a much larger language, reference-resolution
model, and compatibility surface than the workflow currently needs.

The closed algebra covers the canonical value model and can be translated to or
from richer schema systems at integration boundaries later.

## 2026-09-13 — SCHEMA-002: Make v1 compatibility conservative

**Status:** Active

Structural subtyping becomes subtle around optional fields, closed objects,
tagged variants, and provider evolution. Exact canonical equality plus an
explicit expected `any` rule is easy to implement and diagnose. It may reject
safe programs, but never accepts a connection the runtime cannot justify.

Compatibility can be relaxed in a later IR version without invalidating v1
artifacts.
