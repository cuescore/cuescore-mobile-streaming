package app.brix.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.brix.core.withPriority
import app.brix.core.withoutMascot
import app.brix.core.ConnectionPriority
import app.brix.core.AppSettings
import app.brix.core.CueScorePlayer
import app.brix.core.CueScoreTable
import app.brix.core.ButtonAction
import app.brix.core.Ids
import app.brix.core.QuickButtonColumn
import app.brix.core.QuickButtonSlot
import app.brix.core.QuickButtonWidth
import app.brix.core.Scene
import app.brix.core.ServerProfile
import app.brix.core.ServerType
import app.brix.core.SettingsStore
import app.brix.core.StreamProfile
import app.brix.core.defaultConnectionPriorities
import app.brix.core.QuickButtonConfig
import app.brix.core.AppearanceSettings
import app.brix.core.AdvancedSettings
import app.brix.core.AudioSettings
import app.brix.core.HudConfig
import app.brix.core.CameraDefaults
import app.brix.core.SceneWidget
import app.brix.core.WidgetKind
import app.brix.core.ChatSettings
import app.brix.core.MoblinkSettings
import app.brix.core.StreamPreset
import app.brix.core.KickIntegration
import app.brix.core.TwitchIntegration
import app.brix.streaming.kick.KickSession
import app.brix.streaming.chat.OkHttpKickChannelResolver
import app.brix.streaming.twitch.TWITCH_RTMP_INGEST
import app.brix.streaming.twitch.TwitchSession
import app.brix.core.defaultAppSettings
import app.brix.core.withStreamProfilesEnsured
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal const val CUESCORE_SCOREBOARD_URL_PREFIX =
    "https://cuescore.com/scoreboard/stream/?playerId="
internal const val CUESCORE_TABLE_SCOREBOARD_URL_PREFIX =
    "https://cuescore.com/scoreboard/stream/?tableId="

internal sealed interface CueScorePlayerSearchResult {
    data class Found(val players: List<CueScorePlayer>, val nextOffset: Int?) : CueScorePlayerSearchResult
    data object Unavailable : CueScorePlayerSearchResult
}

internal data class CueScoreVenue(val id: String, val name: String, val address: String?)
internal data class CueScoreVenuePage(val venues: List<CueScoreVenue>, val nextOffset: Int?)

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val store = SettingsStore.create(application)

    // Start from defaults so the first composition is instant, then load the
    // persisted settings off the main thread (H13: a synchronous disk + JSON
    // read in the ViewModel constructor blocked the UI on cold start).
    /**
     * That very placeholder object from which things start before the disk is read.
     *
     * Stored separately so we can ask of it, “are these real settings already,
     * or still a placeholder?”—by comparing REFERENCES. The [settingsLoaded] flag does not
     * answer this question: it lives in its own flow and is raised before
     * the new settings reach the screen. In that gap, the
     * “add a server” screen managed to appear on every launch (14.09, caught by log markers:
     * `settingsLoaded=true profiles=0` with three profiles in the model).
     */
    private val placeholder = defaultAppSettings()

    /** Whether these are real settings or the placeholder before disk loading. */
    fun isPlaceholder(candidate: AppSettings): Boolean = candidate === placeholder

    private val _settings = MutableStateFlow(placeholder)
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    // Distinguishes "still the placeholder defaultAppSettings()" from a real
    // (possibly identical-looking) loaded value — MainActivity's language-
    // change recreate() effect needs this to avoid treating the placeholder
    // -> real-settings transition on every cold start as a language change.
    private val _settingsLoaded = MutableStateFlow(false)
    val settingsLoaded: StateFlow<Boolean> = _settingsLoaded.asStateFlow()

    private val _kickViewers = MutableStateFlow<Int?>(null)
    val kickViewers: StateFlow<Int?> = _kickViewers.asStateFlow()

    val twitch = TwitchSession(
        scope = viewModelScope,
        read = { _settings.value.twitch },
        write = { value ->
            _settings.value = _settings.value.copy(twitch = value)
            persist()
        },
    )

    init {
        viewModelScope.launch { app.brix.streaming.DeviceCapabilities.load(application) }
        viewModelScope.launch(Dispatchers.IO) {
            _settings.value = loadSettings()
            _settingsLoaded.value = true
            // Viewers are only needed while live: otherwise, polling once a minute woke
            // the network for nothing, including when the app was minimized (audit 23.09).
            twitch.startBackground { _settings.value.hud.showViewers && isLive() }
        }
        viewModelScope.launch {
            val kick = OkHttpKickChannelResolver()
            while (true) {
                val s = _settings.value
                val slug = s.chat.kickChannel.trim()
                _kickViewers.value = if (s.hud.showViewers && s.chat.kickEnabled && slug.isNotEmpty() && isLive()) {
                    kick.viewers(slug)
                } else {
                    null
                }
                delay(60_000)
            }
        }
    }

    private fun isLive(): Boolean = app.brix.streaming.StreamController.current()?.isStreaming == true

    val kick = KickSession(
        scope = viewModelScope,
        read = { _settings.value.kick },
        write = { value ->
            _settings.value = _settings.value.copy(kick = value)
            persist()
        },
    )

    fun updateKick(value: KickIntegration, commit: Boolean = true) {
        _settings.value = _settings.value.copy(kick = value)
        if (commit) persist()
    }

    suspend fun addKickServer(): Boolean {
        val target = kick.streamTarget() ?: return false
        val s = _settings.value
        val existing = s.serverProfiles.firstOrNull { it.type == ServerType.RTMP && it.name == "Kick" }
        val profile = existing?.copy(baseUrl = target.url, streamId = target.key) ?: ServerProfile(
            id = Ids.newId(),
            name = "Kick",
            type = ServerType.RTMP,
            baseUrl = target.url,
            streamId = target.key,
            enabled = s.serverProfiles.none { it.enabled },
        )
        saveServerProfile(profile)
        return true
    }

    fun updateTwitchWants(value: TwitchIntegration) {
        _settings.value = _settings.value.copy(twitch = value)
        persist()
    }

    suspend fun addTwitchServer(): Boolean {
        val key = twitch.streamKey() ?: return false
        val s = _settings.value
        val existing = s.serverProfiles.firstOrNull { it.type == ServerType.RTMP && it.baseUrl == TWITCH_RTMP_INGEST }
        val profile = existing?.copy(streamId = key) ?: ServerProfile(
            id = Ids.newId(),
            name = "Twitch",
            type = ServerType.RTMP,
            baseUrl = TWITCH_RTMP_INGEST,
            streamId = key,
            enabled = s.serverProfiles.none { it.enabled },
        )
        saveServerProfile(profile)
        return true
    }

    private fun loadSettings(): AppSettings {
        val result = store.load()
        result.exceptionOrNull()?.let { e ->
            Log.e("SettingsViewModel", "Settings file corrupt, resetting to defaults", e)
        }
        // An empty profile list is fixed by inserting one profile, not by
        // resetting the entire file: servers, overlays, and button layouts have
        // nothing to do with it and need not be lost.
        return result.getOrNull()?.withStreamProfilesEnsured() ?: defaultAppSettings()
    }

    // Single-threaded save queue: two rapid mutations must hit the disk in
    // mutation order, otherwise an older snapshot can overwrite a newer one
    //. The conflated state is read INSIDE the queued coroutine.
    private val saveDispatcher = Dispatchers.IO.limitedParallelism(1)

    /** Persist to disk on a background thread — never blocks the main loop. */
    private fun persist() {
        viewModelScope.launch(saveDispatcher) {
            store.save(_settings.value)
        }
    }

    private var debouncedPersistJob: Job? = null

    /**
     * Deferred persistence for actions the user performs in quick succession.
     *
     * `store.save` serializes the entire settings file, calls `fsync`, and copies it to
     * `.bak`. On the toggle in the server list, every tap triggered such a write, and
     * when switching quickly they queued up on the single-threaded
     * dispatcher: the UI responded while state caught up in jumps. Memory
     * is updated immediately; we write to disk once when the user is done.
     */
    private fun persistDebounced(delayMs: Long = 400) {
        debouncedPersistJob?.cancel()
        debouncedPersistJob = viewModelScope.launch(saveDispatcher) {
            delay(delayMs)
            store.save(_settings.value)
        }
    }

    /**
     * Make a server active—all others are disabled.
     *
     * Exactly one is active because that is how the app itself works: the live screen
     * takes `enabledServers.firstOrNull()`, and there is no server switcher there.
     * It was possible to enable several, but the first in the list would still broadcast—
     * the rest were dead weight, and the checkbox promised something it did not do.
     * Hence the radio button instead of a toggle: it cannot express a state
     * that the app does not have.
     */
    fun setActiveServer(id: String) {
        _settings.value = _settings.value.copy(
            serverProfiles = _settings.value.serverProfiles.map { it.copy(enabled = it.id == id) },
        )
        persistDebounced()
    }

    /** Writes whatever is currently in [_settings] to disk right now. For
     *  fields updated live with `commit = false` (Moblink text fields,
     *  connection-priority sliders) — the in-memory state (and anything
     *  reading it, e.g. Moblink's live port/weight) updates on every
     *  keystroke/drag step, but the full serialize+fsync+`.bak` copy only
     *  needs to happen once, when the user is done editing (H: this used to
     *  fire on every keystroke and every slider step). */
    fun flush() {
        persist()
    }

    fun saveStreamProfile(profile: StreamProfile) {
        _settings.value = _settings.value.copy(
            streamProfiles = _settings.value.streamProfiles
                .filterNot { it.id == profile.id } + profile,
        )
        persist()
    }

    fun deleteStreamProfile(id: String) {
        val current = _settings.value
        val remaining = current.streamProfiles.filterNot { it.id == id }
        _settings.value = current.copy(
            streamProfiles = remaining,
            selectedStreamProfileId = if (current.selectedStreamProfileId == id) {
                remaining.firstOrNull()?.id
            } else {
                current.selectedStreamProfileId
            },
        )
        persist()
    }

    fun selectStreamProfile(id: String) {
        _settings.value = _settings.value.copy(selectedStreamProfileId = id)
        persist()
    }

    fun saveServerProfile(profile: ServerProfile) {
        // Preserve the order: edits used to remove a profile and append it to the
        // end, so every save moved it in the list. With the
        // toggle directly in the list, this became obvious: the row moved
        // down under the user's finger.
        val current = _settings.value.serverProfiles
        val updated = if (current.any { it.id == profile.id }) {
            current.map { if (it.id == profile.id) profile else it }
        } else {
            current + profile
        }
        _settings.value = _settings.value.copy(serverProfiles = updated)
        persist()
    }

    fun deleteServerProfile(id: String) {
        _settings.value = _settings.value.copy(
            serverProfiles = _settings.value.serverProfiles.filterNot { it.id == id },
        )
        persist()
    }

    /** Adds profiles from a `brix://`/`moblin://` deep link (see
     *  [app.brix.core.SettingsDeepLink]) — appends, never replaces existing
     *  profiles, since the whole point is combining configs from multiple
     *  devices, not letting one silently clobber the other. Quick-button
     *  layout, if present, DOES replace — it's a single, device-wide
     *  setting, not a list to merge into. */
    fun importSharedConfig(config: app.brix.core.SharedConfig) {
        val current = _settings.value
        _settings.value = current.copy(
            streamProfiles = current.streamProfiles + config.streamProfiles,
            serverProfiles = app.brix.core.mergeImportedServers(current.serverProfiles, config.serverProfiles),
            quickButtons = (config.quickButtons ?: current.quickButtons).withoutMascot(),
            selectedStreamProfileId = current.selectedStreamProfileId
                ?: config.streamProfiles.firstOrNull()?.id,
        )
        persist()
    }

    fun updateQuickButtons(config: QuickButtonConfig) {
        _settings.value = _settings.value.copy(quickButtons = config.withoutMascot())
        persist()
    }

    fun updateCameraDefaults(camera: CameraDefaults) {
        _settings.value = _settings.value.copy(cameraDefaults = camera)
        persist()
    }

    fun updateAppearance(appearance: AppearanceSettings) {
        _settings.value = _settings.value.copy(appearance = appearance)
        persist()
    }

    fun updateHud(hud: HudConfig) {
        _settings.value = _settings.value.copy(hud = hud)
        persist()
    }

    fun updateAdvanced(advanced: AdvancedSettings) {
        _settings.value = _settings.value.copy(advanced = advanced)
        persist()
    }

    fun saveWidget(config: SceneWidget) {
        // Preserve the list order: filterNot+plus would move the edited
        // widget to the end on every save, and the list would shuffle by itself.
        val current = _settings.value.widgets
        val updated = if (current.any { it.id == config.id }) {
            current.map { if (it.id == config.id) config else it }
        } else {
            current + config
        }
        _settings.value = _settings.value.copy(widgets = updated)
        persist()
    }

    fun toggleCueScoreWidget(table: Boolean = false) {
        val current = _settings.value
        val widget = current.widgets.firstOrNull {
            it.kind == WidgetKind.WEB && isCueScoreScoreboardUrl(it.url, table)
        } ?: return
        val enabled = !widget.enabled
        _settings.value = current.copy(
            widgets = current.widgets.map { if (it.id == widget.id) it.copy(enabled = enabled) else it },
            scenes = if (enabled) {
                current.scenes.map { scene ->
                    if (scene.id == current.selectedSceneId && widget.id !in scene.widgetIds) {
                        scene.copy(widgetIds = scene.widgetIds + widget.id)
                    } else {
                        scene
                    }
                }
            } else {
                current.scenes
            },
        )
        persist()
    }

    fun ensureCueScoreQuickButton() {
        val original = _settings.value
        val current = original.copy(
            widgets = original.widgets.map { widget ->
                if (widget.kind == WidgetKind.WEB && isCueScoreScoreboardUrl(widget.url)) {
                    widget.copy(name = "Player scoreboard overlay")
                } else if (widget.kind == WidgetKind.WEB && isCueScoreScoreboardUrl(widget.url, true)) {
                    widget.copy(name = "Table scoreboard overlay")
                } else {
                    widget
                }
            },
        )
        var updated = current
        for (table in listOf(false, true)) {
            if (current.widgets.any { it.kind == WidgetKind.WEB && isCueScoreScoreboardUrl(it.url, table) }) {
                updated = updated.withCueScoreQuickButton(
                    if (table) ButtonAction.CUESCORE_TABLE_SCOREBOARD else ButtonAction.CUESCORE_SCOREBOARD,
                )
            }
        }
        if (updated != original) {
            _settings.value = updated
            persist()
        }
    }

    fun deleteWidget(id: String) {
        _settings.value = _settings.value.copy(
            widgets = _settings.value.widgets.filterNot { it.id == id },
            // Clean up scene references in the same operation: otherwise, a scene retains
            // the ID of a deleted widget, which resurfaces when importing
            // settings or restoring a widget with the same ID.
            scenes = _settings.value.scenes.map { it.copy(widgetIds = it.widgetIds.filterNot { w -> w == id }) },
        )
        persist()
    }

    /** [commit] = false for a live keystroke update (Moblink port/password/
     *  weight fields): the value is applied to in-memory state right away —
     *  MoblinkServer picks it up on the next start/reconfigure — but not
     *  persisted. Callers flush() once editing is done (focus lost / screen
     *  left). */
    fun updateMoblink(settings: MoblinkSettings, commit: Boolean = true) {
        _settings.value = _settings.value.copy(moblink = settings)
        if (commit) persist()
    }

    fun updateChat(settings: ChatSettings, commit: Boolean = true) {
        _settings.value = _settings.value.copy(chat = settings)
        if (commit) persist()
    }

    fun saveCustomPreset(preset: StreamPreset) {
        _settings.value = _settings.value.copy(
            customPresets = _settings.value.customPresets.filterNot { it.name == preset.name } + preset,
        )
        persist()
    }

    /**
     * Persist the first-run wizard result in a single write.
     *
     * The server and quality are saved together with the “completed” flag, not step by step:
     * otherwise, an app closed halfway through would leave half the settings and
     * open the wizard over them again. If specified, the server is immediately made
     * the only active server: the streaming screen uses the first enabled one.
     */
    fun completeOnboarding(
        server: ServerProfile?,
        preset: StreamPreset?,
        cueScorePlayerId: String? = null,
        cueScoreTableId: String? = null,
    ) {
        val s = _settings.value
        val servers = if (server == null) {
            s.serverProfiles
        } else {
            s.serverProfiles.map { it.copy(enabled = false) } + server.copy(enabled = true)
        }
        val profiles = if (preset == null) {
            s.streamProfiles
        } else {
            s.streamProfiles.map { p ->
                if (p.id != s.selectedStreamProfileId) {
                    p
                } else {
                    p.copy(
                        video = p.video.copy(
                            width = preset.width,
                            height = preset.height,
                            fps = preset.fps,
                            bitrateKbps = preset.videoBitrateKbps,
                        ),
                    )
                }
            }
        }
        val appearance = if (s.appearance.language.isBlank()) s.appearance.copy(language = "en") else s.appearance
        val completed = s.copy(
            serverProfiles = servers,
            streamProfiles = profiles,
            appearance = appearance,
            onboardingDone = true,
        )
        _settings.value = completed.withCueScoreOverlays(cueScorePlayerId, cueScoreTableId)
        persist()
    }

    internal fun rememberedCueScorePlayer(): CueScorePlayer? {
        val current = _settings.value
        current.cueScorePlayer?.let { return it }
        // Older setups stored the selected player only in the scoreboard URL.
        val playerId = current.widgets.asSequence()
            .filter { it.kind == WidgetKind.WEB && isCueScoreScoreboardUrl(it.url) }
            .mapNotNull {
                it.url.removePrefix(CUESCORE_SCOREBOARD_URL_PREFIX)
                    .substringBefore('&').toLongOrNull()?.takeIf { id -> id > 0 }
            }
            .firstOrNull() ?: return null
        return CueScorePlayer(playerId.toString())
    }

    internal fun rememberCueScorePlayer(player: CueScorePlayer) {
        _settings.value = _settings.value.copy(cueScorePlayer = player)
        persist()
    }

    internal fun rememberCueScoreTable(table: CueScoreTable) {
        _settings.value = _settings.value.copy(cueScoreTable = table)
        persist()
    }

    internal suspend fun searchCueScoreVenues(query: String, offset: Int = 0): CueScoreVenuePage? =
        requestCueScore("venue/search/?q=${URLEncoder.encode(query.trim(), "UTF-8")}&limit=25&offset=$offset") {
            parseCueScoreVenueSearchResponse(it, offset)
        }

    internal suspend fun lookupCueScoreTables(venue: CueScoreVenue): List<CueScoreTable>? =
        requestCueScore("venue/?id=${venue.id}") { parseCueScoreTablesResponse(it, venue.id) }

    private suspend fun <T> requestCueScore(path: String, parse: (String) -> T): T? = withContext(Dispatchers.IO) {
        val connection = URL("https://api.cuescore.com/$path").openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            val status = connection.responseCode
            if (status !in 200..299) {
                Log.w("CueScoreSearch", "Venue request failed with HTTP $status")
                return@withContext null
            }
            parse(connection.inputStream.bufferedReader().use { it.readText() })
        } catch (e: IOException) {
            Log.w("CueScoreSearch", "Venue request failed", e)
            null
        } catch (e: JSONException) {
            Log.w("CueScoreSearch", "Invalid venue response", e)
            null
        } finally {
            connection.disconnect()
        }
    }

    internal suspend fun searchCueScorePlayers(query: String, offset: Int = 0): CueScorePlayerSearchResult = withContext(Dispatchers.IO) {
        require(query.trim().codePointCount(0, query.trim().length) >= 2)
        require(offset >= 0)
        val encodedQuery = URLEncoder.encode(query.trim(), "UTF-8")
        val connection = URL("https://api.cuescore.com/participant/search/player/?q=$encodedQuery&limit=25&offset=$offset")
            .openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            val status = connection.responseCode
            if (status !in 200..299) {
                Log.w("CueScoreSearch", "Player search failed with HTTP $status")
                return@withContext CueScorePlayerSearchResult.Unavailable
            }
            val response = connection.inputStream.bufferedReader().use { it.readText() }
            parseCueScorePlayerSearchResponse(response, offset)
        } catch (e: IOException) {
            Log.w("CueScoreSearch", "Player search request failed", e)
            CueScorePlayerSearchResult.Unavailable
        } catch (e: JSONException) {
            Log.w("CueScoreSearch", "Invalid player search response", e)
            CueScorePlayerSearchResult.Unavailable
        } finally {
            connection.disconnect()
        }
    }

    fun restartOnboarding() {
        _settings.value = _settings.value.copy(onboardingDone = false)
        persist()
    }

    /**
     * First-run wizard, “import from another Brix or Moblin” branch.
     *
     * Unlike [importSharedConfig] (which appends to existing settings), here
     * there is nothing to replace: a fresh install has one “Default” profile, and
     * the user came specifically with their own settings. Therefore select
     * the imported profile, and make the first enabled server active—exactly
     * one, as required by the live screen (see [setActiveServer]).
     */
    fun completeOnboardingWithImport(
        config: app.brix.core.SharedConfig,
        cueScorePlayerId: String? = null,
        cueScoreTableId: String? = null,
    ) {
        val s = _settings.value
        val activeId = (config.serverProfiles.firstOrNull { it.enabled } ?: config.serverProfiles.firstOrNull())?.id
        val profiles = config.streamProfiles.ifEmpty { s.streamProfiles }
        val completed = s.copy(
            streamProfiles = profiles,
            selectedStreamProfileId = profiles.firstOrNull()?.id ?: s.selectedStreamProfileId,
            serverProfiles = s.serverProfiles.map { it.copy(enabled = false) } +
                config.serverProfiles.map { it.copy(enabled = it.id == activeId) },
            quickButtons = (config.quickButtons ?: s.quickButtons).withoutMascot(),
            onboardingDone = true,
        )
        _settings.value = completed.withCueScoreOverlays(cueScorePlayerId, cueScoreTableId)
        persist()
    }

    fun deleteCustomPreset(name: String) {
        _settings.value = _settings.value.copy(
            customPresets = _settings.value.customPresets.filterNot { it.name == name },
        )
        persist()
    }


    /** [commit] = false while a Slider is being dragged: state updates so the
     *  UI (share %, switch) reflects the new weight instantly, but the
     *  serialize+fsync+`.bak` write is deferred to onValueChangeFinished. */
    fun updateConnectionPriority(
        profileId: String,
        name: String,
        enabled: Boolean,
        weight: Int,
        commit: Boolean = true,
    ) {
        val current = _settings.value
        val updated = current.streamProfiles.map { profile ->
            if (profile.id != profileId) return@map profile
            // The rule and its test are in core (ProfileEditing.kt).
            profile.copy(srtConnectionPriorities = profile.srtConnectionPriorities.withPriority(name, enabled, weight))
        }
        _settings.value = current.copy(streamProfiles = updated)
        if (commit) persist()
    }

    /** [commit] = false while dragging the gain slider: memory is updated on
     *  every step, while the full file write with fsync happens once, on release. */
    // --- Scenes ---
    //
    // The Scene model and AppSettings.scenes/selectedSceneId fields had been in the code from
    // the beginning and were read by NOBODY: the fourth instance of a dead
    // model found today. Here they finally come to life.

    fun saveScene(scene: Scene) {
        val current = _settings.value.scenes
        val updated = if (current.any { it.id == scene.id }) {
            current.map { if (it.id == scene.id) scene else it }
        } else {
            current + scene
        }
        _settings.value = _settings.value.copy(
            scenes = updated,
            // The first scene created immediately becomes active: a scene that
            // no one selected does nothing, and the user would think it
            // doesn't work.
            selectedSceneId = _settings.value.selectedSceneId ?: scene.id,
        )
        persist()
    }

    fun deleteScene(id: String) {
        val current = _settings.value
        val remaining = current.scenes.filterNot { it.id == id }
        _settings.value = current.copy(
            scenes = remaining,
            selectedSceneId = if (current.selectedSceneId == id) remaining.firstOrNull()?.id else current.selectedSceneId,
        )
        persist()
    }

    fun selectScene(id: String?) {
        _settings.value = _settings.value.copy(selectedSceneId = id)
        persist()
    }

    fun updateAudio(audio: AudioSettings, commit: Boolean = true) {
        _settings.value = _settings.value.copy(audio = audio)
        if (commit) persist()
    }
}

internal fun isCueScoreScoreboardUrl(url: String, table: Boolean = false): Boolean =
    url.startsWith(if (table) CUESCORE_TABLE_SCOREBOARD_URL_PREFIX else CUESCORE_SCOREBOARD_URL_PREFIX)

internal fun AppSettings.withCueScoreOverlays(playerId: String?, tableId: String?): AppSettings {
    val withPlayer = playerId?.let { withCueScoreWidget(it, table = false) } ?: this
    return tableId?.let { withPlayer.withCueScoreWidget(it, table = true) } ?: withPlayer
}

private fun AppSettings.withCueScoreWidget(id: String, table: Boolean): AppSettings {
    val numericId = requireNotNull(id.toLongOrNull()?.takeIf { it > 0 }) { "Invalid CueScore ID" }
    val scoreboardUrl = "${if (table) CUESCORE_TABLE_SCOREBOARD_URL_PREFIX else CUESCORE_SCOREBOARD_URL_PREFIX}$numericId"
    val existing = widgets.firstOrNull { it.kind == WidgetKind.WEB && isCueScoreScoreboardUrl(it.url, table) }
    val widget = SceneWidget(
        id = existing?.id ?: Ids.newId(),
        kind = WidgetKind.WEB,
        name = if (table) "Table scoreboard overlay" else "Player scoreboard overlay",
        enabled = true,
        posX = 0.5f,
        posY = 0.5f,
        width = 1f,
        height = 1f,
        url = scoreboardUrl,
    )
    return copy(
        widgets = if (existing == null) widgets + widget else widgets.map { if (it.id == existing.id) widget else it },
        scenes = scenes.map { scene ->
            if (scene.id == selectedSceneId && widget.id !in scene.widgetIds) {
                scene.copy(widgetIds = scene.widgetIds + widget.id)
            } else {
                scene
            }
        },
    ).withCueScoreQuickButton(if (table) ButtonAction.CUESCORE_TABLE_SCOREBOARD else ButtonAction.CUESCORE_SCOREBOARD)
}

private fun AppSettings.withCueScoreQuickButton(action: ButtonAction): AppSettings {
    if (action in quickButtons.slots.map { it.action }) return this
    val occupiedCells = quickButtons.slots.flatMap { slot ->
        if (slot.width == QuickButtonWidth.LARGE) {
            listOf(slot.rowIndex to QuickButtonColumn.LEFT, slot.rowIndex to QuickButtonColumn.RIGHT)
        } else {
            listOfNotNull(slot.columnPosition?.let { slot.rowIndex to it })
        }
    }.toSet()
    val freeCell = (0..4).asSequence().flatMap { row ->
        sequenceOf(QuickButtonColumn.LEFT, QuickButtonColumn.RIGHT).map { column -> row to column }
    }.firstOrNull { it !in occupiedCells } ?: return this
    return copy(
        quickButtons = quickButtons.copy(
            slots = quickButtons.slots + QuickButtonSlot(action, QuickButtonWidth.SMALL, freeCell.first, freeCell.second),
        ),
    )
}

internal fun parseCueScoreVenueSearchResponse(response: String, offset: Int): CueScoreVenuePage {
    val page = JSONObject(response)
    val items = page.getJSONArray("items")
    val venues = (0 until items.length()).map {
        val venue = items.getJSONObject(it)
        val id = venue.getLong("venueId")
        val name = venue.getString("name")
        if (id <= 0 || name.isBlank()) throw JSONException("Invalid venue")
        CueScoreVenue(id.toString(), name, venue.optString("address").takeIf { it.isNotBlank() && it != "null" })
    }
    val next = if (page.getBoolean("hasMore")) page.getInt("nextOffset").also {
        if (it <= offset) throw JSONException("Invalid venue pagination")
    } else null
    return CueScoreVenuePage(venues, next)
}

internal fun parseCueScoreTablesResponse(response: String, venueId: String): List<CueScoreTable> {
    val venue = JSONObject(response)
    if (venue.getLong("venueId").toString() != venueId) throw JSONException("Unexpected venue")
    val name = venue.getString("name")
    val tables = venue.getJSONArray("tables")
    return (0 until tables.length()).map {
        val table = tables.getJSONObject(it)
        val id = table.getLong("tableId")
        val tableName = table.getString("name")
        if (id <= 0 || tableName.isBlank() || name.isBlank()) throw JSONException("Invalid table")
        CueScoreTable(id.toString(), tableName, venueId, name)
    }
}

internal fun parseCueScorePlayerSearchResponse(response: String, offset: Int): CueScorePlayerSearchResult.Found {
    val page = JSONObject(response)
    val items = page.getJSONArray("items")
    val players = (0 until items.length()).map { index ->
        val participant = items.getJSONObject(index)
        val playerId = participant.getLong("playerId")
        val name = participant.getString("name")
        if (playerId <= 0 || name.isBlank()) throw JSONException("Invalid player in search results")
        val country = participant.optJSONObject("country")
        CueScorePlayer(
            id = playerId.toString(),
            name = name,
            imageUrl = participant.optString("image").takeIf { it.isNotBlank() && it != "null" },
            countryImageUrl = country?.optString("image")?.takeIf { it.isNotBlank() && it != "null" },
            countryName = country?.optString("name")?.takeIf { it.isNotBlank() && it != "null" },
        )
    }
    val nextOffset = if (page.getBoolean("hasMore")) {
        page.getInt("nextOffset").also {
            if (it <= offset) throw JSONException("Invalid search pagination offset")
        }
    } else {
        null
    }
    return CueScorePlayerSearchResult.Found(players, nextOffset)
}

fun newStreamProfile(name: String): StreamProfile =
    StreamProfile(id = Ids.newId(), name = name)

fun newWidget(kind: WidgetKind): SceneWidget =
    SceneWidget(id = Ids.newId(), kind = kind)

fun newServerProfile(name: String, url: String): ServerProfile =
    ServerProfile(
        id = Ids.newId(),
        name = name,
        type = if (url.startsWith("srtla://")) ServerType.SRTLA else ServerType.RTMP,
        url = url,
        baseUrl = url,
    )
