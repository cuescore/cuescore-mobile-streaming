package app.brix.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.random.Random

private val IntroBgDark = Color(0xFF0D0D0F)
private val IntroAccent = Color(0xFF467DF7)

private val MatrixChars = listOf("^", "_", "•", "o", "x", ">", "<", "$", "?", "-", "*", "#")

private class RainColumn(
    val speedFactor: Float,
    val headFactor: Float,
    val tail: Int,
    val glyphs: IntArray,
)

private const val MAX_RAIN_COLUMNS = 60

/** The longest time step per frame, in seconds: ~two frames at 30 fps. */
private const val MAX_FRAME_STEP = 1f / 15f

@Composable
fun BrixIntro(onFinished: () -> Unit) {
    val isDark = isSystemInDarkTheme() || (MaterialTheme.colorScheme.background.luminance() < 0.5f)
    val logoRes = if (isDark) R.drawable.ic_cuescore_logo_dark else R.drawable.ic_cuescore_logo_light
    val bgColor = if (isDark) IntroBgDark else MaterialTheme.colorScheme.background

    val logoAlpha = remember { Animatable(0f) }
    val logoScale = remember { Animatable(0.92f) }
    val rainAlpha = remember { Animatable(if (isDark) 1f else 0f) }
    val fade = remember { Animatable(1f) }
    var time by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(Unit) {
        if (isDark) {
            // 1. Frame time counter
            launch {
                var last = withFrameNanos { it }
                while (true) {
                    val now = withFrameNanos { it }
                    time += ((now - last) / 1_000_000_000f).coerceAtMost(MAX_FRAME_STEP)
                    last = now
                }
            }
            delay(1000)
            launch {
                rainAlpha.animateTo(0.12f, tween(500, easing = LinearEasing))
            }
        } else {
            delay(200)
        }

        launch {
            logoScale.animateTo(1f, tween(500, easing = EaseOutCubic))
        }
        logoAlpha.animateTo(1f, tween(400, easing = EaseOutCubic))

        delay(1200)
        fade.animateTo(0f, tween(300, easing = LinearEasing))
        onFinished()
    }

    val measurer = rememberTextMeasurer()
    val glyphStyle = remember {
        TextStyle(
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp,
            color = IntroAccent,
        )
    }

    val layouts = remember(measurer, glyphStyle) {
        MatrixChars.map { measurer.measure(it, glyphStyle) }
    }
    val density = LocalDensity.current
    val glyphImages = remember(layouts, density) {
        layouts.map { layout ->
            val w = layout.size.width.coerceAtLeast(1)
            val h = layout.size.height.coerceAtLeast(1)
            ImageBitmap(w, h).also { image ->
                CanvasDrawScope().draw(density, LayoutDirection.Ltr, Canvas(image), Size(w.toFloat(), h.toFloat())) {
                    drawText(layout, color = Color.White)
                }
            }
        }
    }
    val tailTint = remember { ColorFilter.tint(IntroAccent) }

    val columns = remember {
        val rnd = Random(4242)
        List(MAX_RAIN_COLUMNS) {
            RainColumn(
                speedFactor = 7f + rnd.nextFloat() * 8f,
                headFactor = rnd.nextFloat(),
                tail = 10 + rnd.nextInt(12),
                glyphs = IntArray(32) { rnd.nextInt(MatrixChars.size) },
            )
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(bgColor)
            .graphicsLayer {
                alpha = fade.value
                compositingStrategy = CompositingStrategy.ModulateAlpha
            },
        contentAlignment = Alignment.Center,
    ) {
        if (isDark) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                if (rainAlpha.value <= 0f) return@Canvas

                val charW = glyphImages.first().width.toFloat()
                val charH = glyphImages.first().height.toFloat()
                val step = charW * 1.5f
                val cols = (size.width / step).toInt().coerceIn(10, MAX_RAIN_COLUMNS)
                val maxY = size.height + charH * 20

                val glitchStep = (time * 8f).toInt()

                for (i in 0 until cols) {
                    val col = columns[i]
                    val x = i * step
                    val headY = (col.headFactor * maxY + time * col.speedFactor * charH) % maxY

                    for (k in 0 until col.tail) {
                        val y = headY - k * charH
                        if (y < -charH || y > size.height) continue

                        val fadeK = 1f - (k / col.tail.toFloat())
                        val rawIndex = col.glyphs[(k + (headY / charH).toInt()) % col.glyphs.size]
                        val mutatingIndex = (rawIndex + glitchStep + k * 3 + i * 7).mod(MatrixChars.size)

                        drawImage(
                            image = glyphImages[mutatingIndex],
                            topLeft = Offset(x, y),
                            alpha = (fadeK * fadeK * rainAlpha.value).coerceIn(0f, 1f),
                            colorFilter = if (k == 0) null else tailTint,
                        )
                    }
                }
            }
        }

        Image(
            painter = painterResource(logoRes),
            contentDescription = "CueScore",
            modifier = Modifier
                .graphicsLayer {
                    alpha = logoAlpha.value
                    scaleX = logoScale.value
                    scaleY = logoScale.value
                }
                .size(width = 220.dp, height = 104.dp),
        )
    }
}
