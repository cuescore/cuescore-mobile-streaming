package app.brix.core

import app.brix.core.diagnostics.PeriodicTasks
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class PeriodicTasksTest {

    @Before
    fun setUp() {
        PeriodicTasks.clearForTest()
    }

    @Test
    fun `состав пуст, пока никто не заявился`() {
        assertEquals("", PeriodicTasks.snapshot())
        assertEquals(0, PeriodicTasks.count())
    }

    @Test
    fun `период печатается в секундах`() {
        PeriodicTasks.register("stats", 1000)
        PeriodicTasks.register("vk-chat", 4000)
        assertEquals("stats:1.0s vk-chat:4.0s", PeriodicTasks.snapshot())
    }

    @Test
    fun `дробный период не округляется до нуля`() {
        // 200 ms is the default browser-widget interval. Printing whole seconds
        // would turn it into "0s" and hide the most expensive task of all
        // (12.09 measurement: 9-10% of one core).
        PeriodicTasks.register("widget-ab12", 200)
        assertEquals("widget-ab12:0.2s", PeriodicTasks.snapshot())
    }

    @Test
    fun `сортировка по имени, а не по порядку регистрации`() {
        // Lines from different sessions should be visually comparable without rearranging them.
        PeriodicTasks.register("uptime", 1000)
        PeriodicTasks.register("stats", 1000)
        PeriodicTasks.register("thermal", 5000)
        assertEquals("stats:1.0s thermal:5.0s uptime:1.0s", PeriodicTasks.snapshot())
    }

    @Test
    fun `закрытая заявка уходит из состава`() {
        val reg = PeriodicTasks.register("stats", 1000)
        PeriodicTasks.register("thermal", 5000)
        reg.close()
        assertEquals("thermal:5.0s", PeriodicTasks.snapshot())
        assertEquals(1, PeriodicTasks.count())
    }

    @Test
    fun `повторная регистрация того же имени не плодит строк`() {
        // A task may restart (the live stream was stopped and started again).
        // Duplicates would make the report unreadable and the count misleading.
        PeriodicTasks.register("stats", 1000)
        PeriodicTasks.register("stats", 1000)
        assertEquals(1, PeriodicTasks.count())
        assertEquals("stats:1.0s", PeriodicTasks.snapshot())
    }
}
