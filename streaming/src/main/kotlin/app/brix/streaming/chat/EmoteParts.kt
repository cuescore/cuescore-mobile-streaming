package app.brix.streaming.chat

/**
 * Split emotes into message parts — pure functions, no network access.
 *
 * We use STILL images (static variants from Twitch and 7TV, the first frame for
 * other providers): chat is rendered on a phone that is also streaming and
 * heating up, and animating dozens of emotes means decoding frames on every
 * screen update. The approach is borrowed from Moblin (`Integrations/Emotes`),
 * which also has a still-image URL for each image.
 */
object EmoteParts {

    /**
     * Twitch sends emotes in the `emotes` tag: `25:0-4,12-16/1902:6-10`.
     * Positions are in Unicode **code points**, not Kotlin characters: a smiley
     * emoji made of two surrogate units would otherwise shift every later position.
     */
    fun twitch(text: String, emotesTag: String?): List<ChatPart> {
        if (emotesTag.isNullOrBlank()) return listOf(ChatPart.Text(text))
        val codePoints = text.codePoints().toArray()
        val ranges = mutableListOf<Triple<Int, Int, String>>()
        for (definition in emotesTag.split('/')) {
            val id = definition.substringBefore(':', "")
            if (id.isEmpty()) continue
            for (range in definition.substringAfter(':').split(',')) {
                val start = range.substringBefore('-').toIntOrNull() ?: continue
                val end = range.substringAfter('-').toIntOrNull() ?: continue
                if (start < 0 || end < start || end >= codePoints.size) continue
                ranges += Triple(start, end, id)
            }
        }
        if (ranges.isEmpty()) return listOf(ChatPart.Text(text))
        ranges.sortBy { it.first }
        fun slice(from: Int, toExclusive: Int) = String(codePoints, from, toExclusive - from)
        val parts = mutableListOf<ChatPart>()
        var cursor = 0
        for ((start, end, id) in ranges) {
            if (start < cursor) continue // Overlap means a malformed tag; keep the first.
            if (start > cursor) parts += ChatPart.Text(slice(cursor, start))
            parts += ChatPart.Emote(
                name = slice(start, end + 1),
                url = "https://static-cdn.jtvnw.net/emoticons/v2/$id/static/dark/2.0",
            )
            cursor = end + 1
        }
        if (cursor < codePoints.size) parts += ChatPart.Text(slice(cursor, codePoints.size))
        return parts
    }

    private val kickEmote = Regex("""\[emote:(\d+):([^]]+)]""")

    /** Kick embeds emotes directly in the text: `[emote:37226:KEKW]`. */
    fun kick(content: String): List<ChatPart> {
        val parts = mutableListOf<ChatPart>()
        var cursor = 0
        for (match in kickEmote.findAll(content)) {
            if (match.range.first > cursor) parts += ChatPart.Text(content.substring(cursor, match.range.first))
            parts += ChatPart.Emote(
                name = match.groupValues[2],
                url = "https://files.kick.com/emotes/${match.groupValues[1]}/fullsize",
            )
            cursor = match.range.last + 1
        }
        if (cursor < content.length) parts += ChatPart.Text(content.substring(cursor))
        return parts.ifEmpty { listOf(ChatPart.Text(content)) }
    }

    /** Plain text for [ChatMessage.text]: emotes are represented by their names. */
    fun plain(parts: List<ChatPart>): String = parts.joinToString("") {
        when (it) {
            is ChatPart.Text -> it.value
            is ChatPart.Emote -> it.name
        }
    }

    /**
     * 7TV and BTTV emotes have no markup in the message; they're just words
     * matching a name from the channel's set. Split only text parts and only on
     * whitespace — preserve spaces so the string doesn't get joined together.
     */
    fun applyWordEmotes(parts: List<ChatPart>, emotes: Map<String, ChatPart.Emote>): List<ChatPart> {
        if (emotes.isEmpty()) return parts
        val out = mutableListOf<ChatPart>()
        val pending = StringBuilder()
        fun flush() {
            if (pending.isNotEmpty()) {
                out += ChatPart.Text(pending.toString())
                pending.clear()
            }
        }
        for (part in parts) {
            if (part !is ChatPart.Text) {
                flush()
                out += part
                continue
            }
            for (token in splitKeepingSpaces(part.value)) {
                val emote = if (token.isBlank()) null else emotes[token]
                if (emote == null) {
                    pending.append(token)
                } else {
                    flush()
                    out += emote
                }
            }
        }
        flush()
        return out
    }

    private fun splitKeepingSpaces(s: String): List<String> {
        val tokens = mutableListOf<String>()
        var i = 0
        while (i < s.length) {
            val space = s[i].isWhitespace()
            var j = i
            while (j < s.length && s[j].isWhitespace() == space) j++
            tokens += s.substring(i, j)
            i = j
        }
        return tokens
    }
}
