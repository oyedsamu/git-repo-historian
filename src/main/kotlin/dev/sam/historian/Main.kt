package dev.sam.historian

import com.google.adk.kt.runners.ReplRunner

fun main() {
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

    ReplRunner(HistorianAgent.rootAgent).start()
}
