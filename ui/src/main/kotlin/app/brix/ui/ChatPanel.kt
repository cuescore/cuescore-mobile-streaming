package app.brix.ui

import android.util.LruCache
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.unit.em
import app.brix.streaming.chat.ChatPart
import app.brix.streaming.chat.EmoteImages
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.brix.streaming.chat.ChatMessage
import app.brix.streaming.chat.ChatPlatform
import kotlin.math.round

private const val FONT_SCALE_MIN = 0.7f
private const val FONT_SCALE_MAX = 1.8f
private const val FONT_SCALE_STEP = 0.1f

/**
  * Chat on the streamer screen — not in the HUD (which has a compact stats line;
  * variable-length text would make it jump), and not in the stream: a regular Compose element
  * above the preview, event-driven, does not touch the GL pipeline.
 *
  * Two pitfalls avoided here:
  * - `items(messages, key = { it.id })` — without a key, LazyColumn redraws
  *   the entire list for every new message;
  * - autoscroll uses `scrollToItem`, not `animateScrollToItem`: animation
  *   would keep rendering frames continuously while chat is open, instead of only on a new
  *   message.
 *
  * The panel’s size and position are adjusted by dragging (see `FractionalBox`/
  * `OverlayTuner` in StreamScreen.kt; entry point is the [onEnterPlacement] icon in the header);
  * font size is controlled with the A± buttons next to the connection status.
 *
  * Multichat: [messages] is already a merged feed from all enabled platforms
  * (see `mergeChatMessages` in :streaming); this receives the ready-made list, without
  * knowing about individual clients.
 */
@Composable
fun ChatPanel(
    messages: List<ChatMessage>,
    platformStatuses: List<Pair<ChatPlatform, Boolean>>,
    fontScale: Float,
    onFontScaleChange: (Float) -> Unit,
    onEnterPlacement: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable { mutableStateOf(true) }
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.scrollToItem(messages.size - 1)
        }
    }

    Column(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
            .padding(8.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.chat_panel_title),
                style = MaterialTheme.typography.labelMedium,
                color = Color.White,
            )
            Spacer(Modifier.width(6.dp))
            // Show an icon and status dot for each ENABLED platform — not one
            // shared dot for all: in multichat it matters to see that Kick in particular
            // disconnected, rather than guessing which of the three is offline.
            platformStatuses.forEach { (platform, isConnected) ->
                PlatformStatusBadge(platform, isConnected)
                Spacer(Modifier.width(6.dp))
            }
            Spacer(Modifier.weight(1f))
            HeaderIconButton(
                icon = Icons.Filled.Remove,
                contentDescription = stringResource(R.string.chat_font_smaller),
                enabled = fontScale > FONT_SCALE_MIN,
                onClick = { onFontScaleChange(stepFontScale(fontScale, -FONT_SCALE_STEP)) },
            )
            HeaderIconButton(
                icon = Icons.Filled.Add,
                contentDescription = stringResource(R.string.chat_font_bigger),
                enabled = fontScale < FONT_SCALE_MAX,
                onClick = { onFontScaleChange(stepFontScale(fontScale, FONT_SCALE_STEP)) },
            )
            HeaderIconButton(
                icon = Icons.Filled.OpenWith,
                contentDescription = stringResource(R.string.chat_change_position),
                onClick = onEnterPlacement,
            )
            HeaderIconButton(
                icon = if (expanded) Icons.Filled.KeyboardArrowDown else Icons.Filled.KeyboardArrowUp,
                contentDescription = stringResource(R.string.chat_panel_title),
                onClick = { expanded = !expanded },
            )
        }
        if (expanded) {
            if (messages.isEmpty()) {
                Text(
                    text = stringResource(R.string.chat_panel_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.padding(top = 8.dp),
                )
            } else {
                LazyColumn(
                    state = listState,
                    // weight, not fillMaxHeight: Column does not impose a minimum
                    // height (see FractionalBox.heightIn(max=...)), and without a
                    // weighted child it would size itself to the content. With a
                    // weighted child, it uses the full available height only when
                    // the list is actually shown.
                    modifier = Modifier.fillMaxWidth().weight(1f, fill = true).padding(top = 4.dp),
                ) {
                    items(messages, key = { it.id }) { message ->
                        ChatMessageRow(message, fontScale)
                    }
                }
            }
        }
    }
}

private fun stepFontScale(current: Float, delta: Float): Float {
    // round() avoids accumulating floating-point error (0.1f is not exact as a float).
    val stepped = round((current + delta) / FONT_SCALE_STEP) * FONT_SCALE_STEP
    return stepped.coerceIn(FONT_SCALE_MIN, FONT_SCALE_MAX)
}

@Composable
private fun HeaderIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    Icon(
        imageVector = icon,
        contentDescription = contentDescription,
        tint = if (enabled) Color.White else Color.White.copy(alpha = 0.35f),
        modifier = Modifier
            .padding(horizontal = 2.dp)
            .size(16.dp)
            .clickable(enabled = enabled, onClick = onClick),
    )
}

/** The same platform uses the same icon everywhere: in the header (connection
  *  status) and before every message (who is speaking). Real
  *  brand icons (simpleicons.org, CC0), not homemade labels. */
private fun platformIconRes(platform: ChatPlatform): Int = when (platform) {
    ChatPlatform.TWITCH -> R.drawable.ic_platform_twitch
    ChatPlatform.KICK -> R.drawable.ic_platform_kick
    ChatPlatform.VK -> R.drawable.ic_platform_vk
}

@Composable
private fun PlatformStatusBadge(platform: ChatPlatform, connected: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Spacer(
            Modifier
                .size(6.dp)
                .background(
                    if (connected) Color(0xFF66E066) else Color(0xFFE06666),
                    CircleShape,
                ),
        )
        Spacer(Modifier.width(3.dp))
        Image(
            painter = painterResource(platformIconRes(platform)),
            contentDescription = null,
            modifier = Modifier.size(13.dp),
        )
    }
}

/** Emoji height as a fraction of font size: larger than letters, but does not expand the line. */
private const val EMOTE_EM = 1.6f

/** Ready-made images for the whole panel: each chat row is recomposed while scrolling,
  *  and without this each redraw would call Glide again. */
private val emoteCache = LruCache<String, ImageBitmap>(300)

@Composable
private fun EmoteImage(emote: ChatPart.Emote) {
    val context = LocalContext.current
    val heightPx = with(LocalDensity.current) { 28.dp.roundToPx() }
    val image by produceState(emoteCache.get(emote.url), emote.url) {
        if (value == null) {
            value = EmoteImages.load(context, emote.url, heightPx, emote.aspect)
                ?.asImageBitmap()
                ?.also { emoteCache.put(emote.url, it) }
        }
    }
    image?.let {
        Image(
            bitmap = it,
            contentDescription = emote.name,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun ChatMessageRow(message: ChatMessage, fontScale: Float) {
    val authorColor = message.colorHex?.let { hex ->
        runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrNull()
    } ?: Color(0xFFB39DDB)
    val baseStyle = MaterialTheme.typography.bodySmall
    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier.padding(vertical = 1.dp),
    ) {
        Image(
            painter = painterResource(platformIconRes(message.platform)),
            contentDescription = null,
            modifier = Modifier
                .padding(top = 2.dp, end = 4.dp)
                .size(12.dp * fontScale),
        )
        // Emoji are inserted directly into the text (InlineTextContent), not as separate
        // Images in a Row: this way they wrap along with the words. The insertion key is the
        // image address: identical emoji in a row share one description.
        val emotes = message.parts.filterIsInstance<ChatPart.Emote>().distinctBy { it.url }
        Text(
            text = buildAnnotatedString {
                withStyle(SpanStyle(color = authorColor, fontWeight = FontWeight.Bold)) {
                    append(message.author)
                }
                append(": ")
                for (part in message.parts) {
                    when (part) {
                        is ChatPart.Text -> append(part.value)
                        is ChatPart.Emote -> appendInlineContent(part.url, part.name)
                    }
                }
            },
            inlineContent = emotes.associate { emote ->
                emote.url to InlineTextContent(
                    Placeholder(
                        width = (EMOTE_EM * emote.aspect.coerceIn(0.5f, 4f)).em,
                        height = EMOTE_EM.em,
                        placeholderVerticalAlign = PlaceholderVerticalAlign.Center,
                    ),
                ) { EmoteImage(emote) }
            },
            // lineHeight in Material styles is a fixed sp value, not a fraction of
            // fontSize: both need to be scaled; otherwise a wrapped line
            // is drawn with the old (small) line spacing and overlaps
            // the next line — the font grows, but the space below it does not.
            style = baseStyle.copy(
                fontSize = baseStyle.fontSize * fontScale,
                lineHeight = baseStyle.lineHeight * fontScale,
            ),
            color = Color.White,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
