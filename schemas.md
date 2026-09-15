# Schemas

## Purpose

V1 uses a small structural schema algebra for workflow parameters, expression
results, provider inputs, provider emissions, errors, and branch compatibility.
It is part of the portable IR and does not depend on a host-language type system
or a full JSON Schema implementation.

## Schema forms

The algebra contains:

- `any`;
- `null`, `boolean`, `string`, `integer`, and `decimal`;
- `array`, containing one item schema;
- `object`, containing named required or optional fields and an
  `additional-fields` Boolean, which defaults to `false`;
- `tagged-union`, containing a string discriminator name and object-schema
  variants keyed by discriminator value.

An integer is not accepted as a decimal and no schema performs implicit string,
number, Boolean, or null coercion. Tagged-union variant objects must contain the
declared discriminator with the matching literal string.

## YAML representation

Primitive schemas use their names directly. Composite schemas use mappings:

```yaml
parameters:
  repository:
    schema:
      type: object
      fields:
        owner: {schema: string, required: true}
        name: {schema: string, required: true}
        installation: {schema: string, required: false}
      additional-fields: false

context:
  repositories:
    provider: repository-list
    version: 1
    schema:
      type: array
      items:
        type: object
        fields:
          id: {schema: string, required: true}
          url: {schema: string, required: true}
```

Provider descriptors use the same canonical schema structures. A context entry
may state its output schema explicitly. If it omits the schema, expressions infer
one when possible and provider definitions use the registered provider emission
schema.

## Validation and compatibility

Values are validated whenever they enter the runtime through parameters or a
provider emission. Expression outputs are validated before assignment when their
register has an explicit schema.

V1 static compatibility is intentionally conservative:

- identical canonical schemas are compatible;
- any actual schema is compatible with expected `any`;
- an actual `any` schema is compatible only with expected `any`;
- no other subtyping or coercion is inferred.

Authors may place a provider boundary between different schemas when an explicit
transformation is required. Rich structural subtyping, schema registries, and
compatibility evolution may be added later without changing v1 validation.

## Versioning

Every serialized schema carries the workflow IR format version. Provider
descriptors additionally carry provider id and version. A runtime rejects an
unknown schema form or unsupported IR version before execution rather than
treating it as `any`.
