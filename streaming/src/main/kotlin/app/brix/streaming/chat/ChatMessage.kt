package app.brix.streaming.chat

enum class ChatPlatform {
    TWITCH,
    KICK,
    VK,
}

/** One rendered chat line — platform-tagged so a merged multi-platform feed
 *  can still tell messages apart (colored badge/dot per source in the UI).
 *  Only what the streamer's screen needs; never fed to the encoder. */
data class ChatMessage(
    val id: String,
    val platform: ChatPlatform,
    val author: String,
    /** "#RRGGBB" from the platform, or null when the user has no color set —
     *  the UI falls back to its own default in that case. */
    val colorHex: String?,
    val text: String,
    val timestampMs: Long,
    /** Text split into “words / emoji” chunks. [text] remains alongside it as a
     *  flat version — emoji there use their names, which makes it readable and searchable. */
    val parts: List<ChatPart> = listOf(ChatPart.Text(text)),
)

sealed class ChatPart {
    data class Text(val value: String) : ChatPart()

    /**
     * Image in a line. [aspect] is width-to-height; wide 7TV emotes can be 3:1,
     * and a square slot would squash them. If a platform does not report a size
     * (Twitch, Kick, BTTV), it stays square.
     */
    data class Emote(val name: String, val url: String, val aspect: Float = 1f) : ChatPart()
}
