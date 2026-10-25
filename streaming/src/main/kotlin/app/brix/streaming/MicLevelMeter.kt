package app.brix.streaming

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max

/**
 * Уровень МИКРОФОНА по каналам — для индикатора на экране стримера.
 *
 * Считается на кадре PCM микрофона с уже применённым усилением и мьютом, но ДО
 * подмешивания донатов (владелец, 17.09: на индикаторе нужен голос, а не сумма
 * всего, что уходит в эфир, — иначе алерт выглядит как крик в микрофон).
 *
 * **Почему пик, а не среднее.** Стримеру нужны два ответа: «звук вообще идёт»
 * и «не перегружен ли вход». Оба видны по пику; среднеквадратичное значение
 * ровнее, но клиппинг на нём не заметен.
 *
 * Значение в долях 0..1 по шкале децибел (-60 дБ и тише — ноль), потому что
 * линейная шкала для голоса почти всё время висит в нижней десятой части.
 * Спад добавлен искусственно: без него полоска мигает на каждой паузе между
 * словами и читается как сбой.
 */
class MicLevelMeter(
    private val decayPerFrame: Float = 0.25f,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 },
) {

    private val _left = MutableStateFlow(0f)

    /** Левый канал (в моно — единственный). */
    val left: StateFlow<Float> = _left

    private val _right = MutableStateFlow(0f)
    val right: StateFlow<Float> = _right

    private val _clipping = MutableStateFlow(false)
    val clipping: StateFlow<Boolean> = _clipping

    @Volatile
    var stereo: Boolean = true

    fun submit(pcm16: ByteArray, offset: Int = 0, size: Int = pcm16.size - offset) {
        var peakL = 0
        var peakR = 0
        // Прореживание: кадр — тысячи отсчётов, пик от этого почти не меняется,
        // а считается это в потоке записи звука, где лишние такты не нужны.
        // Шаг кратен кадру стерео (4 байта), иначе каналы перепутаются местами.
        val step = if (stereo) 16 else 8
        val end = offset + size
        var i = offset
        while (i + 1 < end) {
            val l = sampleAt(pcm16, i)
            peakL = max(peakL, abs(l))
            if (stereo && i + 3 < end) {
                peakR = max(peakR, abs(sampleAt(pcm16, i + 2)))
            }
            i += step
        }
        // Внутри — на каждом кадре (спад считается по кадрам), наружу — не чаще
        // PUBLISH_MS. Раньше каждое значение кадра уходило в StateFlow, и HUD
        // перерисовывался ~13 раз/с, пока в комнате есть звук (стенд 04.10).
        // Наружу идёт максимум за окно, а не последний кадр: иначе пик слова,
        // пришедшийся между публикациями, на экран не попадал бы вовсе.
        levelL = max(scale(peakL), levelL - decayPerFrame)
        levelR = if (stereo) max(scale(peakR), levelR - decayPerFrame) else levelL
        windowL = max(windowL, levelL)
        windowR = max(windowR, levelR)

        val now = nowMs()
        // Защёлка перегрузки по времени: раньше флаг горел ровно один кадр
        // (~20 мс) и почти всегда проскакивал между кадрами экрана.
        val clipNow = max(peakL, peakR) >= CLIP_SAMPLE
        if (clipNow) clipUntil = now + CLIP_HOLD_MS
        val clipped = now < clipUntil

        // Начало перегрузки публикуется сразу, не дожидаясь окна.
        if (now - lastPublish >= PUBLISH_MS || (clipped && !_clipping.value)) {
            _left.value = windowL
            _right.value = windowR
            _clipping.value = clipped
            lastPublish = now
            windowL = levelL
            windowR = levelR
        }
    }

    private var levelL = 0f
    private var levelR = 0f
    private var windowL = 0f
    private var windowR = 0f
    private var clipUntil = Long.MIN_VALUE
    private var lastPublish = Long.MIN_VALUE / 2

    private fun sampleAt(pcm16: ByteArray, index: Int): Int =
        (((pcm16[index].toInt() and 0xFF) or (pcm16[index + 1].toInt() shl 8)).toShort()).toInt()

    fun reset() {
        levelL = 0f
        levelR = 0f
        windowL = 0f
        windowR = 0f
        clipUntil = Long.MIN_VALUE
        _left.value = 0f
        _right.value = 0f
        _clipping.value = false
    }

    companion object {
        private const val PUBLISH_MS = 100L
        private const val CLIP_HOLD_MS = 1_500L
        private const val CLIP_SAMPLE = 32_000

        /** Децибелы, а не линейная шкала: на линейной голос почти всё время висит
         *  в нижней десятой части полоски. −60 дБ и тише — ноль. */
        internal fun scale(peak: Int): Float {
            val normalized = peak / 32768f
            val db = if (normalized <= 0.0001f) -60f else 20f * log10(normalized)
            return ((db + 60f) / 60f).coerceIn(0f, 1f)
        }
    }
}
