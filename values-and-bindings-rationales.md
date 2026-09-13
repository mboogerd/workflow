# Values and bindings rationales

This is a chronological, append-only decision log. It explains the design in
[Values and bindings](values-and-bindings.md) but is not itself normative.

## 2026-09-13 — VALUE-001: Consider ordinary Python expressions as workflow glue

**Status:** Superseded by VALUE-003

An embedded Python DSL initially appeared attractive because expressions would
parse normally, receive IDE highlighting, and avoid stringly typed snippets.
Python also makes small transformations much less cumbersome than publishing
every function as an external integration.

The problem is that arbitrary expressions become hidden executable semantics.
A non-Python engine would need Python, a transpiler, or an opaque remote call;
dependency extraction and deterministic replay would also become conditional on
what the code does.

## 2026-09-13 — VALUE-002: Consider a portable expression language

**Status:** Deferred by VALUE-003

A constrained language such as [CEL](https://cel.dev/) could provide portable,
type-checkable predicates. [JMESPath](https://jmespath.org/specification.html)
and JSONata provide increasingly rich JSON transformation. Each would still add
an evaluator, version-compatibility surface, and a second language inside
Python.

CEL remains the leading candidate if the minimal algebra proves insufficient.
JMESPath is more transformation-oriented; JSONata is intentionally broader than
the desired initial surface.

## 2026-09-13 — VALUE-003: Use a tiny serializable binding algebra initially

**Status:** Active

The motivating examples need selection, structural construction, concatenation,
simple predicates, and outcome branching—not arbitrary computation. Encoding
those operations directly as IR nodes gives the compiler complete dependency
information and is straightforward to implement consistently in Python and
Kotlin.

Small glue should remain pleasant and inline. Only transformations outside this
closed algebra cross an explicit provider boundary.

## 2026-09-13 — VALUE-004: Restrict references to singular paths

**Status:** Refined by VALUE-006

[RFC 9535 JSONPath](https://www.rfc-editor.org/rfc/rfc9535.html) distinguishes
singular paths from queries that can select multiple nodes. Singular paths have
predictable cardinality and produce clear dependencies. Wildcards and filters
would hide scatter and filtering behavior inside a value expression, so
collection processing remains an explicit graph construct.

The concrete authoring syntax—JSONPath strings, typed accessors, or both—remains
open, but all forms must lower to the same singular path IR.

## 2026-09-13 — VALUE-005: Distinguish absence from non-arrival

**Status:** Refined by VALUE-007

In an event-driven workflow, “not present yet” is not evidence of absence.
Conflating the two would make branch results depend on timing. Negative tests
therefore require a terminal producer or sealed scope, and optional references
produce an explicit option rather than `null`.

## 2026-09-13 — VALUE-006: Encode the limited expression language structurally in YAML

**Status:** Active

The first authoring surface is now YAML rather than Python. Reserved one-key
objects such as `$ref`, `$concat`, and `$eq` make each expression an explicit
tree in the source document. The compiler does not need to parse arbitrary code
from strings, and the authored form stays close to the IR.

Singular RFC 9535 JSONPath supplies the reference syntax. Rich JSONPath queries
remain excluded because they would conceal collection cardinality and fan-out
inside an expression.

## 2026-09-13 — VALUE-007: Make ordinary references select the latest revision

**Status:** Active

Once context names became versioned registers, reference semantics needed a
simple default. A reference captures the register's latest assignment when its
consumer activates and records that revision as provenance.

Presence and negation now describe that captured snapshot rather than a final
world state. A value absent now may appear later and cause another activation.
Historical revision selection can be added separately if workflows demonstrate
the need.
