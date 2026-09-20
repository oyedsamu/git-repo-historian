package dev.sam.historian

import com.google.adk.kt.callbacks.AfterAgentCallback
import com.google.adk.kt.callbacks.AfterToolCallback
import com.google.adk.kt.callbacks.BeforeAgentCallback
import com.google.adk.kt.callbacks.BeforeToolCallback

/**
 * The callbacks to hang on every agent in the tree.
 *
 * Callbacks are per-agent, not global, so a nested agent that is built without them is
 * simply invisible — which is why [buildHistorian] and [releaseReportPipeline] both take
 * this and pass it down to every child they construct.
 */
data class AgentCallbacks(
    val beforeAgent: List<BeforeAgentCallback> = emptyList(),
    val afterAgent: List<AfterAgentCallback> = emptyList(),
    val beforeTool: List<BeforeToolCallback> = emptyList(),
    val afterTool: List<AfterToolCallback> = emptyList(),
) {
    companion object {
        val none = AgentCallbacks()

        fun of(trace: TraceRecorder? = null, budget: ToolCallBudget? = null) = AgentCallbacks(
            beforeAgent = listOfNotNull(trace?.beforeAgent()),
            afterAgent = listOfNotNull(trace?.afterAgent()),
            // The budget runs first so a refused call is never recorded as having started.
            beforeTool = listOfNotNull(budget?.beforeTool(), trace?.beforeTool()),
            afterTool = listOfNotNull(trace?.afterTool()),
        )
    }
}
