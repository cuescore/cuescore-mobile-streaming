package app.brix.streaming

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReconnectPolicyTest {

    @Test
    fun `nextDelayMs walks the schedule in order`() {
        val policy = ReconnectPolicy()
        assertEquals(1_000L, policy.nextDelayMs())
        assertEquals(2_000L, policy.nextDelayMs())
        assertEquals(5_000L, policy.nextDelayMs())
        assertEquals(10_000L, policy.nextDelayMs())
        assertEquals(30_000L, policy.nextDelayMs())
        assertEquals(5, policy.attempts)
    }

    @Test
    fun `exhausts after the last attempt`() {
        val policy = ReconnectPolicy()
        repeat(5) { policy.nextDelayMs() }
        assertTrue(policy.exhausted)
        assertFalse(policy.hasNext())
        assertNull(policy.nextDelayMs())
    }

    @Test
    fun `reset restarts the backoff schedule`() {
        val policy = ReconnectPolicy()
        repeat(5) { policy.nextDelayMs() }
        assertTrue(policy.exhausted)

        policy.reset()
        assertFalse(policy.exhausted)
        assertEquals(1_000L, policy.nextDelayMs())
        assertEquals(1, policy.attempts)
    }

    /**
     * The schedule is finite, so under concurrent calls no step should be
     * returned twice or go missing: unsynchronized `attempt++` is a
     * read-modify-write, and in production it is called by three threads
     * (start/reconnect under the streamer monitor, onConnectionSuccess from a
     * transport callback, and nextDelayMs from an IO coroutine).
     */
    @Test
    fun `concurrent nextDelayMs hands out each step exactly once`() {
        val schedule = (1..200).map { it * 1_000L }
        repeat(50) {
            val policy = ReconnectPolicy(schedule)
            val threads = 8
            val start = java.util.concurrent.CountDownLatch(1)
            val handed = java.util.concurrent.ConcurrentLinkedQueue<Long>()
            val workers = (1..threads).map {
                Thread {
                    start.await()
                    while (true) handed.add(policy.nextDelayMs() ?: return@Thread)
                }
            }
            workers.forEach { it.start() }
            start.countDown()
            workers.forEach { it.join(5_000) }

            assertEquals(schedule.size, handed.size)
            assertEquals(schedule.toSet(), handed.toSet())
            assertEquals(schedule.size, policy.attempts)
        }
    }
}