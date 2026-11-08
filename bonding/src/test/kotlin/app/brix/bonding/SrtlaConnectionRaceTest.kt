package app.brix.bonding

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P0 race coverage: REG_ERR -> delayed retry must never resurrect a stopped
 * connection (real sockets, real read threads), and a still-running
 * connection must actually re-establish its socket when the retry fires.
 */
class SrtlaConnectionRaceTest {

    private val stateClass = Class.forName("app.brix.bonding.SrtlaConnection\$State")
    private val registeredState = java.lang.Enum.valueOf(
        stateClass as Class<out Enum<*>>,
        "REGISTERED",
    )

    private class RecordingDelegate : SrtlaConnection.Delegate {
        var sendFailedAfterStop = 0
        var registeredCount = 0
        override fun onSocketConnected(connection: SrtlaConnection) = Unit
        override fun onRegNgp(connection: SrtlaConnection) = Unit
        override fun onReg2(connection: SrtlaConnection, groupId: ByteArray) = Unit
        override fun onRegistered(connection: SrtlaConnection) { registeredCount++ }
        override fun onPacket(connection: SrtlaConnection, packet: ByteArray) = Unit
        override fun onSrtAck(connection: SrtlaConnection, sn: Long) = Unit
        override fun onSrtNak(connection: SrtlaConnection, sn: Long) = Unit
        override fun onSrtlaAck(connection: SrtlaConnection, sn: Long) = Unit
        override fun onSendFailed(connection: SrtlaConnection) { sendFailedAfterStop++ }
    }

    private fun startRegisteredConnection(fake: FakeSrtlaRec, delegate: RecordingDelegate): SrtlaConnection {
        val connection = SrtlaConnection("cellular", 1f, null)
        connection.delegate = delegate
        connection.start("127.0.0.1", fake.port)
        assertTrue(
            "connection did not start within 3s",
            awaitTrue(3_000) { getField(connection, "running") == true },
        )
        setField(connection, "state", registeredState)
        return connection
    }

    @Test
    fun `REG_ERR packet schedules a delayed reconnect`() {
        val fake = FakeSrtlaRec()
        fake.start()
        try {
            val connection = startRegisteredConnection(fake, RecordingDelegate())
            val localPort = (getField(connection, "socket") as DatagramSocket).localPort

            val type = Srtla.PacketType.REG_ERR.rawValue or Srt.CONTROL_PACKET_TYPE_BIT
            val payload = byteArrayOf(
                ((type shr 8) and 0xFF).toByte(),
                (type and 0xFF).toByte(),
            )
            // The connection socket is connect()ed to the fake receiver, so the
            // packet must come FROM that exact peer or the kernel drops it.
            fake.socket.send(
                DatagramPacket(payload, payload.size, InetAddress.getByName("127.0.0.1"), localPort),
            )

            assertTrue(
                "REG_ERR did not schedule a reconnect within 2s",
                awaitTrue(2_000) { getField(connection, "pendingReconnect") != null },
            )
            @Suppress("UNCHECKED_CAST")
            assertEquals(1, getField(connection, "regErrCount"))
            // Audit 23.09: without this, REG2 retries kept hitting the server
            // despite the supposedly delayed attempt.
            assertEquals("REG_ERR must park the link until the delayed retry", "IDLE", getField(connection, "state").toString())

            connection.stop()
        } finally {
            fake.stop()
        }
    }

    @Test
    fun `stop during pending delayed retry prevents resurrection`() {
        val fake = FakeSrtlaRec()
        fake.start()
        try {
            val delegate = RecordingDelegate()
            val connection = startRegisteredConnection(fake, delegate)

            invokeScheduleReconnect(connection, 300L)
            assertNotNull(getField(connection, "pendingReconnect"))
            val oldThread = getField(connection, "readThread") as Thread?

            connection.stop()

            // Wait well past the retry deadline: if the guard were broken,
            // reconnect() would install a new socket at ~300ms.
            Thread.sleep(900)

            assertEquals(false, getField(connection, "started"))
            assertEquals(false, getField(connection, "running"))
            val threadNow = getField(connection, "readThread") as Thread?
            assertTrue(threadNow === oldThread || threadNow?.isAlive != true)
            assertEquals(
                "onSendFailed fired after stop — stale retry reached reconnect()",
                0,
                delegate.sendFailedAfterStop,
            )
        } finally {
            fake.stop()
        }
    }

    @Test
    fun `delayed retry re-establishes socket when still running`() {
        val fake = FakeSrtlaRec()
        fake.start()
        try {
            val connection = startRegisteredConnection(fake, RecordingDelegate())
            val oldThread = getField(connection, "readThread") as Thread?
            val oldSocket = getField(connection, "socket") as DatagramSocket

            invokeScheduleReconnect(connection, 200L)

            assertTrue(
                "retry did not replace the read thread within 3s",
                awaitTrue(3_000) {
                    val t = getField(connection, "readThread") as Thread?
                    t !== null && t !== oldThread && t.isAlive
                },
            )
            assertEquals(true, getField(connection, "running"))
            assertNotSame(oldSocket, getField(connection, "socket"))
            oldSocket.close()
            connection.stop()
        } finally {
            fake.stop()
        }
    }

    @Test
    fun `start with mismatched epoch is a no-op for stale queued events`() {
        val fake = FakeSrtlaRec()
        fake.start()
        try {
            val connection = SrtlaConnection("wifi", 1f, null)
            connection.attachEpoch(5L)

            connection.start("127.0.0.1", fake.port, epoch = 7L)
            Thread.sleep(300)

            assertEquals(false, getField(connection, "started"))
            assertFalse((getField(connection, "readThread") as Thread?)?.isAlive == true)
        } finally {
            fake.stop()
        }
    }

    /**
     * Quarantine must not revive a stopped link: same protection as the
     * delayed retry after REG_ERR, through a different entry point.
     */
    @Test
    fun `stop during quarantine prevents resurrection`() {
        val fake = FakeSrtlaRec()
        fake.start()
        try {
            val delegate = RecordingDelegate()
            val connection = startRegisteredConnection(fake, delegate)

            connection.quarantine("test", 300L)
            assertNotNull(getField(connection, "pendingReconnect"))
            val oldThread = getField(connection, "readThread") as Thread?

            connection.stop()
            Thread.sleep(900)

            assertEquals(false, getField(connection, "started"))
            assertEquals(false, getField(connection, "running"))
            val threadNow = getField(connection, "readThread") as Thread?
            assertTrue(threadNow === oldThread || threadNow?.isAlive != true)
            // stop() must reset backoff too, otherwise the next session
            // starts with accumulated delay.
            assertEquals(false, getField(connection, "quarantinePending"))
            assertEquals(0, getField(connection, "quarantineAttempts"))
        } finally {
            fake.stop()
        }
    }

    /**
     * Successful registration clears quarantine and resets backoff:
     * the link rejoined the group, so the next failure must start at
     * one second, not an accumulated minute.
     */
    @Test
    fun `successful registration clears quarantine`() {
        val fake = FakeSrtlaRec()
        fake.start()
        try {
            val delegate = RecordingDelegate()
            val connection = startRegisteredConnection(fake, delegate)

            // Quarantine with a long delay so the attempt cannot run yet
            // and its state remains observable.
            connection.quarantine("test", 60_000L)
            assertEquals(true, getField(connection, "quarantinePending"))
            assertEquals(1, getField(connection, "quarantineAttempts"))

            // Registration takes its own path: WAIT_FOR_REGISTER_RESPONSE
            // state plus REG3 from the receiver.
            setField(connection, "state", waitForRegisterResponseState)
            invokePrivate(connection, "handleSrtlaReg3")

            assertEquals(false, getField(connection, "quarantinePending"))
            assertEquals(0, getField(connection, "quarantineAttempts"))
            assertEquals(1, delegate.registeredCount)
            connection.stop()
        } finally {
            fake.stop()
        }
    }

    /**
     * Backoff grows rather than standing still: each quarantine entry increases
     * the attempt number, which determines the delay.
     */
    @Test
    fun `repeated quarantine walks up the ladder`() {
        val fake = FakeSrtlaRec()
        fake.start()
        try {
            val connection = startRegisteredConnection(fake, RecordingDelegate())
            for (expected in 1..3) {
                connection.quarantine("test", 60_000L)
                assertEquals(expected, getField(connection, "quarantineAttempts"))
                // Clear the latch manually; in production the delayed attempt
                // clears it just before starting.
                setField(connection, "quarantinePending", false)
            }
            connection.stop()
        } finally {
            fake.stop()
        }
    }

    private val waitForRegisterResponseState = java.lang.Enum.valueOf(
        stateClass as Class<out Enum<*>>,
        "WAIT_FOR_REGISTER_RESPONSE",
    )

    private fun invokePrivate(target: Any, name: String) {
        val method = target.javaClass.getDeclaredMethod(name)
        method.isAccessible = true
        method.invoke(target)
    }

    /**
     * Audit 23.09: reconnect() closed the socket without detaching it, so the
     * old reader spun continuously on the closed socket. Failed startup
     * (DNS did not answer) left it spinning for the entire stream, while
     * started=false prevented quarantine retries, permanently killing the link.
     */
    @Test
    fun `failed reconnect stops the old reader and stays eligible for quarantine retry`() {
        val fake = FakeSrtlaRec()
        fake.start()
        var dnsUp = true
        val connection = SrtlaConnection(
            "cellular",
            1f,
            null,
            resolve = { if (dnsUp) InetAddress.getByName("127.0.0.1") else throw java.net.UnknownHostException("no dns") },
        )
        try {
            connection.delegate = RecordingDelegate()
            connection.start("127.0.0.1", fake.port)
            assertTrue(awaitTrue(3_000) { getField(connection, "running") == true })
            val oldReader = getField(connection, "readThread") as Thread

            dnsUp = false
            connection.reconnect("test")

            assertTrue("old reader must exit, not spin", awaitTrue(2_000) { !oldReader.isAlive })
            assertEquals("started must survive a failed reconnect", true, getField(connection, "started"))
            assertEquals("a retry must be scheduled", true, getField(connection, "quarantinePending"))
        } finally {
            connection.stop()
            fake.stop()
        }
    }

    private fun invokeScheduleReconnect(connection: SrtlaConnection, delayMs: Long) {
        val method = connection.javaClass.getDeclaredMethod(
            "scheduleReconnect",
            Long::class.javaPrimitiveType,
            String::class.java,
            Boolean::class.javaPrimitiveType,
        )
        method.isAccessible = true
        method.invoke(connection, delayMs, "test", false)
    }

    private fun setField(target: Any, name: String, value: Any?) {
        val field = target.javaClass.getDeclaredField(name)
        field.isAccessible = true
        field.set(target, value)
    }

    private fun getField(target: Any, name: String): Any? {
        val field = target.javaClass.getDeclaredField(name)
        field.isAccessible = true
        return field.get(target)
    }

    private fun awaitTrue(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(25)
        }
        return condition()
    }
}
