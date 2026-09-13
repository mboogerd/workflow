package io.workflow.compiler

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlList
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlNode
import com.charleskorn.kaml.YamlNull
import com.charleskorn.kaml.YamlScalar
import io.workflow.core.CanonicalValueJson
import io.workflow.core.Value
import io.workflow.core.ValueSchema
import io.workflow.core.isCompatibleWith
import java.math.BigDecimal
import java.math.BigInteger
import java.security.MessageDigest
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
    val producer: Expression,
    val dependencies: List<String>,
    val schema: ValueSchema,
    val source: SourceLocation,
)

data class WorkflowIrDocument(
    val workflowId: String,
    val version: Int,
    val parameters: Map<String, ValueSchema>,
    val registers: List<CompiledRegister>,
    val outputs: List<String>,
    val contentHash: String,
    val irVersion: Int = 1,
) {
    fun canonicalJson(): String = CanonicalIrJson.document(this, includeHash = true, includeSource = true)
}

private data class NodeInfo(val node: YamlNode, val path: String) {
    val location: SourceLocation
        get() = SourceLocation(node.location.line, node.location.column)
}

class WorkflowCompiler(
    private val maxDocumentCodePoints: Int = 1_000_000,
    private val maxNesting: Int = 64,
    private val maxAliases: Int = 32,
) {
    private var activeYamlText: String = ""

    fun compile(yamlText: String): CompilationResult {
        activeYamlText = yamlText
        val diagnostics = mutableListOf<Diagnostic>()
        if (yamlText.length > maxDocumentCodePoints) {
            return CompilationResult(null, listOf(Diagnostic("YAML document exceeds the size limit", "$", SourceLocation(1, 1))))
        }
        if (yamlText.count { it == '&' } > maxAliases || yamlText.count { it == '*' } > maxAliases) {
            diagnostics += Diagnostic("too many YAML anchors or aliases", "$", SourceLocation(1, 1))
        }
        if (yamlText.lineSequence().any { it.trimStart().startsWith("!") || it.contains(" !!") }) {
            diagnostics += Diagnostic("custom YAML tags are not permitted", "$", SourceLocation(1, 1))
        }
        if (yamlText.contains("\n---") || yamlText.trimStart().startsWith("---\n")) {
            val docs = yamlText.lineSequence().count { it.trim() == "---" }
            if (docs > 1) diagnostics += Diagnostic("multiple YAML documents are not permitted", "$", SourceLocation(1, 1))
        }
        if (yamlText.count { it == '[' } > maxNesting || yamlText.count { it == '{' } > maxNesting) {
            diagnostics += Diagnostic("YAML nesting exceeds the limit", "$", SourceLocation(1, 1))
        }
        duplicateMappingKeys(yamlText).forEach { (key, line) -> diagnostics += Diagnostic("duplicate mapping key '$key'", "\$", SourceLocation(line, 1)) }
        val root = try { Yaml.default.parseToYamlNode(yamlText) } catch (e: Exception) {
            diagnostics += Diagnostic("invalid YAML: ${e.message ?: "parse error"}", "$", SourceLocation(1, 1))
            return CompilationResult(null, diagnostics)
        }
        val rootMap = root as? YamlMap ?: run {
            diagnostics += Diagnostic("document must be a mapping", "$", root.location.source())
            return CompilationResult(null, diagnostics)
        }
        val workflow = rootMap.get("workflow") as? YamlMap ?: run {
            diagnostics += Diagnostic("required mapping 'workflow' is missing", "$.workflow", rootMap.location.source())
            return CompilationResult(null, diagnostics)
        }
        checkFields(workflow, setOf("id", "version", "parameters", "context", "outputs"), "$.workflow", diagnostics)
        val id = scalar(workflow, "id", "$.workflow.id", diagnostics)?.takeIf { it.isNotBlank() }
        val version = scalar(workflow, "version", "$.workflow.version", diagnostics)?.toIntOrNull()
        if (version == null) diagnostics += Diagnostic("version must be an integer", "$.workflow.version", location(workflow, "version"))
        val parameters = parseParameters(workflow.get("parameters"), diagnostics)
        val context = workflow.get("context") as? YamlMap ?: run {
            diagnostics += Diagnostic("required mapping 'context' is missing", "$.workflow.context", location(workflow, "context"))
            null
        }
        val outputs = parseOutputs(workflow.get("outputs"), diagnostics)
        if (context == null || id == null || version == null) return CompilationResult(null, diagnostics)

        val definitions = linkedMapOf<String, Pair<YamlNode, Expression>>()
        context.entries.forEach { (key, node) ->
            val name = key.content
            val path = "$.workflow.context.$name"
            if (!name.matches(Regex("[A-Za-z_][A-Za-z0-9_-]*"))) {
                diagnostics += Diagnostic("register name is not a valid identifier", path, key.location.source())
            }
            if (name in definitions) diagnostics += Diagnostic("duplicate register definition '$name'", path, key.location.source())
            val producerFields = (node as? YamlMap)?.entries?.keys?.map { it.content }.orEmpty()
            producerFields.firstOrNull { it == "provider" || it == "match" || it == "map" }?.let {
                diagnostics += Diagnostic("producer form '$it' is not supported by the expression-only compiler", "$path.$it", node.location.source())
            }
            val expr = parseExpression(node, path, diagnostics)
            if (expr != null && name !in definitions) definitions[name] = node to expr
        }
        val schemaExplicit = mutableMapOf<String, ValueSchema>()
        val registers = mutableListOf<CompiledRegister>()
        val names = definitions.keys
        val depsByName = definitions.mapValues { (_, pair) -> dependencies(pair.second).filter { it in names }.distinct().sorted() }
        detectCycles(depsByName, diagnostics, definitions)
        val inferred = mutableMapOf<String, ValueSchema>()
        val inferring = mutableSetOf<String>()
        fun inferRegister(name: String): ValueSchema {
            inferred[name]?.let { return it }
            if (!inferring.add(name)) return ValueSchema.Any
            val (node, expression) = definitions.getValue(name)
            depsByName[name].orEmpty().forEach { inferRegister(it) }
            val schema = inferExpression(expression, parameters, inferred, definitions.keys, emptySet(), diagnostics, "$.workflow.context.$name", node.location.source())
            inferred[name] = schema
            inferring -= name
            return schema
        }
        definitions.forEach { (name, pair) ->
            val explicit = parseEmbeddedSchema(pair.first, "$.workflow.context.$name.schema", diagnostics)
            if (explicit != null) schemaExplicit[name] = explicit
            val inferredSchema = inferRegister(name)
            val schema = explicit ?: inferredSchema
            if (explicit != null && !inferredSchema.isCompatibleWith(explicit)) {
                diagnostics += Diagnostic("expression schema ${schemaName(inferredSchema)} is incompatible with declared ${schemaName(explicit)}", "$.workflow.context.$name.schema", pair.first.location.source())
            }
            registers += CompiledRegister(name, pair.second, depsByName[name].orEmpty(), schema, pair.first.location.source())
        }
        outputs.forEachIndexed { index, output ->
            if (output !in definitions) diagnostics += Diagnostic("output '$output' is unreachable because no such register is defined", "$.workflow.outputs[$index]", location(workflow, "outputs"))
        }
        if (diagnostics.isNotEmpty()) return CompilationResult(null, diagnostics)
        val withoutHash = WorkflowIrDocument(id, version, parameters.toSortedMap(), registers.sortedBy { it.name }, outputs, "")
        val hash = sha256(CanonicalIrJson.document(withoutHash, includeHash = false, includeSource = false))
        return CompilationResult(withoutHash.copy(contentHash = hash), emptyList())
    }

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
        return list.items.mapIndexedNotNull { i, item ->
            val output = (item as? YamlScalar)?.content
            if (output == null) diagnostics += Diagnostic("output name must be a string", "$.workflow.outputs[$i]", item.location.source())
            output
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
                        token.matches(Regex("[0-9]+")) -> parts += PathStep.Index(token.toIntOrNull() ?: -1)
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
                    val required = (fieldMap.get("required") as? YamlScalar)?.content?.toBooleanStrictOrNull() ?: true
                    key.content to ValueSchema.Object.Field(fieldSchema, required)
                }.toMap()
                val additional = (map.get("additional-fields") as? YamlScalar)?.content?.toBooleanStrictOrNull() ?: false
                ValueSchema.Object(fields, additional)
            }
            "tagged-union" -> { diagnostics += Diagnostic("tagged-union schemas are reserved for match producers and not supported by expression-only compilation", path, node.location.source()); null }
            else -> { diagnostics += Diagnostic("unknown schema type '$type'", path, node.location.source()); null }
        }
    }

    private fun parseEmbeddedSchema(node: YamlNode, path: String, diagnostics: MutableList<Diagnostic>): ValueSchema? {
        val schemaNode: YamlNode? = (node as? YamlMap)?.get<YamlNode>("schema")
        return schemaNode?.let { parseSchema(it, path, diagnostics) }
    }

    private fun inferExpression(expression: Expression, parameters: Map<String, ValueSchema>, inferred: Map<String, ValueSchema>, registers: Set<String>, lexical: Set<String>, diagnostics: MutableList<Diagnostic>, path: String, source: SourceLocation): ValueSchema = when (expression) {
        is Expression.Literal -> valueSchema(expression.value)
        is Expression.Ref -> {
            val rootSchema = when {
                expression.root == "parameters" -> ValueSchema.Object(parameters.mapValues { ValueSchema.Object.Field(it.value) })
                expression.root in registers -> inferred[expression.root] ?: ValueSchema.Any
                expression.root in lexical -> ValueSchema.Any
                else -> { diagnostics += Diagnostic("unknown reference root '${expression.root}'", path, source); ValueSchema.Any }
            }
            pathSchema(rootSchema, expression.path, path, diagnostics, source)
        }
        is Expression.ObjectValue -> ValueSchema.Object(expression.fields.mapValues { ValueSchema.Object.Field(inferExpression(it.value, parameters, inferred, registers, lexical, diagnostics, path, source)) })
        is Expression.ArrayValue -> { val schemas = expression.items.map { inferExpression(it, parameters, inferred, registers, lexical, diagnostics, path, source) }; ValueSchema.Array(schemas.firstOrNull() ?: ValueSchema.Any) }
        is Expression.Concat -> {
            expression.parts.forEach { part ->
                val partSchema = inferExpression(part, parameters, inferred, registers, lexical, diagnostics, path, source)
                if (partSchema != ValueSchema.String && partSchema != ValueSchema.Any) diagnostics += Diagnostic("\$concat operands must have string-compatible schemas", path, source)
            }
            ValueSchema.String
        }
        is Expression.Equals -> {
            inferExpression(expression.left, parameters, inferred, registers, lexical, diagnostics, path, source)
            inferExpression(expression.right, parameters, inferred, registers, lexical, diagnostics, path, source)
            ValueSchema.Boolean
        }
        is Expression.Present -> { inferExpression(expression.value, parameters, inferred, registers, lexical, diagnostics, path, source); ValueSchema.Boolean }
        is Expression.And -> { expression.predicates.forEach { inferExpression(it, parameters, inferred, registers, lexical, diagnostics, path, source) }; ValueSchema.Boolean }
        is Expression.Or -> { expression.predicates.forEach { inferExpression(it, parameters, inferred, registers, lexical, diagnostics, path, source) }; ValueSchema.Boolean }
        is Expression.Not -> { inferExpression(expression.predicate, parameters, inferred, registers, lexical, diagnostics, path, source); ValueSchema.Boolean }
    }

    private fun pathSchema(start: ValueSchema, pathSteps: List<PathStep>, path: String, diagnostics: MutableList<Diagnostic>, source: SourceLocation): ValueSchema {
        var current = start
        pathSteps.forEach { step ->
            current = when (step) {
                is PathStep.Field -> when (current) { is ValueSchema.Object -> current.fields[step.name]?.schema ?: run { diagnostics += Diagnostic("path field '${step.name}' is not present in the schema", path, source); ValueSchema.Any }; else -> ValueSchema.Any }
                is PathStep.Index -> when (current) { is ValueSchema.Array -> current.items; else -> ValueSchema.Any }
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
    private fun duplicateMappingKeys(yaml: String): List<Pair<String, Int>> {
        val scopes = mutableListOf<Pair<Int, MutableSet<String>>>()
        val duplicates = mutableListOf<Pair<String, Int>>()
        yaml.lineSequence().forEachIndexed { index, raw ->
            val line = raw.substringBefore(" #")
            if (line.trim().isEmpty()) return@forEachIndexed
            val match = Regex("^(\\s*)([A-Za-z_][A-Za-z0-9_-]*):(?:\\s|$)").find(line) ?: return@forEachIndexed
            val indent = match.groupValues[1].length
            val key = match.groupValues[2]
            while (scopes.isNotEmpty() && indent < scopes.last().first) scopes.removeAt(scopes.lastIndex)
            if (scopes.isEmpty() || indent > scopes.last().first) scopes += indent to mutableSetOf()
            if (!scopes.last().second.add(key)) duplicates += key to (index + 1)
        }
        return duplicates
    }
    private fun scalar(map: YamlMap, key: String, path: String, diagnostics: MutableList<Diagnostic>): String? = (map.get(key) as? YamlScalar)?.content ?: run { diagnostics += Diagnostic("$key must be a scalar", path, location(map, key)); null }
    private fun location(map: YamlMap, key: String): SourceLocation = (map.entries.entries.firstOrNull { it.key.content == key }?.value ?: map).location.source()
    private fun valueSchema(value: Value): ValueSchema = when (value) { Value.Null -> ValueSchema.Null; is Value.BooleanValue -> ValueSchema.Boolean; is Value.StringValue -> ValueSchema.String; is Value.IntegerValue -> ValueSchema.Integer; is Value.DecimalValue -> ValueSchema.Decimal; is Value.ArrayValue -> ValueSchema.Array(value.values.firstOrNull()?.let(::valueSchema) ?: ValueSchema.Any); is Value.ObjectValue -> ValueSchema.Object(value.fields.mapValues { ValueSchema.Object.Field(valueSchema(it.value)) }); is Value.TaggedValue -> ValueSchema.Any }
    private fun scalarValue(node: YamlScalar): Value {
        val text = node.content
        val lines = activeYamlText.lines()
        val line = lines.getOrNull(node.location.line - 1).orEmpty()
        val at = (node.location.column - 1).coerceIn(0, line.length)
        if (line.getOrNull(at) == '\'' || line.getOrNull(at) == '"') return Value.StringValue(text)
        return when {
            text == "null" || text == "~" -> Value.Null
            text == "true" -> Value.BooleanValue(true)
            text == "false" -> Value.BooleanValue(false)
            text.matches(Regex("[-+]?[0-9]+")) -> Value.IntegerValue(BigInteger(text))
            text.matches(Regex("[-+]?(?:[0-9]+\\.[0-9]*|[0-9]*\\.[0-9]+|[0-9]+[eE][-+]?[0-9]+|[-+]?[0-9]+[eE][-+]?[0-9]+)")) -> Value.DecimalValue(BigDecimal(text))
            else -> Value.StringValue(text)
        }
    }
    private fun rawValue(node: YamlNode): Value = when (node) { is YamlNull -> Value.Null; is YamlScalar -> Value.StringValue(node.content); is YamlList -> Value.ArrayValue(node.items.map { rawValue(it) }); is YamlMap -> Value.ObjectValue(node.entries.map { it.key.content to rawValue(it.value) }.toMap()); else -> Value.StringValue(node.contentToString()) }
    private fun schemaName(schema: ValueSchema): String = when (schema) { ValueSchema.Any -> "any"; ValueSchema.Null -> "null"; ValueSchema.Boolean -> "boolean"; ValueSchema.String -> "string"; ValueSchema.Integer -> "integer"; ValueSchema.Decimal -> "decimal"; is ValueSchema.Array -> "array"; is ValueSchema.Object -> "object"; is ValueSchema.TaggedUnion -> "tagged-union" }
}

private fun com.charleskorn.kaml.Location.source() = SourceLocation(line, column)

private fun sha256(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

private object CanonicalIrJson {
    fun document(doc: WorkflowIrDocument, includeHash: Boolean, includeSource: Boolean): String {
        val fields = linkedMapOf<String, JsonElement>(
            "irVersion" to JsonPrimitive(doc.irVersion),
            "workflowId" to JsonPrimitive(doc.workflowId),
            "version" to JsonPrimitive(doc.version),
            "parameters" to JsonObject(doc.parameters.toSortedMap().mapValues { schema(it.value) }),
            "registers" to JsonArray(doc.registers.sortedBy { it.name }.map { register(it, includeSource) }),
            "outputs" to JsonArray(doc.outputs.map(::JsonPrimitive)),
        )
        if (includeHash) fields["contentHash"] = JsonPrimitive(doc.contentHash)
        return JsonObject(fields.toSortedMap()).toString()
    }
    private fun register(register: CompiledRegister, includeSource: Boolean): JsonElement = JsonObject(linkedMapOf(
        "name" to JsonPrimitive(register.name),
        "dependencies" to JsonArray(register.dependencies.sorted().map(::JsonPrimitive)),
        "schema" to schema(register.schema),
        "producer" to expression(register.producer),
        *(if (includeSource) arrayOf("source" to JsonObject(mapOf("line" to JsonPrimitive(register.source.line), "column" to JsonPrimitive(register.source.column)))) else emptyArray()),
    ).toSortedMap())
    private fun expression(expression: Expression): JsonElement = when (expression) {
        is Expression.Literal -> JsonObject(mapOf("kind" to JsonPrimitive("literal"), "value" to jsonValue(expression.value)))
        is Expression.Ref -> JsonObject(mapOf("kind" to JsonPrimitive("ref"), "root" to JsonPrimitive(expression.root), "path" to JsonArray(expression.path.map { JsonPrimitive(if (it is PathStep.Field) ".${it.name}" else "[${(it as PathStep.Index).index}]") }), "requirement" to JsonPrimitive(expression.requirement.name.lowercase())))
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
        is ValueSchema.TaggedUnion -> JsonPrimitive("tagged-union")
    }
    private fun jsonValue(value: Value): JsonElement = Json.parseToJsonElement(CanonicalValueJson.encode(value))
}
