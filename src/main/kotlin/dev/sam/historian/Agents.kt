package dev.sam.historian

import com.google.adk.kt.agents.Instruction
import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.models.Model
import com.google.adk.kt.tools.AgentTool
import java.time.LocalDate

/**
 * The two ways to wire one agent to another.
 *
 *  - [DELEGATE]: the writer is a sub-agent. The model emits `transfer_to_agent` and control
 *    genuinely moves — the writer then owns the conversation and answers the user directly,
 *    until it transfers back. One LLM call, but the historian is no longer steering.
 *
 *  - [TOOL]: the writer is wrapped in an [AgentTool]. The historian calls it like any other
 *    function, gets a string back, and keeps control throughout. Costs an extra LLM call per
 *    invocation, but the historian decides what the writer is given and what reaches the user.
 */
enum class Wiring { DELEGATE, TOOL }

/** A specialist with no tools. It only reshapes commit data somebody else gathered. */
fun changelogWriter(model: Model, callbacks: AgentCallbacks = AgentCallbacks.none): LlmAgent = LlmAgent(
    name = "changelog_writer",
    description = "Writes release notes. Turns a list of commits that has ALREADY been "
        + "gathered into notes grouped by theme and written for a non-technical reader. "
        + "Has no access to the repository and cannot look commits up itself.",
    model = model,
    instruction = Instruction(
        """
        You turn raw commit lists into release notes.

        Group commits by theme rather than listing them in date order. Write each entry as
        the user-visible change, not as the code change: "Fixed a crash when switching
        accounts", not "Refactored AccountViewModel". Drop merge commits and pure test or
        tooling changes unless nothing else is left.

        You cannot read the repository. If you were not given the commits, say so and ask
        for them rather than inventing entries.
        """.trimIndent()
    ),
    beforeAgentCallbacks = callbacks.beforeAgent,
    afterAgentCallbacks = callbacks.afterAgent,
)

fun buildHistorian(
    model: Model,
    gitService: GitService,
    repoPath: String,
    wiring: Wiring,
    today: LocalDate = LocalDate.now(),
    callbacks: AgentCallbacks = AgentCallbacks.none,
): LlmAgent {
    val writer = changelogWriter(model, callbacks)

    val handoffNote = when (wiring) {
        Wiring.DELEGATE -> (
            "When the user asks for release notes or a changelog, gather the relevant "
                + "commits first, then hand the work to changelog_writer."
            )

        Wiring.TOOL -> (
            "When the user asks for release notes or a changelog, gather the relevant "
                + "commits first, then pass them to the changelog_writer tool and relay "
                + "what it returns. Never write the notes yourself."
            )
    }

    return LlmAgent(
        name = "git_repo_historian",
        description = "Answers questions about the history of a local git repository.",
        model = model,
        instruction = Instruction(
            """
            You are a git repository historian for the repository at $repoPath.
            Today is $today, so resolve relative phrases like "last month" against that date.

            Answer questions about the repository's history using the tools available to
            you. You may call several tools in sequence: for example, find a commit with
            recentCommits or commitsBy, then pass its sha to filesChangedIn to see what it
            changed.

            Never guess at commits, authors, dates or file names. If a tool returned
            nothing, say so plainly rather than filling the gap, and suggest what you would
            need in order to answer. If a tool reports an error, relay what went wrong
            instead of retrying the same call.

            The user can bookmark commits they care about. Bookmarks persist beyond this
            conversation, so prefer listBookmarks over scrolling back through what was said
            earlier when the user asks what they saved.

            When the user asks for a release report, use the release_report tool and pass it
            a plain description of the range they asked about. It gathers the commits itself,
            so do not gather them first.

            $handoffNote

            Keep answers short and concrete, and cite short SHAs when relevant.
            """.trimIndent()
        ),
        tools = gitService.generatedTools() +
            BookmarkService(gitService).generatedTools() +
            AgentTool(releaseReportPipeline(model, gitService, callbacks)) +
            if (wiring == Wiring.TOOL) listOf(AgentTool(writer)) else emptyList(),
        subAgents = if (wiring == Wiring.DELEGATE) listOf(writer) else emptyList(),
        beforeAgentCallbacks = callbacks.beforeAgent,
        afterAgentCallbacks = callbacks.afterAgent,
        beforeToolCallbacks = callbacks.beforeTool,
        afterToolCallbacks = callbacks.afterTool,
    )
}
