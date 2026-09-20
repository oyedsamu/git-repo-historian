package dev.sam.historian

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Fixture history, oldest first:
 *
 *   1. Ada  2020-01-01  Add greeting        src/a.txt
 *   2. Ada  2020-01-02  Extend greeting     src/a.txt
 *   3. Linus 2020-06-01 Add farewell        src/b.txt
 *   4. Linus 2020-06-02 Touch both files    src/a.txt, src/b.txt
 */
class GitServiceTest {

    private fun service(): GitService = GitService(Git.at(fixtureRepo().path))

    private fun fixtureRepo(): File {
        val dir = createTempDirectory("historian-fixture").toFile()
        val git = Git(dir)
        git.run("init", "-q", "-b", "main")
        File(dir, "src").mkdirs()

        // git log --since filters on committer date, so the fixture must set both dates.
        fun commit(author: String, email: String, date: String, message: String) {
            val stamp = "${date}T12:00:00"
            git.run("add", "-A")
            Git(dir, mapOf("GIT_COMMITTER_DATE" to stamp)).run(
                "-c", "user.name=$author",
                "-c", "user.email=$email",
                "commit", "-q",
                "--date=$stamp",
                "-m", message,
            )
        }

        File(dir, "src/a.txt").writeText("hello\n")
        commit("Ada Lovelace", "ada@example.com", "2020-01-01", "Add greeting")

        File(dir, "src/a.txt").writeText("hello\nthere\n")
        commit("Ada Lovelace", "ada@example.com", "2020-01-02", "Extend greeting")

        File(dir, "src/b.txt").writeText("bye\n")
        commit("Linus Torvalds", "linus@example.com", "2020-06-01", "Add farewell")

        File(dir, "src/a.txt").writeText("hello\nthere\nagain\n")
        File(dir, "src/b.txt").writeText("bye\nfor now\n")
        commit("Linus Torvalds", "linus@example.com", "2020-06-02", "Touch both files")

        return dir
    }

    // --- recentCommits ---

    @Test
    fun `recentCommits returns newest first`() {
        val commits = service().recentCommits(limit = 10)

        assertEquals(4, commits.size)
        assertEquals("Touch both files", commits[0].subject)
        assertEquals("Add greeting", commits[3].subject)
        assertEquals("Linus Torvalds", commits[0].author)
        assertEquals("2020-06-02", commits[0].date)
    }

    @Test
    fun `recentCommits clamps limits at both ends`() {
        assertEquals(4, service().recentCommits(limit = 10_000).size)
        assertEquals(1, service().recentCommits(limit = 0).size)
        assertEquals(1, service().recentCommits(limit = -5).size)
    }

    // --- commitsBy ---

    @Test
    fun `commitsBy matches an author fragment case-insensitively`() {
        val commits = service().commitsBy(author = "ada")

        assertEquals(2, commits.size)
        assertTrue(commits.all { it.author == "Ada Lovelace" })
    }

    @Test
    fun `commitsBy matches on email too`() {
        assertEquals(2, service().commitsBy(author = "linus@example.com").size)
    }

    @Test
    fun `commitsBy honours the since filter`() {
        assertEquals(2, service().commitsBy(author = "linus", since = "2020-03-01").size)
        assertEquals(0, service().commitsBy(author = "ada", since = "2020-03-01").size)
    }

    @Test
    fun `commitsBy returns nothing for an unknown author rather than failing`() {
        assertEquals(emptyList(), service().commitsBy(author = "nobody-at-all"))
    }

    @Test
    fun `commitsBy rejects an author that would be read as a git option`() {
        assertFailsWith<IllegalArgumentException> { service().commitsBy(author = "--output=/tmp/x") }
    }

    // --- whoTouched ---

    @Test
    fun `whoTouched ranks authors by commit count`() {
        val activity = service().whoTouched(path = "src/a.txt")

        assertEquals(2, activity.size)
        assertEquals("Ada Lovelace", activity[0].author)
        assertEquals(2, activity[0].commits)
        assertEquals("2020-01-01", activity[0].firstCommitDate)
        assertEquals("2020-01-02", activity[0].lastCommitDate)
        assertEquals(1, activity[1].commits)
    }

    @Test
    fun `whoTouched works on a directory`() {
        assertEquals(
            listOf("Ada Lovelace" to 2, "Linus Torvalds" to 2),
            service().whoTouched(path = "src").map { it.author to it.commits }.sortedBy { it.first },
        )
    }

    @Test
    fun `whoTouched returns nothing for a path with no history`() {
        assertEquals(emptyList(), service().whoTouched(path = "does/not/exist.txt"))
    }

    @Test
    fun `whoTouched rejects a path that would be read as a git option`() {
        assertFailsWith<IllegalArgumentException> { service().whoTouched(path = "--all") }
    }

    // --- filesChangedIn ---

    @Test
    fun `filesChangedIn reports every file with per-file counts`() {
        val lookup = service().filesChangedIn(sha = "HEAD")

        assertTrue(lookup.found)
        assertNull(lookup.error)
        val commit = assertNotNull(lookup.commit)
        assertEquals("Touch both files", commit.subject)
        assertEquals(listOf("src/a.txt", "src/b.txt"), commit.files.map { it.path }.sorted())
        assertEquals(2, commit.totalInsertions)
        assertEquals(0, commit.totalDeletions)
    }

    @Test
    fun `filesChangedIn accepts a short sha found via another tool`() {
        val svc = service()
        val sha = svc.recentCommits(limit = 1).single().sha

        assertEquals("Touch both files", svc.filesChangedIn(sha).commit?.subject)
    }

    @Test
    fun `filesChangedIn reports an unknown sha as a structured error`() {
        val lookup = service().filesChangedIn(sha = "deadbeef")

        assertFalse(lookup.found)
        assertNull(lookup.commit)
        assertTrue(lookup.error!!.contains("deadbeef"), "got: ${lookup.error}")
    }

    @Test
    fun `filesChangedIn reports an option-like sha as a structured error`() {
        val lookup = service().filesChangedIn(sha = "--help")

        assertFalse(lookup.found)
        assertTrue(lookup.error!!.contains("sha"), "got: ${lookup.error}")
    }

    // --- Git itself ---

    @Test
    fun `pointing at a non-repository fails loudly`() {
        val notARepo = createTempDirectory("historian-empty").toFile().path

        assertFailsWith<GitCommandFailed> { Git.at(notARepo) }
    }
}
