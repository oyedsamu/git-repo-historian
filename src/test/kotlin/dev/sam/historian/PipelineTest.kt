package dev.sam.historian

import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.agents.ParallelAgent
import com.google.adk.kt.agents.SequentialAgent
import com.google.adk.kt.models.Gemini
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PipelineTest {

    private val model = Gemini(name = "gemini-flash-latest", apiKey = "not-used-in-these-tests")

    private val commits = "abc1234 | 2026-01-01 | Ada | Add a thing"

    private fun gitService(): GitService {
        val dir = createTempDirectory("pipeline-fixture").toFile()
        Git(dir).run("init", "-q", "-b", "main")
        return GitService(Git.at(dir.path))
    }

    // --- prompt builders: the step boundary where data actually moves ---

    @Test
    fun `draft prompt carries the gathered commits through`() {
        val prompt = draftPrompt(mapOf(GATHERED_COMMITS to commits))

        assertTrue(prompt.contains(commits))
        assertFalse(prompt.contains("NOTHING WAS PROVIDED"))
    }

    @Test
    fun `risk prompt carries the gathered commits through`() {
        assertTrue(riskPrompt(mapOf(GATHERED_COMMITS to commits)).contains(commits))
    }

    @Test
    fun `assemble prompt carries both upstream outputs through`() {
        val prompt = assemblePrompt(
            mapOf(DRAFT_NOTES to "the notes", RISK_FLAGS to "the risks")
        )

        assertTrue(prompt.contains("the notes"))
        assertTrue(prompt.contains("the risks"))
        assertFalse(prompt.contains("NOTHING WAS PROVIDED"))
    }

    // --- the failure that matters: a step running on input that never arrived ---

    @Test
    fun `a step with no upstream output refuses instead of inviting invention`() {
        for (prompt in listOf(draftPrompt(emptyMap()), riskPrompt(emptyMap()))) {
            assertTrue(prompt.contains("NOTHING WAS PROVIDED"), "got: $prompt")
            assertTrue(prompt.contains("Do not invent"), "got: $prompt")
        }
    }

    @Test
    fun `assemble names whichever upstream output is missing`() {
        val onlyNotes = assemblePrompt(mapOf(DRAFT_NOTES to "the notes"))

        assertTrue(onlyNotes.contains("the notes"))
        assertTrue(onlyNotes.contains(RISK_FLAGS), "should name the missing key")
    }

    @Test
    fun `state holding an unexpected type is treated as missing`() {
        val prompt = draftPrompt(mapOf(GATHERED_COMMITS to listOf("not", "a", "string")))

        assertTrue(prompt.contains("NOTHING WAS PROVIDED"))
    }

    // --- structure: the order is ours, not the model's ---

    @Test
    fun `the pipeline runs gather then analysis then assemble`() {
        val pipeline = releaseReportPipeline(model, gitService()) as SequentialAgent

        assertEquals(
            listOf("commit_gatherer", "release_analysis", "report_assembler"),
            pipeline.subAgents.map { it.name },
        )
    }

    @Test
    fun `the two analysis steps run in parallel`() {
        val pipeline = releaseReportPipeline(model, gitService()) as SequentialAgent
        val analysis = pipeline.subAgents[1]

        assertTrue(analysis is ParallelAgent, "expected a ParallelAgent, got ${analysis::class}")
        assertEquals(
            listOf("changelog_drafter", "risk_reviewer"),
            analysis.subAgents.map { it.name },
        )
    }

    @Test
    fun `every producing step writes to a distinct output key`() {
        val pipeline = releaseReportPipeline(model, gitService()) as SequentialAgent
        val gatherer = pipeline.subAgents[0] as LlmAgent
        val analysis = pipeline.subAgents[1].subAgents.map { it as LlmAgent }

        val keys = listOf(gatherer.outputKey) + analysis.map { it.outputKey }

        assertEquals(listOf(GATHERED_COMMITS, DRAFT_NOTES, RISK_FLAGS), keys)
        assertEquals(keys.size, keys.toSet().size, "output keys must not collide")
    }

    @Test
    fun `the final step writes no output key because its answer is the result`() {
        val pipeline = releaseReportPipeline(model, gitService()) as SequentialAgent

        assertNull((pipeline.subAgents[2] as LlmAgent).outputKey)
    }

    @Test
    fun `only the gathering step has repository tools`() {
        val pipeline = releaseReportPipeline(model, gitService()) as SequentialAgent

        assertTrue((pipeline.subAgents[0] as LlmAgent).tools.isNotEmpty())
        for (agent in pipeline.subAgents[1].subAgents) {
            assertEquals(emptyList(), (agent as LlmAgent).tools, "${agent.name} should have none")
        }
        assertEquals(emptyList(), (pipeline.subAgents[2] as LlmAgent).tools)
    }

    @Test
    fun `the historian can reach the pipeline as a tool`() {
        val historian = buildHistorian(
            model = model,
            gitService = gitService(),
            repoPath = "/somewhere/repo",
            wiring = Wiring.DELEGATE,
        )

        assertTrue(historian.tools.any { it.name == "release_report" })
    }

    @Test
    fun `fixture sanity - an empty repo really has no commits`() {
        assertEquals(emptyList(), gitService().recentCommits(limit = 5))
    }
}
