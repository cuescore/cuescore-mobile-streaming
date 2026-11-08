package app.brix.core

import kotlinx.serialization.Serializable

@Serializable
enum class ServerType {
    RTMP,
    SRTLA,
    WHIP,
}

@Serializable
enum class Codec {
    H264,
    HEVC,
}

@Serializable
enum class ThemeMode {
    DARK,
    LIGHT,
    SYSTEM,
}

/** Normalizes a stored [AppearanceSettings.language] value into a BCP-47
 *  locale tag ("en", ...) or "" for "follow the system language".
 *  Legacy Russian selections are mapped to English because the app no longer
 *  ships Russian resources. */
fun normalizeLanguageTag(raw: String): String = when {
    raw.isBlank() || raw.equals("AUTO", ignoreCase = true) -> ""
    raw.equals("EN", ignoreCase = true) -> "en"
    raw.equals("RU", ignoreCase = true) || raw.startsWith("ru-", ignoreCase = true) -> "en"
    else -> raw.lowercase()
}

@Serializable
enum class CameraSide {
    BACK,
    FRONT,
}

@Serializable
enum class ButtonAction {
    MUTE,
    TORCH,
    FLIP,
    INFO,
    LOCK,
    BLACK_SCREEN,
    RECONNECT,
    SETTINGS,
    ADAPTIVE_BITRATE,
    OVERLAY,
    VIDEO_EFFECT,
    MASCOT,
    POWER_SAVE,
    MIC,
    SCENE,
    SNAPSHOT,
    CUESCORE_SCOREBOARD,
}

/**
 * Audio input source. Store the device TYPE, not its id: the id lasts only until
 * reconnection, so unplugging and reconnecting a headset would leave the setting
 * pointing nowhere. The type survives both reconnection and reboot.
 */
/**
 * How the system processes audio before it reaches us.
 *
 * This is neither volume nor microphone selection, but the signal-path mode:
 * the system varies noise suppression, echo cancellation and dynamic compression
 * depending on what it thinks the recording is for.
 */
@Serializable
enum class AudioProcessing {
    /** For video. A sensible default: steady levels without aggressive filtering. */
    CAMCORDER,

    /** Standard microphone. */
    MIC,

    /** No processing. For music or when all filtering is handled externally. */
    UNPROCESSED,

    /** For calls: strongest echo and noise suppression, but also processes the voice. */
    VOICE_COMMUNICATION,
}

@Serializable
enum class MicSource {
    /** System choice: usually the bottom microphone, overridden by a headset. */
    AUTO,

    /** Built-in microphone when the phone does not distinguish individual capsules. */
    BUILTIN,

    /** Bottom capsule: the microphone used for phone calls. */
    BUILTIN_BOTTOM,

    /** Capsule near the camera. Measured on S21 on 03.09: `getDevices()` exposes it
     *  as a separate device of the same `TYPE_BUILTIN_MIC` type, distinguished by
     *  `address` ("bottom" versus "back"). Individual capsules can therefore be
     *  selected, just as Moblin on iOS allows through data sources. */
    BUILTIN_BACK,

    /** Top capsule. Not exposed on S21, but many phones provide it. */
    BUILTIN_TOP,

    WIRED,
    BLUETOOTH,
    USB,
}

@Serializable
enum class QuickButtonWidth {
    SMALL,
    LARGE,
}

@Serializable
enum class QuickButtonColumn {
    LEFT,
    RIGHT,
    NULL,
}

@Serializable
data class VideoSettings(
    val width: Int = 1280,
    val height: Int = 720,
    val fps: Int = 30,
    val bitrateKbps: Int = 2500,
    val codec: Codec = Codec.H264,
    val keyframeIntervalSec: Int = 2,
)

@Serializable
data class AudioSettings(
    val sampleRate: Int = 48_000,
    val bitrateKbps: Int = 128,
    val stereo: Boolean = true,
    /** Microphone gain: 1.0 leaves it unchanged, 2.0 doubles the level. A quiet lavalier
     *  or windscreen can reduce the level enough to sound like a whisper on stream. */
    val micGain: Float = 1f,
    val micSource: MicSource = MicSource.AUTO,
    /**
     * The specific device of the selected type, identified by its readable name
     * (`AudioDeviceInfo.productName`, e.g. "WH-1000XM4").
     *
     * Type alone cannot distinguish two devices: with two Bluetooth headsets,
     * the first one found was used. Unlike `id`, the name survives reconnection;
     * unlike Bluetooth `address`, it is not a MAC address, so it can be stored
     * and displayed.
     *
     * Empty means "any device of this type". If the named device is unavailable,
     * fall back to the type: no audio is worse than using another device.
     */
    val micDeviceName: String = "",
    val processing: AudioProcessing = AudioProcessing.CAMCORDER,
)

@Serializable
data class StreamPreset(
    val name: String,
    val width: Int,
    val height: Int,
    val fps: Int,
    val videoBitrateKbps: Int,
    val audioBitrateKbps: Int = 128,
)

val defaultStreamPresets = listOf(
    StreamPreset("720p30 · 2.5M", 1280, 720, 30, 2500),
    StreamPreset("720p60 · 3.5M", 1280, 720, 60, 3500),
    StreamPreset("1080p30 · 4M", 1920, 1080, 30, 4000),
    StreamPreset("1080p60 · 6M", 1920, 1080, 60, 6000),
    StreamPreset("4K30 · 20M", 3840, 2160, 30, 20000),
)

@Serializable
enum class AbrAlgorithm {
    BELABOX,
    FAST_IRL,
    SLOW_IRL,
    CUSTOM,
}

@Serializable
data class AdaptiveBitrateSettings(
    val enabled: Boolean = false,
    val targetBitrateKbps: Int = 6000,
    val minimumBitrateKbps: Int = 1000,
    val initialBitrateKbps: Int = 2500,
    val algorithm: AbrAlgorithm = AbrAlgorithm.BELABOX,
)

@Serializable
data class ConnectionPriority(
    /** Canonical network key: WIFI / CELLULAR / ETHERNET. */
    val name: String,
    val enabled: Boolean = true,
    /**
     * Link rank, 1..10. Its share is weight / sum(enabled weights); 0 means disabled.
     *
     * **Why 1..10 instead of the former 0..100.** The weight goes into
     * `SrtlaConnection.score()`, whose `scaledPriority = 1 + (priority - 1) * factor`
     * formula comes from srtla/BELABOX and expects a RANK: a small number near one.
     * We supplied 0..100 instead: weight 41 produced a fortyfold spread where the
     * algorithm expected only a fewfold difference. A hundred slider steps are
     * unnecessary, and the numbers add no meaning: 4 versus 10 and 41 versus 98
     * express essentially the same thing.
     *
     * Legacy values are converted in `migrate()`.
     */
    val weight: Int = 0,
)

@Serializable
data class AppearanceSettings(
    val theme: ThemeMode = ThemeMode.DARK,
    val dynamicColor: Boolean = false,
    val language: String = "en",
    val autoHideHud: Boolean = true,
)

@Serializable
data class MoblinkSettings(
    val enabled: Boolean = false,
    val port: Int = 7777,
    /** Relay password. Generated on first installation; see [generateMoblinkPassword]. */
    val password: String = "1234",
    /** Weight applied to every connected relay's channel in the SRTLA aggregate
     *  (same scale as [ConnectionPriority.weight]). Relays are dynamic in count,
     *  unlike the fixed WIFI/CELLULAR/ETHERNET slots, so they share one weight
     *  rather than each getting a dedicated priority entry. */
    val relayWeight: Int = 50,
)

/**
 * Twitch chat displayed on the streamer's screen (not in the stream; see HudConfig).
 *
 * Anonymous reading needs no token: login `justinfanNNNNN` without a password.
 * Sending messages is deliberately out of scope: a later iteration with a
 * separate OAuth flow.
 */
@Serializable
data class ChatSettings(
    /** Field name retained from the Twitch-only version: it cannot be renamed,
     *  because serialized settings on users' phones already use this key. */
    val enabled: Boolean = false,
    /** Without "#" or a domain, as entered at twitch.tv/<channel>. */
    val twitchChannel: String = "",
    val kickEnabled: Boolean = false,
    /** Channel slug: the relevant part of kick.com/<slug>. */
    val kickChannel: String = "",
    val vkEnabled: Boolean = false,
    /** Full channel URL, as in their API's channel_url parameter (for example,
     *  https://live.vkvideo.ru/<channel>). */
    val vkChannelUrl: String = "",
    /** Application credentials from dev.live.vkvideo.ru, required for VK: unlike
     *  Twitch/Kick, it has no anonymous reading, only ClientCredentials
     *  (application authentication without user login). Stored in plain text,
     *  like the Moblink password; the risk is limited to this app reading chat. */
    val vkClientId: String = "",
    val vkClientSecret: String = "",
    /** Panel anchor on screen, fractions 0..1 (0.5,0.5 is the center), using
     *  the same scheme as [OverlayConfig.posX]/[posY]. */
    val posX: Float = 0.16f,
    val posY: Float = 0.5f,
    /** Panel size as fractions of the screen. */
    val width: Float = 0.28f,
    val height: Float = 0.42f,
    /** Message font-size multiplier. */
    val fontScale: Float = 1f,
    /** 7TV and BTTV emotes in Twitch and Kick chats. Disabled by default:
     *  these requests send the channel name to third-party services. */
    val thirdPartyEmotes: Boolean = false,
)

/**
 * REPLACES the previous comment, which was outdated in two ways by 03.09.
 *
 * It said "not connected to rendering"; it now is. It also claimed that an empty
 * layer list meant "use the global set". The implementation differs:
 * **an empty list means a clean picture.** Otherwise scenes could not serve their
 * most common purpose: removing everything unnecessary from the frame.
 * A test enforces this behavior, and the scene dialog explains it to the user.
 * Legacy behavior is preserved where it matters: until ANY scene is created,
 * everything enabled is shown.
 */
/**
 * What the scene displays in the frame.
 *
 * Checked against the library and hardware on 03.09:
 * - S21 cannot use two cameras simultaneously (they list each other as conflicting),
 *   so picture-in-picture with two cameras is unavailable;
 * - RootEncoder has no UVC camera support; a third-party library is needed;
 * - screen capture, video files and bitmaps are supported out of the box.
 */
@Serializable
enum class SceneSource {
    /** Phone camera; the side comes from [Scene.camera]. */
    CAMERA,

    /** Holding screen: a still image instead of the camera. An IRL streaming
     *  staple: "be right back" without interrupting the broadcast. */
    IMAGE,

    /** Phone screen: a map, game, chat, or anything the streamer sees.
     *  Requires capture consent, requested again on each app launch:
     *  Android does not allow it to be saved. */
    SCREEN,
}

/**
 * Where the scene gets its audio.
 *
 * Capturing the phone's own audio uses the same token as screen capture, so it
 * requires the same user consent. Not every app permits it: games usually do,
 * music services almost never do. This is their flag and cannot be bypassed.
 */
@Serializable
enum class SceneAudio {
    /** Microphone only, as before. */
    MIC,

    /** Phone audio only: games, videos, music. */
    INTERNAL,

    /** Both: commentary over game audio. */
    BOTH,
}

@Serializable
data class Scene(
    val id: String,
    val name: String,
    val source: SceneSource = SceneSource.CAMERA,
    val audio: SceneAudio = SceneAudio.MIC,
    /** Holding-screen image: content URI from the system file picker. */
    val imageUri: String = "",
    val camera: CameraSide = CameraSide.BACK,
    /** Scene widgets: a unified list; see [SceneWidget]. */
    val widgetIds: List<String> = emptyList(),
    @Deprecated("Слито в widgetIds, читается только миграцией.")
    val overlayIds: List<String> = emptyList(),
    @Deprecated("Слито в widgetIds, читается только миграцией.")
    val browserWidgetIds: List<String> = emptyList(),
)

@Serializable
data class AdvancedSettings(
    val recordStream: Boolean = false,
    /** Record the streaming session to CSV on the phone: power, temperature,
     *  thermal status and transport metrics once per second. Useful when logcat
     *  is unavailable, e.g. a two-hour outing with a power bank and no cable. */
    val debugLog: Boolean = false,
)

@Serializable
data class HudConfig(
    val showBattery: Boolean = true,
    val showThermal: Boolean = true,
    val showNetworks: Boolean = true,
    val showBitrate: Boolean = true,
    val showUptime: Boolean = true,
    val showVersion: Boolean = true,
    /** Current scene name. With multiple scenes, the picture does not always
     *  reveal which is active, especially when they differ only in their layers.
     *  This line appears only on the streamer's screen, not in the stream. */
    val showScene: Boolean = true,
    val showViewers: Boolean = true,
    val showAudioLevel: Boolean = true,
)

@Serializable
data class TwitchIntegration(
    val accessToken: String = "",
    val refreshToken: String = "",
    val expiresAtMs: Long = 0,
    val userId: String = "",
    val login: String = "",
    val grantedScopes: List<String> = emptyList(),
    val wantFollows: Boolean = false,
    val wantSubs: Boolean = false,
    val wantStreamKey: Boolean = false,
) {
    val loggedIn: Boolean get() = refreshToken.isNotBlank() && userId.isNotBlank()

    fun wantedScopes(): List<String> = buildList {
        if (wantFollows) add(SCOPE_FOLLOWERS)
        if (wantSubs) add(SCOPE_SUBSCRIPTIONS)
        if (wantStreamKey) add(SCOPE_STREAM_KEY)
    }

    fun needsRelogin(): Boolean = loggedIn && !grantedScopes.containsAll(wantedScopes())

    fun loggedOut(): TwitchIntegration =
        TwitchIntegration(wantFollows = wantFollows, wantSubs = wantSubs, wantStreamKey = wantStreamKey)

    companion object {
        const val SCOPE_FOLLOWERS = "moderator:read:followers"
        const val SCOPE_SUBSCRIPTIONS = "channel:read:subscriptions"
        const val SCOPE_STREAM_KEY = "channel:read:stream_key"
    }
}

@Serializable
data class KickIntegration(
    val clientId: String = "",
    val clientSecret: String = "",
    val accessToken: String = "",
    val refreshToken: String = "",
    val expiresAtMs: Long = 0,
    val userId: String = "",
    val login: String = "",
    val grantedScopes: List<String> = emptyList(),
    val wantStreamKey: Boolean = false,
) {
    val configured: Boolean get() = clientId.isNotBlank() && clientSecret.isNotBlank()
    val loggedIn: Boolean get() = refreshToken.isNotBlank()

    fun wantedScopes(): List<String> = buildList {
        add(SCOPE_USER)
        add(SCOPE_CHANNEL)
        if (wantStreamKey) add(SCOPE_STREAM_KEY)
    }

    fun needsRelogin(): Boolean = loggedIn && !grantedScopes.containsAll(wantedScopes())

    fun loggedOut(): KickIntegration =
        KickIntegration(clientId = clientId, clientSecret = clientSecret, wantStreamKey = wantStreamKey)

    companion object {
        const val SCOPE_USER = "user:read"
        const val SCOPE_CHANNEL = "channel:read"
        const val SCOPE_STREAM_KEY = "streamkey:read"
        const val REDIRECT_URI = "http://127.0.0.1:58123/callback"
        const val REDIRECT_PORT = 58123
    }
}

@Serializable
data class CameraDefaults(
    val torchOnStart: Boolean = false,
    val defaultCamera: CameraSide = CameraSide.BACK,
    /** Electronic stabilization: crops the frame but suppresses large shakes. */
    val stabilization: Boolean = false,
    /** Optical stabilization: moves the lens without cropping, but handles only
     *  small vibrations. Separate toggle because these mechanisms have different
     *  tradeoffs. The library exposes them separately; before 04.09 we used
     *  only electronic stabilization. */
    val opticalStabilization: Boolean = false,
    /** Mirror the front camera in the PREVIEW, as camera apps do. */
    val mirrorFront: Boolean = true,
    /** Mirror the front camera IN THE STREAM. Separate from preview: streamers
     *  expect a mirror image, but viewers expect a real-world view; otherwise
     *  all text in the frame is reversed. Disabled by default for this reason. */
    val mirrorFrontInStream: Boolean = false,
    /** false = continuous autofocus only (default); true = tap-to-focus mode
     *  (tap a point on the preview to lock focus there briefly). */
    val tapToFocus: Boolean = false,
)

@Serializable
data class QuickButtonSlot(
    val action: ButtonAction,
    val width: QuickButtonWidth,
    val rowIndex: Int,
    val columnPosition: QuickButtonColumn?,
)

@Serializable
data class QuickButtonConfig(
    val slots: List<QuickButtonSlot> = defaultQuickButtonSlots(),
    val cornerSlotAction: ButtonAction? = ButtonAction.MUTE,
)

@Serializable
data class StreamProfile(
    val id: String,
    val name: String,
    val video: VideoSettings = VideoSettings(),
    @Deprecated("Осталось только для миграции в AppSettings.audio.")
    val audio: AudioSettings = AudioSettings(),
    val adaptiveBitrate: AdaptiveBitrateSettings = AdaptiveBitrateSettings(),
    val srtConnectionPriorities: List<ConnectionPriority> = defaultConnectionPriorities(),
)

@Serializable
data class ServerProfile(
    val id: String,
    val name: String,
    val type: ServerType,
    @Deprecated("Kept only for schema migration (url -> baseUrl).")
    val url: String? = null,
    val baseUrl: String = "",
    val streamId: String = "",
    val latencyMs: Int = 2000,
    /** Use only IPv4 when resolving names. Some carriers and receivers handle
     *  IPv6 poorly: the connection establishes, but no data flows. */
    val preferIpv4: Boolean = false,
    val enabled: Boolean = true,
) {
    /**
     * The URL actually used to connect.
     *
     * For RTMP, the stream key is the last path segment: platforms provide the
     * ingest URL and key separately, but the protocol expects them joined.
     * Therefore [streamId] is stored separately and appended here: the form masks
     * it with dots, leaving only a URL safe to show anyone in [baseUrl].
     *
     * Profiles created before the separate field keep the key in [baseUrl]
     * and have an empty [streamId]; their behavior is unchanged.
     *
     * SRTLA keys work differently (the `streamid` query parameter) and are parsed
     * from the URL by the transport, so they are not modified here.
     */
    fun connectUrl(): String {
        val key = streamId.trim()
        if (key.isEmpty()) return baseUrl
        return when (type) {
            // For RTMP, the key is the last path segment.
            ServerType.RTMP -> baseUrl.trimEnd('/') + "/" + key.trimStart('/')
            // For SRTLA, use the `streamid` query parameter, which usually contains
            // `srtauth` with the receiver's access key. Discard the existing query
            // in baseUrl: once this field is filled, it is the source of truth;
            // otherwise we would end up with two streamid parameters.
            ServerType.SRTLA -> baseUrl.substringBefore('?').trimEnd('/') + "?streamid=" + key
            // For WHIP, the key is not included in the URL.
            ServerType.WHIP -> baseUrl
        }
    }
}

@Serializable
data class OverlayConfig(
    val id: String,
    val url: String,
    val enabled: Boolean = true,
    /** Overlay anchor on the video frame, as fractions 0..1 (0.5,0.5 = center). */
    val posX: Float = 0.5f,
    val posY: Float = 0.5f,
    /** Overlay size as fractions of the video frame width/height (0..1). */
    val width: Float = 0.18f,
    val height: Float = 0.18f,
    /** Play alert audio through the device speaker (streamer hears it). */
    val audioOnDevice: Boolean = false,
    /** Mix alert audio into the stream (viewers hear it). */
    val audioInStream: Boolean = false,
    /**
     * Caption size (name, amount, message): a multiplier, with 1.0 as the original.
     *
     * **Why a separate setting.** Text size used to derive from IMAGE WIDTH using
     * the constant 0.18, so the only way to affect it was to randomly resize the
     * overlay window. The owner on 14.09: "it only gets fixed if you randomly
     * change the donation widget's window size".
     */
    val captionScale: Float = 1f,
    /**
     * Whether to show the caption. Disable when the platform widget draws its own text.
     *
     * **There is deliberately no "above or below" option.** Donation alerts follow
     * an established order: image, then nickname and amount, then message text.
     * Users expect it; a control that breaks this order adds unfamiliar behavior
     * rather than flexibility (the owner, 14.09).
     */
    val captionVisible: Boolean = true,
)

/** A generic "browser source" overlay — any URL, rendered live via periodic
 *  WebView snapshots (not the donation-detect pipeline; separate feature,
 *  separate widget list). No audio: Android has no way to capture a
 *  WebView's own audio output into the stream without a MediaProjection
 *  screen-capture consent, which was deliberately ruled out. */
@Serializable
data class BrowserWidgetConfig(
    val id: String,
    val url: String,
    val enabled: Boolean = true,
    val posX: Float = 0.5f,
    val posY: Float = 0.5f,
    val width: Float = 0.18f,
    val height: Float = 0.18f,
    /** How often to re-snapshot the WebView, in ms. Snapshotting is
     *  expensive (main-thread View.draw) — keep this modest. */
    val refreshMs: Long = 200L,
)

/**
 * Scene widget type.
 *
 * Previously there were two lists: "Overlays" (donation alerts) and "Browser widgets".
 * Users had to guess where each item belonged: one browser-based donation widget
 * went in the first list, another in the second (the owner, 14.09).
 * The distinction reflected our implementation, not meaning for the user.
 *
 * Resource use was the other problem. Everything except donation alerts used a
 * WebView, so "add an image" cost an entire browser: measured at 9-10% of a core
 * per widget, and two widgets could easily become four. [WidgetKind.IMAGE] and
 * [WidgetKind.TEXT] render natively without creating a WebView at all.
 */
@Serializable
enum class WidgetKind {
    /** Donation alert: a platform page from which we extract alerts. */
    DONATION_ALERT,

    /** Arbitrary web page, snapshotted every [SceneWidget.refreshMs]. */
    WEB,

    /** Local image (PNG/GIF), without a browser. */
    IMAGE,

    /** Text with substitutions, without a browser. */
    TEXT,
}

/**
 * Scene widget: a unified replacement for [OverlayConfig] and [BrowserWidgetConfig].
 *
 * Fields that apply only to some types live here rather than in subclasses:
 * persistence uses kotlinx, and polymorphic serialization for four types would
 * complicate both the file and migration. Irrelevant fields are simply not read.
 */
@Serializable
data class SceneWidget(
    val id: String,
    val kind: WidgetKind = WidgetKind.DONATION_ALERT,
    /** List name. When empty, show the URL or type. */
    val name: String = "",
    val enabled: Boolean = true,
    /** Frame anchor, fractions 0..1 (0.5,0.5 is the center). */
    val posX: Float = 0.5f,
    val posY: Float = 0.5f,
    /** Size as fractions of frame width and height. */
    val width: Float = 0.18f,
    val height: Float = 0.18f,
    /** [WidgetKind.DONATION_ALERT] and [WidgetKind.WEB]. */
    val url: String = "",
    /** [WidgetKind.DONATION_ALERT]: alert audio through the phone speaker. */
    val audioOnDevice: Boolean = false,
    /** [WidgetKind.DONATION_ALERT]: alert audio in the stream. */
    val audioInStream: Boolean = false,
    /** [WidgetKind.DONATION_ALERT]: caption-size multiplier. */
    val captionScale: Float = 1f,
    /** [WidgetKind.DONATION_ALERT]: whether we draw the caption ourselves. */
    val captionVisible: Boolean = true,
    /** [WidgetKind.WEB]: WebView snapshot interval in ms. Snapshotting is expensive. */
    val refreshMs: Long = 200L,
    /** [WidgetKind.IMAGE]: content URI from the system file picker. */
    val imageUri: String = "",
    /** [WidgetKind.TEXT]: text with substitutions such as {time}. */
    val template: String = "",
    /** [WidgetKind.TEXT]: font-size multiplier. */
    val textScale: Float = 1f,
) {
    /** What to display in the list when no name is set. */
    fun displayTitle(): String = when {
        name.isNotBlank() -> name
        kind == WidgetKind.TEXT -> template
        kind == WidgetKind.IMAGE -> imageUri.substringAfterLast('/')
        else -> url
    }

    fun toOverlayConfig(): OverlayConfig = OverlayConfig(
        id = id,
        url = url,
        enabled = enabled,
        posX = posX,
        posY = posY,
        width = width,
        height = height,
        audioOnDevice = audioOnDevice,
        audioInStream = audioInStream,
        captionScale = captionScale,
        captionVisible = captionVisible,
    )

    fun toBrowserWidgetConfig(): BrowserWidgetConfig = BrowserWidgetConfig(
        id = id,
        url = url,
        enabled = enabled,
        posX = posX,
        posY = posY,
        width = width,
        height = height,
        refreshMs = refreshMs,
    )
}

@Serializable
data class AppSettings(
    /** Audio settings shared by all profiles. Previously each profile held a copy:
     *  users edited them in one place, then switching profiles changed the settings
     *  with no indication why. */
    val audio: AudioSettings = AudioSettings(),
    val streamProfiles: List<StreamProfile> = emptyList(),
    val serverProfiles: List<ServerProfile> = emptyList(),
    val selectedStreamProfileId: String? = null,
    val appearance: AppearanceSettings = AppearanceSettings(),
    val cameraDefaults: CameraDefaults = CameraDefaults(),
    val advanced: AdvancedSettings = AdvancedSettings(),
    val quickButtons: QuickButtonConfig = QuickButtonConfig(),
    val hud: HudConfig = HudConfig(),
    val widgets: List<SceneWidget> = emptyList(),
    @Deprecated("Слито в widgets, читается только миграцией.")
    val overlays: List<OverlayConfig> = emptyList(),
    @Deprecated("Слито в widgets, читается только миграцией.")
    val browserWidgets: List<BrowserWidgetConfig> = emptyList(),
    val customPresets: List<StreamPreset> = emptyList(),
    val moblink: MoblinkSettings = MoblinkSettings(),
    val chat: ChatSettings = ChatSettings(),
    val scenes: List<Scene> = emptyList(),
    val selectedSceneId: String? = null,
    /**
     * Whether the first-run wizard has been completed.
     *
     * `null` means the file predates the wizard (16.09): the user already uses
     * the app, so `migrate()` sets `true` to avoid showing the wizard over a
     * configured stream. Fresh installs get an explicit `false` from
     * [defaultAppSettings]. A plain `false` default would not work: kotlinx omits
     * default values when writing to disk, making old and new files indistinguishable.
     */
    val onboardingDone: Boolean? = null,
    val twitch: TwitchIntegration = TwitchIntegration(),
    val kick: KickIntegration = KickIntegration(),
    @Deprecated("Kept only for schema migration into overlays.")
    val overlayUrl: String? = null,
    @Deprecated("Kept only for schema migration into overlays.")
    val overlayEnabled: Boolean = false,
    @Deprecated("Kept only for schema migration into overlays.")
    val overlayPosX: Float = 0.5f,
    @Deprecated("Kept only for schema migration into overlays.")
    val overlayPosY: Float = 0.5f,
    @Deprecated("Kept only for schema migration into overlays.")
    val overlayWidth: Float = 0.3f,
    @Deprecated("Kept only for schema migration into overlays.")
    val overlayHeight: Float = 0.3f,
    @Deprecated("Kept only for schema migration into overlays.")
    val overlayAudioOnDevice: Boolean = false,
    @Deprecated("Kept only for schema migration into overlays.")
    val overlayAudioInStream: Boolean = false,
) {
    fun selectedStreamProfile(): StreamProfile? =
        streamProfiles.firstOrNull { it.id == selectedStreamProfileId }

    fun selectedScene(): Scene? = scenes.firstOrNull { it.id == selectedSceneId }

    /**
     * Overlays currently in the frame.
     *
     * Until scenes exist, show everything enabled, as before scenes were introduced.
     * Adding scenes must not mean "configure everything again": users without
     * scenes see no change.
     */
    fun activeWidgets(): List<SceneWidget> {
        val scene = selectedScene() ?: return widgets
        return widgets.filter { it.id in scene.widgetIds }
    }

    fun activeOverlays(): List<OverlayConfig> =
        activeWidgets().filter { it.kind == WidgetKind.DONATION_ALERT }.map { it.toOverlayConfig() }

    fun activeBrowserWidgets(): List<BrowserWidgetConfig> =
        activeWidgets().filter { it.kind == WidgetKind.WEB }.map { it.toBrowserWidgetConfig() }

    fun enabledServers(): List<ServerProfile> =
        serverProfiles.filter { it.enabled }

    @Suppress("DEPRECATION")
    fun migrate(): AppSettings {
        val migratedServers = serverProfiles.map { server ->
            if (server.baseUrl.isBlank() && !server.url.isNullOrBlank()) {
                // Clear the old field; otherwise every save writes a stale duplicate
                // of baseUrl back to disk. URL edits would not update it, and its
                // next reader would get an address no longer used for broadcasting.
                // No module reads it (verified), so clearing it is safe and makes
                // migration idempotent.
                server.copy(baseUrl = server.url, url = null)
            } else {
                server
            }
        }
        val migratedOverlays = if (overlays.isEmpty() && !overlayUrl.isNullOrBlank()) {
            listOf(
                OverlayConfig(
                    id = java.util.UUID.randomUUID().toString(),
                    url = overlayUrl,
                    enabled = overlayEnabled,
                    posX = overlayPosX,
                    posY = overlayPosY,
                    width = overlayWidth,
                    height = overlayHeight,
                    audioOnDevice = overlayAudioOnDevice,
                    audioInStream = overlayAudioInStream,
                ),
            )
        } else {
            overlays
        }
        // Move audio from the profile to shared settings. Use the selected profile,
        // since that was what the user saw on the Audio screen. Requiring shared
        // settings to still have defaults makes this a one-time migration:
        // after the first save it no longer runs or overwrites edits.
        @Suppress("DEPRECATION")
        val migratedAudio = if (audio == AudioSettings()) {
            (streamProfiles.firstOrNull { it.id == selectedStreamProfileId } ?: streamProfiles.firstOrNull())
                ?.audio ?: audio
        } else {
            audio
        }
        // Link weights changed from 0..100 to ranks 1..10 (14.09). Values above ten
        // are necessarily legacy: divide and round up so 1..9 do not collapse to
        // zero and silently disable a link. Values at or below 10 are valid ranks
        // and remain unchanged, making migration idempotent.
        // Reset deprecated profile audio after moving it: consuming the migration
        // source makes it genuinely one-time. Previously, resetting shared audio
        // to defaults satisfied the condition again and restored the old profile
        // field. This also keeps that field (including the headset name) out of
        // link exports and diagnostics: kotlinx omits defaults (audit 23.09).
        @Suppress("DEPRECATION")
        val migratedProfiles = streamProfiles.map { profile ->
            profile.copy(
                srtConnectionPriorities = profile.srtConnectionPriorities.map { p ->
                    if (p.weight > 10) p.copy(weight = ((p.weight + 9) / 10).coerceIn(1, 10)) else p
                },
                audio = AudioSettings(),
            )
        }
        // "1234" was the default password before 14.09, known to anyone with the app.
        // Replace it with a random value. This would disrupt a configured pair,
        // but Moblink has never worked with a second phone, so there is nothing
        // to break; a local-network service cannot retain a known password.
        // Migration is idempotent: the replacement is no longer "1234".
        val migratedMoblink = if (moblink.password == "1234") {
            moblink.copy(password = generateMoblinkPassword())
        } else {
            moblink
        }
        // Merge two lists into one (14.09), donation alerts first, browser widgets
        // second. This matches their previous screen order, keeping widgets where
        // users expect them. Clear the old lists after moving them; otherwise they
        // remain permanent duplicates on disk and migration is not idempotent:
        // deleting a widget would restore it on the next read.
        val migratedWidgets = if (widgets.isEmpty() && (migratedOverlays.isNotEmpty() || browserWidgets.isNotEmpty())) {
            migratedOverlays.map { o ->
                SceneWidget(
                    id = o.id,
                    kind = WidgetKind.DONATION_ALERT,
                    enabled = o.enabled,
                    posX = o.posX,
                    posY = o.posY,
                    width = o.width,
                    height = o.height,
                    url = o.url,
                    audioOnDevice = o.audioOnDevice,
                    audioInStream = o.audioInStream,
                    captionScale = o.captionScale,
                    captionVisible = o.captionVisible,
                )
            } + browserWidgets.map { w ->
                SceneWidget(
                    id = w.id,
                    kind = WidgetKind.WEB,
                    enabled = w.enabled,
                    posX = w.posX,
                    posY = w.posY,
                    width = w.width,
                    height = w.height,
                    url = w.url,
                    refreshMs = w.refreshMs,
                )
            }
        } else {
            widgets
        }
        // Scenes referenced two ID lists; merge them into one. Requiring empty
        // widgetIds makes the transfer one-time; otherwise a widget deliberately
        // removed from a scene would be restored.
        val migratedScenes = scenes.map { scene ->
            if (scene.widgetIds.isEmpty() && (scene.overlayIds.isNotEmpty() || scene.browserWidgetIds.isNotEmpty())) {
                scene.copy(
                    widgetIds = scene.overlayIds + scene.browserWidgetIds,
                    overlayIds = emptyList(),
                    browserWidgetIds = emptyList(),
                )
            } else {
                scene
            }
        }
        return copy(
            onboardingDone = onboardingDone ?: true,
            moblink = migratedMoblink,
            serverProfiles = migratedServers,
            widgets = migratedWidgets,
            overlays = emptyList(),
            browserWidgets = emptyList(),
            scenes = migratedScenes,
            audio = migratedAudio,
            streamProfiles = migratedProfiles,
        )
    }
}

/**
 * Moblink password suitable for manual entry.
 *
 * **Why not a long hex string.** Users read this password on one phone and enter it
 * on another, usually outdoors. Nobody will type thirty-two characters; they would
 * replace it with "1234", bringing us back to square one. Eight characters from an
 * unambiguous alphabet provide about forty bits: enough for a service accessible
 * only on the local network and only while streaming.
 *
 * The alphabet excludes easily confused characters: 0 and o, 1 and l/i.
 */
fun generateMoblinkPassword(): String {
    val alphabet = "abcdefghjkmnpqrstuvwxyz23456789"
    val random = java.security.SecureRandom()
    return (1..8).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
}

fun defaultConnectionPriorities(): List<ConnectionPriority> = listOf(
    ConnectionPriority("WIFI", enabled = true, weight = 8),
    ConnectionPriority("CELLULAR", enabled = true, weight = 2),
    ConnectionPriority("ETHERNET", enabled = false, weight = 0),
)

fun defaultQuickButtonSlots(): List<QuickButtonSlot> = listOf(
    QuickButtonSlot(ButtonAction.TORCH, QuickButtonWidth.SMALL, 0, QuickButtonColumn.RIGHT),
    QuickButtonSlot(ButtonAction.FLIP, QuickButtonWidth.SMALL, 0, QuickButtonColumn.LEFT),
    QuickButtonSlot(ButtonAction.INFO, QuickButtonWidth.SMALL, 1, QuickButtonColumn.RIGHT),
    QuickButtonSlot(ButtonAction.LOCK, QuickButtonWidth.SMALL, 1, QuickButtonColumn.LEFT),
    QuickButtonSlot(ButtonAction.ADAPTIVE_BITRATE, QuickButtonWidth.SMALL, 2, QuickButtonColumn.RIGHT),
    QuickButtonSlot(ButtonAction.SETTINGS, QuickButtonWidth.SMALL, 2, QuickButtonColumn.LEFT),
)

/**
 * Restores the "at least one stream profile" invariant WITHOUT losing other settings.
 *
 * Loading previously used `takeIf { streamProfiles.isNotEmpty() } ?: defaultAppSettings()`,
 * so an empty profile list discarded the ENTIRE settings file: servers, overlays,
 * quick-button layout and camera defaults. A single UI line upheld the invariant;
 * breaking it through import, migration or a bug reset the app even though the
 * user had touched nothing except profiles.
 */
/**
 * Clamps SRT latency to a range that can survive the handshake.
 *
 * The value goes into an SRT extension field as UInt16 (`SrtSender.writeUInt16`),
 * so anything above 65535 would silently truncate to garbage: "I set 100000
 * but got 34464". A lower bound is also needed: an overly short receiver buffer
 * cannot recover any lost packets, defeating the reason to use SRT.
 *
 * The bounds 100..10000 cover the operating range of BELABOX ecosystem receivers,
 * where 2000 is typical.
 */
fun clampSrtLatency(ms: Int): Int = ms.coerceIn(100, 10_000)

fun AppSettings.withStreamProfilesEnsured(): AppSettings {
    if (streamProfiles.isNotEmpty()) return this
    val defaults = defaultAppSettings()
    return copy(
        streamProfiles = defaults.streamProfiles,
        selectedStreamProfileId = defaults.selectedStreamProfileId,
    )
}

fun defaultAppSettings(): AppSettings {
    val profile = StreamProfile(
        id = Ids.newId(),
        name = "Default",
    )
    return AppSettings(
        streamProfiles = listOf(profile),
        selectedStreamProfileId = profile.id,
        onboardingDone = false,
    )
}
