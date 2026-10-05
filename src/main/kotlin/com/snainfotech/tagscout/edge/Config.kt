package com.snainfotech.tagscout.edge

import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.FileInputStream
import java.nio.file.Files
import java.nio.file.Paths
import java.util.Properties

private val log = KotlinLogging.logger {}

/**
 * TagScoutEdge configuration.
 *
 * Loaded once at startup from two files, in this precedence order:
 *   1. config/edge-service.properties       — operational tuning
 *   2. secrets/reader-credentials.properties — reader username + password
 *
 * Both must exist; both are gitignored. The committed .example files alongside
 * them document the format. See README (future) for setup instructions.
 *
 * Design:
 *   - Fail fast: every required key is validated at load time. A missing key
 *     throws with the exact filename and key name — the operator fixes it in
 *     one shot rather than discovering issues at runtime.
 *   - Typed access: callers get String / Int / Long directly, never a raw
 *     Properties lookup. Type mistakes are a parse error at load time, not
 *     a mid-shift crash.
 *   - Immutable: all fields are val. The service doesn't reload mid-run; a
 *     config change requires a restart, which is correct for a background
 *     Windows service.
 */
data class Config(
    // Reader
    val readerUrl: String,
    val readerId: String,
    val readerPollMs: Long,
    val readerUsername: String,
    val readerPassword: String,

    // Firebase
    val firebaseServiceAccountPath: String,
    val firebaseCompanyId: String,

    // Pipeline tuning
    val dedupWindowMs: Long,
    val flushIntervalMs: Long,
    val soldRefreshMs: Long,

    // Operations
    val logLevel: String,
    val rssiThresholdDbm: Int
) {
    /**
     * Pretty-prints the config at startup for the operations log — WITHOUT
     * printing the reader password. Readable, scannable, one key per line.
     */
    fun describe(): String = buildString {
        appendLine("Loaded configuration:")
        appendLine("  reader.url                      = $readerUrl")
        appendLine("  reader.id                       = $readerId")
        appendLine("  reader.poll.ms                  = $readerPollMs")
        appendLine("  reader.username                 = $readerUsername")
        appendLine("  reader.password                 = ${"*".repeat(readerPassword.length)}")
        appendLine("  firebase.service.account.path   = $firebaseServiceAccountPath")
        appendLine("  firebase.company.id             = $firebaseCompanyId")
        appendLine("  dedup.window.ms                 = $dedupWindowMs")
        appendLine("  flush.interval.ms               = $flushIntervalMs")
        appendLine("  sold.refresh.ms                 = $soldRefreshMs")
        appendLine("  log.level                       = $logLevel")
        append   ("  rssi.threshold.dbm              = $rssiThresholdDbm")
    }

    companion object {
        private const val SERVICE_FILE = "config/edge-service.properties"
        private const val CREDENTIALS_FILE = "secrets/reader-credentials.properties"

        /**
         * Loads config from the default paths relative to the working directory.
         * Throws ConfigException with an actionable message on any failure.
         */
        fun load(): Config = load(SERVICE_FILE, CREDENTIALS_FILE)

        /**
         * Explicit-path variant — handy for tests and for future deployments
         * that want to put config somewhere other than ./config/.
         */
        fun load(servicePath: String, credentialsPath: String): Config {
            val service = readProperties(servicePath)
            val creds = readProperties(credentialsPath)

            return Config(
                readerUrl = service.requireString("reader.url", servicePath),
                readerId = service.requireString("reader.id", servicePath),
                readerPollMs = service.requireLong("reader.poll.ms", servicePath),
                readerUsername = creds.requireString("reader.username", credentialsPath),
                readerPassword = creds.requireString("reader.password", credentialsPath),

                firebaseServiceAccountPath =
                    service.requireString("firebase.service.account.path", servicePath),
                firebaseCompanyId = service.requireString("firebase.company.id", servicePath).also {
                    if (it == "REPLACE_WITH_FIREBASE_UID") {
                        throw ConfigException(
                            "firebase.company.id in $servicePath is still the placeholder " +
                                    "'REPLACE_WITH_FIREBASE_UID'. Set it to the Firebase user UID " +
                                    "for this customer."
                        )
                    }
                },

                dedupWindowMs = service.requireLong("dedup.window.ms", servicePath),
                flushIntervalMs = service.requireLong("flush.interval.ms", servicePath),
                soldRefreshMs = service.requireLong("sold.refresh.ms", servicePath),

                logLevel = service.requireString("log.level", servicePath).uppercase().also {
                    require(it in setOf("TRACE", "DEBUG", "INFO", "WARN", "ERROR")) {
                        "log.level in $servicePath must be TRACE/DEBUG/INFO/WARN/ERROR, got '$it'"
                    }
                },
                rssiThresholdDbm = service.requireInt("rssi.threshold.dbm", servicePath).also {
                    require(it <= 0) {
                        "rssi.threshold.dbm in $servicePath must be <= 0 (RSSI is negative dBm), got $it"
                    }
                }
            ).also { cfg ->
                // Guard against the reader-credentials placeholder sneaking through.
                if (cfg.readerPassword == "REPLACE_WITH_READER_PASSWORD") {
                    throw ConfigException(
                        "reader.password in $credentialsPath is still the placeholder " +
                                "'REPLACE_WITH_READER_PASSWORD'. Set it to the reader's actual " +
                                "admin password."
                    )
                }
            }
        }

        /**
         * Reads a .properties file into a Properties object, with a clear
         * error if the file is missing or unreadable.
         */
        private fun readProperties(path: String): Properties {
            val filePath = Paths.get(path).toAbsolutePath()
            if (!Files.exists(filePath)) {
                throw ConfigException(
                    "Config file not found: $filePath\n" +
                            "Copy the matching .example file next to it and fill in the values. " +
                            "See the file's header comment for details."
                )
            }
            val props = Properties()
            try {
                FileInputStream(filePath.toFile()).use { props.load(it) }
            } catch (e: Exception) {
                throw ConfigException("Could not read $filePath: ${e.message}", e)
            }
            return props
        }

        // ── Typed accessors ──────────────────────────────────────────

        private fun Properties.requireString(key: String, source: String): String {
            val raw = getProperty(key)
                ?: throw ConfigException("Missing required key '$key' in $source")
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) {
                throw ConfigException("Key '$key' in $source is empty")
            }
            return trimmed
        }

        private fun Properties.requireLong(key: String, source: String): Long {
            val raw = requireString(key, source)
            return raw.toLongOrNull()
                ?: throw ConfigException("Key '$key' in $source must be an integer, got '$raw'")
        }

        private fun Properties.requireInt(key: String, source: String): Int {
            val raw = requireString(key, source)
            return raw.toIntOrNull()
                ?: throw ConfigException("Key '$key' in $source must be an integer, got '$raw'")
        }
    }
}

/**
 * Thrown when configuration can't be loaded or is invalid. Message is written
 * to be read by a human — ideally the operator — not another program.
 */
class ConfigException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)