package dev.sam.historian

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ToolCallBudgetTest {

    @Test
    fun `calls within budget are allowed`() {
        val budget = ToolCallBudget(maxCalls = 3)

        repeat(3) { assertNull(budget.consume("recentCommits"), "call ${it + 1} should pass") }
        assertEquals(3, budget.callsUsed())
    }

    @Test
    fun `the call past the budget is refused`() {
        val budget = ToolCallBudget(maxCalls = 2)
        repeat(2) { budget.consume("recentCommits") }

        val refusal = assertNotNull(budget.consume("filesChangedIn"))

        assertTrue(refusal.getValue("error").contains("filesChangedIn"), "names the blocked tool")
        assertTrue(refusal.getValue("error").contains("2"), "names the budget")
    }

    @Test
    fun `the refusal tells the model what to do instead of retrying`() {
        val budget = ToolCallBudget(maxCalls = 0)

        val refusal = assertNotNull(budget.consume("recentCommits"))

        assertTrue(refusal.getValue("error").contains("Answer with what you already have"))
    }

    @Test
    fun `a budget of zero refuses the very first call`() {
        assertNotNull(ToolCallBudget(maxCalls = 0).consume("recentCommits"))
    }

    @Test
    fun `every call after the budget stays refused`() {
        val budget = ToolCallBudget(maxCalls = 1)
        budget.consume("a")

        repeat(5) { assertNotNull(budget.consume("b"), "should still be refused") }
    }

    @Test
    fun `reset restores the budget`() {
        val budget = ToolCallBudget(maxCalls = 1)
        budget.consume("a")
        assertNotNull(budget.consume("b"))

        budget.reset()

        assertEquals(0, budget.callsUsed())
        assertNull(budget.consume("c"))
    }

    @Test
    fun `parallel agents cannot spend the same budget slot twice`() {
        val limit = 50
        val budget = ToolCallBudget(maxCalls = limit)
        val threads = 8
        val attemptsPerThread = 100
        val allowed = java.util.concurrent.atomic.AtomicInteger(0)
        val pool = Executors.newFixedThreadPool(threads)
        val ready = CountDownLatch(threads)

        repeat(threads) {
            pool.execute {
                ready.countDown()
                ready.await()
                repeat(attemptsPerThread) {
                    if (budget.consume("recentCommits") == null) allowed.incrementAndGet()
                }
            }
        }
        pool.shutdown()
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "workers did not finish")

        assertEquals(limit, allowed.get(), "exactly the budget should have been granted")
    }
}
