package dev.sam.historian

import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.models.Gemini
import com.google.adk.kt.models.Model

/**
 * The entry point that reads configuration. Everything it decides is passed explicitly to
 * [buildHistorian], which is what the tests exercise. Built lazily so that merely referencing
 * this object does not demand an API key or a git repository.
 *
 * Configuration comes from the environment, or from a `.env` file — see [Settings].
 *
 *   GOOGLE_API_KEY       Gemini API key (required)
 *   HISTORIAN_REPO       repository to inspect (default: working directory)
 *   HISTORIAN_WIRING     DELEGATE | TOOL (default: DELEGATE)
 *   HISTORIAN_TRACE      1 to record and print a trace on exit
 *   HISTORIAN_MAX_TOOLS  cap on tool calls before the agent is refused further ones
 */
object HistorianAgent {

    private val settings: Settings = Settings.load()

    private val repoPath: String =
        settings["HISTORIAN_REPO"] ?: System.getProperty("user.dir")

    private val wiring: Wiring =
        settings["HISTORIAN_WIRING"]?.uppercase()?.let(Wiring::valueOf) ?: Wiring.DELEGATE

    val trace: TraceRecorder? =
        if (settings["HISTORIAN_TRACE"] == "1") TraceRecorder() else null

    private val budget: ToolCallBudget? =
        settings["HISTORIAN_MAX_TOOLS"]?.toIntOrNull()?.let(::ToolCallBudget)

    private fun model(): Model = Gemini(
        name = "gemini-flash-latest",
        apiKey = settings.require(
            "GOOGLE_API_KEY",
            "Get a key from https://aistudio.google.com/apikey, then either export it or "
                + "put it in a .env file here or at ~/.config/git-repo-historian/.env",
        ),
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
