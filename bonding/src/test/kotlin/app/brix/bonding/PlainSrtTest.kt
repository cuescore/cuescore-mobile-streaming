package app.brix.bonding

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plain SRT: mode without SRTLA, selected by the `srt://` scheme.
 *
 * Tested against a fake receiver behaving like real `srt-live-transmit` or OBS
 * SRT input: silently discards SRTLA packets and answers only SRT handshakes.
 * Client registration or SRTLA keepalives receive no response, which the
 * test detects.
 */
class PlainSrtTest {


    /** SRT data packet with a given sequence number. The client does not create
         *  data; the stream above supplies it, so tests inject it manually. */
    private fun dataPacket(sn: Long): ByteArray {
        val p = ByteArray(64)
        writeUInt32(p, 0, sn) // Zero high bit means a data packet
        return p
    }

    private fun newClient(plain: Boolean, onReady: () -> Unit): SrtlaClient {
        val client = SrtlaClient()
        client.useSrtla = !plain
        client.onReady = onReady
        return client
    }

    @Test
    fun `клиент выходит на связь с приёмником, не знающим SRTLA`() {
        val fake = FakeSrtlaRec(plainSrt = true)
        fake.start()
        try {
            val ready = CountDownLatch(1)
            val client = newClient(plain = true) { ready.countDown() }
            client.addConnection("wifi", 1f)
            client.start("127.0.0.1", fake.port)

            assertTrue("не вышел в рабочее состояние за 5 с", ready.await(5, TimeUnit.SECONDS))
            assertTrue(client.isRunning())
            client.stop()
        } finally {
            fake.stop()
        }
    }

    @Test
    fun `в простом режиме служебные пакеты SRTLA не отправляются`() {
        val fake = FakeSrtlaRec(plainSrt = true)
        fake.start()
        try {
            val ready = CountDownLatch(1)
            val client = newClient(plain = true) { ready.countDown() }
            client.addConnection("wifi", 1f)
            client.start("127.0.0.1", fake.port)
            assertTrue(ready.await(5, TimeUnit.SECONDS))

            // Inject data: without it the client stays silent and the test
            // would pass vacuously, finding no SRTLA packets with no traffic at all.
            repeat(20) { client.handleLocalPacket(dataPacket(it.toLong())) }
            // Over two seconds: SRTLA keepalive runs once a second, so two
            // could be sent in this window if the mode were broken.
            Thread.sleep(2500)
            assertTrue(
                "приёмник не получил вообще ничего — тест ничего не доказывает",
                fake.receivedPackets().isNotEmpty(),
            )
            client.stop()

            val srtlaPackets = fake.receivedPackets().count { p ->
                Srt.getControlPacketType(p) in setOf(
                    Srtla.PacketType.KEEPALIVE.rawValue,
                    Srtla.PacketType.REG1.rawValue,
                    Srtla.PacketType.REG2.rawValue,
                )
            }
            assertEquals("приёмник получил пакеты SRTLA, хотя не должен", 0, srtlaPackets)
        } finally {
            fake.stop()
        }
    }

    @Test
    fun `против настоящего SRTLA-приёмника простой режим не проходит регистрацию`() {
        // Inverse check: the SRTLA receiver expects REG1, which the plain client
        // never sends. The client correctly considers itself ready because
        // SRT handshakes are answered. Verify registration truly did not occur.
        val fake = FakeSrtlaRec()
        fake.start()
        try {
            val ready = CountDownLatch(1)
            val client = newClient(plain = true) { ready.countDown() }
            client.addConnection("wifi", 1f)
            client.start("127.0.0.1", fake.port)
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            Thread.sleep(500)
            client.stop()

            val reg1 = fake.receivedPackets().count {
                Srt.getControlPacketType(it) == Srtla.PacketType.REG1.rawValue
            }
            assertEquals("REG1 не должен уходить в простом режиме", 0, reg1)
        } finally {
            fake.stop()
        }
    }

    @Test
    fun `в простом режиме поток идёт по одному линку, а не по двум`() {
        // SRTLA deliberately spreads packets across links for server reassembly.
        // Plain SRT has no such layer and sees second-socket packets as unrelated.
        // Field run 14.09 showed exactly this: simultaneous Wi-Fi and cellular traffic.
        val fake = FakeSrtlaRec(plainSrt = true)
        fake.start()
        try {
            val ready = CountDownLatch(1)
            val client = newClient(plain = true) { ready.countDown() }
            client.addConnection("wifi", 1f)
            client.addConnection("cellular", 1f)
            client.start("127.0.0.1", fake.port)
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            // Let the second link connect, otherwise the test proves only
            // that it is not ready yet.
            Thread.sleep(800)
            repeat(50) { client.handleLocalPacket(dataPacket(it.toLong())) }
            Thread.sleep(500)

            // Multiple links produce packets from different source ports.
            // Plain SRT must have exactly as many source ports as sessions: one.
            val ports = fake.receivedSourcePorts()
            assertEquals("поток ушёл по нескольким линкам: $ports", 1, ports.size)
            client.stop()
        } finally {
            fake.stop()
        }
    }
}
