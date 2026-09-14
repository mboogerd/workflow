package io.workflow.compiler

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.AnchorsAndAliases
import com.charleskorn.kaml.YamlConfiguration
import com.charleskorn.kaml.YamlException
import com.charleskorn.kaml.YamlList
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlNode
import com.charleskorn.kaml.YamlNull
import com.charleskorn.kaml.YamlScalar
import com.charleskorn.kaml.YamlTaggedNode
import io.workflow.core.CanonicalValueJson
import io.workflow.core.ProducerId
import io.workflow.core.RegisterId
import io.workflow.core.Value
import io.workflow.core.ValueSchema
import io.workflow.core.WorkflowVersionId
import io.workflow.core.isCompatibleWith
import io.workflow.core.validate
import io.workflow.provider.ProviderDescriptor
import io.workflow.provider.ProviderKey
import io.workflow.provider.ProviderRegistry
import java.math.BigDecimal
import java.math.BigInteger
import java.security.MessageDigest
import kotlin.text.Charsets.UTF_8
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

data class SourceLocation(val line: Int, val column: Int) {
    override fun toString() = "$line:$column"
}

data class Diagnostic(
    val message: String,
    val semanticPath: String,
    val location: SourceLocation,
) {
    override fun toString() = "$location $semanticPath: $message"
}

data class CompilationResult(val ir: WorkflowIrDocument?, val diagnostics: List<Diagnostic>) {
    val isValid get() = diagnostics.isEmpty() && ir != null
}

sealed interface Expression {
    data class Literal(val value: Value) : Expression
    data class Ref(val root: String, val path: List<PathStep>, val requirement: Requirement) : Expression
    data class ObjectValue(val fields: Map<String, Expression>) : Expression
    data class ArrayValue(val items: List<Expression>) : Expression
    data class Concat(val parts: List<Expression>) : Expression
    data class Equals(val left: Expression, val right: Expression) : Expression
    data class Present(val value: Expression) : Expression
    data class And(val predicates: List<Expression>) : Expression
    data class Or(val predicates: List<Expression>) : Expression
    data class Not(val predicate: Expression) : Expression
}

enum class Requirement { REQUIRED, OPTIONAL }
sealed interface PathStep {
    data class Field(val name: String) : PathStep
    data class Index(val index: Int) : PathStep
}

data class CompiledRegister(
    val name: String,
    val registerId: RegisterId,
    val producerId: ProducerId,
    val producer: Expression,
    val dependencies: List<String>,
    val schema: ValueSchema,
    val source: SourceLocation,
    /** Present only for a provider producer; ordinary expressions remain unchanged. */
    val provider: CompiledProvider? = null,
    /** Present for a `match` or `map` producer, and for all nested producers. */
    val compiledProducer: CompiledProducer? = null,
    val match: CompiledMatch? = null,
    val map: CompiledMap? = null,
) {
    /** Name used by consumers that treat all producer forms uniformly. */
    val producerNode: CompiledProducer? get() = compiledProducer
}

data class CompiledProvider(
    val providerId: String,
    val version: Int,
    val config: Expression,
    val input: Expression,
    val capabilities: Set<String> = emptySet(),
    val policy: Value = Value.ObjectValue(emptyMap()),
) {
    val providerVersion: Int get() = version
}

/** The deterministic ordering used when a finite map gathers its item results. */
enum class MapResultOrdering { ARRAY_INDEX, OBJECT_KEY }

/** Stable identity source for expanded items in one map activation. */
enum class MapItemIdentityPolicy { ARRAY_INDEX, OBJECT_KEY }

/** A producer in a nested graph. Expression/provider fields remain available
 * on [CompiledRegister] for compatibility with the first two milestones. */
sealed interface CompiledProducer {
    val producerId: ProducerId
    val schema: ValueSchema
    val dependencies: List<String>
    val source: SourceLocation

    data class Expression(
        val value: io.workflow.compiler.Expression,
        override val producerId: ProducerId,
        override val schema: ValueSchema,
        override val dependencies: List<String>,
        override val source: SourceLocation,
    ) : CompiledProducer

    data class Provider(
        val value: CompiledProvider,
        override val producerId: ProducerId,
        override val schema: ValueSchema,
        override val dependencies: List<String>,
        override val source: SourceLocation,
    ) : CompiledProducer

    data class Match(
        val value: CompiledMatch,
        override val producerId: ProducerId,
        override val schema: ValueSchema,
        override val dependencies: List<String>,
        override val source: SourceLocation,
    ) : CompiledProducer

    data class Map(
        val value: CompiledMap,
        override val producerId: ProducerId,
        override val schema: ValueSchema,
        override val dependencies: List<String>,
        override val source: SourceLocation,
    ) : CompiledProducer
}

/** A match discriminator and its exhaustive, canonically keyed branch graph. */
data class CompiledMatch(
    val discriminator: io.workflow.compiler.Expression,
    val discriminatorSchema: ValueSchema.TaggedUnion,
    val cases: Map<String, CompiledProducer>,
    val outputSchema: ValueSchema,
) {
    /** Alias matching the authoring key. */
    val value: io.workflow.compiler.Expression get() = discriminator
    val caseProducers: Map<String, CompiledProducer> get() = cases
}

/** A finite scatter/gather graph. */
data class CompiledMap(
    val input: io.workflow.compiler.Expression,
    val context: List<CompiledRegister>,
    val output: String,
    val outputSchema: ValueSchema,
    val ordering: MapResultOrdering,
    val itemIdentityPolicy: MapItemIdentityPolicy,
) {
    /** Alias matching the authoring key. */
    val over: io.workflow.compiler.Expression get() = input
    /** Body registers are the nested producer graph. */
    val registers: List<CompiledRegister> get() = context
    val body: List<CompiledRegister> get() = context
    val resultOrdering: MapResultOrdering get() = ordering
}

data class WorkflowIrDocument(
    val workflowId: String,
    val version: Int,
    val workflowVersionId: WorkflowVersionId,
    val parameters: Map<String, ValueSchema>,
    val registers: List<CompiledRegister>,
    val outputs: List<String>,
    val contentHash: String,
    val irVersion: Int = 1,
) {
    fun canonicalJson(): String = CanonicalIrJson.document(this, includeHash = true, includeSource = false)

    /** Hash of the canonical deployed IR excluding its self-referential hash. */
    fun computedContentHash(): String = sha256(CanonicalIrJson.document(this, includeHash = false, includeSource = false))

    fun hasValidContentHash(): Boolean = contentHash == computedContentHash()
}

private data class NodeInfo(val node: YamlNode, val path: String) {
    val location: SourceLocation
        get() = SourceLocation(node.location.line, node.location.column)
}

private sealed interface ProducerDraft {
    val node: YamlNode
    val path: String
    val explicitSchema: ValueSchema?

    data class Expression(
        override val node: YamlNode,
        override val path: String,
        val value: io.workflow.compiler.Expression,
        override val explicitSchema: ValueSchema?,
    ) : ProducerDraft

    data class Provider(
        override val node: YamlNode,
        override val path: String,
        val value: CompiledProvider,
        override val explicitSchema: ValueSchema?,
    ) : ProducerDraft

    data class Match(
        override val node: YamlNode,
        override val path: String,
        val value: io.workflow.compiler.Expression,
        val cases: kotlin.collections.Map<String, ProducerDraft>,
        override val explicitSchema: ValueSchema?,
    ) : ProducerDraft

    data class Map(
        override val node: YamlNode,
        override val path: String,
        val value: io.workflow.compiler.Expression,
        val context: kotlin.collections.Map<String, ProducerDraft>,
        val output: String?,
        override val explicitSchema: ValueSchema?,
    ) : ProducerDraft
}

private data class ScopeCompilation(
    val registers: List<CompiledRegister>,
    val schemas: Map<String, ValueSchema>,
)

private data class DraftCompilation(
    val producer: CompiledProducer,
    val expression: io.workflow.compiler.Expression,
    val provider: CompiledProvider?,
    val match: CompiledMatch?,
    val map: CompiledMap?,
)

class WorkflowCompiler(
    private val maxDocumentCodePoints: Int = 1_000_000,
    private val maxNesting: Int = 64,
    private val maxAliases: Int = 32,
    val providerRegistry: ProviderRegistry = ProviderRegistry(),
) {
    constructor(providerRegistry: ProviderRegistry) : this(1_000_000, 64, 32, providerRegistry)

    private var activeYamlLines: List<String> = emptyList()

    init {
        require(maxDocumentCodePoints > 0) { "maxDocumentCodePoints must be positive" }
        require(maxNesting > 0) { "maxNesting must be positive" }
        require(maxAliases >= 0) { "maxAliases must not be negative" }
    }

    private val yaml = Yaml(
        configuration = YamlConfiguration(
            anchorsAndAliases = AnchorsAndAliases.Permitted(maxAliases.toUInt()),
            codePointLimit = maxDocumentCodePoints,
        ),
    )

    @Synchronized
    fun compile(yamlText: String): CompilationResult {
        activeYamlLines = emptyList()
        val diagnostics = mutableListOf<Diagnostic>()
        preflightNestingLocation(yamlText)?.let {
            return CompilationResult(null, listOf(Diagnostic("YAML nesting exceeds the limit", "$", it)))
        }
        val root = try { yaml.parseToYamlNode(yamlText) } catch (e: Exception) {
            val location = (e as? YamlException)?.let {
                SourceLocation(it.line.coerceAtLeast(1), it.column.coerceAtLeast(1))
            } ?: SourceLocation(1, 1)
            diagnostics += Diagnostic("invalid YAML: ${e.message ?: "parse error"}", "$", location)
            return CompilationResult(null, diagnostics)
        }
        activeYamlLines = yamlText.lines()
        validateYamlTree(root, 1, diagnostics)
        val rootMap = root as? YamlMap ?: run {
            diagnostics += Diagnostic("document must be a mapping", "$", root.location.source())
            return CompilationResult(null, diagnostics)
        }
        checkFields(rootMap, setOf("workflow"), "$", diagnostics)
        val workflow = rootMap.get("workflow") as? YamlMap ?: run {
            diagnostics += Diagnostic("required mapping 'workflow' is missing", "$.workflow", rootMap.location.source())
            return CompilationResult(null, diagnostics)
        }
        checkFields(workflow, setOf("id", "version", "parameters", "context", "outputs"), "$.workflow", diagnostics)
        val id = stringScalar(workflow, "id", "$.workflow.id", diagnostics)?.takeIf { it.isNotBlank() }
        val version = integerScalar(workflow, "version", "$.workflow.version", diagnostics)
        if (version != null && version <= 0) diagnostics += Diagnostic("version must be positive", "$.workflow.version", location(workflow, "version"))
        val parameters = parseParameters(workflow.get("parameters"), diagnostics)
        val context = workflow.get("context") as? YamlMap ?: run {
            diagnostics += Diagnostic("required mapping 'context' is missing", "$.workflow.context", location(workflow, "context"))
            null
        }
        val outputs = parseOutputs(workflow.get("outputs"), diagnostics)
        if (context == null || id == null || version == null) return CompilationResult(null, diagnostics)

        val definitions = linkedMapOf<String, ProducerDraft>()
        context.entries.forEach { (key, node) ->
            val name = key.content
            val path = "$.workflow.context.$name"
            if (!name.matches(Regex("[A-Za-z_][A-Za-z0-9_-]*"))) {
                diagnostics += Diagnostic("register name is not a valid identifier", path, key.location.source())
            }
            if (name in RESERVED_ROOTS) diagnostics += Diagnostic("register name '$name' is reserved", path, key.location.source())
            if (name in definitions) diagnostics += Diagnostic("duplicate register definition '$name'", path, key.location.source())
            parseProducer(node, path, diagnostics)?.let { if (name !in definitions) definitions[name] = it }
        }
        val identityPrefix = "$id@$version"
        val scope = compileScope(
            definitions = definitions,
            scopePath = "$.workflow.context",
            identityPrefix = identityPrefix,
            parameters = parameters,
            outerSchemas = emptyMap(),
            outerNames = emptySet(),
            lexicalRoots = emptySet(),
            lexicalSchemas = emptyMap(),
            forbiddenNames = emptySet(),
            diagnostics = diagnostics,
        )
        val registers = scope.registers
        outputs.forEachIndexed { index, output ->
            if (output !in definitions) diagnostics += Diagnostic("output '$output' is unreachable because no such register is defined", "$.workflow.outputs[$index]", location(workflow, "outputs"))
        }
        if (diagnostics.isNotEmpty()) return CompilationResult(null, diagnostics)
        val withoutHash = WorkflowIrDocument(
            workflowId = id,
            version = version,
            workflowVersionId = WorkflowVersionId("$id@$version"),
            parameters = parameters.toSortedMap(),
            registers = registers.sortedBy { it.name },
            outputs = outputs,
            contentHash = "",
        )
        val hash = sha256(CanonicalIrJson.document(withoutHash, includeHash = false, includeSource = false))
        return CompilationResult(withoutHash.copy(contentHash = hash), emptyList())
    }

    private fun parseProducer(node: YamlNode, path: String, diagnostics: MutableList<Diagnostic>): ProducerDraft? {
        val map = node as? YamlMap
        val fields = map?.entries?.keys?.map { it.content }.orEmpty()
        val forms = fields.filter { it == "provider" || it == "match" || it == "map" }
        if (forms.size > 1) {
            diagnostics += Diagnostic("a producer mapping may contain only one producer form", path, node.location.source())
            return null
        }
        val explicitSchema = parseEmbeddedSchema(node, "$path.schema", diagnostics)
        return when (forms.singleOrNull()) {
            "provider" -> parseProvider(node, path, diagnostics)?.let {
                ProducerDraft.Provider(node, path, it, explicitSchema)
            }
            "match" -> parseMatch(node, path, explicitSchema, diagnostics)
            "map" -> parseMap(node, path, explicitSchema, diagnostics)
            else -> parseExpression(node, path, diagnostics)?.let {
                ProducerDraft.Expression(node, path, it, explicitSchema)
            }
        }
    }

    private fun parseMatch(
        node: YamlNode,
        path: String,
        explicitSchema: ValueSchema?,
        diagnostics: MutableList<Diagnostic>,
    ): ProducerDraft? {
        val map = node as? YamlMap ?: run {
            diagnostics += Diagnostic("match producer form is not supported: match producer must be a mapping", path, node.location.source())
            return null
        }
        checkFields(map, setOf("match", "schema"), path, diagnostics)
        val body = map.get<YamlNode>("match") as? YamlMap ?: run {
            diagnostics += Diagnostic("match producer form is not supported: match requires a mapping", "$path.match", location(map, "match"))
            return null
        }
        checkFields(body, setOf("value", "cases"), "$path.match", diagnostics)
        val valueNode = body.get<YamlNode>("value") ?: run {
            diagnostics += Diagnostic("match requires a discriminator value", "$path.match.value", location(body, "value"))
            return null
        }
        val value = parseExpression(valueNode, "$path.match.value", diagnostics) ?: return null
        val casesNode = body.get<YamlNode>("cases") as? YamlMap ?: run {
            diagnostics += Diagnostic("match requires a cases mapping", "$path.match.cases", location(body, "cases"))
            return null
        }
        val cases = linkedMapOf<String, ProducerDraft>()
        casesNode.entries.forEach { (key, caseNode) ->
            val tag = key.content
            val casePath = "$path.match.cases.$tag"
            if (tag.isBlank()) diagnostics += Diagnostic("match case name must not be empty", casePath, key.location.source())
            if (tag in cases) diagnostics += Diagnostic("duplicate match case '$tag'", casePath, key.location.source())
            parseProducer(caseNode, casePath, diagnostics)?.let { if (tag !in cases) cases[tag] = it }
        }
        return ProducerDraft.Match(node, path, value, cases, explicitSchema)
    }

    private fun parseMap(
        node: YamlNode,
        path: String,
        explicitSchema: ValueSchema?,
        diagnostics: MutableList<Diagnostic>,
    ): ProducerDraft? {
        val map = node as? YamlMap ?: run {
            diagnostics += Diagnostic("map producer form is not supported: map producer must be a mapping", path, node.location.source())
            return null
        }
        checkFields(map, setOf("map", "schema"), path, diagnostics)
        val body = map.get<YamlNode>("map") as? YamlMap ?: run {
            diagnostics += Diagnostic("map producer form is not supported: map requires a mapping", "$path.map", location(map, "map"))
            return null
        }
        checkFields(body, setOf("over", "context", "output"), "$path.map", diagnostics)
        val overNode = body.get<YamlNode>("over") ?: run {
            diagnostics += Diagnostic("map requires an input under 'over'", "$path.map.over", location(body, "over"))
            return null
        }
        val over = parseExpression(overNode, "$path.map.over", diagnostics) ?: return null
        val contextNode = body.get<YamlNode>("context") as? YamlMap ?: run {
            diagnostics += Diagnostic("map requires a nested context mapping", "$path.map.context", location(body, "context"))
            return null
        }
        val context = linkedMapOf<String, ProducerDraft>()
        contextNode.entries.forEach { (key, producerNode) ->
            val name = key.content
            val registerPath = "$path.map.context.$name"
            if (!name.matches(Regex("[A-Za-z_][A-Za-z0-9_-]*"))) {
                diagnostics += Diagnostic("register name is not a valid identifier", registerPath, key.location.source())
            }
            if (name in RESERVED_ROOTS) diagnostics += Diagnostic("register name '$name' is reserved", registerPath, key.location.source())
            if (name in context) diagnostics += Diagnostic("duplicate register definition '$name'", registerPath, key.location.source())
            parseProducer(producerNode, registerPath, diagnostics)?.let { if (name !in context) context[name] = it }
        }
        val outputNode = body.get<YamlNode>("output")
        val output = (outputNode as? YamlScalar)?.let(::scalarValue) as? Value.StringValue
        if (output == null) diagnostics += Diagnostic("map requires output to name one body register", "$path.map.output", location(body, "output"))
        return ProducerDraft.Map(node, path, over, context, output?.value, explicitSchema)
    }

    private fun compileScope(
        definitions: Map<String, ProducerDraft>,
        scopePath: String,
        identityPrefix: String,
        parameters: Map<String, ValueSchema>,
        outerSchemas: Map<String, ValueSchema>,
        outerNames: Set<String>,
        lexicalRoots: Set<String>,
        lexicalSchemas: Map<String, ValueSchema>,
        forbiddenNames: Set<String>,
        diagnostics: MutableList<Diagnostic>,
    ): ScopeCompilation {
        definitions.keys.filter { it in forbiddenNames }.forEach { name ->
            diagnostics += Diagnostic("nested register '$name' shadows an outer register", "$scopePath.$name", definitions.getValue(name).node.location.source())
        }
        val localNames = definitions.keys
        val visibleNames = (localNames + outerNames).toSet()
        val schemas = outerSchemas.toMutableMap()
        localNames.forEach { schemas.putIfAbsent(it, ValueSchema.Any) }
        val compiled = linkedMapOf<String, DraftCompilation>()
        val compiling = mutableSetOf<String>()

        fun draftDependencies(draft: ProducerDraft): Set<String> = when (draft) {
            is ProducerDraft.Expression -> dependencies(draft.value).toSet()
            is ProducerDraft.Provider -> dependencies(draft.value.input).toSet() + dependencies(draft.value.config)
            is ProducerDraft.Match -> draftDependenciesFromExpression(draft.value) + draft.cases.values.flatMap { draftDependencies(it) }
            is ProducerDraft.Map -> draftDependenciesFromExpression(draft.value) + draft.context.values.flatMap { draftDependencies(it) }
        }

        fun compileName(name: String): DraftCompilation {
            compiled[name]?.let { return it }
            val draft = definitions.getValue(name)
            if (!compiling.add(name)) {
                diagnostics += Diagnostic("dependency cycle involving '$name'", "$scopePath.$name", draft.node.location.source())
                return DraftCompilation(
                    producer = CompiledProducer.Expression(io.workflow.compiler.Expression.Literal(Value.Null), ProducerId("$identityPrefix/producer/$name"), ValueSchema.Any, emptyList(), draft.node.location.source()),
                    expression = io.workflow.compiler.Expression.Literal(Value.Null),
                    provider = null,
                    match = null,
                    map = null,
                )
            }
            draftDependencies(draft).filter { it in localNames }.forEach(::compileName)
            val result = compileDraft(
                draft = draft,
                name = name,
                producerId = ProducerId("$identityPrefix/producer/$name"),
                parameters = parameters,
                schemas = schemas,
                visibleNames = visibleNames,
                lexicalRoots = lexicalRoots,
                lexicalSchemas = lexicalSchemas,
                outerNames = outerNames,
                diagnostics = diagnostics,
                identityPrefix = identityPrefix,
            )
            schemas[name] = result.producer.schema
            compiled[name] = result
            compiling -= name
            return result
        }

        definitions.keys.forEach(::compileName)
        val registers = definitions.keys.mapNotNull { name ->
            val result = compiled[name] ?: return@mapNotNull null
            val registerId = RegisterId("$identityPrefix/register/$name")
            val producerId = ProducerId("$identityPrefix/producer/$name")
            val deps = result.producer.dependencies.filter { it in visibleNames }.distinct().sorted()
            CompiledRegister(
                name = name,
                registerId = registerId,
                producerId = producerId,
                producer = result.expression,
                dependencies = deps,
                schema = result.producer.schema,
                source = result.producer.source,
                provider = result.provider,
                compiledProducer = result.producer,
                match = result.match,
                map = result.map,
            )
        }.sortedBy { it.name }
        return ScopeCompilation(registers, localNames.associateWith { schemas.getValue(it) })
    }

    private fun compileDraft(
        draft: ProducerDraft,
        name: String,
        producerId: ProducerId,
        parameters: Map<String, ValueSchema>,
        schemas: Map<String, ValueSchema>,
        visibleNames: Set<String>,
        lexicalRoots: Set<String>,
        lexicalSchemas: Map<String, ValueSchema>,
        outerNames: Set<String>,
        diagnostics: MutableList<Diagnostic>,
        identityPrefix: String,
    ): DraftCompilation {
        val source = draft.node.location.source()
        val expressionSchema = { expression: io.workflow.compiler.Expression, path: String ->
            inferExpression(expression, parameters, schemas, visibleNames, lexicalRoots, diagnostics, path, source, lexicalSchemas)
        }
        fun deps(expression: io.workflow.compiler.Expression): List<String> = dependencies(expression)
            .filter { it in visibleNames }
            .distinct()
            .sorted()
        fun compatible(actual: ValueSchema, expected: ValueSchema?, path: String) {
            if (expected != null && !actual.isCompatibleWith(expected)) {
                diagnostics += Diagnostic("expression schema ${schemaName(actual)} is incompatible with declared ${schemaName(expected)}", path, source)
            }
        }
        return when (draft) {
            is ProducerDraft.Expression -> {
                val schema = expressionSchema(draft.value, draft.path)
                compatible(schema, draft.explicitSchema, "${draft.path}.schema")
                val node = CompiledProducer.Expression(draft.value, producerId, schema, deps(draft.value), source)
                DraftCompilation(node, draft.value, null, null, null)
            }
            is ProducerDraft.Provider -> {
                val descriptor = providerRegistry.resolve(draft.value.providerId, draft.value.version)?.descriptor
                val configSchema = expressionSchema(draft.value.config, "${draft.path}.config")
                val inputSchema = expressionSchema(draft.value.input, "${draft.path}.with")
                if (descriptor != null) {
                    if (!configSchema.isCompatibleWith(descriptor.configurationSchema)) diagnostics += Diagnostic(
                        "configuration schema ${schemaName(configSchema)} is incompatible with provider configuration ${schemaName(descriptor.configurationSchema)}",
                        "${draft.path}.config", source,
                    )
                    if (!inputSchema.isCompatibleWith(descriptor.inputSchema)) diagnostics += Diagnostic(
                        "bound input schema ${schemaName(inputSchema)} is incompatible with provider input ${schemaName(descriptor.inputSchema)}",
                        "${draft.path}.with", source,
                    )
                }
                val schema = descriptor?.emissionSchema ?: ValueSchema.Any
                compatible(schema, draft.explicitSchema, "${draft.path}.schema")
                val node = CompiledProducer.Provider(draft.value, producerId, schema, (deps(draft.value.input) + deps(draft.value.config)).distinct().sorted(), source)
                DraftCompilation(node, draft.value.input, draft.value, null, null)
            }
            is ProducerDraft.Match -> {
                val discriminatorSchema = expressionSchema(draft.value, "${draft.path}.match.value")
                val tagged = discriminatorSchema as? ValueSchema.TaggedUnion
                if (tagged == null) {
                    diagnostics += Diagnostic("match discriminator must have a tagged-union schema", "${draft.path}.match.value", source)
                }
                val expectedTags = tagged?.variants?.keys.orEmpty()
                expectedTags.filterNot(draft.cases.keys::contains).forEach { tag ->
                    diagnostics += Diagnostic("match is not exhaustive; missing case '$tag'", "${draft.path}.match.cases", source)
                }
                draft.cases.keys.filterNot(expectedTags::contains).forEach { tag ->
                    diagnostics += Diagnostic("match case '$tag' is not present in discriminator schema", "${draft.path}.match.cases.$tag", draft.cases.getValue(tag).node.location.source())
                }
                val cases = draft.cases.toSortedMap().mapValues { (tag, caseDraft) ->
                    compileDraft(
                        draft = caseDraft,
                        name = "$name/$tag",
                        producerId = ProducerId("${producerId.value}/match/case/${stableIdSegment(tag)}"),
                        parameters = parameters,
                        schemas = schemas,
                        visibleNames = visibleNames,
                        lexicalRoots = lexicalRoots + "match",
                        lexicalSchemas = lexicalSchemas + ("match" to (tagged?.variants?.get(tag) ?: ValueSchema.Any)),
                        outerNames = outerNames,
                        diagnostics = diagnostics,
                        identityPrefix = identityPrefix,
                    ).producer
                }
                val branchSchemas = cases.values.map { it.schema }.distinct()
                val inferredSchema = when {
                    draft.explicitSchema != null -> draft.explicitSchema
                    branchSchemas.size == 1 -> branchSchemas.single()
                    branchSchemas.isEmpty() -> ValueSchema.Any
                    else -> {
                        diagnostics += Diagnostic("match branch output schemas must be identical", "${draft.path}.match.cases", source)
                        ValueSchema.Any
                    }
                }
                if (draft.explicitSchema == null && branchSchemas.size > 1) {
                    // The diagnostic above is intentionally emitted even when one
                    // branch is `any`: v1 branch compatibility is canonical.
                }
                cases.forEach { (tag, branch) ->
                    if (branch.schema != inferredSchema) diagnostics += Diagnostic(
                        "match branch '$tag' output schema ${schemaName(branch.schema)} is incompatible with match output ${schemaName(inferredSchema)}",
                        "${draft.path}.match.cases.$tag", branch.source,
                    )
                }
                val outputSchema = inferredSchema
                val match = if (tagged != null) CompiledMatch(draft.value, tagged, cases, outputSchema) else null
                val allDeps = (deps(draft.value) + cases.values.flatMap { it.dependencies }).filter { it in visibleNames }.distinct().sorted()
                val node = if (match != null) CompiledProducer.Match(match, producerId, outputSchema, allDeps, source)
                else CompiledProducer.Expression(draft.value, producerId, outputSchema, allDeps, source)
                DraftCompilation(node, draft.value, null, match, null)
            }
            is ProducerDraft.Map -> {
                val inputSchema = expressionSchema(draft.value, "${draft.path}.map.over")
                val ordering = when (inputSchema) {
                    is ValueSchema.Array -> MapResultOrdering.ARRAY_INDEX
                    is ValueSchema.Object -> MapResultOrdering.OBJECT_KEY
                    else -> {
                        diagnostics += Diagnostic("map input must have a finite array or object schema", "${draft.path}.map.over", source)
                        MapResultOrdering.ARRAY_INDEX
                    }
                }
                val bodyScope = compileScope(
                    definitions = draft.context,
                    scopePath = "${draft.path}.map.context",
                    identityPrefix = "${producerId.value}/map",
                    parameters = parameters,
                    outerSchemas = schemas.filterKeys { it in visibleNames },
                    outerNames = visibleNames,
                    lexicalRoots = lexicalRoots + setOf("item", "key"),
                    lexicalSchemas = lexicalSchemas + mapOf(
                        "item" to when (inputSchema) {
                            is ValueSchema.Array -> inputSchema.items
                            is ValueSchema.Object -> inputSchema.fields.values.map { it.schema }.distinct().singleOrNull() ?: ValueSchema.Any
                            else -> ValueSchema.Any
                        },
                        "key" to when (inputSchema) {
                            is ValueSchema.Array -> ValueSchema.Integer
                            is ValueSchema.Object -> ValueSchema.String
                            else -> ValueSchema.Any
                        },
                    ),
                    forbiddenNames = visibleNames,
                    diagnostics = diagnostics,
                )
                val output = draft.output
                val selected = output?.let { bodyScope.registers.firstOrNull { register -> register.name == it } }
                if (output == null) {
                    // parseMap already emitted the source-located missing-field diagnostic.
                } else if (selected == null) {
                    diagnostics += Diagnostic("map output '$output' is not defined in nested context", "${draft.path}.map.output", source)
                }
                val itemSchema = selected?.schema ?: ValueSchema.Any
                val resultSchema = when (inputSchema) {
                    is ValueSchema.Array -> ValueSchema.Array(itemSchema)
                    is ValueSchema.Object -> ValueSchema.Object(
                        inputSchema.fields.mapValues { (_, field) -> ValueSchema.Object.Field(itemSchema, field.required) },
                        inputSchema.additionalFields,
                    )
                    else -> ValueSchema.Any
                }
                compatible(resultSchema, draft.explicitSchema, "${draft.path}.schema")
                val identityPolicy = when (ordering) {
                    MapResultOrdering.ARRAY_INDEX -> MapItemIdentityPolicy.ARRAY_INDEX
                    MapResultOrdering.OBJECT_KEY -> MapItemIdentityPolicy.OBJECT_KEY
                }
                val map = if (output != null && selected != null) {
                    CompiledMap(draft.value, bodyScope.registers, output, itemSchema, ordering, identityPolicy)
                } else null
                val bodyDependencies = bodyScope.registers.flatMap { it.dependencies }.filter { it in visibleNames }
                val allDeps = (deps(draft.value) + bodyDependencies).filter { it in visibleNames }.distinct().sorted()
                val node = if (map != null) CompiledProducer.Map(map, producerId, resultSchema, allDeps, source)
                else CompiledProducer.Expression(draft.value, producerId, resultSchema, allDeps, source)
                DraftCompilation(node, draft.value, null, null, map)
            }
        }
    }

    private fun draftDependenciesFromExpression(expression: io.workflow.compiler.Expression): Set<String> = dependencies(expression).toSet()

    private fun stableIdSegment(value: String): String = value
        .replace("%", "%25")
        .replace("/", "%2F")

    private fun parseParameters(node: YamlNode?, diagnostics: MutableList<Diagnostic>): Map<String, ValueSchema> {
        val map = node as? YamlMap ?: return if (node == null) emptyMap() else run { diagnostics += Diagnostic("parameters must be a mapping", "$.workflow.parameters", node.location.source()); emptyMap() }
        val result = linkedMapOf<String, ValueSchema>()
        map.entries.forEach { (key, value) ->
            val path = "$.workflow.parameters.${key.content}"
            val entry = value as? YamlMap ?: run { diagnostics += Diagnostic("parameter declaration must be a mapping", path, value.location.source()); return@forEach }
            checkFields(entry, setOf("schema"), path, diagnostics)
            val schema = parseSchema(entry.get("schema"), "$path.schema", diagnostics)
            if (schema != null) result[key.content] = schema
        }
        return result
    }

    private fun parseOutputs(node: YamlNode?, diagnostics: MutableList<Diagnostic>): List<String> {
        val list = node as? YamlList ?: return if (node == null) run { diagnostics += Diagnostic("required list 'outputs' is missing", "$.workflow.outputs", SourceLocation(1, 1)); emptyList() } else run { diagnostics += Diagnostic("outputs must be a list", "$.workflow.outputs", node.location.source()); emptyList() }
        val outputs = list.items.mapIndexedNotNull { i, item ->
            val output = (item as? YamlScalar)?.content
            if (output == null) diagnostics += Diagnostic("output name must be a string", "$.workflow.outputs[$i]", item.location.source())
            else if (!output.matches(Regex("[A-Za-z_][A-Za-z0-9_-]*"))) diagnostics += Diagnostic("output name is not a valid identifier", "$.workflow.outputs[$i]", item.location.source())
            output
        }
        outputs.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.forEach {
            diagnostics += Diagnostic("duplicate output '$it'", "$.workflow.outputs", node.location.source())
        }
        return outputs
    }

    private fun parseProvider(node: YamlNode, path: String, diagnostics: MutableList<Diagnostic>): CompiledProvider? {
        val map = node as? YamlMap ?: run {
            diagnostics += Diagnostic("provider producer must be a mapping", path, node.location.source())
            return null
        }
        checkFields(map, setOf("provider", "version", "config", "with", "schema", "policy", "capabilities"), path, diagnostics)
        val providerId = (map.get("provider") as? YamlScalar)?.content?.takeIf { it.isNotBlank() }
        if (providerId == null) {
            diagnostics += Diagnostic("provider producer form is not supported: provider must be a non-empty string", "$path.provider", location(map, "provider"))
            return null
        }
        val version = (map.get("version") as? YamlScalar)?.let(::scalarValue) as? Value.IntegerValue
        val providerVersion = version?.value?.toIntExactOrNull()
        if (providerVersion == null) {
            diagnostics += Diagnostic("provider producer form is not supported: provider version must be an integer", "$path.version", location(map, "version"))
            return null
        }
        val registration = providerRegistry.resolve(providerId, providerVersion)
        if (registration == null) {
            diagnostics += Diagnostic("unknown provider '${ProviderKey(providerId, providerVersion)}'; provider producer forms are not supported without a registered descriptor", path, node.location.source())
            return null
        }
        val descriptor = registration.descriptor
        if (descriptor.protocolFormatVersion != 1) {
            diagnostics += Diagnostic("provider '${ProviderKey(providerId, providerVersion)}' uses unsupported protocol format version ${descriptor.protocolFormatVersion}", path, node.location.source())
        }

        val configNode = map.get<YamlNode>("config")
        val config = configNode?.let { parseExpression(it, "$path.config", diagnostics) }
            ?: Expression.ObjectValue(emptyMap())
        if (configNode != null) {
            staticConfigReferences(configNode, "$path.config", descriptor, diagnostics)
        }

        val withNode = map.get<YamlNode>("with")
        val input = withNode?.let { parseExpression(it, "$path.with", diagnostics) } ?: Expression.Literal(Value.Null)
        if (!containsReference(input)) {
            val inputSchema = inferExpression(input, emptyMap(), emptyMap(), emptySet(), emptySet(), diagnostics, "$path.with", withNode?.location?.source() ?: node.location.source())
            if (!inputSchema.isCompatibleWith(descriptor.inputSchema)) {
                diagnostics += Diagnostic("bound input schema ${schemaName(inputSchema)} is incompatible with provider input ${schemaName(descriptor.inputSchema)}", "$path.with", withNode?.location?.source() ?: node.location.source())
            }
        }

        val requestedCapabilities: List<String> = when (val capabilitiesNode = map.get<YamlNode>("capabilities")) {
            null -> emptyList()
            is YamlList -> capabilitiesNode.items.mapNotNull { item ->
                (item as? YamlScalar)?.content ?: run {
                    diagnostics += Diagnostic("capability must be a string", "$path.capabilities", item.location.source()); null
                }
            }
            else -> {
                diagnostics += Diagnostic("capabilities must be a list", "$path.capabilities", capabilitiesNode.location.source())
                emptyList()
            }
        }
        requestedCapabilities.filterNot(descriptor.capabilities::contains).forEach { capability ->
            diagnostics += Diagnostic("provider capability '$capability' is not declared by descriptor", "$path.capabilities", location(map, "capabilities"))
        }
        val policy = map.get<YamlNode>("policy")?.let(::rawValue) ?: Value.ObjectValue(emptyMap())
        descriptor.policySchema.validate(policy).errors.forEach { error ->
            diagnostics += Diagnostic("policy ${error.message}", "$path.policy${error.path.removePrefix("$")}", map.get<YamlNode>("policy")?.location?.source() ?: node.location.source())
        }
        return CompiledProvider(providerId, providerVersion, config, input, requestedCapabilities.toSet(), policy)
    }

    private fun containsReference(expression: Expression): Boolean = when (expression) {
        is Expression.Ref -> true
        is Expression.ObjectValue -> expression.fields.values.any(::containsReference)
        is Expression.ArrayValue -> expression.items.any(::containsReference)
        is Expression.Concat -> expression.parts.any(::containsReference)
        is Expression.Equals -> containsReference(expression.left) || containsReference(expression.right)
        is Expression.Present -> containsReference(expression.value)
        is Expression.And -> expression.predicates.any(::containsReference)
        is Expression.Or -> expression.predicates.any(::containsReference)
        is Expression.Not -> containsReference(expression.predicate)
        is Expression.Literal -> false
    }

    private fun staticConfigReferences(node: YamlNode, path: String, descriptor: ProviderDescriptor, diagnostics: MutableList<Diagnostic>) {
        staticConfigReferences(node, path, descriptor, diagnostics, null)
    }

    private fun staticConfigReferences(node: YamlNode, path: String, descriptor: ProviderDescriptor, diagnostics: MutableList<Diagnostic>, configField: String?) {
        if (node is YamlMap) {
            node.entries.forEach { (key, value) ->
                val field = key.content
                if (field.startsWith("$")) {
                    val ref = (value as? YamlScalar)?.content
                    if (field == "\$ref" || field == "\$optional") {
                        val root = ref?.let(::referenceRoot)
                        if (root != null && root != "parameters" && (configField == null || descriptor.isDeploymentStatic(configField))) {
                            diagnostics += Diagnostic("deployment-static configuration cannot reference context '$root'", "$path.$field", value.location.source())
                        }
                    }
                }
                staticConfigReferences(value, "$path.$field", descriptor, diagnostics, configField ?: field.takeUnless { it.startsWith("$") })
            }
        } else if (node is YamlList) node.items.forEachIndexed { index, child -> staticConfigReferences(child, "$path[$index]", descriptor, diagnostics, configField) }
    }

    private fun referenceRoot(text: String): String? {
        val body = text.removePrefix("$")
        return when {
            body.startsWith(".") -> body.substring(1).takeWhile { it.isLetterOrDigit() || it == '_' || it == '-' }.takeIf { it.isNotEmpty() }
            body.startsWith("['") || body.startsWith("[\"") -> body.substring(2).takeWhile { it != '\'' && it != '"' }.takeIf { it.isNotEmpty() }
            else -> null
        }
    }

    private fun parseExpression(node: YamlNode, path: String, diagnostics: MutableList<Diagnostic>): Expression? {
        return when (node) {
            is YamlNull -> Expression.Literal(Value.Null)
            is YamlScalar -> Expression.Literal(scalarValue(node))
            is YamlList -> Expression.ArrayValue(node.items.mapIndexedNotNull { i, item -> parseExpression(item, "$path[$i]", diagnostics) })
            is YamlMap -> {
                val keys = node.entries.keys.map { it.content }
                val operators = keys.filter { it.startsWith("$") }
                if (operators.isNotEmpty()) {
                    if (keys.filterNot { it == "schema" }.size != 1) { diagnostics += Diagnostic("an expression mapping must contain exactly one operator", path, node.location.source()); return null }
                    val op = operators.single()
                    val value: YamlNode = node.get<YamlNode>(op)!!
                    when (op) {
                        "\$ref", "\$optional" -> {
                            val ref = (value as? YamlScalar)?.content
                            if (ref == null) { diagnostics += Diagnostic("$op requires a string JSONPath", path, value.location.source()); null }
                            else parseRef(ref, if (op == "\$ref") Requirement.REQUIRED else Requirement.OPTIONAL, path, value.location.source(), diagnostics)
                        }
                        "\$concat" -> parseExpressionList(value, op, path, diagnostics) { Expression.Concat(it) }
                        "\$eq" -> parseFixedExpressionList(value, op, 2, path, diagnostics) { Expression.Equals(it[0], it[1]) }
                        "\$present" -> parseExpression(value, "$path.$op", diagnostics)?.let(Expression::Present)
                        "\$and" -> parseExpressionList(value, op, path, diagnostics) { Expression.And(it) }
                        "\$or" -> parseExpressionList(value, op, path, diagnostics) { Expression.Or(it) }
                        "\$not" -> parseExpression(value, "$path.$op", diagnostics)?.let(Expression::Not)
                        "\$literal" -> Expression.Literal(rawValue(value))
                        else -> { diagnostics += Diagnostic("unknown expression operator '$op'", path, node.location.source()); null }
                    }
                } else Expression.ObjectValue(node.entries.entries.associate { (key, value) -> key.content to (parseExpression(value, "$path.${key.content}", diagnostics) ?: Expression.Literal(Value.Null)) })
            }
            else -> { diagnostics += Diagnostic("custom YAML tags are not permitted", path, node.location.source()); null }
        }
    }

    private fun parseExpressionList(node: YamlNode, op: String, path: String, diagnostics: MutableList<Diagnostic>, make: (List<Expression>) -> Expression): Expression? {
        val list = node as? YamlList ?: run { diagnostics += Diagnostic("$op requires a list", path, node.location.source()); return null }
        return make(list.items.mapIndexedNotNull { i, item -> parseExpression(item, "$path[$i]", diagnostics) })
    }

    private fun parseFixedExpressionList(node: YamlNode, op: String, count: Int, path: String, diagnostics: MutableList<Diagnostic>, make: (List<Expression>) -> Expression): Expression? {
        val list = node as? YamlList ?: run { diagnostics += Diagnostic("$op requires a list of $count expressions", path, node.location.source()); return null }
        if (list.items.size != count) { diagnostics += Diagnostic("$op requires exactly $count expressions", path, node.location.source()); return null }
        val expressions = list.items.mapIndexedNotNull { i, item -> parseExpression(item, "$path[$i]", diagnostics) }
        return if (expressions.size == count) make(expressions) else null
    }

    private fun parseRef(text: String, requirement: Requirement, path: String, source: SourceLocation, diagnostics: MutableList<Diagnostic>): Expression? {
        if (!text.startsWith("$")) { diagnostics += Diagnostic("reference must use a singular JSONPath rooted at '$'", path, source); return null }
        val body = text.substring(1)
        if (body.isEmpty() || body.contains("..") || body.contains("*") || body.contains("?") || body.contains(":") || body.contains("{")) {
            diagnostics += Diagnostic("reference path must be singular (wildcards, filters, slices, and recursive descent are not supported)", path, source); return null
        }
        val parts = mutableListOf<PathStep>()
        var i = 0
        fun readField(): String? {
            val start = i
            while (i < body.length && (body[i].isLetterOrDigit() || body[i] == '_' || body[i] == '-')) i++
            return body.substring(start, i).takeIf { it.isNotEmpty() }
        }
        val root: String
        if (body.startsWith(".")) {
            i = 1
            root = readField() ?: run { diagnostics += Diagnostic("invalid singular reference path '$text'", path, source); return null }
        } else if (body.startsWith("[")) {
            val end = body.indexOf(']')
            val token = if (end > 0) body.substring(1, end) else ""
            if (token.length < 2 || (token.first() != '\'' && token.first() != '"') || token.last() != token.first()) {
                diagnostics += Diagnostic("reference root must be a field selector", path, source); return null
            }
            root = token.substring(1, token.length - 1)
            i = end + 1
        } else {
            diagnostics += Diagnostic("invalid singular reference path '$text'", path, source); return null
        }
        while (i < body.length) {
            when (body[i]) {
                '.' -> { i++; val field = readField() ?: run { diagnostics += Diagnostic("invalid field selector in reference '$text'", path, source); return null }; parts += PathStep.Field(field) }
                '[' -> {
                    val end = body.indexOf(']', i)
                    if (end < 0) { diagnostics += Diagnostic("unterminated path selector in '$text'", path, source); return null }
                    val token = body.substring(i + 1, end)
                    when {
                        token.matches(Regex("[0-9]+")) -> {
                            val index = token.toIntOrNull()
                            if (index == null) { diagnostics += Diagnostic("path index is too large", path, source); return null }
                            parts += PathStep.Index(index)
                        }
                        token.length >= 2 && token.first() == '\'' && token.last() == '\'' -> parts += PathStep.Field(token.substring(1, token.length - 1))
                        token.length >= 2 && token.first() == '"' && token.last() == '"' -> parts += PathStep.Field(token.substring(1, token.length - 1))
                        else -> { diagnostics += Diagnostic("non-singular or invalid path selector '$token'", path, source); return null }
                    }
                    i = end + 1
                }
                else -> { diagnostics += Diagnostic("invalid reference path '$text'", path, source); return null }
            }
        }
        return Expression.Ref(root, parts, requirement)
    }

    private fun parseSchema(node: YamlNode?, path: String, diagnostics: MutableList<Diagnostic>): ValueSchema? {
        if (node == null) { diagnostics += Diagnostic("schema is required", path, SourceLocation(1, 1)); return null }
        if (node is YamlNull) return ValueSchema.Null
        if (node is YamlScalar) return when (node.content) {
            "any" -> ValueSchema.Any; "null" -> ValueSchema.Null; "boolean" -> ValueSchema.Boolean; "string" -> ValueSchema.String; "integer" -> ValueSchema.Integer; "decimal" -> ValueSchema.Decimal
            else -> { diagnostics += Diagnostic("unknown schema '${node.content}'", path, node.location.source()); null }
        }
        val map = node as? YamlMap ?: run { diagnostics += Diagnostic("schema must be a scalar or mapping", path, node.location.source()); return null }
        val type = (map.get("type") as? YamlScalar)?.content ?: run { diagnostics += Diagnostic("composite schema requires a type", path, node.location.source()); return null }
        return when (type) {
            "array" -> { checkFields(map, setOf("type", "items"), path, diagnostics); parseSchema(map.get("items"), "$path.items", diagnostics)?.let(ValueSchema::Array) }
            "object" -> {
                checkFields(map, setOf("type", "fields", "additional-fields"), path, diagnostics)
                val fieldsNode = map.get("fields") as? YamlMap ?: run { diagnostics += Diagnostic("object schema requires fields", "$path.fields", location(map, "fields")); return null }
                val fields = fieldsNode.entries.mapNotNull { (key, value) ->
                    val fieldMap = value as? YamlMap ?: run { diagnostics += Diagnostic("object field schema must be a mapping", "$path.fields.${key.content}", value.location.source()); return@mapNotNull null }
                    checkFields(fieldMap, setOf("schema", "required"), "$path.fields.${key.content}", diagnostics)
                    val fieldSchema = parseSchema(fieldMap.get("schema"), "$path.fields.${key.content}.schema", diagnostics) ?: return@mapNotNull null
                    val required = parseBoolean(fieldMap.get("required"), "$path.fields.${key.content}.required", true, diagnostics)
                    key.content to ValueSchema.Object.Field(fieldSchema, required)
                }.toMap()
                val additional = parseBoolean(map.get("additional-fields"), "$path.additional-fields", false, diagnostics)
                ValueSchema.Object(fields, additional)
            }
            "tagged-union" -> parseTaggedUnionSchema(map, path, diagnostics)
            else -> { diagnostics += Diagnostic("unknown schema type '$type'", path, node.location.source()); null }
        }
    }

    private fun parseEmbeddedSchema(node: YamlNode, path: String, diagnostics: MutableList<Diagnostic>): ValueSchema? {
        val map = node as? YamlMap ?: return null
        if (map.entries.keys.none { it.content.startsWith("$") || it.content in setOf("provider", "match", "map") }) return null
        val schemaNode: YamlNode? = map.get<YamlNode>("schema")
        return schemaNode?.let { parseSchema(it, path, diagnostics) }
    }

    private fun inferExpression(expression: Expression, parameters: Map<String, ValueSchema>, inferred: Map<String, ValueSchema>, registers: Set<String>, lexical: Set<String>, diagnostics: MutableList<Diagnostic>, path: String, source: SourceLocation, lexicalSchemas: Map<String, ValueSchema> = emptyMap()): ValueSchema = when (expression) {
        is Expression.Literal -> valueSchema(expression.value)
        is Expression.Ref -> {
            val rootSchema = when {
                expression.root == "parameters" -> ValueSchema.Object(parameters.mapValues { ValueSchema.Object.Field(it.value) })
                expression.root in registers -> inferred[expression.root] ?: ValueSchema.Any
                expression.root in lexical -> lexicalSchemas[expression.root] ?: ValueSchema.Any
                else -> { diagnostics += Diagnostic("unknown reference root '${expression.root}'", path, source); ValueSchema.Any }
            }
            pathSchema(rootSchema, expression.path, path, diagnostics, source)
        }
        is Expression.ObjectValue -> ValueSchema.Object(expression.fields.mapValues { ValueSchema.Object.Field(inferExpression(it.value, parameters, inferred, registers, lexical, diagnostics, path, source, lexicalSchemas)) })
        is Expression.ArrayValue -> {
            val schemas = expression.items.map { inferExpression(it, parameters, inferred, registers, lexical, diagnostics, path, source, lexicalSchemas) }
            ValueSchema.Array(schemas.distinct().singleOrNull() ?: ValueSchema.Any)
        }
        is Expression.Concat -> {
            expression.parts.forEach { part ->
                val partSchema = inferExpression(part, parameters, inferred, registers, lexical, diagnostics, path, source, lexicalSchemas)
                if (partSchema != ValueSchema.String) diagnostics += Diagnostic("\$concat operands must have string schemas", path, source)
            }
            ValueSchema.String
        }
        is Expression.Equals -> {
            inferExpression(expression.left, parameters, inferred, registers, lexical, diagnostics, path, source, lexicalSchemas)
            inferExpression(expression.right, parameters, inferred, registers, lexical, diagnostics, path, source, lexicalSchemas)
            ValueSchema.Boolean
        }
        is Expression.Present -> {
            inferExpression(expression.value, parameters, inferred, registers, lexical, diagnostics, path, source, lexicalSchemas)
            if (expression.value !is Expression.Ref || expression.value.requirement != Requirement.OPTIONAL) {
                diagnostics += Diagnostic("\$present requires an optional reference", path, source)
            }
            ValueSchema.Boolean
        }
        is Expression.And -> { expression.predicates.forEach { requireBoolean(it, parameters, inferred, registers, lexical, diagnostics, path, source, "\$and", lexicalSchemas) }; ValueSchema.Boolean }
        is Expression.Or -> { expression.predicates.forEach { requireBoolean(it, parameters, inferred, registers, lexical, diagnostics, path, source, "\$or", lexicalSchemas) }; ValueSchema.Boolean }
        is Expression.Not -> { requireBoolean(expression.predicate, parameters, inferred, registers, lexical, diagnostics, path, source, "\$not", lexicalSchemas); ValueSchema.Boolean }
    }

    private fun pathSchema(start: ValueSchema, pathSteps: List<PathStep>, path: String, diagnostics: MutableList<Diagnostic>, source: SourceLocation): ValueSchema {
        var current = start
        pathSteps.forEach { step ->
            current = when (step) {
                is PathStep.Field -> when (current) { is ValueSchema.Object -> current.fields[step.name]?.schema ?: run { diagnostics += Diagnostic("path field '${step.name}' is not present in the schema", path, source); ValueSchema.Any }; ValueSchema.Any -> ValueSchema.Any; else -> { diagnostics += Diagnostic("field selector cannot be applied to ${schemaName(current)}", path, source); ValueSchema.Any } }
                is PathStep.Index -> when (current) { is ValueSchema.Array -> current.items; ValueSchema.Any -> ValueSchema.Any; else -> { diagnostics += Diagnostic("index selector cannot be applied to ${schemaName(current)}", path, source); ValueSchema.Any } }
            }
        }
        return current
    }

    private fun dependencies(expression: Expression): List<String> = when (expression) {
        is Expression.Ref -> if (expression.root != "parameters" && expression.root != "item" && expression.root != "key" && expression.root != "match") listOf(expression.root) else emptyList()
        is Expression.ObjectValue -> expression.fields.values.flatMap(::dependencies)
        is Expression.ArrayValue -> expression.items.flatMap(::dependencies)
        is Expression.Concat -> expression.parts.flatMap(::dependencies)
        is Expression.Equals -> dependencies(expression.left) + dependencies(expression.right)
        is Expression.Present -> dependencies(expression.value)
        is Expression.And -> expression.predicates.flatMap(::dependencies)
        is Expression.Or -> expression.predicates.flatMap(::dependencies)
        is Expression.Not -> dependencies(expression.predicate)
        is Expression.Literal -> emptyList()
    }

    private fun detectCycles(deps: Map<String, List<String>>, diagnostics: MutableList<Diagnostic>, definitions: Map<String, Pair<YamlNode, Expression>>) {
        val visiting = mutableSetOf<String>(); val visited = mutableSetOf<String>()
        fun visit(name: String, stack: List<String>) {
            if (name in visiting) { diagnostics += Diagnostic("dependency cycle: ${(stack + name).joinToString(" -> ")}", "$.workflow.context.$name", definitions[name]?.first?.location?.source() ?: SourceLocation(1, 1)); return }
            if (!visited.add(name)) return
            visiting += name
            deps[name].orEmpty().forEach { visit(it, stack + name) }
            visiting -= name
        }
        deps.keys.forEach { visit(it, emptyList()) }
    }

    private fun checkFields(map: YamlMap, allowed: Set<String>, path: String, diagnostics: MutableList<Diagnostic>) {
        map.entries.forEach { (key, _) -> if (key.content !in allowed) diagnostics += Diagnostic("unknown field '${key.content}'", "$path.${key.content}", key.location.source()) }
    }
    private fun validateYamlTree(node: YamlNode, depth: Int, diagnostics: MutableList<Diagnostic>) {
        if (depth > maxNesting) {
            diagnostics += Diagnostic("YAML nesting exceeds the limit", "$", node.location.source())
            return
        }
        when (node) {
            is YamlTaggedNode -> diagnostics += Diagnostic("custom YAML tags are not permitted", "$", node.location.source())
            is YamlScalar -> if (!isExplicitString(node) && node.content.lowercase() in NON_FINITE_NUMBERS) {
                diagnostics += Diagnostic("non-finite decimals are not supported", "$", node.location.source())
            }
            is YamlList -> node.items.forEach { validateYamlTree(it, depth + 1, diagnostics) }
            is YamlMap -> node.entries.forEach { (key, value) ->
                validateYamlTree(key, depth + 1, diagnostics)
                validateYamlTree(value, depth + 1, diagnostics)
            }
            else -> Unit
        }
    }

    private fun preflightNestingLocation(yamlText: String): SourceLocation? {
        val indentationLevels = mutableListOf<Int>()
        var flowDepth = 0
        var blockScalarParentIndent: Int? = null
        yamlText.lineSequence().forEachIndexed { lineIndex, rawLine ->
            if (rawLine.isBlank() || rawLine.trimStart().startsWith("#")) return@forEachIndexed
            val indentation = rawLine.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
            blockScalarParentIndent?.let { parentIndent ->
                if (indentation > parentIndent) return@forEachIndexed
                blockScalarParentIndent = null
            }
            while (indentationLevels.isNotEmpty() && indentation <= indentationLevels.last()) {
                indentationLevels.removeAt(indentationLevels.lastIndex)
            }
            indentationLevels += indentation

            val trimmed = rawLine.substring(indentation)
            var sequenceDepth = 0
            var sequenceOffset = 0
            while (trimmed.startsWith("- ", sequenceOffset)) {
                sequenceDepth++
                sequenceOffset += 2
            }
            if (indentationLevels.size + sequenceDepth + flowDepth > maxNesting) {
                return SourceLocation(lineIndex + 1, indentation + 1)
            }

            var quote: Char? = null
            var escaped = false
            for (column in trimmed.indices) {
                val character = trimmed[column]
                if (quote == '"' && escaped) {
                    escaped = false
                    continue
                }
                if (quote == '"' && character == '\\') {
                    escaped = true
                    continue
                }
                if (quote != null) {
                    if (character == quote) quote = null
                    continue
                }
                if (character == '#' && (column == 0 || trimmed[column - 1].isWhitespace())) break
                if (character == '\'' || character == '"') quote = character
                else if (character == '[' || character == '{') {
                    flowDepth++
                    if (indentationLevels.size + sequenceDepth + flowDepth > maxNesting) {
                        return SourceLocation(lineIndex + 1, indentation + column + 1)
                    }
                }
                else if (character == ']' || character == '}') flowDepth = (flowDepth - 1).coerceAtLeast(0)
            }
            val contentWithoutComment = trimmed.substringBefore(" #").trimEnd()
            if (Regex("[|>][+-]?$" ).containsMatchIn(contentWithoutComment)) {
                blockScalarParentIndent = indentation
            }
        }
        return null
    }

    private fun parseBoolean(node: YamlNode?, path: String, default: Boolean, diagnostics: MutableList<Diagnostic>): Boolean {
        if (node == null) return default
        val value = (node as? YamlScalar)?.let(::scalarValue) as? Value.BooleanValue
        if (value == null) diagnostics += Diagnostic("value must be a Boolean", path, node.location.source())
        return value?.value ?: default
    }

    private fun parseTaggedUnionSchema(map: YamlMap, path: String, diagnostics: MutableList<Diagnostic>): ValueSchema? {
        checkFields(map, setOf("type", "discriminator", "variants"), path, diagnostics)
        val discriminator = (map.get("discriminator") as? YamlScalar)?.content ?: run {
            diagnostics += Diagnostic("tagged-union schema requires a discriminator", "$path.discriminator", location(map, "discriminator"))
            return null
        }
        val variantsNode = map.get("variants") as? YamlMap ?: run {
            diagnostics += Diagnostic("tagged-union schema requires variants", "$path.variants", location(map, "variants"))
            return null
        }
        val variants = variantsNode.entries.mapNotNull { (key, value) ->
            val variant = parseSchema(value, "$path.variants.${key.content}", diagnostics)
            if (variant !is ValueSchema.Object) {
                diagnostics += Diagnostic("tagged-union variants must be object schemas", "$path.variants.${key.content}", value.location.source())
                null
            } else {
                val discriminatorField = variant.fields[discriminator]
                if (discriminatorField?.schema != ValueSchema.String || !discriminatorField.required) {
                    diagnostics += Diagnostic(
                        "tagged-union variant must require string discriminator '$discriminator'",
                        "$path.variants.${key.content}.fields.$discriminator",
                        value.location.source(),
                    )
                }
                key.content to variant
            }
        }.toMap()
        if (variants.isEmpty()) diagnostics += Diagnostic("tagged-union schema requires at least one variant", "$path.variants", variantsNode.location.source())
        return ValueSchema.TaggedUnion(discriminator, variants)
    }

    private fun requireBoolean(expression: Expression, parameters: Map<String, ValueSchema>, inferred: Map<String, ValueSchema>, registers: Set<String>, lexical: Set<String>, diagnostics: MutableList<Diagnostic>, path: String, source: SourceLocation, operator: String, lexicalSchemas: Map<String, ValueSchema> = emptyMap()) {
        val schema = inferExpression(expression, parameters, inferred, registers, lexical, diagnostics, path, source, lexicalSchemas)
        if (schema != ValueSchema.Boolean) diagnostics += Diagnostic("$operator operands must have boolean schemas", path, source)
    }
    private fun stringScalar(map: YamlMap, key: String, path: String, diagnostics: MutableList<Diagnostic>): String? {
        val node = map.get(key) as? YamlScalar
        val value = node?.let(::scalarValue) as? Value.StringValue
        if (value == null) diagnostics += Diagnostic("$key must be a string", path, location(map, key))
        return value?.value
    }
    private fun integerScalar(map: YamlMap, key: String, path: String, diagnostics: MutableList<Diagnostic>): Int? {
        val node = map.get(key) as? YamlScalar
        val integer = node?.let(::scalarValue) as? Value.IntegerValue
        val value = integer?.value?.toIntExactOrNull()
        if (value == null) diagnostics += Diagnostic("$key must be an integer", path, location(map, key))
        return value
    }
    private fun location(map: YamlMap, key: String): SourceLocation = (map.entries.entries.firstOrNull { it.key.content == key }?.value ?: map).location.source()
    private fun valueSchema(value: Value): ValueSchema = when (value) {
        Value.Null -> ValueSchema.Null
        is Value.BooleanValue -> ValueSchema.Boolean
        is Value.StringValue -> ValueSchema.String
        is Value.IntegerValue -> ValueSchema.Integer
        is Value.DecimalValue -> ValueSchema.Decimal
        is Value.ArrayValue -> ValueSchema.Array(value.values.map(::valueSchema).distinct().singleOrNull() ?: ValueSchema.Any)
        is Value.ObjectValue -> ValueSchema.Object(value.fields.mapValues { ValueSchema.Object.Field(valueSchema(it.value)) })
        is Value.TaggedValue -> ValueSchema.Any
    }
    private fun scalarValue(node: YamlScalar): Value {
        val text = node.content
        if (isExplicitString(node)) return Value.StringValue(text)
        return when {
            text in setOf("null", "Null", "NULL", "~") -> Value.Null
            text in setOf("true", "True", "TRUE") -> Value.BooleanValue(true)
            text in setOf("false", "False", "FALSE") -> Value.BooleanValue(false)
            text.matches(DECIMAL_INTEGER) -> Value.IntegerValue(BigInteger(text))
            text.matches(OCTAL_INTEGER) -> Value.IntegerValue(BigInteger(text.removePrefix("+").removePrefix("0o").let { if (it.startsWith("-0o")) "-${it.removePrefix("-0o")}" else it }, 8))
            text.matches(HEXADECIMAL_INTEGER) -> Value.IntegerValue(BigInteger(text.removePrefix("+").removePrefix("0x").let { if (it.startsWith("-0x")) "-${it.removePrefix("-0x")}" else it }, 16))
            text.matches(DECIMAL_NUMBER) -> Value.DecimalValue(BigDecimal(text))
            else -> Value.StringValue(text)
        }
    }
    private fun isExplicitString(node: YamlScalar): Boolean {
        val line = activeYamlLines.getOrNull(node.location.line - 1).orEmpty()
        val at = (node.location.column - 1).coerceIn(0, line.length)
        return line.getOrNull(at) in setOf('\'', '"', '|', '>')
    }
    private fun rawValue(node: YamlNode): Value = when (node) { is YamlNull -> Value.Null; is YamlScalar -> scalarValue(node); is YamlList -> Value.ArrayValue(node.items.map { rawValue(it) }); is YamlMap -> Value.ObjectValue(node.entries.map { it.key.content to rawValue(it.value) }.toMap()); else -> Value.StringValue(node.contentToString()) }
    private fun schemaName(schema: ValueSchema): String = when (schema) { ValueSchema.Any -> "any"; ValueSchema.Null -> "null"; ValueSchema.Boolean -> "boolean"; ValueSchema.String -> "string"; ValueSchema.Integer -> "integer"; ValueSchema.Decimal -> "decimal"; is ValueSchema.Array -> "array"; is ValueSchema.Object -> "object"; is ValueSchema.TaggedUnion -> "tagged-union" }

    private companion object {
        val RESERVED_ROOTS = setOf("parameters", "item", "key", "match")
        val NON_FINITE_NUMBERS = setOf(".inf", "+.inf", "-.inf", ".nan")
        val DECIMAL_INTEGER = Regex("[-+]?[0-9]+")
        val OCTAL_INTEGER = Regex("[-+]?0o[0-7]+")
        val HEXADECIMAL_INTEGER = Regex("[-+]?0x[0-9a-fA-F]+")
        val DECIMAL_NUMBER = Regex("[-+]?(?:(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][-+]?[0-9]+)?)")
    }
}

private fun BigInteger.toIntExactOrNull(): Int? = try {
    intValueExact()
} catch (_: ArithmeticException) {
    null
}

private fun com.charleskorn.kaml.Location.source() = SourceLocation(line, column)

private fun sha256(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(UTF_8)).joinToString("") { "%02x".format(it) }

private object CanonicalIrJson {
    fun document(doc: WorkflowIrDocument, includeHash: Boolean, includeSource: Boolean): String {
        val fields = linkedMapOf<String, JsonElement>(
            "irVersion" to JsonPrimitive(doc.irVersion),
            "workflowId" to JsonPrimitive(doc.workflowId),
            "version" to JsonPrimitive(doc.version),
            "workflowVersionId" to JsonPrimitive(doc.workflowVersionId.value),
            "parameters" to JsonObject(doc.parameters.toSortedMap().mapValues { schema(it.value) }),
            "registers" to JsonArray(doc.registers.sortedBy { it.name }.map { register(it, includeSource) }),
            "outputs" to JsonArray(doc.outputs.map(::JsonPrimitive)),
        )
        if (includeHash) fields["contentHash"] = JsonPrimitive(doc.contentHash)
        return JsonObject(fields.toSortedMap()).toString()
    }
    private fun register(register: CompiledRegister, includeSource: Boolean): JsonElement {
        val fields = linkedMapOf<String, JsonElement>(
            "name" to JsonPrimitive(register.name),
            "registerId" to JsonPrimitive(register.registerId.value),
            "producerId" to JsonPrimitive(register.producerId.value),
            "dependencies" to JsonArray(register.dependencies.sorted().map(::JsonPrimitive)),
            "schema" to schema(register.schema),
            "producer" to expression(register.producer),
        )
        register.provider?.let { provider ->
            fields["provider"] = JsonObject(linkedMapOf(
                "providerId" to JsonPrimitive(provider.providerId),
                "version" to JsonPrimitive(provider.version),
                "config" to expression(provider.config),
                "input" to expression(provider.input),
                "capabilities" to JsonArray(provider.capabilities.sorted().map(::JsonPrimitive)),
                "policy" to jsonValue(provider.policy),
            ))
        }
        register.match?.let { fields["match"] = match(it) }
        register.map?.let { fields["map"] = map(it) }
        if (includeSource) fields["source"] = JsonObject(mapOf("line" to JsonPrimitive(register.source.line), "column" to JsonPrimitive(register.source.column)))
        return JsonObject(fields.toSortedMap())
    }

    private fun compiledProducer(producer: CompiledProducer): JsonElement {
        val fields = linkedMapOf<String, JsonElement>(
            "producerId" to JsonPrimitive(producer.producerId.value),
            "schema" to schema(producer.schema),
            "dependencies" to JsonArray(producer.dependencies.sorted().map(::JsonPrimitive)),
        )
        when (producer) {
            is CompiledProducer.Expression -> { fields["kind"] = JsonPrimitive("expression"); fields["expression"] = expression(producer.value) }
            is CompiledProducer.Provider -> { fields["kind"] = JsonPrimitive("provider"); fields["provider"] = provider(producer.value) }
            is CompiledProducer.Match -> { fields["kind"] = JsonPrimitive("match"); fields["match"] = match(producer.value) }
            is CompiledProducer.Map -> { fields["kind"] = JsonPrimitive("map"); fields["map"] = map(producer.value) }
        }
        return JsonObject(fields.toSortedMap())
    }

    private fun provider(provider: CompiledProvider): JsonElement = JsonObject(linkedMapOf(
        "providerId" to JsonPrimitive(provider.providerId),
        "version" to JsonPrimitive(provider.version),
        "config" to expression(provider.config),
        "input" to expression(provider.input),
        "capabilities" to JsonArray(provider.capabilities.sorted().map(::JsonPrimitive)),
        "policy" to jsonValue(provider.policy),
    ).toSortedMap())

    private fun match(match: CompiledMatch): JsonElement = JsonObject(linkedMapOf(
        "kind" to JsonPrimitive("match"),
        "discriminator" to expression(match.discriminator),
        "discriminatorSchema" to schema(match.discriminatorSchema),
        "cases" to JsonObject(match.cases.toSortedMap().mapValues { compiledProducer(it.value) }),
        "outputSchema" to schema(match.outputSchema),
    ).toSortedMap())

    private fun map(map: CompiledMap): JsonElement = JsonObject(linkedMapOf(
        "kind" to JsonPrimitive("map"),
        "input" to expression(map.input),
        "context" to JsonArray(map.context.sortedBy { it.name }.map { register(it, includeSource = false) }),
        "output" to JsonPrimitive(map.output),
        "outputSchema" to schema(map.outputSchema),
        "ordering" to JsonPrimitive(map.ordering.name.lowercase()),
        "itemIdentityPolicy" to JsonPrimitive(map.itemIdentityPolicy.name.lowercase()),
    ).toSortedMap())
    private fun expression(expression: Expression): JsonElement = when (expression) {
        is Expression.Literal -> JsonObject(mapOf("kind" to JsonPrimitive("literal"), "value" to jsonValue(expression.value)))
        is Expression.Ref -> JsonObject(mapOf(
            "kind" to JsonPrimitive("ref"),
            "root" to JsonPrimitive(expression.root),
            "path" to JsonArray(expression.path.map(::pathStep)),
            "requirement" to JsonPrimitive(expression.requirement.name.lowercase()),
        ))
        is Expression.ObjectValue -> JsonObject(mapOf("kind" to JsonPrimitive("object"), "fields" to JsonObject(expression.fields.toSortedMap().mapValues { expression(it.value) })))
        is Expression.ArrayValue -> JsonObject(mapOf("kind" to JsonPrimitive("array"), "items" to JsonArray(expression.items.map(::expression))))
        is Expression.Concat -> JsonObject(mapOf("kind" to JsonPrimitive("concat"), "parts" to JsonArray(expression.parts.map(::expression))))
        is Expression.Equals -> JsonObject(mapOf("kind" to JsonPrimitive("equals"), "left" to expression(expression.left), "right" to expression(expression.right)))
        is Expression.Present -> JsonObject(mapOf("kind" to JsonPrimitive("present"), "value" to expression(expression.value)))
        is Expression.And -> JsonObject(mapOf("kind" to JsonPrimitive("and"), "predicates" to JsonArray(expression.predicates.map(::expression))))
        is Expression.Or -> JsonObject(mapOf("kind" to JsonPrimitive("or"), "predicates" to JsonArray(expression.predicates.map(::expression))))
        is Expression.Not -> JsonObject(mapOf("kind" to JsonPrimitive("not"), "predicate" to expression(expression.predicate)))
    }
    private fun schema(schema: ValueSchema): JsonElement = when (schema) {
        ValueSchema.Any -> JsonPrimitive("any"); ValueSchema.Null -> JsonPrimitive("null"); ValueSchema.Boolean -> JsonPrimitive("boolean"); ValueSchema.String -> JsonPrimitive("string"); ValueSchema.Integer -> JsonPrimitive("integer"); ValueSchema.Decimal -> JsonPrimitive("decimal")
        is ValueSchema.Array -> JsonObject(mapOf("type" to JsonPrimitive("array"), "items" to schema(schema.items)))
        is ValueSchema.Object -> JsonObject(mapOf("type" to JsonPrimitive("object"), "fields" to JsonObject(schema.fields.toSortedMap().mapValues { JsonObject(mapOf("schema" to schema(it.value.schema), "required" to JsonPrimitive(it.value.required))) }), "additional-fields" to JsonPrimitive(schema.additionalFields)))
        is ValueSchema.TaggedUnion -> JsonObject(mapOf(
            "type" to JsonPrimitive("tagged-union"),
            "discriminator" to JsonPrimitive(schema.discriminator),
            "variants" to JsonObject(schema.variants.toSortedMap().mapValues { schema(it.value) }),
        ))
    }
    private fun pathStep(step: PathStep): JsonElement = when (step) {
        is PathStep.Field -> JsonObject(mapOf("kind" to JsonPrimitive("field"), "name" to JsonPrimitive(step.name)))
        is PathStep.Index -> JsonObject(mapOf("kind" to JsonPrimitive("index"), "index" to JsonPrimitive(step.index)))
    }
    private fun jsonValue(value: Value): JsonElement = Json.parseToJsonElement(CanonicalValueJson.encode(value))
}
