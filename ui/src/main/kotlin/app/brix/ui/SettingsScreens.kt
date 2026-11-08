package app.brix.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import android.content.Intent
import app.brix.core.diagnostics.CrashReporter
import app.brix.core.diagnostics.Diagnostics
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.brix.core.CameraSide
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import app.brix.core.SceneWidget
import app.brix.core.WidgetKind
import app.brix.core.normalizeLanguageTag
import app.brix.core.OverlayConfig
import app.brix.core.AudioProcessing
import app.brix.core.MicSource
import app.brix.streaming.MicDevices
import app.brix.core.ServerProfile
import app.brix.core.ThemeMode
import java.util.Locale

sealed interface SettingsRoute {
    data object Menu : SettingsRoute
    data object StreamProfiles : SettingsRoute
    data object ServerProfiles : SettingsRoute
    data object Scenes : SettingsRoute
    data class SceneEdit(val sceneId: String?) : SettingsRoute
    data object ChannelPriorities : SettingsRoute
    data object QuickButtons : SettingsRoute
    data object Camera : SettingsRoute
    data object Audio : SettingsRoute
    data object Appearance : SettingsRoute
    data object Hud : SettingsRoute
    /** Unified scene widget list, replacing "Overlays" and "Browser widgets". */
    data object Widgets : SettingsRoute
    data object Language : SettingsRoute
    data object Advanced : SettingsRoute
    data object About : SettingsRoute
    data object Diagnostics : SettingsRoute
    data object DevicePassport : SettingsRoute
    data class StreamProfileEdit(val profileId: String?) : SettingsRoute
    data class ServerProfileEdit(val profileId: String?) : SettingsRoute
    /** [newKind] is set only for creation: choose the type before opening the form. */
    data class WidgetEdit(val widgetId: String?, val newKind: WidgetKind? = null) : SettingsRoute
}

enum class SettingsCategory {
    STREAM, CAMERA, AUDIO, APPEARANCE, ADVANCED, MOBLINK, CHAT, INTEGRATIONS, CONFIG, ABOUT
}

@Composable
fun CategoryRail(
    selected: SettingsCategory,
    onSelect: (SettingsCategory) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val advancedCategories = setOf(
        SettingsCategory.ADVANCED,
        SettingsCategory.MOBLINK,
        SettingsCategory.INTEGRATIONS,
        SettingsCategory.CONFIG,
    )
    val items = buildList {
        add(SettingsCategory.STREAM to R.string.settings_cat_stream)
        add(SettingsCategory.CAMERA to R.string.settings_cat_camera)
        add(SettingsCategory.AUDIO to R.string.settings_cat_audio)
        add(SettingsCategory.APPEARANCE to R.string.settings_cat_appearance)
        add(SettingsCategory.ADVANCED to R.string.settings_cat_advanced)
        if (selected in advancedCategories) {
            add(SettingsCategory.MOBLINK to R.string.settings_cat_moblink)
            add(SettingsCategory.INTEGRATIONS to R.string.settings_cat_integrations)
            add(SettingsCategory.CONFIG to R.string.settings_cat_config)
        }
        add(SettingsCategory.ABOUT to R.string.settings_cat_about)
    }
    // A thin stripe marks the selected category without filling the whole row.
    Column(
        modifier = modifier
            .fillMaxHeight()
            .width(184.dp)
            .background(MaterialTheme.colorScheme.background),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.settings_title),
                fontSize = MaterialTheme.typography.titleMedium.fontSize,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onClose, modifier = Modifier.size(28.dp)) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = stringResource(R.string.btn_close),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        // Scrollable: the expanded Advanced submenu can make the category rail
        // taller than the fixed-height landscape screen.
        // Without scrolling the last entries would be silently clipped off.
        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            items.forEachIndexed { index, (cat, labelRes) ->
                val active = cat == selected
                val nested = cat == SettingsCategory.MOBLINK ||
                    cat == SettingsCategory.INTEGRATIONS ||
                    cat == SettingsCategory.CONFIG
                Row(
                    // IntrinsicSize.Min: otherwise a row inside a scrolling Column
                    // has infinite measurement height and Box.fillMaxHeight()
                    // below collapses to 0. Filling an infinite maximum cannot
                    // be calculated, so the stripe disappears entirely.
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Min)
                        .clickable { onSelect(cat) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .width(5.dp)
                            .fillMaxHeight()
                            .background(if (active) MaterialTheme.colorScheme.primary else Color.Transparent),
                    )
                    Text(
                        text = stringResource(labelRes),
                        fontSize = MaterialTheme.typography.bodyMedium.fontSize,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,

                        color = if (active) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        // Previously 13: nine rows at that height did not fit
                        // the landscape screen, requiring scrolling to reach
                        // the last two items.
                        modifier = Modifier.padding(
                            start = if (nested) 25.dp else 11.dp,
                            top = 9.dp,
                            bottom = 9.dp,
                            end = 14.dp,
                        ),
                    )
                }
                if (index != items.lastIndex) {
                    HorizontalDivider(
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                    )
                }
            }
        }
    }
}

@Composable
fun CategoryHome(
    category: SettingsCategory,
    viewModel: SettingsViewModel,
    onRoute: (SettingsRoute) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Value directly in the row: before 17.09 only five of eighteen rows showed
    // it. Finding the active server or widget count required opening the
    // category (settings audit).
    val settings by viewModel.settings.collectAsState()
    when (category) {
        // Scaffold + SettingsTopBar: same structure as the other eight categories.
        // Stream and About previously alone put their titles inside LazyColumn
        // (SettingsSectionHeader), not the common header: the inconsistent title
        // placement users complained about.
        SettingsCategory.STREAM -> Scaffold(
            modifier = modifier.fillMaxSize(),
            topBar = { SettingsTopBar(stringResource(R.string.settings_stream)) },
        ) { inner ->
            LazyColumn(
                modifier = Modifier.padding(inner.verticalOnly()).padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    BrixCard {
                        BrixNavRow(
                            stringResource(R.string.settings_startup_guide),
                            divider = true,
                            onClick = { viewModel.restartOnboarding() },
                        )
                        BrixNavRow(
                            stringResource(R.string.settings_server_profiles),
                            value = settings.enabledServers().firstOrNull()?.name
                                ?: stringResource(R.string.value_none),
                            divider = true,
                            onClick = { onRoute(SettingsRoute.ServerProfiles) },
                        )
                        val profile = settings.selectedStreamProfile()
                        BrixNavRow(
                            stringResource(R.string.settings_stream_profiles),
                            value = profile?.let { "${it.video.height}p${it.video.fps}" }
                                ?: stringResource(R.string.value_none),
                            divider = true,
                            onClick = { onRoute(SettingsRoute.StreamProfiles) },
                        )
                        BrixNavRow(
                            stringResource(R.string.settings_scenes),
                            value = settings.selectedScene()?.name
                                ?: settings.scenes.size.takeIf { it > 0 }?.toString()
                                ?: stringResource(R.string.value_none),
                            divider = true,
                            onClick = { onRoute(SettingsRoute.Scenes) },
                        )
                        BrixNavRow(
                            stringResource(R.string.settings_channel_priorities),
                            value = profile?.srtConnectionPriorities?.count { it.enabled }?.toString(),
                            divider = true,
                            onClick = { onRoute(SettingsRoute.ChannelPriorities) },
                        )
                        BrixNavRow(
                            stringResource(R.string.settings_widgets),
                            value = settings.widgets.count { it.enabled }.toString(),
                            divider = false,
                            onClick = { onRoute(SettingsRoute.Widgets) },
                        )
                    }
                }
            }
        }
        SettingsCategory.CAMERA -> CameraSettingsScreen(viewModel = viewModel, modifier = modifier)
        SettingsCategory.AUDIO -> AudioSettingsScreen(viewModel = viewModel, modifier = modifier)
        SettingsCategory.APPEARANCE -> AppearanceSettingsScreen(viewModel = viewModel, onRoute = onRoute, modifier = modifier)
        SettingsCategory.ADVANCED -> AdvancedSettingsScreen(viewModel = viewModel, modifier = modifier)
        SettingsCategory.MOBLINK -> MoblinkSettingsScreen(viewModel = viewModel, modifier = modifier)
        SettingsCategory.CHAT -> ChatSettingsScreen(viewModel = viewModel, modifier = modifier)
        SettingsCategory.INTEGRATIONS -> IntegrationsScreen(viewModel = viewModel, modifier = modifier)
        SettingsCategory.CONFIG -> ConfigTransferScreen(viewModel = viewModel, modifier = modifier)
        SettingsCategory.ABOUT -> Scaffold(
            modifier = modifier.fillMaxSize(),
            topBar = { SettingsTopBar(stringResource(R.string.settings_about)) },
        ) { inner ->
            LazyColumn(
                modifier = Modifier.padding(inner.verticalOnly()).padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    BrixCard {
                        BrixNavRow(stringResource(R.string.settings_about_version), divider = true, onClick = { onRoute(SettingsRoute.About) })
                        BrixNavRow(stringResource(R.string.settings_diagnostics), divider = true, onClick = { onRoute(SettingsRoute.Diagnostics) })
                        BrixNavRow(stringResource(R.string.settings_device_passport), divider = false, onClick = { onRoute(SettingsRoute.DevicePassport) })
                    }
                }
            }
        }
    }
}

/**
 * Only vertical Scaffold insets.
 *
 * In landscape, the system adds horizontal content insets for the display cutout
 * (80 px on the left on S21). The category panel starts at the edge without this
 * inset, while content was pushed away, leaving a gap that looked like a layout
 * bug. The panel already covers the left edge, so discard horizontal insets.
 */
@Composable
internal fun PaddingValues.verticalOnly(): PaddingValues = PaddingValues(
    top = calculateTopPadding(),
    bottom = calculateBottomPadding(),
)

/**
 * Left-aligned title, not centered: otherwise an unpaired back button on the left
 * shifts the apparent title center rightward, as reported in the first version.
 * A fixed-width box ALWAYS reserves arrow space, whether the button exists or
 * not, keeping titles in the same position on category screens (without an arrow)
 * and nested screens (with an arrow), without jumps during navigation.
 */
@Composable
internal fun SettingsTopBar(title: String, onBack: (() -> Unit)? = null) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Back is needed only on actual nested screens: profile editors, HUD,
        // quick buttons. Category screens have nowhere to go back to, since
        // the category list is always on the left; Back there meant nothing.
        // A leftover from before the panel existed.
        Box(modifier = Modifier.size(32.dp), contentAlignment = Alignment.Center) {
            if (onBack != null) {
                IconButton(onClick = onBack, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.btn_back),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * Title with Cancel/Save on either side for editor screens (profile,
 * scene, overlay, widget). Previously each drew two equal `Spacer(weight=1f)`
 * elements, centering the title only when LEFT and RIGHT buttons had equal
 * widths. Cancel and Save differ in length, so the text still shifted.
 * Here the title itself gets `weight(1f)` and `textAlign = Center`, centering
 * in the space remaining between buttons regardless of their width difference.
 */
@Composable
internal fun SettingsEditTopBar(
    title: String,
    onCancel: () -> Unit,
    onSave: () -> Unit,
    saveLabel: String,
    saveEnabled: Boolean = true,
    cancelLabel: String = stringResource(R.string.btn_cancel),
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(shape = BrixButtonShape, onClick = onCancel) { Text(cancelLabel) }
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
        )
        TextButton(shape = BrixButtonShape, onClick = onSave, enabled = saveEnabled) { Text(saveLabel) }
    }
}

/** "Category · Screen" in nested-screen titles shows both destination and
 *  originating category (the left list highlights only the category,
 *  not the current nested screen). */
@Composable
internal fun crumb(categoryRes: Int, screenRes: Int): String =
    "${stringResource(categoryRes)} · ${stringResource(screenRes)}"

@Composable
internal fun SettingsSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
    )
}

@Composable
fun CameraSettingsScreen(
    viewModel: SettingsViewModel,
    modifier: Modifier = Modifier,
) {
    val settings by viewModel.settings.collectAsState()
    val camera = settings.cameraDefaults
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = { SettingsTopBar(stringResource(R.string.settings_camera)) },
    ) { inner ->
        LazyColumn(
            modifier = Modifier.padding(inner.verticalOnly()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                BrixCard {
                    BrixSegmentRow(
                        title = stringResource(R.string.camera_tap_focus),
                        subtitle = stringResource(R.string.camera_tap_focus_hint),
                        options = listOf(
                            false to stringResource(R.string.camera_tap_focus_auto),
                            true to stringResource(R.string.camera_tap_focus_manual),
                        ),
                        selected = camera.tapToFocus,
                        divider = true,
                    ) { viewModel.updateCameraDefaults(camera.copy(tapToFocus = it)) }
                    val caps = rememberDeviceCapabilities()
                    if (caps.electronicStabilization || camera.stabilization) {
                        BrixToggleRow(
                            stringResource(R.string.camera_stabilization),
                            subtitle = stringResource(R.string.camera_stabilization_hint),
                            checked = camera.stabilization,
                            divider = true,
                        ) { viewModel.updateCameraDefaults(camera.copy(stabilization = it)) }
                    }
                    if (caps.opticalStabilization || camera.opticalStabilization) {
                        BrixToggleRow(
                            stringResource(R.string.camera_ois),
                            subtitle = stringResource(R.string.camera_ois_hint),
                            checked = camera.opticalStabilization,
                            divider = true,
                        ) { viewModel.updateCameraDefaults(camera.copy(opticalStabilization = it)) }
                    }
                    BrixToggleRow(
                        stringResource(R.string.camera_mirror_front),
                        subtitle = stringResource(R.string.camera_mirror_front_hint),
                        checked = camera.mirrorFront,
                        divider = true,
                    ) { viewModel.updateCameraDefaults(camera.copy(mirrorFront = it)) }
                    BrixToggleRow(
                        stringResource(R.string.camera_mirror_stream),
                        subtitle = stringResource(R.string.camera_mirror_stream_hint),
                        checked = camera.mirrorFrontInStream,
                        divider = true,
                    ) { viewModel.updateCameraDefaults(camera.copy(mirrorFrontInStream = it)) }
                    if (rememberDeviceCapabilities().torch || camera.torchOnStart) {
                        BrixToggleRow(
                            stringResource(R.string.camera_torch_start),
                            subtitle = stringResource(R.string.camera_torch_start_hint),
                            checked = camera.torchOnStart,
                            divider = false,
                        ) { viewModel.updateCameraDefaults(camera.copy(torchOnStart = it)) }
                    }
                }
            }
            item {
                BrixCard {
                    BrixSegmentRow(
                        title = stringResource(R.string.camera_default),
                        subtitle = stringResource(R.string.camera_default_hint),
                        options = listOf(
                            CameraSide.BACK to stringResource(R.string.camera_back),
                            CameraSide.FRONT to stringResource(R.string.camera_front),
                        ),
                        selected = camera.defaultCamera,
                        divider = false,
                    ) { viewModel.updateCameraDefaults(camera.copy(defaultCamera = it)) }
                }
            }
        }
    }
}

@Composable
fun AudioSettingsScreen(
    viewModel: SettingsViewModel,
    modifier: Modifier = Modifier,
) {
    val settings by viewModel.settings.collectAsState()
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = { SettingsTopBar(stringResource(R.string.settings_audio)) },
    ) { inner ->
        val audio = settings.audio
        LazyColumn(
            modifier = Modifier.padding(inner.verticalOnly()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Organize by meaning, as Moblin does: DEVICE first (audio source),
            // then STREAM (the format sent to viewers). Previously everything
            // was mixed together, with sample rate beside capsule selection
            // despite their different purposes.
            item { AudioGroupTitle(stringResource(R.string.audio_group_stream)) }
            item {
                BrixCard {
                    BrixSegmentRow(
                        title = stringResource(R.string.field_sample_rate),
                        options = listOf(44100 to "44.1 kHz", 48000 to "48 kHz"),
                        selected = audio.sampleRate,
                        divider = false,
                    ) { viewModel.updateAudio(audio.copy(sampleRate = it)) }
                }
            }
            item {
                BrixCard {
                    BrixSegmentRow(
                        title = stringResource(R.string.field_audio_bitrate),
                        options = listOf(64 to "64", 128 to "128", 192 to "192", 256 to "256"),
                        selected = audio.bitrateKbps,
                        divider = false,
                    ) { viewModel.updateAudio(audio.copy(bitrateKbps = it)) }
                }
            }
            item {
                BrixCard {
                    BrixToggleRow(
                        stringResource(R.string.field_stereo),
                        subtitle = stringResource(R.string.field_stereo_hint),
                        checked = audio.stereo,
                        divider = false,
                    ) { viewModel.updateAudio(audio.copy(stereo = it)) }
                }
            }
            item { AudioGroupTitle(stringResource(R.string.audio_group_input)) }
            item {
                BrixCard {
                    // Show ONLY connected devices: Bluetooth without a headset
                    // is as misleading as a toggle with no implementation.
                    // Recalculate on every redraw because headsets can be
                    // connected mid-stream.
                    val context = LocalContext.current
                    val mics = MicDevices.available(context)
                    BrixSegmentRow(
                        title = stringResource(R.string.field_audio_processing),
                        options = listOf(
                            AudioProcessing.CAMCORDER to stringResource(R.string.audio_proc_camcorder),
                            AudioProcessing.MIC to stringResource(R.string.audio_proc_mic),
                            AudioProcessing.UNPROCESSED to stringResource(R.string.audio_proc_raw),
                            AudioProcessing.VOICE_COMMUNICATION to stringResource(R.string.audio_proc_voice),
                        ),
                        selected = audio.processing,
                        divider = true,
                    ) { viewModel.updateAudio(audio.copy(processing = it)) }
                    Text(
                        text = stringResource(R.string.field_audio_processing_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                    )
                    // Type alone cannot distinguish devices: with two Bluetooth
                    // headsets, the first one found was used. When a selected
                    // type has multiple devices, ask which one. With only one,
                    // do not ask: a single-item dialog is work without benefit.
                    var pickFor by remember { mutableStateOf<MicSource?>(null) }
                    BrixSegmentRow(
                        title = stringResource(R.string.field_mic_source),
                        options = mics.map { it to micSourceLabel(it) },
                        selected = if (audio.micSource in mics) audio.micSource else MicSource.AUTO,
                        divider = false,
                    ) { picked ->
                        val names = MicDevices.deviceNamesFor(context, picked)
                        if (names.size > 1) {
                            pickFor = picked
                        } else {
                            viewModel.updateAudio(
                                audio.copy(micSource = picked, micDeviceName = names.firstOrNull().orEmpty()),
                            )
                        }
                    }
                    // Selected device name below the row. Without it, users see
                    // Bluetooth but cannot tell which of two headsets is selected.
                    if (audio.micDeviceName.isNotBlank()) {
                        Text(
                            text = audio.micDeviceName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                        )
                    }
                    pickFor?.let { source ->
                        MicDevicePickerDialog(
                            names = MicDevices.deviceNamesFor(context, source),
                            selected = audio.micDeviceName,
                            onDismiss = { pickFor = null },
                        ) { name ->
                            viewModel.updateAudio(audio.copy(micSource = source, micDeviceName = name))
                            pickFor = null
                        }
                    }
                }
            }
            item {
                BrixCard {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            text = stringResource(
                                R.string.field_mic_gain,
                                (audio.micGain * 100).toInt(),
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Slider(
                            value = audio.micGain,
                            // Write to disk only on release: otherwise each drag
                            // step would serialize the entire file with fsync,
                            // repeating the server-toggle mistake.
                            onValueChange = {
                                viewModel.updateAudio(
                                    audio.copy(micGain = (it * 20).toInt() / 20f),
                                    commit = false,
                                )
                            },
                            onValueChangeFinished = { viewModel.updateAudio(audio) },
                            valueRange = 0.5f..3f,
                        )
                        Text(
                            text = stringResource(R.string.field_mic_gain_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun AppearanceSettingsScreen(
    viewModel: SettingsViewModel,
    onRoute: (SettingsRoute) -> Unit,
    modifier: Modifier = Modifier,
) {
    val settings by viewModel.settings.collectAsState()
    val appearance = settings.appearance
    val normalizedLanguage = normalizeLanguageTag(appearance.language)
    val languageLabel = if (normalizedLanguage.isEmpty()) {
        stringResource(R.string.language_auto)
    } else {
        displayNameForLanguageTag(normalizedLanguage)
    }
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = { SettingsTopBar(stringResource(R.string.settings_appearance)) },
    ) { inner ->
        LazyColumn(
            modifier = Modifier.padding(inner.verticalOnly()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                BrixCard {
                    BrixSegmentRow(
                        title = stringResource(R.string.appearance_theme),
                        options = listOf(
                            ThemeMode.DARK to stringResource(R.string.appearance_dark),
                            ThemeMode.LIGHT to stringResource(R.string.appearance_light),
                            ThemeMode.SYSTEM to stringResource(R.string.appearance_system),
                        ),
                        selected = appearance.theme,
                        divider = false,
                    ) { viewModel.updateAppearance(appearance.copy(theme = it)) }
                }
            }
            item {
                BrixCard {
                    BrixToggleRow(
                        stringResource(R.string.appearance_dynamic),
                        subtitle = stringResource(R.string.appearance_dynamic_hint),
                        checked = appearance.dynamicColor,
                        divider = true,
                    ) { viewModel.updateAppearance(appearance.copy(dynamicColor = it)) }
                    BrixNavRow(
                        stringResource(R.string.appearance_language),
                        subtitle = stringResource(R.string.appearance_language_hint),
                        value = languageLabel,
                        divider = true,
                        onClick = { onRoute(SettingsRoute.Language) },
                    )
                    BrixToggleRow(
                        stringResource(R.string.appearance_auto_hide_hud),
                        subtitle = stringResource(R.string.appearance_auto_hide_hud_hint),
                        checked = appearance.autoHideHud,
                        divider = false,
                    ) { viewModel.updateAppearance(appearance.copy(autoHideHud = it)) }
                }
            }
            item {
                BrixCard {
                    // HUD moved here from Stream on 03.09. It concerns what the
                    // streamer sees, not broadcast parameters; beside servers
                    // it was confusing. Moblin's equivalent (Local overlays)
                    // also lives under Display. HUD auto-hide is already on this
                    // screen, so the setting and its contents are now together.
                    val hud = settings.hud
                    val hudShown = listOf(
                        hud.showBattery, hud.showThermal, hud.showNetworks, hud.showBitrate,
                        hud.showUptime, hud.showVersion, hud.showScene, hud.showViewers,
                    ).count { it }
                    BrixNavRow(
                        stringResource(R.string.settings_hud),
                        value = "$hudShown/8",
                        divider = true,
                        onClick = { onRoute(SettingsRoute.Hud) },
                    )
                    BrixNavRow(
                        stringResource(R.string.settings_quick_buttons),
                        value = settings.quickButtons.slots.size.toString(),
                        divider = false,
                        onClick = { onRoute(SettingsRoute.QuickButtons) },
                    )
                }
            }
        }
    }
}

/** Display name for a locale tag in that locale's own language. */
/**
 * Languages the app is actually translated into.
 *
 * `assets.locales` includes not only our values-xx directories but all Android
 * system-resource languages. The first-run wizard consequently listed dozens
 * of languages despite only two translations (16.09). Verify each candidate:
 * its localized `locale_tag` must name that same language. Without a translation,
 * Android returns the base resource and the tag mismatches. Translators still
 * need no Kotlin, only `locale_tag` in their resource file.
 */
internal fun supportedAppLanguages(context: android.content.Context): List<String> =
    context.assets.locales
        .map { it.lowercase().substringBefore("-") }
        .filter { it.isNotBlank() }
        .distinct()
        .filter { tag ->
            val config = android.content.res.Configuration(context.resources.configuration)
            config.setLocale(java.util.Locale.forLanguageTag(tag))
            context.createConfigurationContext(config).getString(R.string.locale_tag) == tag
        }
        .sorted()

internal fun displayNameForLanguageTag(tag: String): String {
    val locale = Locale.forLanguageTag(tag)
    return locale.getDisplayLanguage(locale).replaceFirstChar { it.titlecase(locale) }
}

@Composable
fun LanguageScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val settings by viewModel.settings.collectAsState()
    val appearance = settings.appearance
    val normalizedLanguage = normalizeLanguageTag(appearance.language)
    val context = LocalContext.current
    // No hardcoded list: any values-xx/ resource folder this APK ships makes
    // "xx" show up here automatically — a translator adding a new language
    // needs to touch zero Kotlin code, just drop in a translated strings.xml.
    val options = remember(context) {
        listOf("" to null) + supportedAppLanguages(context).map { it to it }
    }
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = { SettingsTopBar(crumb(R.string.settings_appearance, R.string.appearance_language), onBack) },
    ) { inner ->
        LazyColumn(
            modifier = Modifier.padding(inner.verticalOnly()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                BrixCard {
                    options.forEachIndexed { index, (tag, displayTag) ->
                        val label = if (displayTag == null) {
                            stringResource(R.string.language_auto)
                        } else {
                            displayNameForLanguageTag(displayTag)
                        }
                        val selected = normalizedLanguage == tag
                        BrixNavRow(
                            title = label,
                            value = if (selected) "✓" else null,
                            divider = index != options.lastIndex,
                            onClick = { viewModel.updateAppearance(appearance.copy(language = tag)) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun AdvancedSettingsScreen(
    viewModel: SettingsViewModel,
    modifier: Modifier = Modifier,
) {
    val settings by viewModel.settings.collectAsState()
    val advanced = settings.advanced
    val context = LocalContext.current
    Scaffold(
        modifier = modifier.fillMaxSize(),
        // No onBack: this is the Advanced category screen; the only destination
        // is another category on the left. Back would lead to where we already are.
        // The title matches the left menu item (Advanced), not the separate
        // settings_advanced_screen string ("SRTLA, recording and debugging"),
        // which matched nothing else in the UI and only caused confusion.
        topBar = { SettingsTopBar(stringResource(R.string.settings_cat_advanced)) },
    ) { inner ->
        LazyColumn(
            modifier = Modifier.padding(inner.verticalOnly()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                BrixCard {
                    BrixToggleRow(
                        stringResource(R.string.advanced_record),
                        subtitle = stringResource(R.string.advanced_record_hint),
                        checked = advanced.recordStream,
                        divider = false,
                    ) { viewModel.updateAdvanced(advanced.copy(recordStream = it)) }
                }
            }
        }
    }
}

@Composable
fun AboutScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val version = remember(context) {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
        } catch (e: Exception) {
            "?"
        }
    }
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = { SettingsTopBar(crumb(R.string.settings_about, R.string.settings_about_version), onBack) },
    ) { inner ->
        LazyColumn(
            modifier = Modifier.padding(inner.verticalOnly()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    androidx.compose.foundation.Image(
                        painter = androidx.compose.ui.res.painterResource(
                            if (MaterialTheme.colorScheme.background.luminance() < 0.5f) {
                                R.drawable.ic_cuescore_logo_dark
                            } else {
                                R.drawable.ic_cuescore_logo_light
                            },
                        ),
                        contentDescription = null,
                        modifier = Modifier
                            .padding(vertical = 16.dp)
                            .size(width = 200.dp, height = 95.dp),
                    )
                }
            }
            item {
                BrixCard {
                    BrixNavRow(
                        stringResource(R.string.app_name),
                        value = version,
                        divider = false,
                        onClick = {},
                    )
                }
            }
            item {
                BrixCard {
                    BrixNavRow(
                        stringResource(R.string.about_license),
                        divider = true,
                        onClick = {},
                    )
                    BrixNavRow(
                        stringResource(R.string.about_source),
                        divider = false,
                        onClick = {},
                    )
                }
            }
        }
    }
}

@Composable
fun StreamProfilesScreen(
    viewModel: SettingsViewModel,
    onEdit: (String?) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val settings by viewModel.settings.collectAsState()
    var pendingDelete by remember { mutableStateOf<String?>(null) }
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = { SettingsTopBar(crumb(R.string.settings_stream, R.string.settings_stream_profiles), onBack) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding.verticalOnly())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            BrixCard {
                settings.streamProfiles.forEachIndexed { index, profile ->
                    // Audio is shared across profiles (AppSettings.audio); the
                    // old profile field showed values the streamer no longer reads.
                    val a = settings.audio
                    val audioMode = stringResource(
                        if (a.stereo) R.string.audio_stereo else R.string.audio_mono,
                    )
                    ProfileRow(
                        title = profile.name,
                        subtitle = "${profile.video.width}x${profile.video.height} · " +
                            "${profile.video.bitrateKbps} kbps · ${profile.video.fps} fps · " +
                            stringResource(
                                R.string.audio_info,
                                a.sampleRate,
                                a.bitrateKbps,
                                audioMode,
                            ),
                        selected = settings.selectedStreamProfileId == profile.id,
                        onSelect = { viewModel.selectStreamProfile(profile.id) },
                        onClick = { onEdit(profile.id) },
                        onDelete = {
                            pendingDelete = if (settings.streamProfiles.size == 1) {
                                "last"
                            } else {
                                profile.id
                            }
                        },
                    )
                    if (index != settings.streamProfiles.lastIndex) {
                        HorizontalDivider(
                            thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            FilledTonalButton(
                shape = BrixButtonShape,
                onClick = { onEdit(null) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.btn_add))
            }
            if (settings.twitch.loggedIn &&
                app.brix.core.TwitchIntegration.SCOPE_STREAM_KEY in settings.twitch.grantedScopes
            ) {
                val scope = androidx.compose.runtime.rememberCoroutineScope()
                var twitchResult by remember { mutableStateOf<Boolean?>(null) }
                Spacer(Modifier.height(8.dp))
                FilledTonalButton(
                    shape = BrixButtonShape,
                    onClick = { scope.launch { twitchResult = viewModel.addTwitchServer() } },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.twitch_add_server))
                }
                if (twitchResult == false) {
                    Text(
                        stringResource(R.string.twitch_server_failed),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            if (settings.kick.loggedIn &&
                app.brix.core.KickIntegration.SCOPE_STREAM_KEY in settings.kick.grantedScopes
            ) {
                val kickScope = androidx.compose.runtime.rememberCoroutineScope()
                var kickResult by remember { mutableStateOf<Boolean?>(null) }
                Spacer(Modifier.height(8.dp))
                FilledTonalButton(
                    shape = BrixButtonShape,
                    onClick = { kickScope.launch { kickResult = viewModel.addKickServer() } },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.kick_add_server))
                }
                if (kickResult == false) {
                    Text(
                        stringResource(R.string.twitch_server_failed),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
    if (pendingDelete != null) {
        BrixDialog(
            title = stringResource(R.string.dialog_delete_profile_title),
            onDismiss = { pendingDelete = null },
            dismissLabel = stringResource(R.string.btn_cancel),
            confirmLabel = stringResource(R.string.btn_delete),
            destructive = true,
            onConfirm = {
                pendingDelete?.let { if (it != "last") viewModel.deleteStreamProfile(it) }
                pendingDelete = null
            },
        ) {
            if (pendingDelete == "last") {
                Text(
                    text = stringResource(R.string.dialog_delete_profile_last),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
fun ServerProfilesScreen(
    viewModel: SettingsViewModel,
    onEdit: (String?) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val settings by viewModel.settings.collectAsState()
    var pendingDelete by remember { mutableStateOf<Pair<ServerProfile, Boolean>?>(null) }
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = { SettingsTopBar(crumb(R.string.settings_stream, R.string.settings_server_profiles), onBack) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding.verticalOnly())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            BrixCard {
                settings.serverProfiles.forEachIndexed { index, server ->
                    ProfileRow(
                        title = server.name,
                        subtitle = "${server.type} · ${
                            if (server.enabled) stringResource(R.string.settings_enabled)
                            else stringResource(R.string.settings_disabled)
                        }",
                        selected = server.enabled,
                        onSelect = { viewModel.setActiveServer(server.id) },
                        onClick = { onEdit(server.id) },
                        onDelete = {
                            val enabledCount = settings.serverProfiles.count { it.enabled }
                            pendingDelete = server to (server.enabled && enabledCount == 1)
                        },
                    )
                    if (index != settings.serverProfiles.lastIndex) {
                        HorizontalDivider(
                            thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            FilledTonalButton(
                shape = BrixButtonShape,
                onClick = { onEdit(null) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.btn_add))
            }
        }
    }
    if (pendingDelete != null) {
        val (server, isLastEnabled) = pendingDelete!!
        BrixDialog(
            title = stringResource(R.string.dialog_delete_server_title),
            onDismiss = { pendingDelete = null },
            dismissLabel = stringResource(R.string.btn_cancel),
            confirmLabel = stringResource(R.string.btn_delete),
            destructive = true,
            onConfirm = {
                viewModel.deleteServerProfile(server.id)
                pendingDelete = null
            },
        ) {
            if (isLastEnabled) {
                Text(
                    text = stringResource(R.string.dialog_delete_server_last_enabled),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
fun DiagnosticsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val settings by viewModel.settings.collectAsState()
    val scope = rememberCoroutineScope()
    var crashCount by remember { mutableStateOf(0) }
    var sessionLogs by remember { mutableStateOf(0) }
    var exported by remember { mutableStateOf(false) }
    LaunchedEffect(settings) {
        crashCount = withContext(Dispatchers.IO) { CrashReporter.listReports(context).size }
        sessionLogs = withContext(Dispatchers.IO) { Diagnostics.listSessionLogs(context).size }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = { SettingsTopBar(crumb(R.string.settings_about, R.string.settings_diagnostics), onBack) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding.verticalOnly())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            BrixCard {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.diagnostics_crashes, crashCount),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.diagnostics_info),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            // Session log moved here from Advanced (the owner, 17.09). Beside
            // Record stream, the checkboxes were confused before an update
            // measurement: MP4 was recorded without a log, losing the measurement.
            BrixCard {
                BrixToggleRow(
                    stringResource(R.string.advanced_debug_log),
                    subtitle = stringResource(R.string.advanced_debug_log_hint),
                    checked = settings.advanced.debugLog,
                    divider = true,
                ) { viewModel.updateAdvanced(settings.advanced.copy(debugLog = it)) }
                BrixNavRow(
                    stringResource(R.string.advanced_debug_log_share),
                    value = if (sessionLogs == 0) {
                        stringResource(R.string.advanced_debug_log_none)
                    } else {
                        sessionLogs.toString()
                    },
                    divider = false,
                    onClick = {
                        // Read the log directory off the main thread, as with
                        // diagnostics export below (H10, audit 23.09).
                        scope.launch(Dispatchers.IO) {
                            val uri = Diagnostics.exportLatestSessionLog(context) ?: return@launch
                            withContext(Dispatchers.Main) {
                                val send = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/csv"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(
                                    Intent.createChooser(send, context.getString(R.string.advanced_debug_log_share)),
                                )
                            }
                        }
                    },
                )
            }
            Spacer(Modifier.height(8.dp))
            FilledTonalButton(
                shape = BrixButtonShape,
                onClick = {
                    // Export does blocking subprocess + file I/O — never on the
                    // UI thread (H10).
                    scope.launch(Dispatchers.IO) {
                        val uri = Diagnostics.export(context, settings)
                        withContext(Dispatchers.Main) {
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(intent, null))
                            exported = true
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.btn_export_diagnostics))
            }
            if (exported) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.btn_export_diagnostics) + " ✓",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
fun MoblinkSettingsScreen(
    viewModel: SettingsViewModel,
    modifier: Modifier = Modifier,
) {
    val settings by viewModel.settings.collectAsState()
    val moblink = settings.moblink
    var portText by rememberSaveable(moblink.port) { mutableStateOf(moblink.port.toString()) }
    var passwordText by rememberSaveable(moblink.password) { mutableStateOf(moblink.password) }
    var weightText by rememberSaveable(moblink.relayWeight) { mutableStateOf(moblink.relayWeight.toString()) }

    var relays by remember { mutableStateOf<List<app.brix.moblink.MoblinkRelayInfo>>(emptyList()) }
    LaunchedEffect(Unit) {
        while (true) {
            relays = app.brix.streaming.StreamController.current()?.state?.value?.moblinkRelays ?: emptyList()
            kotlinx.coroutines.delay(2000)
        }
    }

    // Port/password/weight below update in-memory state on every keystroke
    // (commit = false) so the screen and MoblinkServer see the live value
    // instantly, but only hit disk once — on focus loss, or on leaving this
    // screen entirely — instead of doing a full serialize + fsync + .bak copy
    // per keystroke (6.4.1).
    DisposableEffect(Unit) {
        onDispose { viewModel.flush() }
    }

    val portValue = portText.toIntOrNull()
    val portError = portValue == null || portValue !in 1..65535

    Scaffold(
        modifier = modifier.fillMaxSize(),
        // No onBack: Moblink category screen; only another category on
        // the left is a destination.
        topBar = { SettingsTopBar(stringResource(R.string.settings_cat_moblink)) },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.padding(innerPadding.verticalOnly()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                BrixCard {
                    BrixToggleRow(
                        stringResource(R.string.moblink_enabled),
                        subtitle = stringResource(R.string.moblink_hint),
                        checked = moblink.enabled,
                        divider = false,
                    ) { viewModel.updateMoblink(moblink.copy(enabled = it)) }
                }
            }
            item {
                BrixCard {
                    Column(Modifier.padding(16.dp)) {
                        OutlinedTextField(
                            value = portText,
                            onValueChange = { text ->
                                portText = text
                                val parsed = text.toIntOrNull()
                                // Out-of-range/unparseable values are shown as
                                // an error but never reach settings — MoblinkServer
                                // would otherwise throw on start and Moblink would
                                // silently fail to come up (6.4.2).
                                if (parsed != null && parsed in 1..65535) {
                                    viewModel.updateMoblink(moblink.copy(port = parsed), commit = false)
                                }
                            },
                            label = { Text(stringResource(R.string.moblink_port)) },
                            isError = portError,
                            supportingText = {
                                if (portError) {
                                    Text(stringResource(R.string.moblink_port_error))
                                }
                            },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .onFocusChanged { if (!it.isFocused) viewModel.flush() },
                        )
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            value = passwordText,
                            onValueChange = {
                                passwordText = it
                                viewModel.updateMoblink(moblink.copy(password = it), commit = false)
                            },
                            label = { Text(stringResource(R.string.moblink_password)) },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .onFocusChanged { if (!it.isFocused) viewModel.flush() },
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.moblink_password_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            value = weightText,
                            onValueChange = { text ->
                                weightText = text
                                text.toIntOrNull()?.let {
                                    viewModel.updateMoblink(moblink.copy(relayWeight = it), commit = false)
                                }
                            },
                            label = { Text(stringResource(R.string.moblink_relay_weight)) },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .onFocusChanged { if (!it.isFocused) viewModel.flush() },
                        )
                    }
                }
            }
            item { SettingsSectionHeader(stringResource(R.string.moblink_relays_title)) }
            if (relays.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.moblink_no_relays),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
            } else {
                item {
                    BrixCard {
                        relays.forEachIndexed { index, relay ->
                            BrixNavRow(
                                title = relay.name,
                                subtitle = relay.batteryPercentage?.let { "$it%" },
                                divider = index != relays.lastIndex,
                                onClick = {},
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ChatSettingsScreen(
    viewModel: SettingsViewModel,
    modifier: Modifier = Modifier,
) {
    val settings by viewModel.settings.collectAsState()
    val chat = settings.chat
    var channelText by rememberSaveable(chat.twitchChannel) { mutableStateOf(chat.twitchChannel) }
    var kickChannelText by rememberSaveable(chat.kickChannel) { mutableStateOf(chat.kickChannel) }
    var vkChannelText by rememberSaveable(chat.vkChannelUrl) { mutableStateOf(chat.vkChannelUrl) }
    var vkClientIdText by rememberSaveable(chat.vkClientId) { mutableStateOf(chat.vkClientId) }
    var vkClientSecretText by rememberSaveable(chat.vkClientSecret) { mutableStateOf(chat.vkClientSecret) }

    // Same approach as Moblink: each keystroke updates memory (commit = false),
    // while focus loss or leaving the screen writes to disk.
    DisposableEffect(Unit) {
        onDispose { viewModel.flush() }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        // No onBack: Chat category screen; only another category on
        // the left is a destination.
        topBar = { SettingsTopBar(stringResource(R.string.settings_cat_chat)) },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.padding(innerPadding.verticalOnly()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { SettingsSectionHeader("Twitch") }
            item {
                BrixCard {
                    BrixToggleRow(
                        stringResource(R.string.chat_enabled),
                        subtitle = stringResource(R.string.chat_hint),
                        checked = chat.enabled,
                        divider = false,
                    ) { viewModel.updateChat(chat.copy(enabled = it)) }
                }
            }
            item {
                BrixCard {
                    Column(Modifier.padding(16.dp)) {
                        OutlinedTextField(
                            value = channelText,
                            onValueChange = { text ->
                                channelText = text
                                viewModel.updateChat(chat.copy(twitchChannel = text), commit = false)
                            },
                            label = { Text(stringResource(R.string.chat_twitch_channel)) },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .onFocusChanged { if (!it.isFocused) viewModel.flush() },
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.chat_twitch_channel_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            item { SettingsSectionHeader("Kick") }
            item {
                BrixCard {
                    BrixToggleRow(
                        stringResource(R.string.chat_kick_enabled),
                        subtitle = stringResource(R.string.chat_hint),
                        checked = chat.kickEnabled,
                        divider = false,
                    ) { viewModel.updateChat(chat.copy(kickEnabled = it)) }
                }
            }
            item {
                BrixCard {
                    Column(Modifier.padding(16.dp)) {
                        OutlinedTextField(
                            value = kickChannelText,
                            onValueChange = { text ->
                                kickChannelText = text
                                viewModel.updateChat(chat.copy(kickChannel = text), commit = false)
                            },
                            label = { Text(stringResource(R.string.chat_kick_channel)) },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .onFocusChanged { if (!it.isFocused) viewModel.flush() },
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.chat_kick_channel_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // Twitch and Kick's own emotes always appear: they arrive with the
            // message. 7TV and BTTV are separate services, contacted only with
            // consent because requests reveal the channel being viewed.
            item { SettingsSectionHeader(stringResource(R.string.chat_emotes_section)) }
            item {
                BrixCard {
                    BrixToggleRow(
                        stringResource(R.string.chat_third_party_emotes),
                        subtitle = stringResource(R.string.chat_third_party_emotes_hint),
                        checked = chat.thirdPartyEmotes,
                        divider = false,
                    ) { viewModel.updateChat(chat.copy(thirdPartyEmotes = it)) }
                }
            }

            item { SettingsSectionHeader("VK Video Live") }
            item {
                BrixCard {
                    BrixToggleRow(
                        stringResource(R.string.chat_vk_enabled),
                        subtitle = stringResource(R.string.chat_vk_hint),
                        checked = chat.vkEnabled,
                        divider = false,
                    ) { viewModel.updateChat(chat.copy(vkEnabled = it)) }
                }
            }
            item {
                BrixCard {
                    Column(Modifier.padding(16.dp)) {
                        OutlinedTextField(
                            value = vkChannelText,
                            onValueChange = { text ->
                                vkChannelText = text
                                viewModel.updateChat(chat.copy(vkChannelUrl = text), commit = false)
                            },
                            label = { Text(stringResource(R.string.chat_vk_channel)) },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .onFocusChanged { if (!it.isFocused) viewModel.flush() },
                        )
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            value = vkClientIdText,
                            onValueChange = { text ->
                                vkClientIdText = text
                                viewModel.updateChat(chat.copy(vkClientId = text), commit = false)
                            },
                            label = { Text(stringResource(R.string.chat_vk_client_id)) },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .onFocusChanged { if (!it.isFocused) viewModel.flush() },
                        )
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            value = vkClientSecretText,
                            onValueChange = { text ->
                                vkClientSecretText = text
                                viewModel.updateChat(chat.copy(vkClientSecret = text), commit = false)
                            },
                            label = { Text(stringResource(R.string.chat_vk_client_secret)) },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .onFocusChanged { if (!it.isFocused) viewModel.flush() },
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.chat_vk_hint_detail),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ConfigTransferScreen(
    viewModel: SettingsViewModel,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val settings by viewModel.settings.collectAsState()
    var linkText by rememberSaveable { mutableStateOf("") }
    var importError by remember { mutableStateOf(false) }
    var askIncludeSecrets by remember { mutableStateOf(false) }
    var pendingImport by remember { mutableStateOf<app.brix.core.SharedConfig?>(null) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        // No onBack: Import/export category screen; only another category
        // on the left is a destination.
        topBar = { SettingsTopBar(stringResource(R.string.settings_config_title)) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding.verticalOnly())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            BrixCard {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.config_transfer_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            FilledTonalButton(
                shape = BrixButtonShape,
                onClick = { askIncludeSecrets = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.settings_share_config))
            }
            if (askIncludeSecrets) {
                // Ask BEFORE sending, not after: a link cannot be recalled from
                // someone else's messages or browser history.
                val share = { withSecrets: Boolean ->
                    askIncludeSecrets = false
                    val link = app.brix.core.SettingsDeepLink.encode(settings, includeSecrets = withSecrets)
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, link)
                    }
                    context.startActivity(Intent.createChooser(intent, null))
                }
                AlertDialog(
                    onDismissRequest = { askIncludeSecrets = false },
                    title = { Text(stringResource(R.string.dialog_share_secrets_title)) },
                    text = { Text(stringResource(R.string.dialog_share_secrets_text)) },
                    confirmButton = {
                        TextButton(shape = BrixButtonShape, onClick = { share(false) }) {
                            Text(stringResource(R.string.dialog_share_without_keys))
                        }
                    },
                    dismissButton = {
                        TextButton(shape = BrixButtonShape, onClick = { share(true) }) {
                            Text(stringResource(R.string.dialog_share_with_keys))
                        }
                    },
                )
            }
            Spacer(Modifier.height(16.dp))
            BrixCard {
                Column(Modifier.padding(16.dp)) {
                    OutlinedTextField(
                        value = linkText,
                        onValueChange = {
                            linkText = it
                            importError = false
                        },
                        label = { Text(stringResource(R.string.config_field_link)) },
                        placeholder = { Text("brix://...") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (importError) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.deep_link_import_invalid),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(modifier = Modifier.fillMaxWidth()) {
                        TextButton(
                            shape = BrixButtonShape,
                            onClick = {
                                val clipboard = context.getSystemService(
                                    android.content.ClipboardManager::class.java,
                                )
                                val clip = clipboard?.primaryClip?.takeIf { it.itemCount > 0 }
                                clip?.getItemAt(0)?.text?.let {
                                    linkText = it.toString()
                                    importError = false
                                }
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(stringResource(R.string.btn_paste_clipboard))
                        }
                        Spacer(Modifier.width(8.dp))
                        FilledTonalButton(
                            shape = BrixButtonShape,
                            onClick = {
                                val config = app.brix.core.SettingsDeepLink.decodeAny(linkText.trim())
                                if (config == null) {
                                    importError = true
                                } else {
                                    pendingImport = config
                                }
                            },
                            enabled = linkText.isNotBlank(),
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(stringResource(R.string.btn_import))
                        }
                    }
                }
            }
        }
    }
    val toImport = pendingImport
    if (toImport != null) {
        AlertDialog(
            onDismissRequest = { pendingImport = null },
            title = { Text(stringResource(R.string.deep_link_import_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.deep_link_import_message,
                        toImport.streamProfiles.size,
                        toImport.serverProfiles.size,
                    ),
                )
            },
            confirmButton = {
                TextButton(shape = BrixButtonShape, onClick = {
                    viewModel.importSharedConfig(toImport)
                    pendingImport = null
                    linkText = ""
                }) {
                    Text(stringResource(R.string.btn_import))
                }
            },
            dismissButton = {
                TextButton(shape = BrixButtonShape, onClick = { pendingImport = null }) {
                    Text(stringResource(R.string.btn_cancel))
                }
            },
        )
    }
}

@Composable
fun HudSettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val settings by viewModel.settings.collectAsState()
    val hud = settings.hud
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = { SettingsTopBar(crumb(R.string.settings_appearance, R.string.settings_hud), onBack) },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .padding(innerPadding.verticalOnly())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                BrixCard {
                    BrixToggleRow(
                        stringResource(R.string.hud_battery),
                        checked = hud.showBattery,
                        divider = true,
                    ) { viewModel.updateHud(hud.copy(showBattery = it)) }
                    BrixToggleRow(
                        stringResource(R.string.hud_thermal),
                        checked = hud.showThermal,
                        divider = true,
                    ) { viewModel.updateHud(hud.copy(showThermal = it)) }
                    BrixToggleRow(
                        stringResource(R.string.hud_networks),
                        checked = hud.showNetworks,
                        divider = true,
                    ) { viewModel.updateHud(hud.copy(showNetworks = it)) }
                    BrixToggleRow(
                        stringResource(R.string.hud_bitrate),
                        checked = hud.showBitrate,
                        divider = true,
                    ) { viewModel.updateHud(hud.copy(showBitrate = it)) }
                    BrixToggleRow(
                        stringResource(R.string.hud_uptime),
                        checked = hud.showUptime,
                        divider = true,
                    ) { viewModel.updateHud(hud.copy(showUptime = it)) }
                    BrixToggleRow(
                        stringResource(R.string.hud_scene),
                        checked = hud.showScene,
                        divider = true,
                    ) { viewModel.updateHud(hud.copy(showScene = it)) }
                    BrixToggleRow(
                        stringResource(R.string.hud_version),
                        checked = hud.showVersion,
                        divider = true,
                    ) { viewModel.updateHud(hud.copy(showVersion = it)) }
                    BrixToggleRow(
                        stringResource(R.string.hud_audio_level),
                        checked = hud.showAudioLevel,
                        divider = false,
                    ) { viewModel.updateHud(hud.copy(showAudioLevel = it)) }
                }
            }
        }
    }
}

@Composable
internal fun ProfileRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    onSelect: () -> Unit,
    onClick: () -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 11.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (onDelete != null) {
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = stringResource(R.string.btn_delete),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/**
 * Unified scene widget list.
 *
 * Previously two screens, Overlays and Browser widgets, reflected an internal
 * distinction rather than meaning for users: one browser-based donation widget
 * went in the first list, another in the second (the owner, 14.09).
 * Now there is one list, with the item type selected at creation.
 */
@Composable
fun WidgetsSettingsScreen(
    viewModel: SettingsViewModel,
    onEdit: (widgetId: String?, newKind: WidgetKind?) -> Unit,
    onPlaceWidget: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val settings by viewModel.settings.collectAsState()
    var pendingDelete by remember { mutableStateOf<String?>(null) }
    var pickKind by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = { SettingsTopBar(crumb(R.string.settings_stream, R.string.settings_widgets), onBack) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding.verticalOnly())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            if (settings.widgets.isEmpty()) {
                Text(
                    text = stringResource(R.string.widget_hint_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
            } else {
                BrixCard {
                    settings.widgets.forEachIndexed { index, widget ->
                        WidgetRow(
                            widget = widget,
                            inAnyScene = settings.scenes.isEmpty() ||
                                settings.scenes.any { widget.id in it.widgetIds },
                            onToggle = { viewModel.saveWidget(widget.copy(enabled = it)) },
                            onEdit = { onEdit(widget.id, null) },
                            onPlace = { onPlaceWidget(widget.id) },
                            onDelete = { pendingDelete = widget.id },
                        )
                        if (index != settings.widgets.lastIndex) {
                            HorizontalDivider(
                                thickness = 0.5.dp,
                                color = MaterialTheme.colorScheme.outlineVariant,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
            FilledTonalButton(
                shape = BrixButtonShape,
                onClick = { pickKind = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.btn_add))
            }
        }
    }
    if (pickKind) {
        // Streaming phones are held horizontally with little vertical space:
        // four types with explanations did not fit. Text was invisible and
        // Image's description was cut mid-word (the owner, 15.09).
        // Hence scrolling and a compact dialog instead of a Material one.
        BrixDialog(
            title = stringResource(R.string.widget_pick_kind),
            onDismiss = { pickKind = false },
            confirmLabel = stringResource(R.string.btn_cancel),
            onConfirm = { pickKind = false },
        ) {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                WidgetKind.entries.forEach { kind ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                pickKind = false
                                onEdit(null, kind)
                            }
                            .padding(vertical = 6.dp),
                    ) {
                        Text(
                            text = stringResource(widgetKindLabel(kind)),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            text = stringResource(widgetKindHint(kind)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
    if (pendingDelete != null) {
        // Previously the dialog asked "Delete widget?" without identifying which,
        // and both buttons looked identical (the owner, 15.09).
        val doomed = settings.widgets.firstOrNull { it.id == pendingDelete }
        BrixDialog(
            title = stringResource(R.string.dialog_delete_widget_title),
            onDismiss = { pendingDelete = null },
            dismissLabel = stringResource(R.string.btn_cancel),
            confirmLabel = stringResource(R.string.btn_delete),
            destructive = true,
            onConfirm = {
                pendingDelete?.let { viewModel.deleteWidget(it) }
                pendingDelete = null
            },
        ) {
            if (doomed != null) {
                Text(
                    text = stringResource(
                        R.string.dialog_delete_widget_text,
                        stringResource(widgetKindLabel(doomed.kind)),
                        doomed.displayTitle(),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

internal fun widgetKindLabel(kind: WidgetKind): Int = when (kind) {
    WidgetKind.DONATION_ALERT -> R.string.widget_kind_donation
    WidgetKind.WEB -> R.string.widget_kind_web
    WidgetKind.IMAGE -> R.string.widget_kind_image
    WidgetKind.TEXT -> R.string.widget_kind_text
}

private fun widgetKindHint(kind: WidgetKind): Int = when (kind) {
    WidgetKind.DONATION_ALERT -> R.string.widget_kind_donation_hint
    WidgetKind.WEB -> R.string.widget_kind_web_hint
    WidgetKind.IMAGE -> R.string.widget_kind_image_hint
    WidgetKind.TEXT -> R.string.widget_kind_text_hint
}

@Composable
private fun WidgetRow(
    widget: SceneWidget,
    /** Whether the widget belongs to at least one scene. Otherwise it does nothing,
         *  which must be stated explicitly: an enabled widget outside all scenes
         *  silently does nothing while users think it is configured (the owner, 14.09). */
    inAnyScene: Boolean,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onPlace: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 11.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BrixToggle(checked = widget.enabled, onCheckedChange = onToggle)
        Spacer(Modifier.width(12.dp))
        Column(
            modifier = Modifier
                .weight(1f)
                .clickable(onClick = onEdit),
        ) {
            Text(
                text = widget.displayTitle().ifBlank { stringResource(widgetKindLabel(widget.kind)) },
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // Type as text, not an icon: an icon would need explanation, whereas
            // "Donation alert · enabled" reads without a legend.
            Text(
                text = stringResource(widgetKindLabel(widget.kind)) + " · " + when {
                    !widget.enabled -> stringResource(R.string.settings_disabled)
                    !inAnyScene -> stringResource(R.string.overlay_no_scene)
                    else -> stringResource(R.string.settings_enabled)
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (widget.enabled && !inAnyScene) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        IconButton(onClick = onPlace) {
            Icon(
                imageVector = Icons.Filled.OpenWith,
                contentDescription = stringResource(R.string.overlay_change_position),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onDelete) {
            Icon(
                imageVector = Icons.Filled.Delete,
                contentDescription = stringResource(R.string.btn_delete),
                tint = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/** Single-widget form. Fields depend on type; only the name is common to all. */
@Composable
fun WidgetEditScreen(
    viewModel: SettingsViewModel,
    widgetId: String?,
    newKind: WidgetKind?,
    onNext: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val settings by viewModel.settings.collectAsState()
    val existing = widgetId?.let { id -> settings.widgets.firstOrNull { it.id == id } }
    val kind = existing?.kind ?: newKind ?: WidgetKind.DONATION_ALERT

    var name by rememberSaveable { mutableStateOf(existing?.name ?: "") }
    var url by rememberSaveable { mutableStateOf(existing?.url ?: "") }
    var audioOnDevice by rememberSaveable { mutableStateOf(existing?.audioOnDevice ?: false) }
    var audioInStream by rememberSaveable { mutableStateOf(existing?.audioInStream ?: false) }
    var captionScale by rememberSaveable { mutableStateOf(existing?.captionScale ?: 1f) }
    var captionVisible by rememberSaveable { mutableStateOf(existing?.captionVisible ?: true) }
    var refresh by rememberSaveable { mutableStateOf((existing?.refreshMs ?: 200L).toString()) }
    var imageUri by rememberSaveable { mutableStateOf(existing?.imageUri ?: "") }
    var template by rememberSaveable { mutableStateOf(existing?.template ?: "") }
    var textScale by rememberSaveable { mutableStateOf(existing?.textScale ?: 1f) }

    val context = LocalContext.current
    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            // Permission survives reboot: without it the URI is readable only
            // until the process ends, and the image disappears after restart.
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            imageUri = uri.toString()
        }
    }

    val base = existing ?: newWidget(kind)
    val draft = base.copy(
        kind = kind,
        name = name,
        url = url,
        audioOnDevice = audioOnDevice,
        audioInStream = audioInStream,
        captionScale = captionScale,
        captionVisible = captionVisible,
        // A lower bound prevents accidentally requesting WebView snapshots
        // every few milliseconds: this is per-frame GL pipeline work,
        // not a cost-free number in settings.
        refreshMs = refresh.toLongOrNull()?.coerceIn(50L, 5_000L) ?: base.refreshMs,
        imageUri = imageUri,
        template = template,
        textScale = textScale,
    )

    val filled = when (kind) {
        WidgetKind.DONATION_ALERT, WidgetKind.WEB -> url.isNotBlank()
        WidgetKind.IMAGE -> imageUri.isNotBlank()
        WidgetKind.TEXT -> template.isNotBlank()
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            SettingsEditTopBar(
                title = if (widgetId == null) {
                    stringResource(R.string.widget_add_title)
                } else {
                    stringResource(widgetKindLabel(kind))
                },
                onCancel = onBack,
                onSave = {
                    viewModel.saveWidget(draft)
                    if (widgetId == null) onNext(draft.id) else onBack()
                },
                saveLabel = stringResource(if (widgetId == null) R.string.btn_next else R.string.btn_save),
                saveEnabled = filled,
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding.verticalOnly())
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Deliberately no type heading inside the card: the screen header
            // already names it, and repetition prompted "what is this about?"
            // (the owner, 15.09). BrixCard sets only horizontal padding,
            // so add vertical padding here to keep the bottom hint from
            // touching the rounded edge.
            BrixCard {
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.field_widget_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                when (kind) {
                    WidgetKind.DONATION_ALERT, WidgetKind.WEB -> {
                        OutlinedTextField(
                            value = url,
                            onValueChange = { url = it },
                            label = {
                                Text(
                                    stringResource(
                                        if (kind == WidgetKind.DONATION_ALERT) {
                                            R.string.field_overlay_url
                                        } else {
                                            R.string.field_browser_widget_url
                                        },
                                    ),
                                )
                            },
                            placeholder = { Text("https://...") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        // Identify the service by domain: users immediately see
                        // whether the URL is correct and which HUD marker to expect.
                        if (kind == WidgetKind.DONATION_ALERT && url.isNotBlank()) {
                            val service = app.brix.core.DonationService.forUrl(url)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = if (service == app.brix.core.DonationService.UNKNOWN) {
                                    stringResource(R.string.overlay_service_unknown)
                                } else {
                                    stringResource(R.string.overlay_service_known, service.name.lowercase(), service.label)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (service == app.brix.core.DonationService.UNKNOWN) {
                                    MaterialTheme.colorScheme.tertiary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                        if (kind == WidgetKind.WEB) {
                            Spacer(Modifier.height(12.dp))
                            OutlinedTextField(
                                value = refresh,
                                onValueChange = { refresh = it.filter(Char::isDigit) },
                                label = { Text(stringResource(R.string.field_widget_refresh)) },
                                supportingText = { Text(stringResource(R.string.field_widget_refresh_hint)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }

                    WidgetKind.IMAGE -> {
                        Text(
                            text = imageUri.ifBlank { stringResource(R.string.field_widget_image) },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(8.dp))
                        FilledTonalButton(shape = BrixButtonShape, onClick = { pickImage.launch(arrayOf("image/*")) }) {
                            Text(stringResource(R.string.btn_pick_file))
                        }
                    }

                    WidgetKind.TEXT -> {
                        OutlinedTextField(
                            value = template,
                            onValueChange = { template = it },
                            label = { Text(stringResource(R.string.field_widget_text)) },
                            supportingText = { Text(stringResource(R.string.widget_text_hint)) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.widget_text_scale, (textScale * 100).toInt()),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Slider(
                            value = textScale,
                            onValueChange = { textScale = it },
                            valueRange = 0.5f..3f,
                            steps = 24,
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(widgetKindHint(kind)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
            }

            if (kind == WidgetKind.DONATION_ALERT) {
                BrixCard {
                    Text(
                        text = stringResource(R.string.overlay_audio_title),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(4.dp))
                    BrixToggleRow(
                        title = stringResource(R.string.overlay_audio_device),
                        subtitle = stringResource(R.string.overlay_audio_device_hint),
                        checked = audioOnDevice,
                        divider = true,
                    ) {
                        audioOnDevice = it
                    }
                    BrixToggleRow(
                        title = stringResource(R.string.overlay_audio_stream),
                        subtitle = stringResource(R.string.overlay_audio_stream_hint),
                        checked = audioInStream,
                        divider = false,
                    ) {
                        audioInStream = it
                    }
                }
                Spacer(Modifier.height(8.dp))
                // Caption: name, amount, message. Previously its size derived from
                // image width, requiring random window resizing to change text size.
                BrixCard {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            text = stringResource(R.string.overlay_caption_title),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.height(4.dp))
                        // Deliberately no "above or below" option: donation alerts
                        // have an established order, and breaking it is not
                        // flexibility. Only "show or hide".
                        BrixToggleRow(
                            title = stringResource(R.string.overlay_caption_show),
                            subtitle = stringResource(R.string.overlay_caption_show_hint),
                            checked = captionVisible,
                            divider = true,
                        ) { captionVisible = it }
                        if (captionVisible) {
                            Text(
                                text = stringResource(
                                    R.string.overlay_caption_scale,
                                    (captionScale * 100).toInt(),
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Slider(
                                value = captionScale,
                                onValueChange = { captionScale = it },
                                valueRange = 0.5f..3f,
                                steps = 24,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun micSourceLabel(source: MicSource): String = stringResource(
    when (source) {
        MicSource.AUTO -> R.string.mic_auto
        MicSource.BUILTIN -> R.string.mic_builtin
        MicSource.BUILTIN_BOTTOM -> R.string.mic_bottom
        MicSource.BUILTIN_BACK -> R.string.mic_back
        MicSource.BUILTIN_TOP -> R.string.mic_top
        MicSource.WIRED -> R.string.mic_wired
        MicSource.BLUETOOTH -> R.string.mic_bluetooth
        MicSource.USB -> R.string.mic_usb
    },
)

/**
 * The specific device of the selected type.
 *
 * Shown only when multiple devices share this type: two Bluetooth headsets,
 * two USB inputs. Use `productName` because Bluetooth `address` is a MAC
 * address, which must not be displayed or stored.
 */
@Composable
private fun MicDevicePickerDialog(
    names: List<String>,
    selected: String,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.mic_device_pick_title)) },
        text = {
            Column {
                names.forEach { name ->
                    BrixNavRow(
                        title = name,
                        value = if (name == selected) "✓" else null,
                        divider = name != names.last(),
                        onClick = { onPick(name) },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(shape = BrixButtonShape, onClick = onDismiss) { Text(stringResource(R.string.btn_cancel)) }
        },
    )
}

/** Semantic group heading within the audio settings screen. */
@Composable
private fun AudioGroupTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 2.dp),
    )
}
