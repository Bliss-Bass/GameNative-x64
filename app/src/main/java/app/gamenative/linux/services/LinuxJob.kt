package app.gamenative.linux.services

import org.json.JSONArray
import org.json.JSONObject

/**
 * One job as the Linux Services addon defines it: what to run, and whether to bring it back.
 *
 * Only the fields the engine acts on are read. Triggers, window mode and display are the addon's
 * business -- it decides when a job runs and where its window goes -- and are ignored here, so the
 * addon can grow them without an engine release.
 */
data class LinuxJob(
    val id: String,
    val name: String,
    val enabled: Boolean,
    val type: Type,
    /** An argument vector, run as is. Exactly one of this and [shell] is set. */
    val argv: List<String>?,
    /** A shell script, run by /bin/sh. May span lines. */
    val shell: String?,
    val cwd: String,
    val env: Map<String, String>,
    val restart: Restart,
    val logMaxBytes: Long,
) {

    enum class Type(val key: String) {
        /** No display: a server, a broker, a script. */
        SERVICE("service"),

        /** A command in an xterm window, for programs someone watches. */
        TERMINAL("terminal"),

        /** A graphical application in its own window. */
        GUI("gui"),
        ;

        val hasWindow: Boolean get() = this != SERVICE

        companion object {
            fun of(key: String): Type? = entries.firstOrNull { it.key == key }
        }
    }

    enum class Policy(val key: String) {
        NEVER("never"),

        /** Restart on a non-zero exit or a signal, not on a clean exit. */
        ON_FAILURE("on-failure"),
        ALWAYS("always"),
        ;

        companion object {
            fun of(key: String): Policy? = entries.firstOrNull { it.key == key }
        }
    }

    /**
     * When to restart, and when to give up: more than [maxRestarts] restarts within [windowMs]
     * marks the job failed, since a job that dies on start would otherwise spin forever.
     */
    data class Restart(val policy: Policy, val maxRestarts: Int, val windowMs: Long)

    fun shouldRestart(exitCode: Int): Boolean = when (restart.policy) {
        Policy.NEVER -> false
        Policy.ON_FAILURE -> exitCode != 0
        Policy.ALWAYS -> true
    }

    companion object {

        private val ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")

        private const val DEFAULT_MAX_RESTARTS = 5
        private const val DEFAULT_WINDOW_SEC = 120L
        private const val DEFAULT_LOG_KB = 1024L
        private const val MAX_LOG_KB = 16L * 1024

        /** Parses a sync document, refusing all of it if any job is malformed or an id repeats. */
        fun parseAll(json: String): List<LinuxJob> {
            val array = JSONArray(json)
            val jobs = (0 until array.length()).map { parse(array.getJSONObject(it)) }
            val duplicate = jobs.groupBy { it.id }.entries.firstOrNull { it.value.size > 1 }
            require(duplicate == null) { "job id ${duplicate?.key} is listed twice" }
            return jobs
        }

        fun parse(o: JSONObject): LinuxJob {
            val id = o.getString("id")
            // The id names a log file and a script in the rootfs, so it must be a plain file name.
            require(ID.matches(id)) { "job id \"$id\" must be 1-64 of A-Z a-z 0-9 . _ -" }

            val type = o.optString("type", Type.SERVICE.key).let {
                Type.of(it) ?: throw IllegalArgumentException("job $id: unknown type \"$it\"")
            }

            val argv = o.optJSONArray("argv")?.let { a -> (0 until a.length()).map { a.getString(it) } }
                ?.takeIf { it.isNotEmpty() }
            val shell = o.optString("shell").takeIf { it.isNotBlank() }
            require((argv == null) != (shell == null)) { "job $id: set exactly one of argv and shell" }

            val env = o.optJSONObject("env")?.let { e ->
                e.keys().asSequence().associateWith { e.getString(it) }
            }.orEmpty()
            env.keys.forEach { require(Regex("[A-Za-z_][A-Za-z0-9_]*").matches(it)) { "job $id: bad env name \"$it\"" } }

            val restart = o.optJSONObject("restart") ?: JSONObject()
            val policy = restart.optString("policy", Policy.NEVER.key).let {
                Policy.of(it) ?: throw IllegalArgumentException("job $id: unknown restart policy \"$it\"")
            }

            val logKb = o.optJSONObject("logs")?.optLong("maxKb", DEFAULT_LOG_KB) ?: DEFAULT_LOG_KB

            return LinuxJob(
                id = id,
                name = o.optString("name").ifBlank { id },
                enabled = o.optBoolean("enabled", true),
                type = type,
                argv = argv,
                shell = shell,
                cwd = o.optString("cwd").ifBlank { "/root" },
                env = env,
                restart = Restart(
                    policy = policy,
                    maxRestarts = restart.optInt("maxRestarts", DEFAULT_MAX_RESTARTS).coerceIn(1, 100),
                    windowMs = restart.optLong("windowSec", DEFAULT_WINDOW_SEC).coerceIn(10, 3600) * 1000,
                ),
                logMaxBytes = logKb.coerceIn(16, MAX_LOG_KB) * 1024,
            )
        }
    }
}
