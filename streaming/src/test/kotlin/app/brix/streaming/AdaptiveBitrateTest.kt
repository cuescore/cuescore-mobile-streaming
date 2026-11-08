package app.brix.streaming

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveBitrateTest {

    private fun goodStats() = SrtStats(rttMs = 20.0, packetsInFlight = 5.0, mbpsSendRate = 7.0, latencyMs = 1000)

    private fun congestedStats() = SrtStats(rttMs = 50.0, packetsInFlight = 400.0, mbpsSendRate = 7.0, latencyMs = 1000)

    @Test
    fun `good network raises bitrate above initial`() {
        val clock = ManualClock()
        val abr = AdaptiveBitrateSrtBelabox(
            targetBitrate = 7_000_000,
            minimumBitrate = 1_000_000,
            initialBitrate = 2_000_000,
            clock = clock::now,
        )

        abr.update(goodStats())
        assertTrue("expected bitrate to increase, got ${abr.currentBitrateKbps()}", abr.currentBitrateKbps() > 2000)
    }

    @Test
    fun `congestion lowers bitrate below initial`() {
        val clock = ManualClock()
        val abr = AdaptiveBitrateSrtBelabox(
            targetBitrate = 7_000_000,
            minimumBitrate = 1_000_000,
            initialBitrate = 2_000_000,
            clock = clock::now,
        )

        // Two consecutive measurements: a single spike is now intentionally not enough to reduce bitrate; see
        // the test “a single in-flight spike does not reduce bitrate”.
        abr.update(congestedStats())
        abr.update(congestedStats())
        assertTrue("expected bitrate to decrease, got ${abr.currentBitrateKbps()}", abr.currentBitrateKbps() < 2000)
    }

    @Test
    fun `одиночный всплеск в полёте не режет битрейт`() {
        // Field analysis 31.08: packetsInFlight is sampled instantaneously once
        // every 200 ms, and sawtooths between acknowledgments — a sawtooth
        // from 15 to 1055 was observed at steady RTT (47–58 ms) and ZERO losses, because
        // one acknowledgment removes hundreds of packets from flight at once. One such sample
        // above the threshold cut the bitrate in half, and during rapid movement the image
        // broke into artifacts despite a completely healthy network.
        val clock = ManualClock()
        val abr = AdaptiveBitrateSrtBelabox(
            targetBitrate = 7_000_000,
            minimumBitrate = 1_000_000,
            initialBitrate = 5_000_000,
            clock = clock::now,
        )
        // Healthy network: collect averages so the thresholds stabilize.
        repeat(20) {
            clock.advance(200)
            abr.update(goodStats())
        }
        val before = abr.currentBitrateKbps()

        // One sawtooth peak — the network is healthy: RTT is unchanged and there are no losses.
        clock.advance(200)
        abr.update(SrtStats(rttMs = 20.0, packetsInFlight = 900.0, mbpsSendRate = 7.0, latencyMs = 1000))
        assertTrue(
            "одиночный всплеск не должен резать битрейт: было $before, стало ${abr.currentBitrateKbps()}",
            abr.currentBitrateKbps() >= before,
        )

        // But sustained excess should.
        clock.advance(200)
        abr.update(SrtStats(rttMs = 20.0, packetsInFlight = 900.0, mbpsSendRate = 7.0, latencyMs = 1000))
        clock.advance(200)
        abr.update(SrtStats(rttMs = 20.0, packetsInFlight = 900.0, mbpsSendRate = 7.0, latencyMs = 1000))
        assertTrue(
            "устойчивый затор обязан снизить битрейт, стало ${abr.currentBitrateKbps()}",
            abr.currentBitrateKbps() < before,
        )
    }

    @Test
    fun `severe congestion clamps to minimum bitrate`() {
        val clock = ManualClock()
        val abr = AdaptiveBitrateSrtBelabox(
            targetBitrate = 7_000_000,
            minimumBitrate = 1_000_000,
            initialBitrate = 2_000_000,
            clock = clock::now,
        )

        val stats = SrtStats(rttMs = 500.0, packetsInFlight = 5000.0, mbpsSendRate = 7.0, latencyMs = 1000)
        repeat(3) {
            clock.advance(250)
            abr.update(stats)
        }
        assertEquals(1000L, abr.currentBitrateKbps())
    }

    @Test
    fun `bitrate never exceeds target`() {
        val clock = ManualClock()
        val abr = AdaptiveBitrateSrtBelabox(
            targetBitrate = 5_000_000,
            minimumBitrate = 1_000_000,
            initialBitrate = 2_000_000,
            clock = clock::now,
        )

        // Same interval as in production: SrtlaStream.startAdaptiveLoop ticks every 200 ms.
        // Thread.sleep(450) used to be here — the test depended on wall-clock
        // time and on what else the machine was doing.
        repeat(40) {
            abr.update(goodStats())
            clock.advance(TICK_MS)
        }
        assertTrue("expected bitrate within target, got ${abr.currentBitrateKbps()}", abr.currentBitrateKbps() <= 5000)
    }

    @Test
    fun `configure resets to initial`() {
        val clock = ManualClock()
        val abr = AdaptiveBitrateSrtBelabox(
            targetBitrate = 7_000_000,
            minimumBitrate = 1_000_000,
            initialBitrate = 2_000_000,
            clock = clock::now,
        )
        abr.update(congestedStats())
        abr.update(congestedStats())
        assertTrue(abr.currentBitrateKbps() < 2000)

        abr.configure(target = 8_000_000, min = 1_000_000, initial = 3_000_000)
        assertEquals(3000L, abr.currentBitrateKbps())
    }

    // --- New: ABR correctness after removing thermal coupling ---

    private class ManualClock(var t: Long = 1000L) {
        fun now() = t
        fun advance(ms: Long) { t += ms }
    }

    // ---- Time constants ----
    //
    // The algorithm is ported from belacoder, which ticks every 20 ms, while our loop runs
    // every 200 ms. The smoothing coefficients were copied verbatim, so
    // all time constants ended up ten times longer than intended, and not
    // a single test caught it: only currentBitrateKbps() was exposed externally.
    // The tests below inspect the smoothing itself through debugState() and fail if
    // the coefficients drift out of sync with the polling interval again.

    private fun abrWithClock(clock: ManualClock) = AdaptiveBitrateSrtBelabox(
        targetBitrate = 7_000_000,
        minimumBitrate = 1_000_000,
        initialBitrate = 2_000_000,
        clock = clock::now,
    )

    @Test
    fun `усреднение RTT проходит ступеньку за две секунды, а не за двадцать`() {
        val clock = ManualClock()
        val abr = abrWithClock(clock)
        repeat(40) { clock.advance(TICK_MS); abr.update(goodStats()) }
        val before = abr.debugState().rttAverage

        // Step up, exactly 2 s (ten 200 ms ticks) — over one time
        // constant the exponential covers 1 - 1/e ≈ 63% of the distance.
        val stepRtt = 120.0
        repeat(10) { clock.advance(TICK_MS); abr.update(goodStats().copy(rttMs = stepRtt)) }
        val after = abr.debugState().rttAverage

        val progress = (after - before) / (stepRtt - before)
        assertTrue(
            "за 2 с усреднение RTT должно пройти ~63 % ступеньки, прошло ${(progress * 100).toInt()} %",
            progress in 0.55..0.75,
        )
    }

    @Test
    fun `пропускная способность выходит на уровень за две трети секунды`() {
        val clock = ManualClock()
        val abr = abrWithClock(clock)
        val target = 7.0 * 1000.0 * 1000.0 / 1024.0

        // One tick is deliberately less than one third of the distance.
        clock.advance(TICK_MS)
        abr.update(goodStats())
        assertTrue(
            "после одного тика пропускная не должна быть уже почти на месте",
            abr.debugState().throughput / target < 0.40,
        )

        // Three more ticks — 0.8 s total, i.e. greater than one time constant.
        repeat(3) { clock.advance(TICK_MS); abr.update(goodStats()) }
        val reached = abr.debugState().throughput / target
        assertTrue(
            "за 0.8 с пропускная должна выйти минимум на 55 % уровня, вышла на ${(reached * 100).toInt()} %",
            reached > 0.55,
        )
    }

    @Test
    fun `нижняя оценка RTT ползёт вверх примерно на пять процентов в секунду`() {
        val clock = ManualClock()
        val abr = abrWithClock(clock)
        // Warm-up at low RTT pulls rttMin down to 20 ms.
        repeat(20) { clock.advance(TICK_MS); abr.update(goodStats()) }
        val start = abr.debugState().rttMin

        // One second at high RTT: nothing pulls rttMin down, so we see the
        // pure upward drift. That is what sets rttThMin — the only threshold in the
        // growth branch; while it drifted ten times more slowly, growth was blocked.
        val high = goodStats().copy(rttMs = 500.0)
        repeat(5) { clock.advance(TICK_MS); abr.update(high) }
        val grown = abr.debugState().rttMin / start

        assertTrue(
            "за секунду rttMin должен подрасти примерно на 5 %, подрос на ${((grown - 1) * 100)}%",
            grown in 1.03..1.08,
        )
    }

    @Test
    fun `всплеск джиттера рассасывается за пару секунд`() {
        val clock = ManualClock()
        val abr = abrWithClock(clock)
        repeat(20) { clock.advance(TICK_MS); abr.update(goodStats()) }
        // A single in-flight spike raises the peak jitter estimate.
        clock.advance(TICK_MS)
        abr.update(goodStats().copy(packetsInFlight = 400.0))
        val peak = abr.debugState().sendBufferSizeJitter
        assertTrue("всплеск должен поднять оценку джиттера", peak > 100.0)

        // Two seconds of a quiet network is about one time constant,
        // so about 1/e of the peak should remain. With the old coefficient,
        // 90% would remain: the spike would last twenty seconds.
        repeat(10) { clock.advance(TICK_MS); abr.update(goodStats()) }
        val left = abr.debugState().sendBufferSizeJitter / peak
        assertTrue(
            "за 2 с от всплеска должно остаться около трети, осталось ${(left * 100).toInt()} %",
            left < 0.45,
        )
    }

    // ---- Gaps that were not covered at all ----

    @Test
    fun `отброшенный пакет режет битрейт на четверть сразу`() {
        val clock = ManualClock()
        val abr = abrWithClock(clock)
        clock.advance(TICK_MS)
        // Drop branch: a real loss in the window, so reduce by a quarter immediately, without waiting for
        // confirming measurements — unlike thresholds, this is a fact, not an estimate.
        abr.update(goodStats().copy(droppedPackets = 1))
        assertEquals(1500L, abr.currentBitrateKbps())
    }

    @Test
    fun `профиль алгоритма меняет глубину реза`() {
        fun cutWith(algorithm: app.brix.core.AbrAlgorithm): Long {
            val clock = ManualClock()
            val abr = AdaptiveBitrateSrtBelabox(
                targetBitrate = 7_000_000,
                minimumBitrate = 100_000,
                initialBitrate = 2_000_000,
                clock = clock::now,
                algorithm = algorithm,
            )
            clock.advance(TICK_MS)
            abr.update(goodStats().copy(droppedPackets = 1))
            return abr.currentBitrateKbps()
        }
        // FAST_IRL cuts one and a half times deeper than the baseline, SLOW_IRL is twice as gentle.
        val base = cutWith(app.brix.core.AbrAlgorithm.BELABOX)
        assertTrue(cutWith(app.brix.core.AbrAlgorithm.FAST_IRL) < base)
        assertTrue(cutWith(app.brix.core.AbrAlgorithm.SLOW_IRL) > base)
    }

    private companion object {
        /** ABR cycle interval in production — `SrtlaStream.startAdaptiveLoop`. */
        const val TICK_MS = 200L
    }

    @Test
    fun `sustained rtt spike drops to minimum then recovers to target`() {
        val clock = ManualClock()
        val abr = AdaptiveBitrateSrtBelabox(
            targetBitrate = 7_000_000,
            minimumBitrate = 1_000_000,
            initialBitrate = 2_000_000,
            clock = clock::now,
        )
        // latency 1000 -> hard threshold rtt >= 333. De-oscillation requires a
        // few consecutive bad samples before cutting (H7), so feed three.
        val spike = SrtStats(rttMs = 500.0, packetsInFlight = 5.0, mbpsSendRate = 7.0, latencyMs = 1000, relaxed = false)
        repeat(3) {
            clock.advance(250)
            abr.update(spike)
        }
        assertEquals("sustained spike must drop to minimum", 1000L, abr.currentBitrateKbps())

        // Network recovers: sustained good RTT, advancing the inc window each step.
        repeat(80) {
            clock.advance(TICK_MS)
            abr.update(goodStats())
        }
        assertTrue(
            "ABR must climb back from minimum, got ${abr.currentBitrateKbps()}",
            abr.currentBitrateKbps() > 4000,
        )
    }

    @Test
    fun `does not stick at minimum under stable good network`() {
        val clock = ManualClock()
        val abr = AdaptiveBitrateSrtBelabox(
            targetBitrate = 6_000_000,
            minimumBitrate = 1_000_000,
            initialBitrate = 1_000_000,
            clock = clock::now,
        )
        // The growth window is 400 ms at a 200 ms tick and opens on a strict comparison
        // once every three ticks — 90 ticks means 30 increases, or 18 seconds of broadcast.
        repeat(90) {
            clock.advance(TICK_MS)
            abr.update(goodStats())
        }
        assertTrue(
            "stable good network must raise bitrate toward target, got ${abr.currentBitrateKbps()}",
            abr.currentBitrateKbps() >= 5000,
        )
    }

    @Test
    fun `relaxed window protects startup buffer burst from slamming`() {
        // At session start the encoder can emit a buffer burst; relaxed doubles
        // the fast-decrease threshold so the bitrate is not slammed to minimum.
        val clock = ManualClock()
        val burst = SrtStats(rttMs = 120.0, packetsInFlight = 220.0, mbpsSendRate = 7.0, latencyMs = 1000)

        val relaxed = AdaptiveBitrateSrtBelabox(
            targetBitrate = 7_000_000, minimumBitrate = 1_000_000, initialBitrate = 2_000_000, clock = clock::now,
        )
        relaxed.update(burst.copy(relaxed = true))

        val strict = AdaptiveBitrateSrtBelabox(
            targetBitrate = 7_000_000, minimumBitrate = 1_000_000, initialBitrate = 2_000_000, clock = clock::now,
        )
        strict.update(burst.copy(relaxed = false))

        assertTrue(
            "relaxed must keep bitrate above strict after a startup burst, " +
                "relaxed=${relaxed.currentBitrateKbps()} strict=${strict.currentBitrateKbps()}",
            relaxed.currentBitrateKbps() >= strict.currentBitrateKbps(),
        )
    }

    @Test
    fun `relaxed does not mask a true rtt spike`() {
        // relaxed only widens the buffer threshold; a genuine sustained RTT
        // spike must still drop (we must not hide real congestion).
        val clock = ManualClock()
        val spike = SrtStats(rttMs = 500.0, packetsInFlight = 5.0, mbpsSendRate = 7.0, latencyMs = 1000)
        val abr = AdaptiveBitrateSrtBelabox(
            targetBitrate = 7_000_000, minimumBitrate = 1_000_000, initialBitrate = 2_000_000, clock = clock::now,
        )
        repeat(3) {
            clock.advance(250)
            abr.update(spike.copy(relaxed = true))
        }
        assertEquals(1000L, abr.currentBitrateKbps())
    }

    @Test
    fun `configured minimum is a hard floor even under mild congestion`() {
        val clock = ManualClock()
        val abr = AdaptiveBitrateSrtBelabox(
            targetBitrate = 7_000_000, minimumBitrate = 4_000_000, initialBitrate = 2_500_000, clock = clock::now,
        )
        // A buffer spike that would normally decrease on a single link must
        // still respect the user's minimum as an absolute floor.
        abr.update(SrtStats(rttMs = 120.0, packetsInFlight = 300.0, mbpsSendRate = 7.0, latencyMs = 1000))
        assertTrue(
            "minimum must hold as floor, got ${abr.currentBitrateKbps()}",
            abr.currentBitrateKbps() >= 4000,
        )
    }

    @Test
    fun `updateLimits raises the floor live without resetting bitrate`() {
        val clock = ManualClock()
        val abr = AdaptiveBitrateSrtBelabox(
            targetBitrate = 7_000_000, minimumBitrate = 1_000_000, initialBitrate = 2_500_000, clock = clock::now,
        )
        abr.update(goodStats())
        val before = abr.currentBitrateKbps()
        abr.updateLimits(target = 7_000_000, min = 4_000_000)
        assertTrue("precondition: bitrate below new floor, got $before", before < 4000)
        // Next update must coerce the running bitrate up to the new minimum.
        abr.update(goodStats())
        assertEquals(4000L, abr.currentBitrateKbps())
    }
}
