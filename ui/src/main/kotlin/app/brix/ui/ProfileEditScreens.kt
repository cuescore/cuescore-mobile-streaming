package app.brix.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import app.brix.core.AbrAlgorithm
import app.brix.core.Codec
import app.brix.core.Ids
import app.brix.core.ServerProfile
import app.brix.core.ServerType
import app.brix.core.StreamProfile
import app.brix.core.defaultStreamPresets
import app.brix.streaming.EncoderCapabilities
import app.brix.streaming.VideoMode

/** Long addresses and keys do not benefit from a landscape screen's width: the line
 *  stretches to 800 dp, making a typo in the middle of a URL hard to spot.
 *  Constrain the fields themselves, not the column—the lists and reports need the full width
 *  (the owner, 20.09). */
// Order matters: widthIn first, then fillMaxWidth. In the reverse order,
// fillMaxWidth fixes the parent's width, and widthIn can no longer make it narrower.
private val LongFieldWidth = Modifier.widthIn(max = 480.dp).fillMaxWidth()

/** Numeric settings fields open the numeric keyboard: letters have no place in width,
 *  fps, or bitrate, and switching layouts on the fly is an unnecessary
 *  step (audit 20.09). Input is additionally filtered to digits:
 *  the keyboard can be changed manually, and pasting bypasses it anyway. */
private val numberKeyboard = KeyboardOptions(keyboardType = KeyboardType.Number)

@Composable
fun StreamProfileEditScreen(
    viewModel: SettingsViewModel,
    profileId: String?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val settings by viewModel.settings.collectAsState()
    val profile = profileId?.let { id -> settings.streamProfiles.firstOrNull { it.id == id } }

    var name by rememberSaveable { mutableStateOf(profile?.name ?: "") }
    var codec by rememberSaveable { mutableStateOf(profile?.video?.codec ?: Codec.H264) }
    var width by rememberSaveable { mutableStateOf((profile?.video?.width ?: 1280).toString()) }
    var height by rememberSaveable { mutableStateOf((profile?.video?.height ?: 720).toString()) }
    var fps by rememberSaveable { mutableStateOf((profile?.video?.fps ?: 30).toString()) }
    var bitrate by rememberSaveable { mutableStateOf((profile?.video?.bitrateKbps ?: 2500).toString()) }
    var abrEnabled by rememberSaveable { mutableStateOf(profile?.adaptiveBitrate?.enabled ?: false) }
    var abrTarget by rememberSaveable { mutableStateOf((profile?.adaptiveBitrate?.targetBitrateKbps ?: 6000).toString()) }
    var abrMin by rememberSaveable { mutableStateOf((profile?.adaptiveBitrate?.minimumBitrateKbps ?: 250).toString()) }
    var abrInit by rememberSaveable { mutableStateOf((profile?.adaptiveBitrate?.initialBitrateKbps ?: 2500).toString()) }
    var abrAlgorithm by rememberSaveable { mutableStateOf(profile?.adaptiveBitrate?.algorithm ?: AbrAlgorithm.BELABOX) }
    var keyframe by rememberSaveable { mutableStateOf((profile?.video?.keyframeIntervalSec ?: 2).toString()) }

    // An erased field used to simply leave the previous value unchanged, so the user left
    // the screen confident they had changed the resolution (audit 20.09).
    val numbersOk = app.brix.core.videoFieldsValid(width, height, fps, bitrate)

    val base = profile ?: newStreamProfile(name)
    val draft = base.copy(
        name = name.ifBlank { "Unnamed" },
        // An empty or invalid field leaves the profile's PREVIOUS value, rather than
        // inserting a hard-coded constant. Previously, erasing the width of a
        // 1080p profile silently changed it to 1280: the user edited one field, but its
        // neighbor changed, and this could only be noticed in the live stream's picture.
        video = base.video.copy(
            codec = codec,
            width = width.toIntOrNull() ?: base.video.width,
            height = height.toIntOrNull() ?: base.video.height,
            fps = fps.toIntOrNull() ?: base.video.fps,
            bitrateKbps = bitrate.toIntOrNull() ?: base.video.bitrateKbps,
            keyframeIntervalSec = keyframe.toIntOrNull()?.coerceIn(1, 10) ?: base.video.keyframeIntervalSec,
        ),
        adaptiveBitrate = base.adaptiveBitrate.copy(
            enabled = abrEnabled,
            targetBitrateKbps = abrTarget.toIntOrNull() ?: base.adaptiveBitrate.targetBitrateKbps,
            minimumBitrateKbps = abrMin.toIntOrNull() ?: base.adaptiveBitrate.minimumBitrateKbps,
            initialBitrateKbps = abrInit.toIntOrNull() ?: base.adaptiveBitrate.initialBitrateKbps,
            algorithm = abrAlgorithm,
        ),
    )

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            SettingsEditTopBar(
                title = if (profileId == null) stringResource(R.string.settings_new_stream) else name,
                onCancel = onBack,
                onSave = {
                    viewModel.saveStreamProfile(draft)
                    onBack()
                },
                saveLabel = stringResource(R.string.btn_save),
                saveEnabled = numbersOk,
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
            BrixCard {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.field_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                val caps = rememberDeviceCapabilities()
                val codecs = Codec.entries.filter { it in caps.hardwareCodecs || it == codec }
                BrixSegmentRow(
                    title = stringResource(R.string.field_codec),
                    options = codecs.map { it to codecLabel(it) },
                    selected = codec,
                    divider = false,
                ) { codec = it }
                if (codec !in caps.hardwareCodecs) {
                    Text(
                        stringResource(R.string.caps_codec_missing),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            BrixCard {
                Text(
                    text = stringResource(R.string.profile_presets),
                    style = MaterialTheme.typography.labelMedium,
                )
                Spacer(Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    val caps = rememberDeviceCapabilities()
                    (defaultStreamPresets + settings.customPresets).filter { preset ->
                        caps.supports(codec, VideoMode(preset.width, preset.height, preset.fps))
                    }.forEach { preset ->
                        val isCustom = settings.customPresets.any { it.name == preset.name }
                        val selected = width == preset.width.toString() &&
                            height == preset.height.toString() &&
                            fps == preset.fps.toString() &&
                            bitrate == preset.videoBitrateKbps.toString()
                        FilterChip(
                            selected = selected,
                            onClick = {
                                width = preset.width.toString()
                                height = preset.height.toString()
                                fps = preset.fps.toString()
                                bitrate = preset.videoBitrateKbps.toString()
                                // Audio has been global since 03.09, not part of the profile: the preset
                                // writes its bitrate to the global settings.
                                viewModel.updateAudio(
                                    settings.audio.copy(bitrateKbps = preset.audioBitrateKbps),
                                )
                            },
                            label = { Text(preset.name) },
                            trailingIcon = if (isCustom) {
                                {
                                    IconButton(
                                        onClick = { viewModel.deleteCustomPreset(preset.name) },
                                        modifier = Modifier.size(18.dp),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.Close,
                                            contentDescription = stringResource(R.string.btn_delete),
                                        )
                                    }
                                }
                            } else {
                                null
                            },
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                var showSavePresetDialog by remember { mutableStateOf(false) }
                TextButton(shape = BrixButtonShape, onClick = { showSavePresetDialog = true }) {
                    Text(stringResource(R.string.preset_save_as))
                }
                if (showSavePresetDialog) {
                    var presetName by remember { mutableStateOf("") }
                    AlertDialog(
                        onDismissRequest = { showSavePresetDialog = false },
                        title = { Text(stringResource(R.string.preset_save_title)) },
                        text = {
                            OutlinedTextField(
                                value = presetName,
                                onValueChange = { presetName = it },
                                label = { Text(stringResource(R.string.field_name)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        },
                        confirmButton = {
                            TextButton(
                                shape = BrixButtonShape,
                                enabled = presetName.isNotBlank(),
                                onClick = {
                                    viewModel.saveCustomPreset(
                                        app.brix.core.StreamPreset(
                                            name = presetName,
                                            width = width.toIntOrNull() ?: 1280,
                                            height = height.toIntOrNull() ?: 720,
                                            fps = fps.toIntOrNull() ?: 30,
                                            videoBitrateKbps = bitrate.toIntOrNull() ?: 2500,
                                            audioBitrateKbps = settings.audio.bitrateKbps,
                                        ),
                                    )
                                    showSavePresetDialog = false
                                },
                            ) {
                                Text(stringResource(R.string.btn_save))
                            }
                        },
                        dismissButton = {
                            TextButton(shape = BrixButtonShape, onClick = { showSavePresetDialog = false }) {
                                Text(stringResource(R.string.btn_cancel))
                            }
                        },
                    )
                }
            }

            SettingsSectionHeader(stringResource(R.string.group_video))
            BrixCard {
                Row {
                    OutlinedTextField(
                        value = width,
                        onValueChange = { width = it.filter(Char::isDigit) },
                        keyboardOptions = numberKeyboard,
                        label = { Text(stringResource(R.string.field_width)) },
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(12.dp))
                    OutlinedTextField(
                        value = height,
                        onValueChange = { height = it.filter(Char::isDigit) },
                        keyboardOptions = numberKeyboard,
                        label = { Text(stringResource(R.string.field_height)) },
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row {
                    OutlinedTextField(
                        value = fps,
                        onValueChange = { fps = it.filter(Char::isDigit) },
                        keyboardOptions = numberKeyboard,
                        label = { Text(stringResource(R.string.field_fps)) },
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(12.dp))
                    OutlinedTextField(
                        value = bitrate,
                        onValueChange = { bitrate = it.filter(Char::isDigit) },
                        keyboardOptions = numberKeyboard,
                        label = { Text(stringResource(R.string.field_video_bitrate)) },
                        modifier = Modifier.weight(1f),
                    )
                }
                val modeOk = rememberDeviceCapabilities().supports(
                    codec,
                    VideoMode(width.toIntOrNull() ?: 0, height.toIntOrNull() ?: 0, fps.toIntOrNull() ?: 0),
                )
                if (!modeOk) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.caps_mode_unsupported),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
                // There is no other way to see the truth about the bitrate mode: RootEncoder
                // requests CBR, silently falls back to VBR if refused, and only logs this
                // to the system log. The mode cannot be selected—there is no setter on the encoder—
                // so this is just an honest message.
                Spacer(Modifier.height(4.dp))
                val cbr = remember(codec) { EncoderCapabilities.isCbrSupported(codec) }
                Text(
                    text = stringResource(
                        if (cbr) R.string.field_bitrate_mode_cbr else R.string.field_bitrate_mode_vbr,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (cbr) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.tertiary
                    },
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
                Spacer(Modifier.height(12.dp))
                // The field had been in the model from the start and was never read anywhere.
                OutlinedTextField(
                    value = keyframe,
                    onValueChange = { keyframe = it.filter(Char::isDigit) },
                    keyboardOptions = numberKeyboard,
                    label = { Text(stringResource(R.string.field_keyframe)) },
                    supportingText = { Text(stringResource(R.string.field_keyframe_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            // Image geometry and the adaptive bitrate algorithm are separate
            // concerns: on one screen, someone changing fps had to scroll past the entire ABR
            // every time (audit 20.09).
            SettingsSectionHeader(stringResource(R.string.group_abr))
            BrixCard {
                BrixToggleRow(
                    title = stringResource(R.string.adaptive_bitrate),
                    checked = abrEnabled,
                    divider = false,
                ) { abrEnabled = it }
                if (abrEnabled) {
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = abrTarget,
                        onValueChange = { abrTarget = it.filter(Char::isDigit) },
                        keyboardOptions = numberKeyboard,
                        label = { Text(stringResource(R.string.field_abr_target)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Row {
                        OutlinedTextField(
                            value = abrMin,
                            onValueChange = { abrMin = it.filter(Char::isDigit) },
                            keyboardOptions = numberKeyboard,
                            label = { Text(stringResource(R.string.field_abr_min)) },
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(12.dp))
                        OutlinedTextField(
                            value = abrInit,
                            onValueChange = { abrInit = it.filter(Char::isDigit) },
                            keyboardOptions = numberKeyboard,
                            label = { Text(stringResource(R.string.field_abr_init)) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    // The algorithm was in the model and reached the controller
                    // (SrtlaStream -> AdaptiveBitrate.setAlgorithm), but there was nowhere to select it—
                    // just like the SRT latency.
                    BrixSegmentRow(
                        title = stringResource(R.string.field_abr_algorithm),
                        options = listOf(
                            AbrAlgorithm.BELABOX to "Belabox",
                            AbrAlgorithm.FAST_IRL to "Fast",
                            AbrAlgorithm.SLOW_IRL to "Slow",
                        ),
                        selected = abrAlgorithm,
                        divider = false,
                    ) { abrAlgorithm = it }
                }
            }
        }
    }
}

@Composable
fun ServerProfileEditScreen(
    viewModel: SettingsViewModel,
    profileId: String?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val settings by viewModel.settings.collectAsState()
    val server = profileId?.let { id -> settings.serverProfiles.firstOrNull { it.id == id } }

    var name by rememberSaveable { mutableStateOf(server?.name ?: "") }
    var baseUrl by rememberSaveable { mutableStateOf(server?.baseUrl ?: "") }
    var streamKey by rememberSaveable { mutableStateOf(server?.streamId ?: "") }
    var keyVisible by rememberSaveable { mutableStateOf(false) }
    var type by rememberSaveable { mutableStateOf(server?.type ?: ServerType.SRTLA) }
    var enabled by rememberSaveable { mutableStateOf(server?.enabled ?: true) }
    var latency by rememberSaveable { mutableStateOf((server?.latencyMs ?: 2000).toString()) }
    var preferIpv4 by rememberSaveable { mutableStateOf(server?.preferIpv4 ?: false) }
    // Deleting a server is irreversible and the button is next to Save: ask for confirmation,
    // as the server list already does.
    var confirmDelete by remember { mutableStateOf(false) }

    // An empty or schemeless address was silently saved, and the user only found out
    // when going live. The Save button is now disabled, and the field shows
    // what is missing (audit 20.09).
    val urlOk = remember(baseUrl) { app.brix.core.looksLikeServerUrl(baseUrl) }

    val base = server ?: ServerProfile(
        id = Ids.newId(),
        name = name,
        type = type,
        baseUrl = baseUrl,
    )
    val draft = base.copy(
        name = name.ifBlank { "Unnamed" },
        baseUrl = baseUrl,
        type = type,
        enabled = enabled,
        // An empty or invalid field leaves the previous value rather than a constant—
        // as in the stream profile editor.
        latencyMs = latency.toIntOrNull() ?: base.latencyMs,
        preferIpv4 = preferIpv4,
        // The key is stored separately from the address and appended when connecting
        // (ServerProfile.connectUrl). For non-RTMP, the field is hidden, but
        // we do not erase the value: switching types back and forth must not lose it.
        streamId = streamKey.trim(),
    )

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            SettingsEditTopBar(
                title = if (profileId == null) stringResource(R.string.settings_new_server) else name,
                onCancel = onBack,
                onSave = {
                    viewModel.saveServerProfile(draft)
                    onBack()
                },
                saveLabel = stringResource(R.string.btn_save),
                saveEnabled = urlOk,
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
            BrixCard {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.field_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                BrixSegmentRow(
                    title = stringResource(R.string.field_type),
                    options = listOf(
                        ServerType.SRTLA to "SRT(LA)",
                        ServerType.RTMP to "RTMP",
                        ServerType.WHIP to "WHIP",
                    ),
                    selected = type,
                    divider = true,
                ) { type = it }
            }
            SettingsSectionHeader(stringResource(R.string.group_server_address))
            BrixCard {
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text(stringResource(R.string.field_url)) },
                    isError = baseUrl.isNotBlank() && !urlOk,
                    // The address scheme selects the mode, not a separate server type:
                    // Moblin works the same way, and some users come from there.
                    supportingText = when {
                        baseUrl.isNotBlank() && !urlOk -> {
                            {
                                Text(
                                    stringResource(R.string.field_url_invalid),
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                        type == ServerType.SRTLA -> {
                            { Text(stringResource(R.string.field_url_hint_srtla)) }
                        }
                        // http:// is allowed for WHIP (the owner, 04.10), but the user
                        // should know that the token is sent in the clear.
                        type == ServerType.WHIP && baseUrl.trim().startsWith("http://", ignoreCase = true) -> {
                            {
                                Text(
                                    stringResource(R.string.field_url_cleartext),
                                    color = MaterialTheme.colorScheme.tertiary,
                                )
                            }
                        }
                        else -> null
                    },
                    singleLine = true,
                    modifier = LongFieldWidth,
                )
                // For both RTMP and SRTLA, the channel access secret is in the address:
                // for the former, in the last path segment; for the latter, inside
                // streamid along with srtauth. There is one field, but the label
                // differs because platforms use different names for it.
                if (type != ServerType.WHIP) {
                    val isRtmp = type == ServerType.RTMP
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = streamKey,
                        onValueChange = { streamKey = it },
                        label = {
                            Text(
                                stringResource(
                                    if (isRtmp) R.string.field_stream_key else R.string.field_stream_id,
                                ),
                            )
                        },
                        supportingText = {
                            Text(
                                stringResource(
                                    if (isRtmp) R.string.field_stream_key_hint else R.string.field_stream_id_hint,
                                ),
                            )
                        },
                        // Default bullets: the stream key grants access to the
                        // channel, and streamers also open settings while live.
                        visualTransformation = if (keyVisible) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        trailingIcon = {
                            IconButton(onClick = { keyVisible = !keyVisible }) {
                                Icon(
                                    imageVector = if (keyVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                    contentDescription = stringResource(R.string.field_stream_key_show),
                                )
                            }
                        },
                        singleLine = true,
                        modifier = LongFieldWidth,
                    )
                }
            }
            if (type == ServerType.SRTLA) {
                SettingsSectionHeader(stringResource(R.string.group_server_transport))
                BrixCard {
                    BrixToggleRow(
                        title = stringResource(R.string.field_prefer_ipv4),
                        subtitle = stringResource(R.string.field_prefer_ipv4_hint),
                        checked = preferIpv4,
                        divider = true,
                    ) { preferIpv4 = it }
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = latency,
                        onValueChange = { latency = it.filter(Char::isDigit) },
                        keyboardOptions = numberKeyboard,
                        label = { Text(stringResource(R.string.field_srt_latency)) },
                        supportingText = {
                            // Moblin issues the same warning for values below
                            // 1000 in its SRT implementation. Our implementation is also
                            // custom, and the same principle applies: with low latency, the window for
                            // retransmitting a lost packet is nearly zero, meaning
                            // SRT stops doing what people use it for.
                            val low = latency.toIntOrNull()?.let { it < 1000 } ?: false
                            Text(
                                text = stringResource(
                                    if (low) R.string.field_srt_latency_low else R.string.field_srt_latency_hint,
                                ),
                                color = if (low) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                // There is no longer an Enabled toggle here: there is only one active server, and it is
                // selected with a radio button in the list. Otherwise, the editor could
                // enable a second one and bring back the same confusion where the first server in the list
                // broadcasts while two servers are checked.
            }
            if (profileId != null) {
                // Red, like the confirmation in the dialog: the delete button used to
                // look exactly like Save on neighboring screens, and in
                // a hurry, only the position distinguished them.
                Button(
                    shape = BrixButtonShape,
                    onClick = { confirmDelete = true },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.btn_delete))
                }
            }
        }
    }

    if (confirmDelete && profileId != null) {
        BrixDialog(
            title = stringResource(R.string.dialog_delete_server_title),
            onDismiss = { confirmDelete = false },
            dismissLabel = stringResource(R.string.btn_cancel),
            confirmLabel = stringResource(R.string.btn_delete),
            destructive = true,
            onConfirm = {
                confirmDelete = false
                viewModel.deleteServerProfile(profileId)
                onBack()
            },
        )
    }
}
