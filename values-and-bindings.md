# Values and bindings

## Boundary data model

Register assignments and provider inputs/outputs use a portable data model:

- `null`, Boolean, string, integer, and decimal number;
- ordered arrays;
- string-keyed objects;
- tagged values defined by schemas.

Canonical serialization is JSON-compatible. Schema metadata may preserve
stronger distinctions, such as integer versus decimal, that plain JSON does not
fully describe.

These states are distinct:

- **unassigned**: the register has no revision yet;
- **missing**: a singular path is absent in the selected current value;
- **null**: the path exists and contains an assigned null value;
- **provider failure**: an activation failed without emitting a replacement
  value;
- **error value**: a provider intentionally emitted a tagged application-level
  error as data.

A failed activation does not erase the register's previous current value.

## Reference semantics

An ordinary reference selects the latest assignment to a register at activation
time, then follows a singular path within that value. The activation records the
selected register revision. Historical-revision queries are not part of the
first expression language.

A required reference prevents activation until its register has a current value
and fails binding if the selected path is missing. An optional reference produces
an explicit option-like value. It may be absent in one activation and present in
a later one.

Negative predicates describe only the captured current snapshot. `Not(Present)`
does not claim that a value can never arrive later.

## Initial expression algebra

Expression-bearing YAML positions accept plain YAML values plus a small set of
reserved operators. The compiler lowers them to:

- `Literal(value)`
- `Ref(register, singular_path, requirement)`
- `Object(fields: Expression)`
- `Array(items: Expression)`
- `Concat(parts: Expression)`
- `Equals(left, right)`
- `Present(optional_value)`
- `And(predicates)`
- `Or(predicates)`
- `Not(predicate)`

Provider result variants may be handled by graph-level `match`.

## YAML representation

Plain YAML scalars are literals. Sequences and mappings recursively construct
arrays and objects. A mapping with exactly one reserved `$` key denotes an
expression operator:

| YAML form | IR form |
| --- | --- |
| `{$ref: "$.event.issue.id"}` | Required singular reference |
| `{$optional: "$.event.issue.parent"}` | Optional singular reference |
| `{$concat: [...]}` | String concatenation |
| `{$eq: [left, right]}` | Equality predicate |
| `{$present: value}` | Presence predicate |
| `{$and: [...]}` / `{$or: [...]}` | Boolean composition |
| `{$not: predicate}` | Boolean negation |
| `{$literal: value}` | Escape a value that would otherwise look like an operator |

References use the singular subset of RFC 9535 JSONPath. The available roots
are lexical:

- ordinary definitions expose current context registers as `$.<name>` and
  workflow parameters as `$.parameters`;
- a `map` body additionally exposes `$.item` and `$.key`;
- provider implementations receive their bound input through the provider
  protocol rather than gaining implicit access to the whole context.

The compiler rejects non-singular paths and unavailable roots. Unknown `$`
operators are errors. A literal application object containing `$` keys must be
wrapped in `$literal`.

## No general-purpose expressions

The portable graph does not initially contain host-language expressions,
embedded string programs, or an unrestricted expression VM. A transformation
outside the expression algebra is a provider.

An operation may later become built-in only when it is common, deterministic
across runtimes, dependency-transparent, and has a total result or typed error.

## Illustrative authoring shape

```yaml
context:
  repo:
    $ref: "$.event.issue.component"

  agent-id:
    $concat:
      - agentic-builder-
      - {$ref: "$.event.issue.id"}

  result:
    match:
      value: {$ref: "$.pr"}
      cases:
        Succeeded:
          provider: jira.comment
          with:
            issue: {$ref: "$.event.issue.id"}
            body: {$ref: "$.pr.value.description"}
        Failed:
          provider: jira.comment
          with:
            issue: {$ref: "$.event.issue.id"}
            body: {$ref: "$.pr.error.report"}
```

The exact branch-local reference shape remains subject to the provider output
schema.

