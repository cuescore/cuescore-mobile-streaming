package app.brix.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.net.URLDecoder
import java.net.URLEncoder

/** The shareable subset of [AppSettings] carried by a `brix://` link —
 *  stream/server profiles and the quick-button layout. Deliberately not the
 *  whole [AppSettings] (no per-device appearance settings) — this is meant for
 *  moving *stream setup*, not the whole app state.
 *
 *  The previous comment promised that passwords for other people's servers
 *  wouldn't be silently imported. It described import, but the leak was in
 *  EXPORT: the link contained the entire [ServerProfile], including the owner's
 *  stream keys. Now [SettingsDeepLink.encode] strips secrets unless explicitly
 *  asked to include them. */
@Serializable
data class SharedConfig(
    val streamProfiles: List<StreamProfile> = emptyList(),
    val serverProfiles: List<ServerProfile> = emptyList(),
    val quickButtons: QuickButtonConfig? = null,
)

/**
 * `brix://settings?config=<JSON>` — mirrors Moblin's own deep-link design
 * (`MoblinSettingsUrl.swift`: `moblin://...?<raw JSON in query>`, no base64)
 * rather than inventing a new format: plain JSON in a query parameter, so a
 * generated link stays readable in share sheets/browser history.
 *
 * Also does a best-effort **import** of `moblin://` links (one-way — we
 * don't export in Moblin's schema, just read it) by mapping their
 * `streams[]` entries onto our [StreamProfile]/[ServerProfile] pair. Unknown
 * or incompatible fields are simply skipped, not treated as an error.
 *
 * Works on plain strings, not `android.net.Uri` — the latter isn't mockable
 * in plain JVM unit tests without Robolectric (unconfigured here), and the
 * only real dependency on the platform type is at the very edge (MainActivity
 * turning an incoming `Intent.data` into a string, or building an
 * `android.net.Uri` from what [encode] returns to hand to a share sheet).
 */
/**
 * Removes everything from the profile that could let someone stream as the owner.
 *
 * The key is hidden in two places, and missing either means missing both:
 * `streamId` (SRTLA puts `?srtauth=<key>` there) and `baseUrl` itself (RTMP
 * stores the stream key directly in the path: `rtmp://host/live/<key>`).
 *
 * We strip the entire query string from `baseUrl` and the last path segment if
 * there is more than one. This heuristic is intentionally crude: better to give
 * the recipient an address they'll have to finish manually than silently share
 * the key. That's also why the name, type, and port remain—the configuration is
 * transferred, not access.
 */
fun ServerProfile.withoutSecrets(): ServerProfile = copy(
    streamId = "",
    baseUrl = baseUrl.substringBefore('?').let { noQuery ->
        val schemeEnd = noQuery.indexOf("://")
        val authorityStart = if (schemeEnd >= 0) schemeEnd + 3 else 0
        val pathStart = noQuery.indexOf('/', authorityStart)
        if (pathStart < 0) return@let noQuery
        val path = noQuery.substring(pathStart).trim('/')
        if (path.isEmpty() || !path.contains('/')) return@let noQuery
        noQuery.substring(0, pathStart) + "/" + path.substringBeforeLast('/')
    },
)

object SettingsDeepLink {
    const val SCHEME = "brix"
    private const val HOST = "settings"
    private const val PARAM = "config"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }


    /**
     * Builds a full `brix://settings?config=...` URI string.
     *
     * @param includeSecrets whether to include stream keys in the link. NO by default:
     *  the link goes into the share sheet, browser history, and messages, and whoever
     *  receives it can stream to your channel. Enable this knowingly, and only when
     *  moving settings to another device of your own.
     */
    fun encode(settings: AppSettings, includeSecrets: Boolean = false): String {
        // Exclude deprecated profile audio: it contains the headset name, and on
        // another phone it would also override the shared audio settings during migration.
        @Suppress("DEPRECATION")
        val profiles = settings.streamProfiles.map { it.copy(audio = AudioSettings()) }
        val shared = SharedConfig(
            streamProfiles = profiles,
            serverProfiles = if (includeSecrets) {
                settings.serverProfiles
            } else {
                settings.serverProfiles.map { it.withoutSecrets() }
            },
            quickButtons = settings.quickButtons,
        )
        val payload = json.encodeToString(shared)
        val encoded = URLEncoder.encode(payload, "UTF-8")
        return "$SCHEME://$HOST?$PARAM=$encoded"
    }

    /** Decodes a `brix://` link produced by [encode]. Returns null on any
     *  scheme mismatch, missing payload, or malformed JSON — callers should
     *  treat null as "not a config link", not crash. */
    fun decode(uri: String): SharedConfig? {
        if (!uri.startsWith("$SCHEME://")) return null
        val payload = queryParam(uri, PARAM) ?: return null
        return try {
            // A link from an older version may carry audio in the profile. Import
            // discards it; otherwise, on the next load, migration would silently
            // put someone else's audio settings into the shared settings (Sep 23 audit).
            @Suppress("DEPRECATION")
            json.decodeFromString<SharedConfig>(payload).let { c ->
                c.copy(streamProfiles = c.streamProfiles.map { it.copy(audio = AudioSettings()) })
            }
        } catch (_: Exception) {
            null
        }
    }

    /** Best-effort import of a Moblin-shaped settings link — the raw
     *  `{"streams":[...]}` JSON directly in the query, no `config=` key.
     *  Accepts it under either `moblin://` (Moblin's own scheme) or
     *  `brix://` (a hand-built/copy-pasted link using our scheme with
     *  Moblin's payload shape — [decode] already tried and failed on it by
     *  the time callers reach this). See class doc — one-way, lossy by
     *  design (only the fields we have an equivalent for). */
    fun decodeMoblin(uri: String): SharedConfig? {
        if (!uri.startsWith("moblin://") && !uri.startsWith("brix://")) return null
        val query = uri.substringAfter('?', missingDelimiterValue = "")
        if (query.isEmpty()) return null
        return try {
            val decoded = URLDecoder.decode(query, "UTF-8")
            val root = json.parseToJsonElement(decoded).jsonObject
            val streams = (root["streams"] as? JsonArray) ?: return null
            val serverProfiles = mutableListOf<ServerProfile>()
            val streamProfiles = mutableListOf<StreamProfile>()
            for (entry in streams) {
                val stream = entry as? JsonObject ?: continue
                val name = stream["name"]?.jsonPrimitive?.content ?: continue
                val url = stream["url"]?.jsonPrimitive?.content ?: continue
                // Both schemes map to one type: SRT(LA) is one transport with two
                // modes, selected by the address scheme rather than the profile type.
                // `srtla://` is bonding with group registration; `srt://` is a regular
                // receiver without it.
                val type = if (url.startsWith("srt://") || url.startsWith("srtla://")) {
                    ServerType.SRTLA
                } else {
                    ServerType.RTMP
                }
                serverProfiles += ServerProfile(
                    id = Ids.newId(),
                    name = name,
                    type = type,
                    baseUrl = url,
                )
                val video = stream["video"] as? JsonObject
                val bitrateBps = video?.get("bitrate")?.jsonPrimitive?.longOrNull
                val fps = video?.get("fps")?.jsonPrimitive?.intOrNull
                val audio = stream["audio"] as? JsonObject
                val audioBitrateBps = audio?.get("bitrate")?.jsonPrimitive?.longOrNull
                streamProfiles += StreamProfile(
                    id = Ids.newId(),
                    name = name,
                    video = VideoSettings(
                        fps = fps ?: 30,
                        bitrateKbps = ((bitrateBps ?: 5_000_000L) / 1000).toInt(),
                    ),
                    audio = AudioSettings(
                        bitrateKbps = ((audioBitrateBps ?: 128_000L) / 1000).toInt(),
                    ),
                )
            }
            if (serverProfiles.isEmpty()) null else SharedConfig(streamProfiles, serverProfiles)
        } catch (_: Exception) {
            null
        }
    }

    /** Any link we understand: ours, Moblin, or Larix (also used by IRL Pro). */
    fun decodeAny(uri: String): SharedConfig? =
        decode(uri) ?: decodeMoblin(uri) ?: decodeLarix(uri)

    /**
     * Import a Larix Grove link — `larix://set/v1?conn[][url]=…&enc[vid][res]=…`.
     *
     * Both Larix and IRL Pro use this format to share settings (owner report,
     * Sep 16), so it's worth supporting. The format is public:
     * https://softvelum.com/larix/grove/ — connections are an array `conn[]`,
     * encoder settings are in `enc[vid]`/`enc[aud]` blocks, and bitrates are in kbps.
     *
     * Like Moblin, this is one-way and lossy. We take only fields we have an
     * equivalent for: address, name, `srtstreamid`, `srtlatency`, and encoder.
     * We skip the RTMP login (`user`/`pass`) and SRT password (`srtpass`)—we
     * deliberately don't support SRT encryption (see TODO), and RTMP login isn't
     * in our model. Connections with schemes we don't recognize (RIST, WebRTC) are
     * skipped too, rather than breaking the import.
     */
    fun decodeLarix(uri: String): SharedConfig? {
        if (!uri.startsWith("larix://")) return null
        val query = uri.substringAfter('?', missingDelimiterValue = "")
        if (query.isEmpty()) return null
        return try {
            val connections = mutableListOf<MutableMap<String, String>>()
            val encoder = mutableMapOf<String, String>()
            for (pair in query.split('&')) {
                val idx = pair.indexOf('=')
                if (idx < 0) continue
                // Brackets in the key can be literal or URL-encoded (%5B%5D).
                val key = URLDecoder.decode(pair.substring(0, idx), "UTF-8")
                val value = URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
                when {
                    key.startsWith("conn[][") -> {
                        val field = key.removePrefix("conn[][").removeSuffix("]")
                        // `conn[]` is an unindexed array: a new connection starts
                        // when the field has already appeared in the current one.
                        val current = connections.lastOrNull()
                        if (current == null || field in current) {
                            connections += mutableMapOf(field to value)
                        } else {
                            current[field] = value
                        }
                    }
                    key.startsWith("enc[") -> encoder[key] = value
                }
            }
            val servers = connections.mapNotNull { c ->
                val url = c["url"]?.trim().orEmpty()
                val type = when {
                    url.startsWith("srt://") || url.startsWith("srtla://") -> ServerType.SRTLA
                    url.startsWith("rtmp://") || url.startsWith("rtmps://") -> ServerType.RTMP
                    else -> return@mapNotNull null
                }
                ServerProfile(
                    id = Ids.newId(),
                    name = c["name"]?.takeIf { it.isNotBlank() }
                        ?: url.substringAfter("://").substringBefore('/').substringBefore(':'),
                    type = type,
                    baseUrl = url,
                    streamId = if (type == ServerType.SRTLA) c["srtstreamid"].orEmpty() else "",
                    latencyMs = c["srtlatency"]?.toIntOrNull() ?: 2000,
                    enabled = c["active"] != "off",
                )
            }
            if (servers.isEmpty()) return null
            val profiles = if (encoder.isEmpty()) {
                emptyList()
            } else {
                val defaults = VideoSettings()
                val res = encoder["enc[vid][res]"]?.split('x', 'X')?.mapNotNull { it.trim().toIntOrNull() }
                listOf(
                    StreamProfile(
                        id = Ids.newId(),
                        name = "Larix",
                        video = VideoSettings(
                            width = res?.getOrNull(0) ?: defaults.width,
                            height = res?.getOrNull(1) ?: defaults.height,
                            fps = encoder["enc[vid][fps]"]?.toDoubleOrNull()?.toInt() ?: defaults.fps,
                            bitrateKbps = encoder["enc[vid][bitrate]"]?.toIntOrNull() ?: defaults.bitrateKbps,
                            codec = if (encoder["enc[vid][format]"] == "hevc") Codec.HEVC else Codec.H264,
                        ),
                        audio = AudioSettings(
                            bitrateKbps = encoder["enc[aud][bitrate]"]?.toIntOrNull() ?: AudioSettings().bitrateKbps,
                        ),
                    ),
                )
            }
            SharedConfig(streamProfiles = profiles, serverProfiles = servers)
        } catch (_: Exception) {
            null
        }
    }

    private fun queryParam(uri: String, name: String): String? {
        val query = uri.substringAfter('?', missingDelimiterValue = "")
        if (query.isEmpty()) return null
        for (pair in query.split('&')) {
            val idx = pair.indexOf('=')
            if (idx < 0) continue
            val key = pair.substring(0, idx)
            if (key == name) return URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
        }
        return null
    }
}
