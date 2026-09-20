package dev.sam.historian

import com.google.adk.kt.runners.ReplRunner

fun main() {
    // Configuration problems are the user's to fix, not a bug to report, so they get a
    // message rather than a stack trace.
    val agent = try {
        HistorianAgent.rootAgent
    } catch (e: IllegalStateException) {
        System.err.println(e.message ?: "Could not start.")
        kotlin.system.exitProcess(1)
    } catch (e: IllegalArgumentException) {
        System.err.println(e.message ?: "Could not start.")
        kotlin.system.exitProcess(1)
    } catch (e: GitCommandFailed) {
        System.err.println(
            "Not a git repository: ${System.getProperty("user.dir")}\n" +
                "Run this inside a repository, or set HISTORIAN_REPO to point at one."
        )
        kotlin.system.exitProcess(1)
    }

    val trace = HistorianAgent.trace

    if (trace != null) {
        // ReplRunner owns the loop and gives us no completion hook, so the trace is printed
        // on the way out.
        Runtime.getRuntime().addShutdownHook(
            Thread {
                println()
                println("--- trace ---")
                println(trace.render())
                val parallel = trace.parallelPairs()
                if (parallel.isNotEmpty()) {
                    println()
                    println("ran in parallel:")
                    parallel.forEach { (a, b) -> println("  ${a.name} || ${b.name}") }
                }
            }
        )
    }

    ReplRunner(agent).start()
}
