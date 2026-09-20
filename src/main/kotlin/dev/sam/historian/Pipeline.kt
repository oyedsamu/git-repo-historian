package dev.sam.historian

import com.google.adk.kt.agents.BaseAgent
import com.google.adk.kt.agents.Instruction
import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.agents.ParallelAgent
import com.google.adk.kt.agents.SequentialAgent
import com.google.adk.kt.models.Model
import com.google.adk.kt.types.Content

/**
 * A deterministic pipeline, as opposed to the LLM-driven routing in [buildHistorian].
 *
 * Nothing here asks a model who should run next. [SequentialAgent] runs its children in the
 * order given and [ParallelAgent] runs them concurrently; the shape is fixed by this code.
 * Data moves between steps through session state: an [LlmAgent] with an `outputKey` writes
 * its final text to that key, and a later step reads it back.
 *
 * Note that the `{key}` instruction templating documented for ADK's Python SDK does NOT
 * exist in the Kotlin SDK. The Kotlin equivalent is [Instruction] built from a lambda
 * ([Instruction.Provider]), which receives the context and reads state explicitly. That is
 * more verbose and considerably harder to get silently wrong.
 */

internal const val GATHERED_COMMITS = "gathered_commits"
internal const val DRAFT_NOTES = "draft_notes"
internal const val RISK_FLAGS = "risk_flags"

/** Builds an instruction at call time from whatever the previous steps left in state. */
internal fun stateInstruction(build: (Map<String, Any>) -> String): Instruction =
    Instruction { context -> Content.fromText("user", build(context.state)) }

internal fun missing(key: String) =
    "NOTHING WAS PROVIDED for '$key'. Say exactly that and stop. Do not invent content."

// The prompt builders are plain functions of the state map so they can be tested without a
// model or a ToolContext. A step that silently prompts on missing input is the failure mode
// worth guarding: the model would cheerfully invent a changelog from nothing.

internal fun draftPrompt(state: Map<String, Any>): String {
    val commits = state[GATHERED_COMMITS] as? String ?: return missing(GATHERED_COMMITS)
    return """
        Turn these commits into release notes.

        Group by theme rather than by date. Write each entry as the user-visible change,
        not the code change. Drop merge commits and pure test or tooling changes unless
        nothing else is left. Output the notes only, with no preamble.

        COMMITS:
        $commits
    """.trimIndent()
}

internal fun riskPrompt(state: Map<String, Any>): String {
    val commits = state[GATHERED_COMMITS] as? String ?: return missing(GATHERED_COMMITS)
    return """
        Review these commits for release risk. Flag anything touching payments, money
        movement, authentication, KYC, migrations, or dependency or SDK versions, and
        anything whose subject suggests a revert or a hotfix.

        Output one line per flagged commit as: sha | why it is risky
        If nothing is risky, output exactly: NO RISKS FLAGGED

        COMMITS:
        $commits
    """.trimIndent()
}

internal fun assemblePrompt(state: Map<String, Any>): String = """
    Combine the two sections below into a single release report, under the headings
    "What changed" and "Worth a closer look". Do not add anything that is not in them.
    If the risk section says NO RISKS FLAGGED, say that nothing stood out.

    RELEASE NOTES:
    ${state[DRAFT_NOTES] as? String ?: missing(DRAFT_NOTES)}

    RISK REVIEW:
    ${state[RISK_FLAGS] as? String ?: missing(RISK_FLAGS)}
""".trimIndent()

internal fun commitGatherer(
    model: Model,
    gitService: GitService,
    callbacks: AgentCallbacks = AgentCallbacks.none,
): LlmAgent = LlmAgent(
    name = "commit_gatherer",
    description = "Collects the commits a release report should cover.",
    model = model,
    instruction = Instruction(
        """
        You collect raw material for a release report. Work out which commits the user is
        asking about and fetch them with your tools.

        Output ONLY the commits, one per line, as: sha | date | author | subject
        No preamble, no grouping, no commentary. If you cannot find any commits, output
        exactly: NO COMMITS FOUND
        """.trimIndent()
    ),
    tools = gitService.generatedTools(),
    outputKey = GATHERED_COMMITS,
    beforeAgentCallbacks = callbacks.beforeAgent,
    afterAgentCallbacks = callbacks.afterAgent,
    beforeToolCallbacks = callbacks.beforeTool,
    afterToolCallbacks = callbacks.afterTool,
)

internal fun changelogDrafter(model: Model, callbacks: AgentCallbacks = AgentCallbacks.none): LlmAgent = LlmAgent(
    name = "changelog_drafter",
    description = "Turns a gathered commit list into user-facing release notes.",
    model = model,
    instruction = stateInstruction(::draftPrompt),
    outputKey = DRAFT_NOTES,
    beforeAgentCallbacks = callbacks.beforeAgent,
    afterAgentCallbacks = callbacks.afterAgent,
    beforeToolCallbacks = callbacks.beforeTool,
    afterToolCallbacks = callbacks.afterTool,
)

internal fun riskReviewer(model: Model, callbacks: AgentCallbacks = AgentCallbacks.none): LlmAgent = LlmAgent(
    name = "risk_reviewer",
    description = "Flags commits in a release that deserve extra scrutiny.",
    model = model,
    instruction = stateInstruction(::riskPrompt),
    outputKey = RISK_FLAGS,
    beforeAgentCallbacks = callbacks.beforeAgent,
    afterAgentCallbacks = callbacks.afterAgent,
    beforeToolCallbacks = callbacks.beforeTool,
    afterToolCallbacks = callbacks.afterTool,
)

internal fun reportAssembler(model: Model, callbacks: AgentCallbacks = AgentCallbacks.none): LlmAgent = LlmAgent(
    name = "report_assembler",
    description = "Combines the release notes and the risk review into one report.",
    model = model,
    instruction = stateInstruction(::assemblePrompt),
    beforeAgentCallbacks = callbacks.beforeAgent,
    afterAgentCallbacks = callbacks.afterAgent,
    beforeToolCallbacks = callbacks.beforeTool,
    afterToolCallbacks = callbacks.afterTool,
)

/**
 * gather -> (draft notes || review risk) -> assemble.
 *
 * The two middle steps both read [GATHERED_COMMITS] and neither reads the other's output,
 * so they run concurrently and write to separate keys.
 */
fun releaseReportPipeline(
    model: Model,
    gitService: GitService,
    callbacks: AgentCallbacks = AgentCallbacks.none,
): BaseAgent = SequentialAgent(
    name = "release_report",
    description = "Produces a release report for a set of commits: what changed, plus the "
        + "commits worth a closer look. Give it a plain description of the range, for "
        + "example 'the last 20 commits' or 'everything Samuel did this month'.",
    subAgents = listOf(
        commitGatherer(model, gitService, callbacks),
        ParallelAgent(
            name = "release_analysis",
            description = "Drafts notes and reviews risk at the same time.",
            subAgents = listOf(changelogDrafter(model, callbacks), riskReviewer(model, callbacks)),
            beforeAgentCallbacks = callbacks.beforeAgent,
            afterAgentCallbacks = callbacks.afterAgent,
        ),
        reportAssembler(model, callbacks),
    ),
    beforeAgentCallbacks = callbacks.beforeAgent,
    afterAgentCallbacks = callbacks.afterAgent,
)
