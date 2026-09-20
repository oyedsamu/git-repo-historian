package dev.sam.historian

import java.io.File

/**
 * Configuration, read from the real environment first and from a `.env` file second.
 *
 * Sourcing `.env` in the shell only works when you are standing in the project directory.
 * Someone who installed the tool is somewhere else entirely, so the file has to be found and
 * parsed here. The real environment still wins, so `GOOGLE_API_KEY=... git-repo-historian`
 * does what it looks like it does.
 */
class Settings(
    private val fileValues: Map<String, String> = emptyMap(),
    private val system: (String) -> String? = System::getenv,
) {
    operator fun get(key: String): String? =
        system(key)?.takeIf { it.isNotBlank() } ?: fileValues[key]?.takeIf { it.isNotBlank() }

    fun require(key: String, hint: String): String = get(key) ?: error("$key is not set. $hint")

    companion object {

        /**
         * Where a `.env` may live, nearest first: the working directory, then the user's
         * config directory, so an installed copy has somewhere permanent to keep the key.
         */
        fun candidatePaths(
            workingDir: File = File(System.getProperty("user.dir")),
            // HOME first: macOS JVMs derive `user.home` from the OS user record and ignore
            // the environment, which is wrong under sudo -u, in containers and in CI.
            home: File = File(System.getenv("HOME") ?: System.getProperty("user.home")),
            xdgConfigHome: String? = System.getenv("XDG_CONFIG_HOME"),
        ): List<File> = listOf(
            File(workingDir, ".env"),
            File(xdgConfigHome?.let(::File) ?: File(home, ".config"), "git-repo-historian/.env"),
        )

        fun load(paths: List<File> = candidatePaths()): Settings {
            val merged = mutableMapOf<String, String>()
            // Nearest file wins, so later ones must not overwrite earlier ones. A blank value
            // counts as unset — the same rule `get` applies to the environment — so a key
            // left empty nearby still falls through to the next file.
            for (path in paths) {
                if (!path.isFile) continue
                val parsed = runCatching { parse(path.readText()) }.getOrElse { emptyMap() }
                for ((key, value) in parsed) {
                    if (value.isNotBlank()) merged.putIfAbsent(key, value)
                }
            }
            return Settings(merged)
        }

        /**
         * A deliberately small `.env` parser: `KEY=VALUE` per line, `#` comments, an optional
         * `export` prefix, and optional matching quotes around the value. No variable
         * expansion and no multi-line values — anything fancier belongs in the real
         * environment.
         */
        fun parse(text: String): Map<String, String> = buildMap {
            for (rawLine in text.lines()) {
                val line = rawLine.trim()
                if (line.isEmpty() || line.startsWith("#")) continue

                val withoutExport = line.removePrefix("export ").trim()
                val separator = withoutExport.indexOf('=')
                if (separator <= 0) continue

                val key = withoutExport.take(separator).trim()
                if (key.isEmpty() || !key.all { it.isLetterOrDigit() || it == '_' }) continue

                val value = withoutExport.substring(separator + 1).trim().let(::unquote)
                put(key, value)
            }
        }

        private fun unquote(value: String): String {
            val quoted = value.length >= 2 &&
                value.first() == value.last() &&
                (value.first() == '"' || value.first() == '\'')
            // An unquoted value may carry a trailing comment; a quoted one is taken verbatim.
            return if (quoted) {
                value.substring(1, value.length - 1)
            } else {
                value.substringBefore(" #").trim()
            }
        }
    }
}
