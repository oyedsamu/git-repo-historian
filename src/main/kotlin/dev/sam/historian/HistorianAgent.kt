package dev.sam.historian

import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.models.Gemini
import com.google.adk.kt.models.Model

/**
 * The entry point that reads the environment. Everything it decides is passed explicitly to
 * [buildHistorian], which is what the tests exercise. Built lazily so that merely referencing
 * this object does not demand an API key or a git repository.
 *
 * Environment:
 *   HISTORIAN_REPO       repository to inspect (default: working directory)
 *   HISTORIAN_WIRING     DELEGATE | TOOL (default: DELEGATE)
 *   HISTORIAN_TRACE      1 to record and print a trace on exit
 *   HISTORIAN_MAX_TOOLS  cap on tool calls before the agent is refused further ones
 */
object HistorianAgent {

    private val repoPath: String =
        System.getenv("HISTORIAN_REPO") ?: System.getProperty("user.dir")

    private val wiring: Wiring =
        System.getenv("HISTORIAN_WIRING")?.uppercase()?.let(Wiring::valueOf) ?: Wiring.DELEGATE

    val trace: TraceRecorder? =
        if (System.getenv("HISTORIAN_TRACE") == "1") TraceRecorder() else null

    private val budget: ToolCallBudget? =
        System.getenv("HISTORIAN_MAX_TOOLS")?.toIntOrNull()?.let(::ToolCallBudget)

    private fun model(): Model = Gemini(
        name = "gemini-flash-latest",
        apiKey = System.getenv("GOOGLE_API_KEY")
            ?: error("GOOGLE_API_KEY is not set. Copy .env.example to .env and fill it in.")
    )

    val rootAgent: LlmAgent by lazy {
        buildHistorian(
            model = model(),
            gitService = GitService(Git.at(repoPath)),
            repoPath = repoPath,
            wiring = wiring,
            callbacks = AgentCallbacks.of(trace = trace, budget = budget),
        )
    }
}
