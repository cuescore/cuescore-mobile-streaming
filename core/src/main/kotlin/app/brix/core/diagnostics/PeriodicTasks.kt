package app.brix.core.diagnostics

import java.util.concurrent.ConcurrentHashMap

/**
 * List of periodic tasks currently running.
 *
 * **Why.** By 12.09, the app had accumulated about a dozen independent timers:
 * a stats tick every second, thermal-status polling every five seconds, VK chat
 * polling every four, browser-widget redraws at their own interval, SRTLA keepalive
 * on every link, and UI animations. Each is justified on its own, but together
 * they form a picture that could only be reconstructed by reading the project
 * line by line. When analyzing a field log, there was no way to answer, “What was
 * actually running during that live stream?”
 *
 * This stopped being theoretical that same day: a 07.09 session reached thermal
 * `critical` in six and a half minutes, and its log could not establish whether
 * electronic stabilization was enabled or the browser widget was running.
 * Conclusions about overheating had to be based on guesses about the workload.
 *
 * **What this does NOT do.** The registry does not start, stop, or schedule
 * anything — it only knows what has registered. Each task still owns its
 * coroutine as before; this adds one diagnostic line and one place to see the
 * entire set at once.
 *
 * Registration has an attention cost, so only long-lived tasks register here —
 * those that run throughout the live stream. A one-off delay or a two-second
 * animation does not belong here: it does not change the workload picture.
 *
 * Thread-safe: registrations come from different coroutines and threads, and
 * reads come from the stats tick.
 */
object PeriodicTasks {

    /** Work registration. Removed via [close] — usually in `finally` or the
     *  owner's `stop()`, so a stopped task does not linger in the report. */
    class Registration internal constructor(private val key: String) : AutoCloseable {
        override fun close() {
            active.remove(key)
        }
    }

    private val active = ConcurrentHashMap<String, Long>()

    /**
     * @param name short task name as it will appear in an investigation:
     *   `stats`, `thermal`, `vk-chat`. If multiple tasks can share a name (widgets,
     *   links), the name must include a differentiator.
     * @param periodMs period in milliseconds — the value passed to `delay`.
     */
    fun register(name: String, periodMs: Long): Registration {
        active[name] = periodMs
        return Registration(name)
    }

    /**
     * The set in one log line: `stats:1.0s thermal:5.0s vk-chat:4.0s`.
     * Sorted by name so lines from different sessions can be compared visually
     * without rearranging entries.
     *
     * @return an empty string if nothing is registered — so the log does not
     *   contain empty brackets.
     */
    fun snapshot(): String = active.entries
        .sortedBy { it.key }
        .joinToString(" ") { (name, period) -> "$name:${period / 1000.0}s" }

    /** Number of tasks currently registered. For tests and the diagnostics screen. */
    fun count(): Int = active.size

    /** For tests only: the registry is a singleton and must be cleared between tests. */
    internal fun clearForTest() {
        active.clear()
    }
}
