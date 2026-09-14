package io.workflow.runtime

import io.workflow.core.ActivationId
import io.workflow.core.ActivationIntent
import io.workflow.core.ActivationIntentId
import io.workflow.core.AssignmentId
import io.workflow.core.AssignmentMutation
import io.workflow.core.AttemptId
import io.workflow.core.CanonicalValueJson
import io.workflow.core.Clock
import io.workflow.core.ContextId
import io.workflow.core.EmissionId
import io.workflow.core.ExecutionId
import io.workflow.core.IdSource
import io.workflow.core.InvocationId
import io.workflow.core.JournalBatch
import io.workflow.core.JournalBatchId
import io.workflow.core.ProducerId
import io.workflow.core.RegisterId
import io.workflow.core.SystemClock
import io.workflow.core.UuidIdSource
import io.workflow.core.Value
import io.workflow.core.WorkflowId
import io.workflow.core.WorkflowVersionId
import java.io.File
import java.nio.file.Path
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Types
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.sqlite.SQLiteConfig
import org.sqlite.SQLiteConnection

/** Failure-injection points around the durable single-assignment commit. */
enum class SqliteCommitStep {
    TRANSACTION_STARTED,
    REVISION_ALLOCATED,
    BATCH_APPENDED,
    ASSIGNMENT_APPENDED,
    CURRENT_VIEW_UPDATED,
    ACTIVATION_INTENTS_APPENDED,
    BEFORE_COMMIT,
}

fun interface SqliteFaultInjector {
    fun after(step: SqliteCommitStep)
}

/**
 * SQLite implementation of [WorkflowJournalStore].  The database is opened
 * per operation, which makes a store safe to reopen after a process crash and
 * lets separate store instances coordinate through SQLite's writer lock.
 */
class SqliteJournalStore @JvmOverloads constructor(
    databasePath: Path,
    private val clock: Clock = SystemClock,
    private val idSource: IdSource = UuidIdSource(),
    private val claimLease: Duration = Duration.ofMinutes(1),
    private val faultInjector: SqliteFaultInjector = SqliteFaultInjector { },
) : WorkflowJournalStore, AutoCloseable {
    companion object {
        const val CURRENT_DATABASE_VERSION = 1
        const val DATABASE_FORMAT_VERSION = CURRENT_DATABASE_VERSION
        private const val DEFAULT_BUSY_TIMEOUT_MILLIS = 10_000

        private val ASSIGNMENT_COLUMNS = """
            assignment_id, journal_batch_id, mutation_ordinal, format_version,
            journal_position, workflow_id, workflow_version_id, execution_id,
            context_id, register_id, value_json, producer_id, activation_id,
            dependency_revisions_json, invocation_id, emission_id, causation_id,
            parent_activation_id, discriminator_revision, revision, occurred_at,
            map_activation_id, map_item_id, map_item_index, map_item_key,
            map_input_revision
        """.trimIndent().replace("\n", " ")

        private val INTENT_COLUMNS = """
            id, activation_id, producer_id, workflow_id, workflow_version_id,
            execution_id, context_id, journal_batch_id, created_at,
            dependency_revisions_json, map_activation_id, map_item_id,
            map_item_index, map_item_key, map_input_revision, parent_context_id,
            parent_activation_id, target_register_id, lexical_bindings_json,
            discriminator_revision, branch_tag, state, claimed_by, claimed_until,
            deferred_requirements_json
        """.trimIndent().replace("\n", " ")

        private val QUALIFIED_ASSIGNMENT_COLUMNS = ASSIGNMENT_COLUMNS.split(", ").joinToString(", ") { "a.${it.trim()}" }
    }

    constructor(
        databasePath: String,
        clock: Clock = SystemClock,
        idSource: IdSource = UuidIdSource(),
        claimLease: Duration = Duration.ofMinutes(1),
        faultInjector: SqliteFaultInjector = SqliteFaultInjector { },
    ) : this(Path.of(databasePath), clock, idSource, claimLease, faultInjector)

    constructor(
        databaseFile: File,
        clock: Clock = SystemClock,
        idSource: IdSource = UuidIdSource(),
        claimLease: Duration = Duration.ofMinutes(1),
        faultInjector: SqliteFaultInjector = SqliteFaultInjector { },
    ) : this(databaseFile.toPath(), clock, idSource, claimLease, faultInjector)

    val path: Path = databasePath.toAbsolutePath().normalize()
    private val jdbcUrl = "jdbc:sqlite:${path}"
    private val ownerId = "owner-${UUID.randomUUID()}"
    private val closed = AtomicBoolean(false)
    private val writeLock = Any()

    init {
        require(!claimLease.isNegative) { "activation claim lease must not be negative" }
        migrate()
    }

    override fun close() {
        closed.set(true)
    }

    private fun ensureOpen() {
        check(!closed.get()) { "SQLite journal store is closed" }
    }

    private fun openConnection(): Connection {
        ensureOpen()
        Class.forName("org.sqlite.JDBC")
        return java.sql.DriverManager.getConnection(jdbcUrl).also { connection ->
            connection.createStatement().use { statement ->
                statement.execute("PRAGMA foreign_keys = ON")
                statement.execute("PRAGMA busy_timeout = $DEFAULT_BUSY_TIMEOUT_MILLIS")
            }
        }
    }

    private fun <T> read(block: (Connection) -> T): T = openConnection().use(block)

    private fun <T> write(block: (Connection) -> T): T {
        synchronized(writeLock) {
            var attempt = 0
            while (true) {
                try {
                    return openConnection().use { connection ->
                        (connection as? SQLiteConnection)?.setCurrentTransactionMode(SQLiteConfig.TransactionMode.IMMEDIATE)
                        connection.autoCommit = false
                        try {
                            val result = block(connection)
                            connection.commit()
                            result
                        } catch (failure: Throwable) {
                            try { connection.rollback() } catch (_: Throwable) { }
                            throw failure
                        } finally {
                            try { connection.autoCommit = true } catch (_: Throwable) { }
                        }
                    }
                } catch (failure: SQLException) {
                    val locked = failure.message?.contains("locked", ignoreCase = true) == true
                    if (!locked || attempt++ >= 20) throw failure
                    Thread.sleep(25L * attempt)
                }
            }
        }
    }

    private fun migrate() {
        read { connection ->
            val version = connection.createStatement().use { statement ->
                statement.executeQuery("PRAGMA user_version").use { result ->
                    result.next()
                    result.getInt(1)
                }
            }
            require(version <= CURRENT_DATABASE_VERSION) {
                "unsupported newer SQLite database version $version (current is $CURRENT_DATABASE_VERSION)"
            }
        }
        write { connection ->
            val version = connection.createStatement().use { statement ->
                statement.executeQuery("PRAGMA user_version").use { result ->
                    result.next()
                    result.getInt(1)
                }
            }
            require(version <= CURRENT_DATABASE_VERSION) {
                "unsupported newer SQLite database version $version (current is $CURRENT_DATABASE_VERSION)"
            }
            if (version < 1) {
                createVersionOneSchema(connection)
                connection.createStatement().use { it.execute("PRAGMA user_version = 1") }
            } else {
                // The version marker is authoritative, but IF NOT EXISTS keeps
                // an interrupted first migration repairable.
                createVersionOneSchema(connection)
            }
            connection.prepareStatement(
                "INSERT OR IGNORE INTO database_metadata(singleton, format_version) VALUES (1, ?)",
            ).use { statement ->
                statement.setInt(1, CURRENT_DATABASE_VERSION)
                statement.executeUpdate()
            }
        }
    }

    private fun createVersionOneSchema(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS database_metadata(
                    singleton INTEGER PRIMARY KEY CHECK(singleton = 1),
                    format_version INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            statement.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS workflow_definitions(
                    workflow_id TEXT NOT NULL,
                    workflow_version_id TEXT NOT NULL,
                    content_hash TEXT NOT NULL,
                    content TEXT,
                    created_at TEXT NOT NULL,
                    PRIMARY KEY(workflow_id, workflow_version_id)
                )
                """.trimIndent(),
            )
            statement.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS executions(
                    execution_id TEXT PRIMARY KEY,
                    workflow_id TEXT,
                    workflow_version_id TEXT NOT NULL,
                    content_hash TEXT NOT NULL,
                    parameters_json TEXT NOT NULL,
                    created_at TEXT NOT NULL
                )
                """.trimIndent(),
            )
            statement.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS contexts(
                    execution_id TEXT NOT NULL,
                    context_id TEXT NOT NULL,
                    parent_context_id TEXT,
                    created_at TEXT NOT NULL,
                    PRIMARY KEY(execution_id, context_id)
                )
                """.trimIndent(),
            )
            statement.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS journal_batches(
                    journal_batch_id TEXT PRIMARY KEY,
                    format_version INTEGER NOT NULL,
                    committed_at TEXT NOT NULL,
                    journal_position INTEGER NOT NULL UNIQUE
                )
                """.trimIndent(),
            )
            statement.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS assignments(
                    assignment_id TEXT PRIMARY KEY,
                    journal_batch_id TEXT NOT NULL UNIQUE REFERENCES journal_batches(journal_batch_id),
                    mutation_ordinal INTEGER NOT NULL CHECK(mutation_ordinal = 0),
                    format_version INTEGER NOT NULL CHECK(format_version = 1),
                    journal_position INTEGER NOT NULL UNIQUE,
                    workflow_id TEXT NOT NULL,
                    workflow_version_id TEXT NOT NULL,
                    execution_id TEXT NOT NULL,
                    context_id TEXT NOT NULL,
                    register_id TEXT NOT NULL,
                    value_json TEXT NOT NULL,
                    producer_id TEXT NOT NULL,
                    activation_id TEXT,
                    dependency_revisions_json TEXT NOT NULL,
                    invocation_id TEXT,
                    emission_id TEXT,
                    causation_id TEXT,
                    parent_activation_id TEXT,
                    discriminator_revision TEXT,
                    revision INTEGER NOT NULL CHECK(revision > 0),
                    occurred_at TEXT NOT NULL,
                    map_activation_id TEXT,
                    map_item_id TEXT,
                    map_item_index INTEGER,
                    map_item_key TEXT,
                    map_input_revision TEXT,
                    UNIQUE(execution_id, context_id, register_id, revision),
                    UNIQUE(journal_batch_id, mutation_ordinal)
                )
                """.trimIndent(),
            )
            statement.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS current_registers(
                    execution_id TEXT NOT NULL,
                    context_id TEXT NOT NULL,
                    register_id TEXT NOT NULL,
                    assignment_id TEXT NOT NULL UNIQUE REFERENCES assignments(assignment_id),
                    revision INTEGER NOT NULL,
                    PRIMARY KEY(execution_id, context_id, register_id)
                )
                """.trimIndent(),
            )
            statement.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS activation_intents(
                    id TEXT PRIMARY KEY,
                    activation_id TEXT NOT NULL,
                    producer_id TEXT NOT NULL,
                    workflow_id TEXT NOT NULL,
                    workflow_version_id TEXT NOT NULL,
                    execution_id TEXT NOT NULL,
                    context_id TEXT NOT NULL,
                    journal_batch_id TEXT NOT NULL,
                    created_at TEXT NOT NULL,
                    dependency_revisions_json TEXT NOT NULL,
                    map_activation_id TEXT,
                    map_item_id TEXT,
                    map_item_index INTEGER,
                    map_item_key TEXT,
                    map_input_revision TEXT,
                    parent_context_id TEXT,
                    parent_activation_id TEXT,
                    target_register_id TEXT,
                    lexical_bindings_json TEXT NOT NULL,
                    discriminator_revision TEXT,
                    branch_tag TEXT,
                    state TEXT NOT NULL CHECK(state IN ('pending', 'claimed', 'deferred', 'open', 'done', 'stopped')),
                    claimed_by TEXT,
                    claimed_until TEXT,
                    deferred_requirements_json TEXT,
                    UNIQUE(activation_id, execution_id)
                )
                """.trimIndent(),
            )
            statement.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS activation_records(
                    intent_id TEXT PRIMARY KEY REFERENCES activation_intents(id),
                    activation_id TEXT NOT NULL,
                    workflow_id TEXT NOT NULL,
                    workflow_version_id TEXT NOT NULL,
                    execution_id TEXT NOT NULL,
                    producer_id TEXT NOT NULL,
                    context_id TEXT NOT NULL,
                    dependency_revisions_json TEXT NOT NULL,
                    status TEXT NOT NULL,
                    started_at TEXT NOT NULL,
                    completed_at TEXT,
                    failure TEXT,
                    invocation_id TEXT,
                    attempt_id TEXT,
                    map_activation_id TEXT,
                    map_item_id TEXT,
                    map_item_index INTEGER,
                    map_item_key TEXT,
                    map_input_revision TEXT,
                    parent_activation_id TEXT,
                    discriminator_revision TEXT,
                    branch_tag TEXT
                )
                """.trimIndent(),
            )
            statement.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS provider_events(
                    event_id TEXT PRIMARY KEY,
                    type TEXT NOT NULL,
                    workflow_id TEXT NOT NULL,
                    workflow_version_id TEXT NOT NULL,
                    execution_id TEXT NOT NULL,
                    context_id TEXT NOT NULL,
                    producer_id TEXT NOT NULL,
                    activation_id TEXT NOT NULL,
                    intent_id TEXT NOT NULL,
                    invocation_id TEXT NOT NULL,
                    attempt_id TEXT,
                    emission_id TEXT,
                    causation_id TEXT,
                    value_json TEXT,
                    error_json TEXT,
                    diagnostic TEXT,
                    correlation_id TEXT,
                    occurred_at TEXT NOT NULL,
                    provider_id TEXT,
                    provider_version INTEGER,
                    map_activation_id TEXT,
                    map_item_id TEXT,
                    map_item_index INTEGER,
                    map_item_key TEXT,
                    map_input_revision TEXT,
                    parent_activation_id TEXT,
                    discriminator_revision TEXT
                )
                """.trimIndent(),
            )
            statement.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS provider_invocations(
                    event_id TEXT PRIMARY KEY REFERENCES provider_events(event_id),
                    invocation_id TEXT NOT NULL,
                    execution_id TEXT NOT NULL,
                    occurred_at TEXT NOT NULL
                )
                """.trimIndent(),
            )
            statement.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS provider_attempts(
                    event_id TEXT PRIMARY KEY REFERENCES provider_events(event_id),
                    attempt_id TEXT NOT NULL,
                    invocation_id TEXT NOT NULL,
                    execution_id TEXT NOT NULL,
                    occurred_at TEXT NOT NULL
                )
                """.trimIndent(),
            )
            statement.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS provider_emissions(
                    event_id TEXT PRIMARY KEY REFERENCES provider_events(event_id),
                    emission_id TEXT,
                    invocation_id TEXT NOT NULL,
                    execution_id TEXT NOT NULL,
                    accepted INTEGER NOT NULL,
                    occurred_at TEXT NOT NULL
                )
                """.trimIndent(),
            )
            statement.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS provider_failures(
                    event_id TEXT PRIMARY KEY REFERENCES provider_events(event_id),
                    invocation_id TEXT NOT NULL,
                    attempt_id TEXT,
                    execution_id TEXT NOT NULL,
                    diagnostic TEXT,
                    occurred_at TEXT NOT NULL
                )
                """.trimIndent(),
            )
            statement.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS recovery_decisions(
                    decision_id TEXT PRIMARY KEY,
                    execution_id TEXT NOT NULL,
                    activation_id TEXT,
                    invocation_id TEXT,
                    decision_type TEXT NOT NULL,
                    payload_json TEXT,
                    occurred_at TEXT NOT NULL
                )
                """.trimIndent(),
            )
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_assignments_order ON assignments(journal_position)")
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_intents_ready ON activation_intents(execution_id, state, created_at)")
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_events_execution ON provider_events(execution_id, occurred_at)")
        }
    }

    override fun recordWorkflowDefinition(
        workflowId: WorkflowId,
        workflowVersionId: WorkflowVersionId,
        contentHash: String,
        content: String?,
    ) = write { connection ->
        val existing = connection.prepareStatement(
            "SELECT content_hash, content FROM workflow_definitions WHERE workflow_id = ? AND workflow_version_id = ?",
        ).use { statement ->
            statement.setString(1, workflowId.value)
            statement.setString(2, workflowVersionId.value)
            statement.executeQuery().use { result ->
                if (!result.next()) null else result.getString(1) to result.getString(2)
            }
        }
        if (existing != null) {
            require(existing.first == contentHash && (content == null || existing.second == null || existing.second == content)) {
                "workflow version is already bound to different content"
            }
            if (existing.second == null && content != null) {
                connection.prepareStatement(
                    "UPDATE workflow_definitions SET content = ? WHERE workflow_id = ? AND workflow_version_id = ?",
                ).use { statement ->
                    statement.setString(1, content)
                    statement.setString(2, workflowId.value)
                    statement.setString(3, workflowVersionId.value)
                    statement.executeUpdate()
                }
            }
        } else {
            connection.prepareStatement(
                "INSERT INTO workflow_definitions(workflow_id, workflow_version_id, content_hash, content, created_at) VALUES (?, ?, ?, ?, ?)",
            ).use { statement ->
                statement.setString(1, workflowId.value)
                statement.setString(2, workflowVersionId.value)
                statement.setString(3, contentHash)
                statement.setNullableString(4, content)
                statement.setString(5, clock.now().toString())
                statement.executeUpdate()
            }
        }
        Unit
    }

    override fun bindExecution(
        executionId: ExecutionId,
        workflowVersionId: WorkflowVersionId,
        contentHash: String,
        parameters: Map<String, Value>,
    ): Map<String, Value> = write { connection ->
        val encoded = encodeValues(parameters)
        val existing = connection.prepareStatement(
            "SELECT workflow_version_id, content_hash, parameters_json FROM executions WHERE execution_id = ?",
        ).use { statement ->
            statement.setString(1, executionId.value)
            statement.executeQuery().use { result ->
                if (!result.next()) null else Triple(result.getString(1), result.getString(2), result.getString(3))
            }
        }
        ensureContext(connection, executionId, ContextId(InMemoryWorkflowRunner.ANONYMOUS_CONTEXT), null)
        if (existing != null) {
            require(existing.first == workflowVersionId.value && existing.second == contentHash && existing.third == encoded) {
                "execution id is already bound to different workflow content or parameters"
            }
            return@write parameters
        }
        connection.prepareStatement(
            "INSERT INTO executions(execution_id, workflow_version_id, content_hash, parameters_json, created_at) VALUES (?, ?, ?, ?, ?)",
        ).use { statement ->
            statement.setString(1, executionId.value)
            statement.setString(2, workflowVersionId.value)
            statement.setString(3, contentHash)
            statement.setString(4, encoded)
            statement.setString(5, clock.now().toString())
            statement.executeUpdate()
        }
        parameters
    }

    override fun commit(batch: JournalBatch, activationIntents: Collection<ActivationIntent>): JournalBatch = write { connection ->
        require(batch.mutations.size == 1) { "v1 journal batch must contain exactly one assignment mutation" }
        require(batch.formatVersion == 1) { "unsupported journal batch format version ${batch.formatVersion}" }
        val mutation = batch.assignment
        require(mutation.formatVersion == 1) { "unsupported assignment format version ${mutation.formatVersion}" }
        require(mutation.mutationOrdinal == 0) { "the only v1 mutation must have ordinal zero" }
        faultInjector.after(SqliteCommitStep.TRANSACTION_STARTED)

        val duplicate = findBatch(connection, batch.journalBatchId)
        if (duplicate != null) {
            val requested = mutation
            require(
                duplicate.assignment.copy(revision = requested.revision) == requested &&
                    duplicate.formatVersion == batch.formatVersion && duplicate.committedAt == batch.committedAt,
            ) { "journal batch id is already committed with different contents" }
            if (activationIntents.isNotEmpty()) {
                val persisted = intentsForBatch(connection, batch.journalBatchId)
                require(persisted.size == activationIntents.size && activationIntents.all { proposed ->
                    persisted.any { it.id == proposed.id && it.sameIdentityAndProvenance(proposed) }
                }) { "journal batch id is already committed with different activation intents" }
            }
            return@write duplicate
        }
        require(!exists(connection, "SELECT 1 FROM assignments WHERE assignment_id = ?", mutation.assignmentId.value)) {
            "assignment id is already committed"
        }
        val key = RegisterKey(mutation.executionId, mutation.contextId, mutation.registerId)
        val nextRevision = connection.prepareStatement(
            "SELECT COALESCE(MAX(revision), 0) + 1 FROM assignments WHERE execution_id = ? AND context_id = ? AND register_id = ?",
        ).use { statement ->
            statement.setString(1, key.executionId.value)
            statement.setString(2, key.contextId.value)
            statement.setString(3, key.registerId.value)
            statement.executeQuery().use { result -> result.next(); result.getLong(1) }
        }
        val committedMutation = mutation.copy(revision = nextRevision)
        faultInjector.after(SqliteCommitStep.REVISION_ALLOCATED)

        val nextJournalPosition = connection.createStatement().use { statement ->
            statement.executeQuery("SELECT COALESCE(MAX(journal_position), 0) + 1 FROM journal_batches").use { result ->
                result.next(); result.getLong(1)
            }
        }
        connection.prepareStatement(
            "INSERT INTO journal_batches(journal_batch_id, format_version, committed_at, journal_position) VALUES (?, ?, ?, ?)",
        ).use { statement ->
            statement.setString(1, batch.journalBatchId.value)
            statement.setInt(2, batch.formatVersion)
            statement.setString(3, batch.committedAt.toString())
            statement.setLong(4, nextJournalPosition)
            statement.executeUpdate()
        }
        faultInjector.after(SqliteCommitStep.BATCH_APPENDED)
        insertAssignment(connection, committedMutation, batch.journalBatchId, nextJournalPosition)
        faultInjector.after(SqliteCommitStep.ASSIGNMENT_APPENDED)
        ensureContext(connection, committedMutation.executionId, committedMutation.contextId, null)
        connection.prepareStatement(
            "INSERT INTO current_registers(execution_id, context_id, register_id, assignment_id, revision) VALUES (?, ?, ?, ?, ?) " +
                "ON CONFLICT(execution_id, context_id, register_id) DO UPDATE SET assignment_id = excluded.assignment_id, revision = excluded.revision",
        ).use { statement ->
            statement.setString(1, committedMutation.executionId.value)
            statement.setString(2, committedMutation.contextId.value)
            statement.setString(3, committedMutation.registerId.value)
            statement.setString(4, committedMutation.assignmentId.value)
            statement.setLong(5, committedMutation.revision)
            statement.executeUpdate()
        }
        faultInjector.after(SqliteCommitStep.CURRENT_VIEW_UPDATED)
        activationIntents.forEach { intent ->
            validateIntentForAssignment(intent, committedMutation, batch.journalBatchId)
            insertIntent(connection, intent)
        }
        awakenDeferredForAssignment(connection, committedMutation)
        faultInjector.after(SqliteCommitStep.ACTIVATION_INTENTS_APPENDED)
        faultInjector.after(SqliteCommitStep.BEFORE_COMMIT)
        JournalBatch(batch.journalBatchId, listOf(committedMutation), batch.committedAt, batch.formatVersion)
    }

    override fun commit(proposal: JournalBatchProposal, activationIntents: Collection<ActivationIntent>): JournalBatch {
        require(proposal.mutations.size == 1) { "v1 journal batch must contain exactly one assignment mutation" }
        require(proposal.mutations.single().mutationOrdinal == 0) { "the only v1 mutation must have ordinal zero" }
        return commit(
            JournalBatch(proposal.journalBatchId, proposal.mutations, proposal.committedAt, proposal.formatVersion),
            activationIntents,
        )
    }

    fun commit(proposal: JournalBatchProposal): JournalBatch = commit(proposal, emptyList())

    fun commit(mutation: AssignmentMutation, activationIntents: Collection<ActivationIntent> = emptyList()): JournalBatch = commit(
        JournalBatch(JournalBatchId("batch-${idSource.nextId()}"), listOf(mutation), clock.now()),
        activationIntents,
    )

    override fun persistActivationIntents(intents: Collection<ActivationIntent>) = write { connection ->
        intents.forEach { insertIntent(connection, it) }
    }

    override fun current(key: RegisterKey): AssignmentMutation? = read { connection ->
        connection.prepareStatement(
            "SELECT $ASSIGNMENT_COLUMNS FROM assignments WHERE assignment_id = (SELECT assignment_id FROM current_registers WHERE execution_id = ? AND context_id = ? AND register_id = ?)",
        ).use { statement ->
            statement.setString(1, key.executionId.value)
            statement.setString(2, key.contextId.value)
            statement.setString(3, key.registerId.value)
            statement.executeQuery().use { result -> if (result.next()) readAssignment(result) else null }
        }
    }

    override fun currentFor(executionId: ExecutionId, contextId: ContextId): Map<RegisterId, AssignmentMutation> = read { connection ->
        connection.prepareStatement(
            "SELECT $QUALIFIED_ASSIGNMENT_COLUMNS FROM assignments a JOIN current_registers c ON c.assignment_id = a.assignment_id " +
                "WHERE c.execution_id = ? AND c.context_id = ? ORDER BY c.register_id",
        ).use { statement ->
            statement.setString(1, executionId.value)
            statement.setString(2, contextId.value)
            statement.executeQuery().use { result ->
                buildMap { while (result.next()) { val assignment = readAssignment(result); put(assignment.registerId, assignment) } }
            }
        }
    }

    override fun allCurrent(): Map<RegisterKey, AssignmentMutation> = read { connection -> loadCurrent(connection) }

    override fun batches(): List<JournalBatch> = read { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery(
                "SELECT journal_batch_id, format_version, committed_at FROM journal_batches ORDER BY journal_position",
            ).use { result ->
                buildList {
                    while (result.next()) {
                        val id = JournalBatchId(result.getString(1))
                        val assignment = findBatch(connection, id)!!.assignment
                        add(JournalBatch(id, listOf(assignment), Instant.parse(result.getString(3)), result.getInt(2)))
                    }
                }
            }
        }
    }

    override fun assignments(): List<AssignmentMutation> = batches().map { it.assignment }

    override fun activationIntents(): List<ActivationIntent> = read { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT $INTENT_COLUMNS FROM activation_intents ORDER BY rowid").use { result ->
                buildList { while (result.next()) add(readIntent(result)) }
            }
        }
    }

    override fun activations(): List<ActivationRecord> = read { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery(
                "SELECT intent_id, activation_id, workflow_id, workflow_version_id, execution_id, producer_id, context_id, " +
                    "dependency_revisions_json, status, started_at, completed_at, failure, invocation_id, attempt_id, " +
                    "map_activation_id, map_item_id, map_item_index, map_item_key, map_input_revision, parent_activation_id, " +
                    "discriminator_revision, branch_tag FROM activation_records ORDER BY rowid",
            ).use { result -> buildList { while (result.next()) add(readActivation(result)) } }
        }
    }

    override fun providerEvents(): List<ProviderLifecycleEvent> = read { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery(
                "SELECT event_id, type, workflow_id, workflow_version_id, execution_id, context_id, producer_id, activation_id, " +
                    "intent_id, invocation_id, attempt_id, emission_id, causation_id, value_json, error_json, diagnostic, " +
                    "correlation_id, occurred_at, provider_id, provider_version, map_activation_id, map_item_id, map_item_index, " +
                    "map_item_key, map_input_revision, parent_activation_id, discriminator_revision FROM provider_events ORDER BY rowid",
            ).use { result -> buildList { while (result.next()) add(readProviderEvent(result)) } }
        }
    }

    override fun events(): List<ProviderLifecycleEvent> = providerEvents()
    override fun providerLifecycleEvents(): List<ProviderLifecycleEvent> = providerEvents()
    override fun providerInvocations(): List<ProviderLifecycleEvent> = providerEvents().filter { it.type == ProviderEventType.INVOCATION }
    override fun providerAttempts(): List<ProviderLifecycleEvent> = providerEvents().filter { it.type == ProviderEventType.ATTEMPT_STARTED }
    override fun providerEmissions(): List<ProviderLifecycleEvent> = providerEvents().filter {
        it.type == ProviderEventType.EMISSION_RECEIVED || it.type == ProviderEventType.EMISSION_ACCEPTED || it.type == ProviderEventType.EMISSION_REFUSED
    }
    override fun providerFailures(): List<ProviderLifecycleEvent> = providerEvents().filter { it.type == ProviderEventType.FAILED }
    override fun invocationRecords(): List<ProviderLifecycleEvent> = providerInvocations()
    override fun attemptRecords(): List<ProviderLifecycleEvent> = providerAttempts()
    override fun emissionRecords(): List<ProviderLifecycleEvent> = providerEmissions()
    override fun failureRecords(): List<ProviderLifecycleEvent> = providerFailures()

    override fun assignment(id: AssignmentId): AssignmentMutation? = read { connection ->
        connection.prepareStatement("SELECT $ASSIGNMENT_COLUMNS FROM assignments WHERE assignment_id = ?").use { statement ->
            statement.setString(1, id.value)
            statement.executeQuery().use { result -> if (result.next()) readAssignment(result) else null }
        }
    }

    override fun recordProviderEvent(event: ProviderLifecycleEvent) = write { connection ->
        require(!exists(connection, "SELECT 1 FROM provider_events WHERE event_id = ?", event.eventId)) {
            "provider event id is already committed"
        }
        connection.prepareStatement(
            "INSERT INTO provider_events(event_id, type, workflow_id, workflow_version_id, execution_id, context_id, producer_id, activation_id, intent_id, invocation_id, attempt_id, emission_id, causation_id, value_json, error_json, diagnostic, correlation_id, occurred_at, provider_id, provider_version, map_activation_id, map_item_id, map_item_index, map_item_key, map_input_revision, parent_activation_id, discriminator_revision) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        ).use { statement ->
            statement.setString(1, event.eventId)
            statement.setString(2, event.type.name)
            statement.setString(3, event.workflowId.value)
            statement.setString(4, event.workflowVersionId.value)
            statement.setString(5, event.executionId.value)
            statement.setString(6, event.contextId.value)
            statement.setString(7, event.producerId.value)
            statement.setString(8, event.activationId.value)
            statement.setString(9, event.intentId.value)
            statement.setString(10, event.invocationId.value)
            statement.setNullableString(11, event.attemptId?.value)
            statement.setNullableString(12, event.emissionId?.value)
            statement.setNullableString(13, event.causationId)
            statement.setNullableString(14, event.value?.let(CanonicalValueJson::encode))
            statement.setNullableString(15, event.error?.let(CanonicalValueJson::encode))
            statement.setNullableString(16, event.diagnostic)
            statement.setNullableString(17, event.correlationId)
            statement.setString(18, event.occurredAt.toString())
            statement.setNullableString(19, event.providerId)
            statement.setNullableInt(20, event.providerVersion)
            statement.setNullableString(21, event.mapActivationId?.value)
            statement.setNullableString(22, event.mapItemId)
            statement.setNullableInt(23, event.mapItemIndex)
            statement.setNullableString(24, event.mapItemKey)
            statement.setNullableString(25, event.mapInputRevision?.value)
            statement.setNullableString(26, event.parentActivationId?.value)
            statement.setNullableString(27, event.discriminatorRevision?.value)
            statement.executeUpdate()
        }
        when (event.type) {
            ProviderEventType.INVOCATION -> connection.prepareStatement(
                "INSERT INTO provider_invocations(event_id, invocation_id, execution_id, occurred_at) VALUES (?, ?, ?, ?)",
            ).use { statement ->
                statement.setString(1, event.eventId); statement.setString(2, event.invocationId.value)
                statement.setString(3, event.executionId.value); statement.setString(4, event.occurredAt.toString()); statement.executeUpdate()
            }
            ProviderEventType.ATTEMPT_STARTED -> connection.prepareStatement(
                "INSERT INTO provider_attempts(event_id, attempt_id, invocation_id, execution_id, occurred_at) VALUES (?, ?, ?, ?, ?)",
            ).use { statement ->
                statement.setString(1, event.eventId); statement.setString(2, event.attemptId?.value ?: "")
                statement.setString(3, event.invocationId.value); statement.setString(4, event.executionId.value); statement.setString(5, event.occurredAt.toString()); statement.executeUpdate()
            }
            ProviderEventType.EMISSION_RECEIVED, ProviderEventType.EMISSION_ACCEPTED, ProviderEventType.EMISSION_REFUSED -> connection.prepareStatement(
                "INSERT INTO provider_emissions(event_id, emission_id, invocation_id, execution_id, accepted, occurred_at) VALUES (?, ?, ?, ?, ?, ?)",
            ).use { statement ->
                statement.setString(1, event.eventId); statement.setNullableString(2, event.emissionId?.value)
                statement.setString(3, event.invocationId.value); statement.setString(4, event.executionId.value)
                statement.setInt(5, if (event.type == ProviderEventType.EMISSION_ACCEPTED) 1 else 0)
                statement.setString(6, event.occurredAt.toString()); statement.executeUpdate()
            }
            ProviderEventType.FAILED -> connection.prepareStatement(
                "INSERT INTO provider_failures(event_id, invocation_id, attempt_id, execution_id, diagnostic, occurred_at) VALUES (?, ?, ?, ?, ?, ?)",
            ).use { statement ->
                statement.setString(1, event.eventId); statement.setString(2, event.invocationId.value)
                statement.setNullableString(3, event.attemptId?.value); statement.setString(4, event.executionId.value)
                statement.setNullableString(5, event.diagnostic); statement.setString(6, event.occurredAt.toString()); statement.executeUpdate()
            }
            else -> Unit
        }
        Unit
    }

    override fun claimNextActivation(executionId: ExecutionId): ActivationIntent? = write { connection ->
        val now = clock.now()
        connection.prepareStatement(
            "UPDATE activation_intents SET state = 'pending', claimed_by = NULL, claimed_until = NULL WHERE state = 'claimed' AND claimed_until IS NOT NULL AND claimed_until <= ?",
        ).use { statement -> statement.setString(1, now.toString()); statement.executeUpdate() }
        val candidate = connection.prepareStatement(
            "SELECT $INTENT_COLUMNS FROM activation_intents WHERE execution_id = ? AND state = 'pending' ORDER BY rowid LIMIT 1",
        ).use { statement ->
            statement.setString(1, executionId.value)
            statement.executeQuery().use { result -> if (result.next()) readIntent(result) else null }
        } ?: return@write null
        val leaseUntil = now.plus(claimLease)
        connection.prepareStatement(
            "UPDATE activation_intents SET state = 'claimed', claimed_by = ?, claimed_until = ? WHERE id = ? AND state = 'pending'",
        ).use { statement ->
            statement.setString(1, ownerId); statement.setString(2, leaseUntil.toString()); statement.setString(3, candidate.id.value)
            require(statement.executeUpdate() == 1) { "activation claim was lost to a concurrent worker" }
        }
        candidate
    }

    /** Explicitly recover claims whose lease expired, useful to operators and tests. */
    fun recoverExpiredClaims(at: Instant = clock.now()): Int = write { connection ->
        connection.prepareStatement(
            "UPDATE activation_intents SET state = 'pending', claimed_by = NULL, claimed_until = NULL WHERE state = 'claimed' AND claimed_until IS NOT NULL AND claimed_until <= ?",
        ).use { statement -> statement.setString(1, at.toString()); statement.executeUpdate() }
    }

    override fun completeActivation(intentId: ActivationIntentId) = write { connection ->
        require(exists(connection, "SELECT 1 FROM activation_intents WHERE id = ?", intentId.value)) {
            "cannot complete an unknown activation intent"
        }
        connection.prepareStatement(
            "UPDATE activation_intents SET state = 'done', claimed_by = NULL, claimed_until = NULL, deferred_requirements_json = NULL WHERE id = ?",
        ).use { statement -> statement.setString(1, intentId.value); statement.executeUpdate() }
        Unit
    }

    override fun releaseActivation(intentId: ActivationIntentId) = write { connection ->
        require(exists(connection, "SELECT 1 FROM activation_intents WHERE id = ?", intentId.value)) {
            "cannot release an unknown activation intent"
        }
        connection.prepareStatement(
            "UPDATE activation_intents SET state = 'pending', claimed_by = NULL, claimed_until = NULL WHERE id = ? AND state = 'claimed'",
        ).use { statement -> statement.setString(1, intentId.value); statement.executeUpdate() }
        Unit
    }

    override fun keepActivationOpen(intentId: ActivationIntentId) = write { connection ->
        require(exists(connection, "SELECT 1 FROM activation_intents WHERE id = ?", intentId.value)) {
            "cannot keep an unknown activation intent open"
        }
        require(!isState(connection, intentId, "done")) { "cannot reopen a completed activation intent" }
        connection.prepareStatement(
            "UPDATE activation_intents SET state = 'open', claimed_by = NULL, claimed_until = NULL, deferred_requirements_json = NULL WHERE id = ?",
        ).use { statement -> statement.setString(1, intentId.value); statement.executeUpdate() }
        Unit
    }

    override fun stopActivation(intentId: ActivationIntentId) = write { connection ->
        require(exists(connection, "SELECT 1 FROM activation_intents WHERE id = ?", intentId.value)) {
            "cannot stop an unknown activation intent"
        }
        require(!isState(connection, intentId, "done")) { "cannot stop a completed activation intent" }
        connection.prepareStatement(
            "UPDATE activation_intents SET state = 'stopped', claimed_by = NULL, claimed_until = NULL, deferred_requirements_json = NULL WHERE id = ?",
        ).use { statement -> statement.setString(1, intentId.value); statement.executeUpdate() }
        Unit
    }

    override fun stopExecution(executionId: ExecutionId) = write { connection ->
        connection.prepareStatement(
            "UPDATE activation_intents SET state = 'stopped', claimed_by = NULL, claimed_until = NULL, deferred_requirements_json = NULL " +
                "WHERE execution_id = ? AND state != 'done'",
        ).use { statement -> statement.setString(1, executionId.value); statement.executeUpdate() }
        Unit
    }

    override fun deferActivationIfMissing(intentId: ActivationIntentId, required: Set<RegisterKey>): Boolean = write { connection ->
        require(exists(connection, "SELECT 1 FROM activation_intents WHERE id = ?", intentId.value)) {
            "cannot defer an unknown activation intent"
        }
        require(!isState(connection, intentId, "done")) { "cannot defer a completed activation intent" }
        val missing = required.filterTo(linkedSetOf()) { !exists(
            connection,
            "SELECT 1 FROM current_registers WHERE execution_id = ? AND context_id = ? AND register_id = ?",
            it.executionId.value, it.contextId.value, it.registerId.value,
        ) }
        if (missing.isEmpty()) return@write false
        connection.prepareStatement(
            "UPDATE activation_intents SET state = 'deferred', claimed_by = NULL, claimed_until = NULL, deferred_requirements_json = ? WHERE id = ?",
        ).use { statement ->
            statement.setString(1, encodeRegisterKeys(missing)); statement.setString(2, intentId.value); statement.executeUpdate()
        }
        true
    }

    override fun wakeDeferredActivations(executionId: ExecutionId) = write { connection ->
        connection.prepareStatement(
            "UPDATE activation_intents SET state = 'pending', deferred_requirements_json = NULL WHERE execution_id = ? AND state = 'deferred'",
        ).use { statement -> statement.setString(1, executionId.value); statement.executeUpdate() }
        Unit
    }

    override fun hasPendingActivations(executionId: ExecutionId): Boolean = read { connection ->
        exists(connection, "SELECT 1 FROM activation_intents WHERE execution_id = ? AND state IN ('pending', 'claimed')", executionId.value)
    }

    override fun isOpen(intentId: ActivationIntentId): Boolean = read { connection -> isState(connection, intentId, "open") }

    override fun isStopped(intentId: ActivationIntentId): Boolean = read { connection -> isState(connection, intentId, "stopped") }

    override fun recordActivation(record: ActivationRecord) = write { connection ->
        val intent = findIntent(connection, record.intentId)
            ?: error("cannot record activation for an unknown intent")
        require(record.activationId == intent.activationId && record.workflowId == intent.workflowId && record.workflowVersionId == intent.workflowVersionId)
        require(record.executionId == intent.executionId && record.contextId == intent.contextId && record.producerId == intent.producerId)
        require(record.dependencyRevisions == intent.dependencyRevisions && record.parentActivationId == intent.parentActivationId)
        require(record.discriminatorRevision == intent.discriminatorRevision && record.branchTag == intent.branchTag)
        connection.prepareStatement(
            "INSERT INTO activation_records(intent_id, activation_id, workflow_id, workflow_version_id, execution_id, producer_id, context_id, dependency_revisions_json, status, started_at, completed_at, failure, invocation_id, attempt_id, map_activation_id, map_item_id, map_item_index, map_item_key, map_input_revision, parent_activation_id, discriminator_revision, branch_tag) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
                "ON CONFLICT(intent_id) DO UPDATE SET activation_id = excluded.activation_id, workflow_id = excluded.workflow_id, workflow_version_id = excluded.workflow_version_id, execution_id = excluded.execution_id, producer_id = excluded.producer_id, context_id = excluded.context_id, dependency_revisions_json = excluded.dependency_revisions_json, status = excluded.status, started_at = excluded.started_at, completed_at = excluded.completed_at, failure = excluded.failure, invocation_id = excluded.invocation_id, attempt_id = excluded.attempt_id, map_activation_id = excluded.map_activation_id, map_item_id = excluded.map_item_id, map_item_index = excluded.map_item_index, map_item_key = excluded.map_item_key, map_input_revision = excluded.map_input_revision, parent_activation_id = excluded.parent_activation_id, discriminator_revision = excluded.discriminator_revision, branch_tag = excluded.branch_tag",
        ).use { statement ->
            statement.setString(1, record.intentId.value); statement.setString(2, record.activationId.value)
            statement.setString(3, record.workflowId.value); statement.setString(4, record.workflowVersionId.value)
            statement.setString(5, record.executionId.value); statement.setString(6, record.producerId.value); statement.setString(7, record.contextId.value)
            statement.setString(8, encodeRevisionMap(record.dependencyRevisions)); statement.setString(9, record.status.name)
            statement.setString(10, record.startedAt.toString()); statement.setNullableString(11, record.completedAt?.toString())
            statement.setNullableString(12, record.failure); statement.setNullableString(13, record.invocationId?.value); statement.setNullableString(14, record.attemptId?.value)
            statement.setNullableString(15, record.mapActivationId?.value); statement.setNullableString(16, record.mapItemId); statement.setNullableInt(17, record.mapItemIndex)
            statement.setNullableString(18, record.mapItemKey); statement.setNullableString(19, record.mapInputRevision?.value); statement.setNullableString(20, record.parentActivationId?.value)
            statement.setNullableString(21, record.discriminatorRevision?.value); statement.setNullableString(22, record.branchTag); statement.executeUpdate()
        }
        Unit
    }

    override fun isCompleted(intentId: ActivationIntentId): Boolean = read { connection -> isState(connection, intentId, "done") }

    override fun rebuildCurrentView(): Map<RegisterKey, AssignmentMutation> = write { connection ->
        connection.createStatement().use { it.executeUpdate("DELETE FROM current_registers") }
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT $ASSIGNMENT_COLUMNS FROM assignments ORDER BY journal_position").use { result ->
                while (result.next()) {
                    val assignment = readAssignment(result)
                    connection.prepareStatement(
                        "INSERT INTO current_registers(execution_id, context_id, register_id, assignment_id, revision) VALUES (?, ?, ?, ?, ?) " +
                            "ON CONFLICT(execution_id, context_id, register_id) DO UPDATE SET assignment_id = excluded.assignment_id, revision = excluded.revision",
                    ).use { upsert ->
                        upsert.setString(1, assignment.executionId.value); upsert.setString(2, assignment.contextId.value); upsert.setString(3, assignment.registerId.value)
                        upsert.setString(4, assignment.assignmentId.value); upsert.setLong(5, assignment.revision); upsert.executeUpdate()
                    }
                }
            }
        }
        loadCurrent(connection)
    }

    private fun insertAssignment(connection: Connection, assignment: AssignmentMutation, batchId: JournalBatchId, journalPosition: Long) {
        connection.prepareStatement(
            "INSERT INTO assignments($ASSIGNMENT_COLUMNS) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        ).use { statement ->
            statement.setString(1, assignment.assignmentId.value); statement.setString(2, batchId.value); statement.setInt(3, assignment.mutationOrdinal)
            statement.setInt(4, assignment.formatVersion); statement.setLong(5, journalPosition); statement.setString(6, assignment.workflowId.value)
            statement.setString(7, assignment.workflowVersionId.value); statement.setString(8, assignment.executionId.value); statement.setString(9, assignment.contextId.value)
            statement.setString(10, assignment.registerId.value); statement.setString(11, CanonicalValueJson.encode(assignment.value)); statement.setString(12, assignment.producerId.value)
            statement.setNullableString(13, assignment.activationId?.value); statement.setString(14, encodeRevisionMap(assignment.dependencyRevisions)); statement.setNullableString(15, assignment.invocationId?.value)
            statement.setNullableString(16, assignment.emissionId?.value); statement.setNullableString(17, assignment.causationId); statement.setNullableString(18, assignment.parentActivationId?.value)
            statement.setNullableString(19, assignment.discriminatorRevision?.value); statement.setLong(20, assignment.revision); statement.setString(21, assignment.occurredAt.toString())
            statement.setNullableString(22, assignment.mapActivationId?.value); statement.setNullableString(23, assignment.mapItemId); statement.setNullableInt(24, assignment.mapItemIndex)
            statement.setNullableString(25, assignment.mapItemKey); statement.setNullableString(26, assignment.mapInputRevision?.value); statement.executeUpdate()
        }
    }

    private fun findBatch(connection: Connection, id: JournalBatchId): JournalBatch? = connection.prepareStatement(
        "SELECT journal_batch_id, format_version, committed_at FROM journal_batches WHERE journal_batch_id = ?",
    ).use { statement ->
        statement.setString(1, id.value)
        statement.executeQuery().use { result ->
            if (!result.next()) null else {
                val assignment = connection.prepareStatement("SELECT $ASSIGNMENT_COLUMNS FROM assignments WHERE journal_batch_id = ?").use { assignmentStatement ->
                    assignmentStatement.setString(1, id.value)
                    assignmentStatement.executeQuery().use { assignmentResult -> require(assignmentResult.next()); readAssignment(assignmentResult) }
                }
                JournalBatch(id, listOf(assignment), Instant.parse(result.getString(3)), result.getInt(2))
            }
        }
    }

    private fun loadCurrent(connection: Connection): Map<RegisterKey, AssignmentMutation> = connection.createStatement().use { statement ->
        statement.executeQuery(
            "SELECT $QUALIFIED_ASSIGNMENT_COLUMNS FROM assignments a JOIN current_registers c ON c.assignment_id = a.assignment_id ORDER BY c.rowid",
        ).use { result ->
            buildMap {
                while (result.next()) {
                    val assignment = readAssignment(result)
                    put(RegisterKey(assignment.executionId, assignment.contextId, assignment.registerId), assignment)
                }
            }
        }
    }

    private fun insertIntent(connection: Connection, intent: ActivationIntent) {
        val existing = findIntent(connection, intent.id)
        if (existing != null) {
            require(existing.sameIdentityAndProvenance(intent)) { "activation intent id is already persisted with different contents" }
            return
        }
        ensureContext(connection, intent.executionId, intent.contextId, intent.parentContextId)
        connection.prepareStatement(
            "INSERT INTO activation_intents($INTENT_COLUMNS) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'pending', NULL, NULL, NULL)",
        ).use { statement ->
            statement.setString(1, intent.id.value); statement.setString(2, intent.activationId.value); statement.setString(3, intent.producerId.value)
            statement.setString(4, intent.workflowId.value); statement.setString(5, intent.workflowVersionId.value); statement.setString(6, intent.executionId.value)
            statement.setString(7, intent.contextId.value); statement.setString(8, intent.journalBatchId.value); statement.setString(9, intent.createdAt.toString())
            statement.setString(10, encodeRevisionMap(intent.dependencyRevisions)); statement.setNullableString(11, intent.mapActivationId?.value); statement.setNullableString(12, intent.mapItemId)
            statement.setNullableInt(13, intent.mapItemIndex); statement.setNullableString(14, intent.mapItemKey); statement.setNullableString(15, intent.mapInputRevision?.value)
            statement.setNullableString(16, intent.parentContextId?.value); statement.setNullableString(17, intent.parentActivationId?.value); statement.setNullableString(18, intent.targetRegisterId?.value)
            statement.setString(19, encodeValues(intent.lexicalBindings)); statement.setNullableString(20, intent.discriminatorRevision?.value); statement.setNullableString(21, intent.branchTag)
            statement.executeUpdate()
        }
    }

    private fun findIntent(connection: Connection, id: ActivationIntentId): ActivationIntent? = connection.prepareStatement(
        "SELECT $INTENT_COLUMNS FROM activation_intents WHERE id = ?",
    ).use { statement -> statement.setString(1, id.value); statement.executeQuery().use { result -> if (result.next()) readIntent(result) else null } }

    private fun intentsForBatch(connection: Connection, batchId: JournalBatchId): List<ActivationIntent> = connection.prepareStatement(
        "SELECT $INTENT_COLUMNS FROM activation_intents WHERE journal_batch_id = ? ORDER BY rowid",
    ).use { statement -> statement.setString(1, batchId.value); statement.executeQuery().use { result -> buildList { while (result.next()) add(readIntent(result)) } } }

    private fun validateIntentForAssignment(intent: ActivationIntent, mutation: AssignmentMutation, batchId: JournalBatchId) {
        require(intent.workflowId == mutation.workflowId && intent.workflowVersionId == mutation.workflowVersionId)
        require(intent.executionId == mutation.executionId && intent.contextId == mutation.contextId)
        require(intent.journalBatchId == batchId) { "activation intent must point at its causative journal batch" }
    }

    private fun awakenDeferredForAssignment(connection: Connection, assignment: AssignmentMutation) {
        val candidates = connection.prepareStatement(
            "SELECT id, deferred_requirements_json FROM activation_intents WHERE execution_id = ? AND state = 'deferred'",
        ).use { statement ->
            statement.setString(1, assignment.executionId.value)
            statement.executeQuery().use { result -> buildList {
                while (result.next()) {
                    val requirements = result.getString(2)?.let(::decodeRegisterKeys).orEmpty()
                    if (RegisterKey(assignment.executionId, assignment.contextId, assignment.registerId) in requirements) add(result.getString(1))
                }
            } }
        }
        candidates.forEach { id -> connection.prepareStatement(
            "UPDATE activation_intents SET state = 'pending', deferred_requirements_json = NULL WHERE id = ?",
        ).use { statement -> statement.setString(1, id); statement.executeUpdate() } }
    }

    private fun ensureContext(connection: Connection, executionId: ExecutionId, contextId: ContextId, parent: ContextId?) {
        connection.prepareStatement(
            "INSERT OR IGNORE INTO contexts(execution_id, context_id, parent_context_id, created_at) VALUES (?, ?, ?, ?)",
        ).use { statement ->
            statement.setString(1, executionId.value); statement.setString(2, contextId.value); statement.setNullableString(3, parent?.value); statement.setString(4, clock.now().toString()); statement.executeUpdate()
        }
    }

    private fun exists(connection: Connection, sql: String, vararg args: String): Boolean = connection.prepareStatement(sql).use { statement ->
        args.forEachIndexed { index, value -> statement.setString(index + 1, value) }
        statement.executeQuery().use { it.next() }
    }

    private fun isState(connection: Connection, intentId: ActivationIntentId, state: String): Boolean = connection.prepareStatement(
        "SELECT state FROM activation_intents WHERE id = ?",
    ).use { statement -> statement.setString(1, intentId.value); statement.executeQuery().use { result -> result.next() && result.getString(1) == state } }

    private fun readAssignment(result: ResultSet): AssignmentMutation = AssignmentMutation(
        assignmentId = AssignmentId(result.getString(1)),
        workflowId = WorkflowId(result.getString(6)), workflowVersionId = WorkflowVersionId(result.getString(7)), executionId = ExecutionId(result.getString(8)),
        contextId = ContextId(result.getString(9)), registerId = RegisterId(result.getString(10)), value = CanonicalValueJson.decode(result.getString(11)), producerId = ProducerId(result.getString(12)),
        activationId = result.getString(13)?.let(::ActivationId), dependencyRevisions = decodeRevisionMap(result.getString(14)), invocationId = result.getString(15)?.let(::InvocationId), emissionId = result.getString(16)?.let(::EmissionId), causationId = result.getString(17),
        parentActivationId = result.getString(18)?.let(::ActivationId), discriminatorRevision = result.getString(19)?.let(::AssignmentId), revision = result.getLong(20), occurredAt = Instant.parse(result.getString(21)),
        mapActivationId = result.getString(22)?.let(::ActivationId), mapItemId = result.getString(23), mapItemIndex = result.getObject(24)?.let { (it as Number).toInt() }, mapItemKey = result.getString(25), mapInputRevision = result.getString(26)?.let(::AssignmentId), formatVersion = result.getInt(4), mutationOrdinal = result.getInt(3),
    )

    private fun readIntent(result: ResultSet): ActivationIntent = ActivationIntent(
        id = ActivationIntentId(result.getString(1)), activationId = ActivationId(result.getString(2)), producerId = ProducerId(result.getString(3)), workflowId = WorkflowId(result.getString(4)), workflowVersionId = WorkflowVersionId(result.getString(5)), executionId = ExecutionId(result.getString(6)), contextId = ContextId(result.getString(7)), journalBatchId = JournalBatchId(result.getString(8)), createdAt = Instant.parse(result.getString(9)), dependencyRevisions = decodeRevisionMap(result.getString(10)), mapActivationId = result.getString(11)?.let(::ActivationId), mapItemId = result.getString(12), mapItemIndex = result.getObject(13)?.let { (it as Number).toInt() }, mapItemKey = result.getString(14), mapInputRevision = result.getString(15)?.let(::AssignmentId), parentContextId = result.getString(16)?.let(::ContextId), parentActivationId = result.getString(17)?.let(::ActivationId), targetRegisterId = result.getString(18)?.let(::RegisterId), lexicalBindings = decodeValues(result.getString(19)), discriminatorRevision = result.getString(20)?.let(::AssignmentId), branchTag = result.getString(21),
    )

    private fun readActivation(result: ResultSet): ActivationRecord = ActivationRecord(
        activationId = ActivationId(result.getString(2)), intentId = ActivationIntentId(result.getString(1)), workflowId = WorkflowId(result.getString(3)), workflowVersionId = WorkflowVersionId(result.getString(4)), executionId = ExecutionId(result.getString(5)), producerId = ProducerId(result.getString(6)), contextId = ContextId(result.getString(7)), dependencyRevisions = decodeRevisionMap(result.getString(8)), status = ActivationRecord.Status.valueOf(result.getString(9)), startedAt = Instant.parse(result.getString(10)), completedAt = result.getString(11)?.let(Instant::parse), failure = result.getString(12), invocationId = result.getString(13)?.let(::InvocationId), attemptId = result.getString(14)?.let(::AttemptId), mapActivationId = result.getString(15)?.let(::ActivationId), mapItemId = result.getString(16), mapItemIndex = result.getObject(17)?.let { (it as Number).toInt() }, mapItemKey = result.getString(18), mapInputRevision = result.getString(19)?.let(::AssignmentId), parentActivationId = result.getString(20)?.let(::ActivationId), discriminatorRevision = result.getString(21)?.let(::AssignmentId), branchTag = result.getString(22),
    )

    private fun readProviderEvent(result: ResultSet): ProviderLifecycleEvent = ProviderLifecycleEvent(
        eventId = result.getString(1), type = ProviderEventType.valueOf(result.getString(2)), workflowId = WorkflowId(result.getString(3)), workflowVersionId = WorkflowVersionId(result.getString(4)), executionId = ExecutionId(result.getString(5)), contextId = ContextId(result.getString(6)), producerId = ProducerId(result.getString(7)), activationId = ActivationId(result.getString(8)), intentId = ActivationIntentId(result.getString(9)), invocationId = InvocationId(result.getString(10)), attemptId = result.getString(11)?.let(::AttemptId), emissionId = result.getString(12)?.let(::EmissionId), causationId = result.getString(13), value = result.getString(14)?.let(CanonicalValueJson::decode), error = result.getString(15)?.let(CanonicalValueJson::decode), diagnostic = result.getString(16), correlationId = result.getString(17), occurredAt = Instant.parse(result.getString(18)), providerId = result.getString(19), providerVersion = result.getObject(20)?.let { (it as Number).toInt() }, mapActivationId = result.getString(21)?.let(::ActivationId), mapItemId = result.getString(22), mapItemIndex = result.getObject(23)?.let { (it as Number).toInt() }, mapItemKey = result.getString(24), mapInputRevision = result.getString(25)?.let(::AssignmentId), parentActivationId = result.getString(26)?.let(::ActivationId), discriminatorRevision = result.getString(27)?.let(::AssignmentId),
    )

    private fun encodeRevisionMap(values: Map<RegisterId, AssignmentId>): String = CanonicalValueJson.encode(
        Value.ObjectValue(values.entries.sortedBy { it.key.value }.associate { (key, value) -> key.value to Value.StringValue(value.value) }),
    )

    private fun decodeRevisionMap(json: String): Map<RegisterId, AssignmentId> = decodeValues(json).map { (key, value) ->
        RegisterId(key) to AssignmentId((value as? Value.StringValue ?: error("revision vector contains a non-string assignment id")).value)
    }.toMap()

    private fun encodeValues(values: Map<String, Value>): String = CanonicalValueJson.encode(Value.ObjectValue(values.toSortedMap()))

    private fun decodeValues(json: String): Map<String, Value> = (CanonicalValueJson.decode(json) as? Value.ObjectValue)?.fields ?: error("expected encoded object")

    private fun encodeRegisterKeys(keys: Collection<RegisterKey>): String = CanonicalValueJson.encode(
        Value.ArrayValue(keys.sortedWith(compareBy({ it.executionId.value }, { it.contextId.value }, { it.registerId.value })).map {
            Value.ObjectValue(mapOf("executionId" to Value.StringValue(it.executionId.value), "contextId" to Value.StringValue(it.contextId.value), "registerId" to Value.StringValue(it.registerId.value)))
        }),
    )

    private fun decodeRegisterKeys(json: String): Set<RegisterKey> = (CanonicalValueJson.decode(json) as? Value.ArrayValue)?.values?.map {
        val fields = (it as? Value.ObjectValue)?.fields ?: error("invalid deferred requirement")
        RegisterKey(ExecutionId((fields["executionId"] as Value.StringValue).value), ContextId((fields["contextId"] as Value.StringValue).value), RegisterId((fields["registerId"] as Value.StringValue).value))
    }?.toSet() ?: error("expected deferred requirement array")

    private fun PreparedStatement.setNullableString(index: Int, value: String?) { if (value == null) setNull(index, Types.VARCHAR) else setString(index, value) }
    private fun PreparedStatement.setNullableInt(index: Int, value: Int?) { if (value == null) setNull(index, Types.INTEGER) else setInt(index, value) }
}

/** Common capitalization used by some integrations. */
typealias SQLiteJournalStore = SqliteJournalStore

private fun ActivationIntent.sameIdentityAndProvenance(other: ActivationIntent): Boolean = copy(createdAt = other.createdAt) == other
