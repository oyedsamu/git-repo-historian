package dev.sam.historian

import com.google.adk.kt.annotations.Param
import com.google.adk.kt.annotations.Tool

private const val UNIT = ""

data class CommitSummary(
    val sha: String,
    val date: String,
    val author: String,
    val subject: String,
)

data class FileChange(
    val path: String,
    val insertions: Int,
    val deletions: Int,
)

data class CommitDetail(
    val sha: String,
    val date: String,
    val author: String,
    val subject: String,
    val files: List<FileChange>,
    val totalInsertions: Int,
    val totalDeletions: Int,
)

data class CommitLookup(
    val found: Boolean,
    val error: String? = null,
    val commit: CommitDetail? = null,
)

data class AuthorActivity(
    val author: String,
    val commits: Int,
    val firstCommitDate: String,
    val lastCommitDate: String,
)

class GitService(private val git: Git) {

    @Tool(
        description = "List the most recent commits on the current branch, newest first. "
            + "Use this for general 'what happened lately' questions."
    )
    fun recentCommits(
        @Param("How many commits to return. Capped at 100.") limit: Int
    ): List<CommitSummary> = summaries("-n", limit.coerceIn(1, 100).toString())

    @Tool(
        description = "List commits by a specific author, newest first. The author argument is "
            + "matched as a case-insensitive substring of the author name or email, so 'samuel' "
            + "or 'oyedele' both work. Use this to answer 'what has <person> been working on'."
    )
    fun commitsBy(
        @Param("Author name or email fragment, e.g. 'samuel' or 'remi'") author: String,
        @Param("Optional start date as YYYY-MM-DD, or a relative phrase git understands such as '2 weeks ago'")
        since: String? = null,
        @Param("How many commits to return. Defaults to 20, capped at 100.") limit: Int? = null,
    ): List<CommitSummary> = summaries(
        *listOfNotNull(
            "-n", (limit ?: 20).coerceIn(1, 100).toString(),
            "--regexp-ignore-case",
            "--author=" + author.requireNotAnOption("author"),
            since?.let { "--since=" + it.requireNotAnOption("since") },
        ).toTypedArray()
    )

    @Tool(
        description = "Show which authors have changed a given file or directory, with how many "
            + "commits each made and when. Use this to answer 'who owns this' or 'who has been "
            + "touching this code'. Note that renames are not followed."
    )
    fun whoTouched(
        @Param("Repository-relative path to a file or directory, e.g. 'app/src/main'") path: String,
        @Param("Optional start date as YYYY-MM-DD, or a relative phrase such as '3 months ago'")
        since: String? = null,
    ): List<AuthorActivity> {
        val args = listOfNotNull(
            "--no-pager", "log", "--date=short", "--pretty=format:%an" + UNIT + "%ad",
            since?.let { "--since=" + it.requireNotAnOption("since") },
            "--", path.requireNotAnOption("path"),
        )
        val lines = log(*args.toTypedArray())
            .orEmpty()
            .lines()
            .filter { it.isNotBlank() }

        return lines
            .map { it.split(UNIT) }
            .groupBy({ it[0] }, { it[1] })
            .map { (author, dates) ->
                AuthorActivity(
                    author = author,
                    commits = dates.size,
                    firstCommitDate = dates.min(),
                    lastCommitDate = dates.max(),
                )
            }
            .sortedByDescending { it.commits }
    }

    @Tool(
        description = "Show the full detail of one commit: its metadata plus every file it "
            + "changed with per-file insertion and deletion counts. Use this after finding a "
            + "commit with another tool and needing to know what it actually did."
    )
    fun filesChangedIn(
        @Param("Commit SHA (short or full) or a ref such as 'HEAD' or 'master'") sha: String
    ): CommitLookup {
        val rev = try {
            sha.requireNotAnOption("sha")
        } catch (e: IllegalArgumentException) {
            return CommitLookup(found = false, error = e.message)
        }

        val format = "--pretty=format:%H" + UNIT + "%h" + UNIT + "%ad" + UNIT + "%an" + UNIT + "%s"
        val meta = try {
            git.run("show", "-s", "--date=short", format, rev)
        } catch (e: GitCommandFailed) {
            return CommitLookup(found = false, error = "No commit matching '" + sha + "'.")
        }

        val parts = meta.split(UNIT)
        val files = git.run("show", "--numstat", "--pretty=format:", parts[0])
            .lines()
            .filter { it.isNotBlank() }
            .map { line ->
                val columns = line.split("\t", limit = 3)
                FileChange(
                    path = columns[2],
                    insertions = columns[0].toIntOrNull() ?: 0,
                    deletions = columns[1].toIntOrNull() ?: 0,
                )
            }

        return CommitLookup(
            found = true,
            commit = CommitDetail(
                sha = parts[1],
                date = parts[2],
                author = parts[3],
                subject = parts[4],
                files = files,
                totalInsertions = files.sumOf { it.insertions },
                totalDeletions = files.sumOf { it.deletions },
            ),
        )
    }

    private fun summaries(vararg logArgs: String): List<CommitSummary> {
        val format = "--pretty=format:%h" + UNIT + "%ad" + UNIT + "%an" + UNIT + "%s"
        val out = log("--no-pager", "log", "--date=short", format, *logArgs) ?: return emptyList()
        return out.lines().filter { it.isNotBlank() }.map { line ->
            val parts = line.split(UNIT)
            CommitSummary(sha = parts[0], date = parts[1], author = parts[2], subject = parts[3])
        }
    }

    // A repository with no commits yet has no HEAD, and `git log` exits non-zero rather than
    // printing nothing. That is an empty history, not a failure, so it must not throw out of
    // a tool call.
    private fun log(vararg args: String): String? = try {
        git.run(*args)
    } catch (e: GitCommandFailed) {
        if (hasCommits()) throw e else null
    }

    private fun hasCommits(): Boolean =
        runCatching { git.run("rev-parse", "--verify", "HEAD") }.isSuccess
}

// The model supplies these strings, so a value starting with '-' would be read by git as an
// option rather than as data.
private fun String.requireNotAnOption(field: String): String {
    require(isNotBlank()) { "The '" + field + "' argument must not be blank." }
    require(!startsWith("-")) { "The '" + field + "' argument must not start with '-'." }
    return this
}
