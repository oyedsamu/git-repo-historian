package dev.sam.historian

import com.google.adk.kt.callbacks.AfterAgentCallback
import com.google.adk.kt.callbacks.AfterToolCallback
import com.google.adk.kt.callbacks.BeforeAgentCallback
import com.google.adk.kt.callbacks.BeforeToolCallback
import com.google.adk.kt.callbacks.CallbackChoice
import com.google.adk.kt.events.EventActions
import java.util.concurrent.ConcurrentLinkedQueue

enum class Phase { START, END }

data class TraceEvent(
    val kind: String,
    val name: String,
    val phase: Phase,
    val atMs: Long,
    val detail: String? = null,
    val branch: String? = null,
)

data class Span(
    val kind: String,
    val name: String,
    val startMs: Long,
    val endMs: Long?,
    val detail: String?,
    /**
     * Only children of a [com.google.adk.kt.agents.ParallelAgent] get one, as
     * "<parallelAgent>.<child>". Everywhere else it is null, which makes it a reliable
     * marker for "this agent ran inside a parallel fan-out".
     */
    val branch: String? = null,
) {
    val parallelGroup: String? get() = branch?.substringBeforeLast('.')

    val durationMs: Long? get() = endMs?.let { it - startMs }

    /** True when the two spans were open at the same moment, so they really did overlap. */
    fun overlaps(other: Span): Boolean {
        val thisEnd = endMs ?: Long.MAX_VALUE
        val otherEnd = other.endMs ?: Long.MAX_VALUE
        return startMs < otherEnd && other.startMs < thisEnd
    }
}

/**
 * Records what the agents and tools actually did, with timings.
 *
 * A [com.google.adk.kt.agents.ParallelAgent] runs its children on different coroutines, so
 * callbacks fire concurrently and the collection has to be thread-safe. A plain
 * `mutableListOf` here would drop events under exactly the workload this is meant to measure.
 */
class TraceRecorder(private val clock: () -> Long = System::currentTimeMillis) {

    private val events = ConcurrentLinkedQueue<TraceEvent>()

    fun record(
        kind: String,
        name: String,
        phase: Phase,
        detail: String? = null,
        branch: String? = null,
    ) {
        events += TraceEvent(kind, name, phase, clock(), detail, branch)
    }

    fun events(): List<TraceEvent> = events.toList()

    /** Pairs each START with the next END of the same kind and name. */
    fun spans(): List<Span> {
        val open = mutableMapOf<Pair<String, String>, MutableList<TraceEvent>>()
        val spans = mutableListOf<Span>()

        for (event in events.toList().sortedBy { it.atMs }) {
            val key = event.kind to event.name
            when (event.phase) {
                Phase.START -> open.getOrPut(key) { mutableListOf() } += event
                Phase.END -> {
                    val start = open[key]?.removeFirstOrNull()
                    if (start == null) {
                        spans += Span(
                            event.kind, event.name, event.atMs, event.atMs,
                            event.detail, event.branch,
                        )
                    } else {
                        spans += Span(
                            kind = event.kind,
                            name = event.name,
                            startMs = start.atMs,
                            endMs = event.atMs,
                            detail = event.detail ?: start.detail,
                            branch = start.branch ?: event.branch,
                        )
                    }
                }
            }
        }

        // Anything still open never finished — a crash, or a step that was skipped.
        for ((key, starts) in open) {
            for (start in starts) {
                spans += Span(key.first, key.second, start.atMs, null, start.detail, start.branch)
            }
        }

        return spans.sortedBy { it.startMs }
    }

    fun render(): String {
        val spans = spans()
        if (spans.isEmpty()) return "(no trace recorded)"

        val origin = spans.minOf { it.startMs }
        val header = "%-6s  %-22s  %7s  %7s  %s".format("KIND", "NAME", "AT(ms)", "TOOK", "DETAIL")
        val rows = spans.map { span ->
            "%-6s  %-22s  %7d  %7s  %s".format(
                span.kind,
                span.name.take(22),
                span.startMs - origin,
                span.durationMs?.let { "${it}ms" } ?: "-",
                span.detail.orEmpty(),
            )
        }
        return (listOf(header) + rows).joinToString("\n")
    }

    /**
     * Sibling agents that were genuinely running at the same time.
     *
     * Overlap alone proves nothing, because a parent's span always contains its children's.
     * Only agents sharing a [Span.parallelGroup] are siblings in a fan-out, so those are the
     * only pairs whose overlap means real concurrency.
     */
    fun parallelPairs(): List<Pair<Span, Span>> =
        spans()
            .filter { it.kind == "agent" && it.parallelGroup != null }
            .groupBy { it.parallelGroup }
            .values
            .flatMap { group ->
                group.indices.flatMap { i ->
                    (i + 1 until group.size)
                        .filter { j -> group[i].overlaps(group[j]) }
                        .map { j -> group[i] to group[j] }
                }
            }

    // --- the callbacks themselves ---

    fun beforeAgent(): BeforeAgentCallback = BeforeAgentCallback { context ->
        record("agent", context.agentName, Phase.START, branch = context.branch)
        CallbackChoice.Continue(EventActions())
    }

    fun afterAgent(): AfterAgentCallback = AfterAgentCallback { context ->
        record("agent", context.agentName, Phase.END, branch = context.branch)
        CallbackChoice.Continue(Unit)
    }

    fun beforeTool(): BeforeToolCallback = BeforeToolCallback { _, tool, args ->
        record("tool", tool.name, Phase.START, args.entries.joinToString { "${it.key}=${it.value}" })
        CallbackChoice.Continue(args)
    }

    fun afterTool(): AfterToolCallback = AfterToolCallback { _, tool, _, response ->
        record("tool", tool.name, Phase.END)
        response
    }
}

/**
 * Stops a runaway agent. A model that keeps calling tools in a loop costs real money and
 * time, and nothing in the framework caps it by default.
 *
 * Returning [CallbackChoice.Break] skips the tool entirely and hands the model the map as
 * the tool's result, so it learns why it was stopped rather than silently retrying.
 */
class ToolCallBudget(private val maxCalls: Int) {

    private val used = java.util.concurrent.atomic.AtomicInteger(0)

    fun callsUsed(): Int = used.get()

    fun reset() = used.set(0)

    /** Returns null while within budget, or the refusal to hand back to the model. */
    fun consume(toolName: String): Map<String, String>? =
        if (used.incrementAndGet() > maxCalls) {
            mapOf(
                "error" to "Tool call budget of $maxCalls exhausted; '$toolName' was not run. "
                    + "Answer with what you already have, or tell the user what is missing."
            )
        } else {
            null
        }

    fun beforeTool(): BeforeToolCallback = BeforeToolCallback { _, tool, args ->
        consume(tool.name)
            ?.let { CallbackChoice.Break(it) }
            ?: CallbackChoice.Continue(args)
    }
}
