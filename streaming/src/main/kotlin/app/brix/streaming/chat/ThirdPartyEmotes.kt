package app.brix.streaming.chat

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

/** The platform for 7TV and BTTV is part of their API URLs. */
enum class EmotePlatform(val apiName: String) {
    TWITCH("twitch"),
    KICK("kick"),
}

/** Network behind an interface—like [KickChannelResolver]—so tests need no real requests.
 *  `null` means an error; an empty string means the service has no such channel (HTTP 404). */
fun interface EmoteHttp {
    suspend fun get(url: String): String?
}

@Serializable
private data class SeventvFile(val name: String, val static_name: String? = null, val width: Int = 0, val height: Int = 0)

@Serializable
private data class SeventvHost(val url: String, val files: List<SeventvFile> = emptyList())

@Serializable
private data class SeventvEmoteData(val host: SeventvHost)

@Serializable
private data class SeventvEmote(val name: String, val data: SeventvEmoteData)

@Serializable
private data class SeventvEmoteSet(val emotes: List<SeventvEmote>? = null)

@Serializable
private data class SeventvUser(val emote_set: SeventvEmoteSet? = null)

@Serializable
private data class BttvEmote(val id: String, val code: String)

@Serializable
private data class BttvChannel(
    val channelEmotes: List<BttvEmote> = emptyList(),
    val sharedEmotes: List<BttvEmote> = emptyList(),
)

private val json = Json { ignoreUnknownKeys = true }

/**
 * Parsers for 7TV and BTTV responses are pure functions, tested against saved responses.
 */
internal object ThirdPartyEmoteParser {

    private fun seventv(emotes: List<SeventvEmote>?): Map<String, ChatPart.Emote> =
        emotes.orEmpty().mapNotNull { e ->
            // 2x is about 64 px tall: a chat row on a 3x screen is 36–60 px,
            // and 1x (32 px) looks noticeably blurry.
            val file = e.data.host.files.firstOrNull { it.name == "2x.webp" }
                ?: e.data.host.files.firstOrNull { it.name.endsWith(".webp") }
                ?: return@mapNotNull null
            val name = file.static_name ?: file.name
            val aspect = if (file.width > 0 && file.height > 0) file.width.toFloat() / file.height else 1f
            e.name to ChatPart.Emote(e.name, "https:${e.data.host.url}/$name", aspect)
        }.toMap()

    fun seventvGlobal(body: String): Map<String, ChatPart.Emote> =
        seventv(json.decodeFromString<SeventvEmoteSet>(body).emotes)

    fun seventvUser(body: String): Map<String, ChatPart.Emote> =
        seventv(json.decodeFromString<SeventvUser>(body).emote_set?.emotes)

    private fun bttvEmote(e: BttvEmote) =
        e.code to ChatPart.Emote(e.code, "https://cdn.betterttv.net/emote/${e.id}/2x")

    fun bttvGlobal(body: String): Map<String, ChatPart.Emote> =
        json.decodeFromString<List<BttvEmote>>(body).associate(::bttvEmote)

    fun bttvUser(body: String): Map<String, ChatPart.Emote> {
        val channel = json.decodeFromString<BttvChannel>(body)
        // Channel emotes take precedence over shared ones—they come last and override them.
        return (channel.sharedEmotes + channel.channelEmotes).associate(::bttvEmote)
    }
}

/**
 * 7TV and BTTV sets for one channel: global plus channel-specific.
 *
 * Enabled by a separate toggle (owner, Sep 16): these are requests to third-party
 * services, and they reveal which channel is open.
 *
 * Merge order matches Moblin: BTTV, then 7TV, with later entries overriding earlier
 * ones. On failure, retry with an increasing delay: services are sometimes slow, and
 * the live stream can run for hours.
 *
 * **Difference from Moblin found during testing on Sep 16:** for Kick, 7TV expects
 * the USER's ID (`user_id`), while Moblin sends the chatroom ID. For channel xqc,
 * `/v3/users/kick/668` (chatroom) returns 404; `/676` (user) returns 968 emotes.
 */
class ThirdPartyEmotes(
    private val scope: CoroutineScope,
    private val http: EmoteHttp = OkHttpEmoteHttp(),
) {
    private val tag = "BrixChat"
    private val _emotes = MutableStateFlow<Map<String, ChatPart.Emote>>(emptyMap())
    val emotes: StateFlow<Map<String, ChatPart.Emote>> = _emotes

    private var job: Job? = null
    private var loadedKey: String? = null

    /** Load sets for a channel. A repeated call for the same channel does nothing. */
    fun load(platform: EmotePlatform, channelId: String) {
        val key = "${platform.apiName}/$channelId"
        if (key == loadedKey) return
        loadedKey = key
        job?.cancel()
        _emotes.value = emptyMap()
        job = scope.launch {
            var pause = 30_000L
            while (true) {
                val result = fetchAll(platform, channelId)
                _emotes.value = result.emotes
                if (!result.failed) {
                    Log.i(tag, "эмодзи 7TV/BTTV: ${result.emotes.size} для ${platform.apiName}")
                    return@launch
                }
                Log.w(tag, "эмодзи 7TV/BTTV: не всё загрузилось, повтор через ${pause / 1000} с")
                delay(pause)
                pause = (pause * 2).coerceAtMost(3_600_000L)
            }
        }
    }

    fun clear() {
        job?.cancel()
        job = null
        loadedKey = null
        _emotes.value = emptyMap()
    }

    private class Result(val emotes: Map<String, ChatPart.Emote>, val failed: Boolean)

    private suspend fun fetchAll(platform: EmotePlatform, channelId: String): Result {
        var failed = false
        val merged = LinkedHashMap<String, ChatPart.Emote>()
        suspend fun take(url: String, parse: (String) -> Map<String, ChatPart.Emote>) {
            val body = http.get(url)
            if (body == null) {
                failed = true
                return
            }
            if (body.isEmpty()) return // The service has no such channel—not an error.
            runCatching { parse(body) }
                .onSuccess { merged.putAll(it) }
                .onFailure { failed = true }
        }
        val p = platform.apiName
        take("https://api.betterttv.net/3/cached/emotes/global", ThirdPartyEmoteParser::bttvGlobal)
        take("https://api.betterttv.net/3/cached/users/$p/$channelId", ThirdPartyEmoteParser::bttvUser)
        take("https://7tv.io/v3/emote-sets/global", ThirdPartyEmoteParser::seventvGlobal)
        take("https://7tv.io/v3/users/$p/$channelId", ThirdPartyEmoteParser::seventvUser)
        return Result(merged, failed)
    }
}

internal class OkHttpEmoteHttp(
    private val client: OkHttpClient = OkHttpClient(),
) : EmoteHttp {
    override suspend fun get(url: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                when {
                    response.code == 404 -> ""
                    response.isSuccessful -> response.body?.string()
                    else -> null
                }
            }
        }.getOrNull()
    }
}
