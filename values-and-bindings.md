# Values and bindings

## Boundary data model

Workflow facts and provider inputs/outputs use a portable data model:

- `null`, Boolean, string, integer, and decimal number;
- ordered arrays;
- string-keyed objects;
- tagged values defined by schemas, including `Outcome` variants.

Canonical serialization is JSON-compatible. Schema metadata may preserve
stronger distinctions, such as integer versus decimal, that plain JSON does
not fully describe.

`unresolved`, `missing`, `null`, and `failure` are different states:

- **unresolved** means the producer has not reached a terminal assignment;
- **missing** means a singular path is absent in a terminal object;
- **null** is an assigned data value;
- **failure** is an explicit `Outcome` variant.

## Initial binding algebra

The compiler represents glue as a small AST with these value forms:

- `Literal(value)`
- `Ref(fact, singular_path, requirement)`
- `Object(fields: ValueBinding)`
- `Array(items: ValueBinding)`
- `Concat(parts: ValueBinding)`

`requirement` is either required or optional. A missing required reference is a
typed binding error. An optional reference produces an option-like tagged value
rather than silently becoming `null`.

References permit only singular navigation through object fields and array
indices. Wildcards, filters, recursive descent, scripts, and collection
projection are excluded. Collection work uses `map`, making fan-out and
fan-in visible in the graph.

## Predicate algebra

Sources and finite branch guards use:

- `Equals(left, right)`
- `Present(optional_value)`
- `And(predicates)`
- `Or(predicates)`
- `Not(predicate)`

Predicates evaluate only after all referenced facts are terminal. `Not` never
means “has not arrived yet.” Absence may be tested only on a terminal object or
a sealed input scope.

Provider outcomes are handled by typed `match`, for example over `Succeeded`,
`Failed`, `TimedOut`, and `Cancelled`. Outcome matching is preferred to testing
for the presence of a success payload.

## No general expressions

The portable graph does not initially contain Python expressions, embedded
strings in another language, or an unrestricted expression VM. A transformation
outside the binding algebra is an explicit provider call.

An operation may later become a built-in only when it is common, deterministic
across runtimes, statically dependency-transparent, and has a total result or a
typed error.

## Illustrative authoring shape

```python
repo = let(ref(event).issue.component)

agent_id = let(concat("agentic-builder-", ref(event).issue.id))

result = match(
    ref(pr),
    Succeeded=lambda value: call("jira.comment", issue=ref(event).issue.id,
                                 body=value.description),
    Failed=lambda error: call("jira.comment", issue=ref(event).issue.id,
                              body=error.report),
)
```

This syntax is illustrative. The semantic requirement is that each helper
constructs a serializable AST rather than evaluating application logic.

