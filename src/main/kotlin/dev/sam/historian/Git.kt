package dev.sam.historian

import java.io.File
import java.util.concurrent.TimeUnit

class GitCommandFailed(val command: List<String>, val exitCode: Int, val stderr: String) :
    RuntimeException("git ${command.joinToString(" ")} failed ($exitCode): $stderr")

class Git(private val repo: File, private val env: Map<String, String> = emptyMap()) {

    fun run(vararg args: String): String {
        val builder = ProcessBuilder(listOf("git") + args)
            .directory(repo)
            .redirectErrorStream(false)
        builder.environment().putAll(env)
        val process = builder.start()

        val stdout = process.inputStream.bufferedReader().readText()
        val stderr = process.errorStream.bufferedReader().readText()

        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw GitCommandFailed(args.toList(), -1, "timed out after 30s")
        }
        if (process.exitValue() != 0) {
            throw GitCommandFailed(args.toList(), process.exitValue(), stderr.trim())
        }
        return stdout.trim()
    }

    companion object {
        fun at(path: String): Git {
            val dir = File(path)
            require(dir.isDirectory) { "Not a directory: $path" }
            val git = Git(dir)
            git.run("rev-parse", "--git-dir")
            return git
        }
    }
}
