package dev.sam.historian

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BookmarkRulesTest {

    private val bookmark = Bookmark(sha = "abc1234", subject = "Fix the thing", note = "regression")

    // --- round trip ---

    @Test
    fun `encode then decode preserves bookmarks`() {
        val original = listOf(bookmark, bookmark.copy(sha = "def5678"))

        assertEquals(original, BookmarkRules.decode(BookmarkRules.encode(original)))
    }

    @Test
    fun `encode produces only plain strings so it survives the session service`() {
        val encoded = BookmarkRules.encode(listOf(bookmark))

        assertEquals(listOf(mapOf("sha" to "abc1234", "subject" to "Fix the thing", "note" to "regression")), encoded)
    }

    // --- decoding whatever state actually hands back ---

    @Test
    fun `decode treats absent state as no bookmarks`() {
        assertEquals(emptyList(), BookmarkRules.decode(null))
    }

    @Test
    fun `decode ignores state of an entirely unexpected type`() {
        assertEquals(emptyList(), BookmarkRules.decode("a string written by an older version"))
        assertEquals(emptyList(), BookmarkRules.decode(42))
        assertEquals(emptyList(), BookmarkRules.decode(mapOf("sha" to "abc")))
    }

    @Test
    fun `decode drops malformed rows but keeps the good ones`() {
        val stored = listOf(
            mapOf("sha" to "abc1234", "subject" to "Fix the thing", "note" to "regression"),
            mapOf("subject" to "no sha at all"),
            "not a map",
            null,
            mapOf("sha" to ""),
            mapOf("sha" to 99),
        )

        assertEquals(listOf(bookmark), BookmarkRules.decode(stored))
    }

    @Test
    fun `decode tolerates rows missing the optional fields`() {
        val decoded = BookmarkRules.decode(listOf(mapOf("sha" to "abc1234")))

        assertEquals(listOf(Bookmark(sha = "abc1234", subject = "", note = "")), decoded)
    }

    // --- add ---

    @Test
    fun `add appends to an empty list`() {
        val result = BookmarkRules.add(existing = emptyList(), bookmark = bookmark)

        assertTrue(result.saved)
        assertNull(result.error)
        assertEquals(bookmark, result.bookmark)
        assertEquals(1, result.totalBookmarks)
    }

    @Test
    fun `add rejects a sha that is already bookmarked`() {
        val result = BookmarkRules.add(existing = listOf(bookmark), bookmark = bookmark)

        assertFalse(result.saved)
        assertNull(result.bookmark)
        assertTrue(result.error!!.contains("abc1234"), "got: ${result.error}")
        assertEquals(1, result.totalBookmarks)
    }

    @Test
    fun `add rejects a duplicate sha even when the note differs`() {
        val result = BookmarkRules.add(
            existing = listOf(bookmark),
            bookmark = bookmark.copy(note = "a completely different reason"),
        )

        assertFalse(result.saved)
    }

    @Test
    fun `add allows a different sha`() {
        val result = BookmarkRules.add(
            existing = listOf(bookmark),
            bookmark = bookmark.copy(sha = "def5678"),
        )

        assertTrue(result.saved)
        assertEquals(2, result.totalBookmarks)
    }
}
