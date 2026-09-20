package dev.sam.historian

import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.models.Gemini
import com.google.adk.kt.tools.AgentTool
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Structural tests: they assert how the agents are wired together without calling a model,
 * so they need no API key and cost nothing. They catch the mistake that is easy to make and
 * hard to spot at runtime — wiring the writer up BOTH ways, which would give the model two
 * different routes to the same specialist.
 */
class AgentWiringTest {

    private val model = Gemini(name = "gemini-flash-latest", apiKey = "not-used-in-these-tests")

    private fun historian(wiring: Wiring): LlmAgent = buildHistorian(
        model = model,
        gitService = GitService(Git.at(emptyRepo().path)),
        repoPath = "/somewhere/repo",
        wiring = wiring,
    )

    private fun emptyRepo(): File {
        val dir = createTempDirectory("wiring-fixture").toFile()
        Git(dir).run("init", "-q", "-b", "main")
        return dir
    }

    private fun LlmAgent.agentToolNames(): List<String> =
        tools.filterIsInstance<AgentTool>().map { it.agent.name }

    @Test
    fun `delegate wiring exposes the writer as a sub-agent only`() {
        val agent = historian(Wiring.DELEGATE)

        assertEquals(listOf("changelog_writer"), agent.subAgents.map { it.name })
        assertFalse(
            "changelog_writer" in agent.agentToolNames(),
            "The writer must not also be reachable as a tool.",
        )
    }

    @Test
    fun `tool wiring exposes the writer as a tool only`() {
        val agent = historian(Wiring.TOOL)

        assertEquals(emptyList(), agent.subAgents.map { it.name })
        assertTrue("changelog_writer" in agent.agentToolNames())
    }

    @Test
    fun `the release pipeline is reachable as a tool under both wirings`() {
        for (wiring in Wiring.entries) {
            assertTrue("release_report" in historian(wiring).agentToolNames(), "$wiring")
        }
    }

    @Test
    fun `both wirings keep every git and bookmark tool`() {
        val expected = setOf(
            "recentCommits", "commitsBy", "whoTouched", "filesChangedIn",
            "bookmarkCommit", "listBookmarks", "clearBookmarks",
        )

        for (wiring in Wiring.entries) {
            val names = historian(wiring).tools.map { it.name }.toSet()
            assertTrue(names.containsAll(expected), "$wiring is missing ${expected - names}")
        }
    }

    @Test
    fun `the instruction tells the model which route to use`() {
        assertTrue(historian(Wiring.DELEGATE).instruction.toString().contains("hand the work to"))
        assertTrue(historian(Wiring.TOOL).instruction.toString().contains("changelog_writer tool"))
    }

    @Test
    fun `the writer has no tools of its own in either wiring`() {
        assertEquals(emptyList(), changelogWriter(model).tools)
    }
}
