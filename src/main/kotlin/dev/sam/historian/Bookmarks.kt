package dev.sam.historian

import com.google.adk.kt.annotations.Param
import com.google.adk.kt.annotations.Tool
import com.google.adk.kt.sessions.State
import com.google.adk.kt.tools.ToolContext

/**
 * Session state, as opposed to conversation history.
 *
 * History is transcript text the model re-reads every turn, and it falls out of the context
 * window as the conversation grows. State is a structured key/value map that our own code
 * reads and writes, and that the framework carries alongside the session.
 *
 * Keys are scoped by prefix: [State.USER_PREFIX] survives across sessions for the same user,
 * [State.APP_PREFIX] is shared by every user of the app, [State.TEMP_PREFIX] lasts one
 * invocation, and an unprefixed key lives for the session.
 */
private const val BOOKMARKS_KEY = State.USER_PREFIX + "bookmarks"

data class Bookmark(
    val sha: String,
    val subject: String,
    val note: String,
)

data class BookmarkResult(
    val saved: Boolean,
    val error: String? = null,
    val bookmark: Bookmark? = null,
    val totalBookmarks: Int = 0,
)

/**
 * The rules, with no framework types involved so they can be tested directly. A ToolContext
 * needs a fully built InvocationContext, so anything that touches one is effectively
 * untestable — the plumbing stays in [BookmarkService] and the decisions live here.
 */
internal object BookmarkRules {

    /**
     * State arrives back as whatever the session service round-tripped, which is plain
     * collections rather than our types, and which a previous version of this code may have
     * written in a different shape. Anything unreadable is dropped rather than throwing.
     */
    fun decode(stored: Any?): List<Bookmark> {
        val rows = stored as? List<*> ?: return emptyList()
        return rows.mapNotNull { row ->
            val map = row as? Map<*, *> ?: return@mapNotNull null
            val sha = map["sha"] as? String ?: return@mapNotNull null
            if (sha.isBlank()) return@mapNotNull null
            Bookmark(
                sha = sha,
                subject = map["subject"] as? String ?: "",
                note = map["note"] as? String ?: "",
            )
        }
    }

    fun encode(bookmarks: List<Bookmark>): List<Map<String, String>> = bookmarks.map {
        mapOf("sha" to it.sha, "subject" to it.subject, "note" to it.note)
    }

    fun add(existing: List<Bookmark>, bookmark: Bookmark): BookmarkResult =
        if (existing.any { it.sha == bookmark.sha }) {
            BookmarkResult(
                saved = false,
                error = "Commit ${bookmark.sha} is already bookmarked.",
                totalBookmarks = existing.size,
            )
        } else {
            BookmarkResult(
                saved = true,
                bookmark = bookmark,
                totalBookmarks = existing.size + 1,
            )
        }
}

class BookmarkService(private val git: GitService) {

    @Tool(
        description = "Save a commit to the user's bookmarks with a short note, so it can be "
            + "recalled later even after the conversation has moved on. Use this when the user "
            + "asks to remember, bookmark, pin or flag a commit."
    )
    fun bookmarkCommit(
        context: ToolContext,
        @Param("Commit SHA (short or full) to bookmark") sha: String,
        @Param("Why this commit matters, in a few words") note: String,
    ): BookmarkResult {
        val lookup = git.filesChangedIn(sha)
        val commit = lookup.commit
            ?: return BookmarkResult(saved = false, error = lookup.error ?: "Commit not found.")

        val existing = read(context)
        val outcome = BookmarkRules.add(
            existing = existing,
            bookmark = Bookmark(sha = commit.sha, subject = commit.subject, note = note),
        )
        if (outcome.saved) {
            write(context, existing + outcome.bookmark!!)
        }
        return outcome
    }

    @Tool(
        description = "List every commit the user has bookmarked so far, with the note they "
            + "gave for each. Use this when the user asks what they saved, pinned or flagged."
    )
    fun listBookmarks(context: ToolContext): List<Bookmark> = read(context)

    @Tool(
        description = "Remove every bookmark the user has saved. This cannot be undone, so it "
            + "asks the user to confirm before running.",
        requireConfirmation = true,
    )
    fun clearBookmarks(context: ToolContext): BookmarkResult {
        write(context, emptyList())
        return BookmarkResult(saved = true, totalBookmarks = 0)
    }

    // Writes made earlier in this same turn are still sitting in the pending delta, so they
    // have to be preferred over committed state or a second call in one turn loses the first.
    private fun read(context: ToolContext): List<Bookmark> = BookmarkRules.decode(
        context.actions.stateDelta[BOOKMARKS_KEY] ?: context.context.state[BOOKMARKS_KEY]
    )

    private fun write(context: ToolContext, bookmarks: List<Bookmark>) {
        context.actions.stateDelta[BOOKMARKS_KEY] = BookmarkRules.encode(bookmarks)
    }
}
