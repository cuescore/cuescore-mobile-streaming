package app.brix.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.random.Random

/** Mirrors real stream state — see mascotStateFor() in StreamScreen.kt. */
enum class MascotState { OFFLINE, IDLE, LIVE, MUTED, RECONNECTING, OVERHEAT, DONATION }

private data class MascotLook(val face: String, val blinkFace: String, val color: Color)

private fun lookFor(state: MascotState): MascotLook = when (state) {
    MascotState.OFFLINE -> MascotLook("?_?", "?-?", Color(0xFF8A857E))
    MascotState.IDLE -> MascotLook("•_•", "-_-", Color(0xFFFFC257))
    // The second face is a BLINK, i.e. closed eyes. It used to be “^o^”: a round
    // eye in the middle of a happy face reads nothing like a blink (the owner,
    // 14.09: “during the stream he gets a butthole on his face”).
    MascotState.LIVE -> MascotLook("^_^", "-_-", Color(0xFFFFC257))
    MascotState.MUTED -> MascotLook("o_x", "-_x", Color(0xFFFFC257))
    MascotState.RECONNECTING -> MascotLook(">.<", "-.-", Color(0xFFFFC257))
    MascotState.OVERHEAT -> MascotLook("x_x", "x_x", Color(0xFFFF6B5B))
    // The same round eye that LIVE used to have, and the same reason to remove it.
    MascotState.DONATION -> MascotLook("\$_\$", "\$_\$", Color(0xFFFFC257))
}

private val CardBg = Color(0xFF140F06)
private val CardBorder = Color(0xFF3A3A3D)
private val OverheatGlow = Color(0xFFFF6B5B)
private val EaseInOutSine = CubicBezierEasing(0.45f, 0f, 0.55f, 1f)

/** About 2.5 s of wobbling during reconnection (280 ms cycle). */
private const val ALARM_WOBBLE_CYCLES = 9

/** Three border pulses during overheating (1 s cycle). */
private const val ALARM_GLOW_CYCLES = 3

/**
 * Live "kaomoji" status mascot — replaces a plain icon in the quick-button
 * grid. Not just a glyph change: it blinks on a random timer, bounces
 * when the state changes or on a donation, briefly wobbles during reconnection,
 * and pulses its border when overheating. There is no constant motion — see the
 * measurement in the function body.
 * Purely decorative — no click behavior of its own.
 */
@Composable
fun BrixMascot(state: MascotState, modifier: Modifier = Modifier) {
    // There are deliberately no infinite animations here. Measurement on an S21
    // on 23.09 during a bare SRTLA stream: while the mascot “breathed” (an infinite
    // transition), the UI drew 60 fps for the whole stream — main thread 30% CPU,
    // render thread 32%. Without the mascot: 8 frames in 5 s, main thread 7.5%,
    // render thread absent from the top CPU consumers. Decorative swaying cost
    // more than half a core, more than all transport on two links. Rule for this
    // file: animate only as a brief response to an event; stay still at rest.

    // Blinks on its own random rhythm, independent of state changes, so it
    // never looks like it blinks "because" something happened — that's what
    // actually reads as alive rather than reactive-only. It blinks at rest too:
    // on 14.09 I removed that after the complaint “its expression changes while AFK,”
    // but that complaint was about something else (see blinkFace for LIVE), so
    // blinking was restored. Two frames every 3–5 seconds are nearly free, unlike breathing.
    var blinking by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(Random.nextLong(2600, 5200))
            blinking = true
            delay(110)
            blinking = false
        }
    }

    // One-shot punch on entering DONATION — on top of (not instead of) the
    // normal state-change transition below.
    val bounce = remember { Animatable(1f) }
    LaunchedEffect(state) {
        if (state == MascotState.DONATION) {
            bounce.snapTo(1.3f)
            bounce.animateTo(
                targetValue = 1f,
                animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
            )
        } else {
            bounce.snapTo(1f)
        }
    }

    // Alarm states draw attention with motion only for the first couple of seconds,
    // then stay still. Previously, reconnection wobble and the overheating border
    // pulse continued for as long as the state lasted — adding load while the phone
    // was already at its worst (the owner, 23.09).
    val wobble = remember { Animatable(0f) }
    LaunchedEffect(state) {
        if (state == MascotState.RECONNECTING) {
            repeat(ALARM_WOBBLE_CYCLES) {
                wobble.animateTo(1f, tween(140, easing = LinearEasing))
                wobble.animateTo(-1f, tween(140, easing = LinearEasing))
            }
            wobble.animateTo(0f, tween(140, easing = LinearEasing))
        } else {
            wobble.snapTo(0f)
        }
    }

    val glow = remember { Animatable(1f) }
    LaunchedEffect(state) {
        if (state == MascotState.OVERHEAT) {
            repeat(ALARM_GLOW_CYCLES) {
                glow.animateTo(0.25f, tween(500, easing = EaseInOutSine))
                glow.animateTo(0.9f, tween(500, easing = EaseInOutSine))
            }
            // Then a bright, static border: overheating remains visible, but nothing moves.
            glow.animateTo(1f, tween(200))
        } else {
            glow.snapTo(1f)
        }
    }

    val borderColor = if (state == MascotState.OVERHEAT) OverheatGlow.copy(alpha = glow.value) else CardBorder

    Box(
        modifier = modifier
            .scale(bounce.value)
            .rotate(wobble.value * 5f)
            .clip(RoundedCornerShape(18.dp))
            .background(CardBg)
            .border(2.dp, borderColor, RoundedCornerShape(18.dp)),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            targetState = state,
            transitionSpec = {
                (
                    scaleIn(initialScale = 0.55f, animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy)) +
                        fadeIn(tween(120))
                    ) togetherWith (scaleOut(targetScale = 0.55f, animationSpec = tween(120)) + fadeOut(tween(90)))
            },
            label = "mascot-face",
        ) { s ->
            val look = lookFor(s)
            Text(
                text = if (blinking) look.blinkFace else look.face,
                color = look.color,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
            )
        }
    }
}
