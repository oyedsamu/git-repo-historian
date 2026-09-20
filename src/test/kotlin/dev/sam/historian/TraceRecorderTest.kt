package dev.sam.historian

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TraceRecorderTest {

    /** A clock we drive by hand, so timings are assertions rather than measurements. */
    private class FakeClock(var now: Long = 0) : () -> Long {
        override fun invoke(): Long = now
    }

    // --- span pairing ---

    @Test
    fun `a start and end become one span with a duration`() {
        val clock = FakeClock()
        val trace = TraceRecorder(clock)

        trace.record("tool", "recentCommits", Phase.START, detail = "limit=5")
        clock.now = 30
        trace.record("tool", "recentCommits", Phase.END)

        val span = trace.spans().single()
        assertEquals("recentCommits", span.name)
        assertEquals(0, span.startMs)
        assertEquals(30, span.durationMs)
        assertEquals("limit=5", span.detail)
    }

    @Test
    fun `a step that never finished is kept with no duration`() {
        val clock = FakeClock()
        val trace = TraceRecorder(clock)

        trace.record("agent", "crashed_agent", Phase.START)

        val span = trace.spans().single()
        assertNull(span.endMs)
        assertNull(span.durationMs)
    }

    @Test
    fun `repeated calls to the same tool become separate spans`() {
        val clock = FakeClock()
        val trace = TraceRecorder(clock)

        repeat(3) {
            trace.record("tool", "recentCommits", Phase.START)
            clock.now += 10
            trace.record("tool", "recentCommits", Phase.END)
            clock.now += 5
        }

        assertEquals(3, trace.spans().size)
        assertTrue(trace.spans().all { it.durationMs == 10L })
    }

    // --- overlap ---

    @Test
    fun `overlap is true only when the spans were open at the same moment`() {
        val a = Span("agent", "a", startMs = 0, endMs = 100, detail = null)
        val overlapping = Span("agent", "b", startMs = 50, endMs = 150, detail = null)
        val after = Span("agent", "c", startMs = 100, endMs = 200, detail = null)

        assertTrue(a.overlaps(overlapping))
        assertTrue(overlapping.overlaps(a))
        assertTrue(!a.overlaps(after), "touching at the boundary is not overlapping")
    }

    @Test
    fun `an unfinished span overlaps everything that started after it`() {
        val open = Span("agent", "a", startMs = 0, endMs = null, detail = null)
        val later = Span("agent", "b", startMs = 5_000, endMs = 6_000, detail = null)

        assertTrue(open.overlaps(later))
    }

    // --- real parallelism, not mere nesting ---

    @Test
    fun `a parent containing its child is not reported as parallel`() {
        val clock = FakeClock()
        val trace = TraceRecorder(clock)

        trace.record("agent", "parent", Phase.START)
        clock.now = 10
        trace.record("agent", "child", Phase.START)
        clock.now = 20
        trace.record("agent", "child", Phase.END)
        clock.now = 30
        trace.record("agent", "parent", Phase.END)

        assertEquals(emptyList(), trace.parallelPairs())
    }

    @Test
    fun `siblings in the same fan-out that overlap are reported as parallel`() {
        val clock = FakeClock()
        val trace = TraceRecorder(clock)

        trace.record("agent", "drafter", Phase.START, branch = "analysis.drafter")
        clock.now = 5
        trace.record("agent", "reviewer", Phase.START, branch = "analysis.reviewer")
        clock.now = 40
        trace.record("agent", "reviewer", Phase.END, branch = "analysis.reviewer")
        clock.now = 50
        trace.record("agent", "drafter", Phase.END, branch = "analysis.drafter")

        val pair = trace.parallelPairs().single()
        assertEquals(setOf("drafter", "reviewer"), setOf(pair.first.name, pair.second.name))
    }

    @Test
    fun `siblings in the same fan-out that did not overlap are not reported`() {
        val clock = FakeClock()
        val trace = TraceRecorder(clock)

        trace.record("agent", "drafter", Phase.START, branch = "analysis.drafter")
        clock.now = 10
        trace.record("agent", "drafter", Phase.END, branch = "analysis.drafter")
        trace.record("agent", "reviewer", Phase.START, branch = "analysis.reviewer")
        clock.now = 20
        trace.record("agent", "reviewer", Phase.END, branch = "analysis.reviewer")

        assertEquals(emptyList(), trace.parallelPairs())
    }

    @Test
    fun `agents in different fan-outs are never paired`() {
        val clock = FakeClock()
        val trace = TraceRecorder(clock)

        trace.record("agent", "a", Phase.START, branch = "groupOne.a")
        trace.record("agent", "b", Phase.START, branch = "groupTwo.b")
        clock.now = 10
        trace.record("agent", "a", Phase.END, branch = "groupOne.a")
        trace.record("agent", "b", Phase.END, branch = "groupTwo.b")

        assertEquals(emptyList(), trace.parallelPairs())
    }

    @Test
    fun `the parallel group is the parent agent name`() {
        val span = Span("agent", "drafter", 0, 10, null, branch = "release_analysis.drafter")

        assertEquals("release_analysis", span.parallelGroup)
    }

    // --- the property that made a thread-safe queue necessary ---

    @Test
    fun `concurrent recording loses no events`() {
        val trace = TraceRecorder()
        val threads = 8
        val perThread = 500
        val pool = Executors.newFixedThreadPool(threads)
        val ready = CountDownLatch(threads)

        repeat(threads) { t ->
            pool.execute {
                ready.countDown()
                ready.await()
                repeat(perThread) { i -> trace.record("tool", "t$t-$i", Phase.START) }
            }
        }
        pool.shutdown()
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "workers did not finish")

        assertEquals(threads * perThread, trace.events().size)
    }

    @Test
    fun `render produces one row per span plus a header`() {
        val clock = FakeClock()
        val trace = TraceRecorder(clock)
        trace.record("tool", "recentCommits", Phase.START)
        clock.now = 10
        trace.record("tool", "recentCommits", Phase.END)

        val lines = trace.render().lines()
        assertEquals(2, lines.size)
        assertTrue(lines[0].contains("KIND"))
        assertTrue(lines[1].contains("recentCommits"))
    }

    @Test
    fun `render says so when nothing was recorded`() {
        assertTrue(TraceRecorder().render().contains("no trace"))
    }

    @Test
    fun `spans come back in start order regardless of completion order`() {
        val clock = FakeClock()
        val trace = TraceRecorder(clock)

        trace.record("agent", "first", Phase.START)
        clock.now = 5
        trace.record("agent", "second", Phase.START)
        clock.now = 10
        trace.record("agent", "second", Phase.END)
        clock.now = 20
        trace.record("agent", "first", Phase.END)

        assertEquals(listOf("first", "second"), trace.spans().map { it.name })
    }

    @Test
    fun `a span carries the branch through from its start event`() {
        val clock = FakeClock()
        val trace = TraceRecorder(clock)
        trace.record("agent", "drafter", Phase.START, branch = "analysis.drafter")
        clock.now = 5
        trace.record("agent", "drafter", Phase.END)

        assertEquals("analysis.drafter", assertNotNull(trace.spans().single().branch))
    }
}
