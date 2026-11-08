package app.brix.ui

import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import app.brix.core.Ids
import app.brix.core.CueScorePlayer
import app.brix.core.ButtonAction
import app.brix.core.ServerProfile
import app.brix.core.ServerType
import app.brix.core.SettingsDeepLink
import app.brix.core.SharedConfig
import app.brix.core.StreamPreset
import app.brix.core.defaultStreamPresets
import com.bumptech.glide.Glide
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ExecutionException

private enum class OnboardingStep { WELCOME, CUESCORE, PERMISSIONS, SERVER, QUALITY, DONE }

@Composable
private fun CueScorePlayerRow(player: CueScorePlayer, modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier.fillMaxWidth().padding(vertical = 8.dp),
    ) {
        Box(Modifier.size(48.dp)) {
            Box(
                Modifier.fillMaxSize().clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.Person,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(32.dp),
                )
                CueScorePlayerImage(
                    player.imageUrl,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
            player.countryImageUrl?.let { flagUrl ->
                CueScorePlayerImage(
                    flagUrl,
                    modifier = Modifier.align(Alignment.BottomEnd).size(24.dp, 18.dp),
                    contentDescription = player.countryName,
                )
            }
        }
        Text(
            player.name ?: player.id,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun CueScorePlayerImage(
    url: String?,
    modifier: Modifier,
    contentDescription: String? = null,
    contentScale: ContentScale = ContentScale.Fit,
) {
    val context = LocalContext.current.applicationContext
    val imageSize = with(LocalDensity.current) { 48.dp.roundToPx() }
    val bitmap by produceState<ImageBitmap?>(null, url, imageSize) {
        value = null
        if (url.isNullOrBlank()) return@produceState
        val imageUrl = if (url.startsWith("//")) "https:$url" else url
        val request = Glide.with(context).asBitmap().load(imageUrl)
            .dontTransform().submit(imageSize, imageSize)
        try {
            value = withContext(Dispatchers.IO) { request.get().asImageBitmap() }
            awaitDispose { }
        } catch (e: ExecutionException) {
            Log.w("CueScorePlayerImage", "Could not load participant image", e.cause)
        } finally {
            Glide.with(context).clear(request)
        }
    }
    bitmap?.let {
        Image(
            bitmap = it,
            contentDescription = contentDescription,
            contentScale = contentScale,
            modifier = modifier,
        )
    }
}

// Own list, not StreamScreen's: that private list separates required permissions
// from notifications for stream gating. Request everything together here:
// newly installed users expect these questions.
private val ONBOARDING_PERMISSIONS: Array<String> = buildList {
    add(Manifest.permission.CAMERA)
    add(Manifest.permission.RECORD_AUDIO)
    if (android.os.Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
}.toTypedArray()

// Explanation for each preset by index in defaultStreamPresets. The preset list
// lives in core and rarely changes; if the lists diverge, extra presets
// simply have no explanation.
private val PRESET_NOTES = listOf(
    R.string.onboarding_preset_720p30,
    R.string.onboarding_preset_720p60,
    R.string.onboarding_preset_1080p30,
    R.string.onboarding_preset_1080p60,
    R.string.onboarding_preset_4k30,
)

/**
 * First-run wizard (roadmap item 9). Portrait: MainActivity rotates the window,
 * while the camera streaming screen has not been created at all.
 *
 * Draft stays in rememberSaveable and writes settings in one final call
 * ([SettingsViewModel.completeOnboarding]), except the verified CueScore player,
 * which is saved immediately for subsequent runs. Changing language on step two
 * recreates Activity; the step and entered values survive as with rotation.
 *
 */
@Composable
fun OnboardingScreen(viewModel: SettingsViewModel) {
    val settings by viewModel.settings.collectAsState()
    val context = LocalContext.current

    var stepIndex by rememberSaveable { mutableIntStateOf(0) }
    val step = OnboardingStep.entries[stepIndex]

    var serverType by rememberSaveable { mutableStateOf(ServerType.SRTLA) }
    var serverUrl by rememberSaveable { mutableStateOf("") }
    var serverKey by rememberSaveable { mutableStateOf("") }
    var keyVisible by rememberSaveable { mutableStateOf(false) }
    val rememberedPlayer = remember { viewModel.rememberedCueScorePlayer() }
    var cueScoreSearchQuery by rememberSaveable { mutableStateOf("") }
    var cueScoreEditing by rememberSaveable { mutableStateOf(rememberedPlayer == null) }
    var verifiedCueScorePlayerId by rememberSaveable { mutableStateOf(rememberedPlayer?.id) }
    var cueScorePlayerName by rememberSaveable { mutableStateOf(rememberedPlayer?.name) }
    var cueScorePlayerImageUrl by rememberSaveable { mutableStateOf(rememberedPlayer?.imageUrl) }
    var cueScoreCountryImageUrl by rememberSaveable { mutableStateOf(rememberedPlayer?.countryImageUrl) }
    var cueScoreCountryName by rememberSaveable { mutableStateOf(rememberedPlayer?.countryName) }
    var cueScoreLookupMessage by rememberSaveable { mutableStateOf<String?>(null) }
    var cueScoreLookupInProgress by remember { mutableStateOf(false) }
    var cueScoreSearchResults by remember { mutableStateOf<List<CueScorePlayer>?>(null) }
    var cueScoreNextOffset by remember { mutableStateOf<Int?>(null) }
    var cueScoreExampleEnabled by rememberSaveable { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    fun searchPlayers(offset: Int = 0) {
        val query = cueScoreSearchQuery.trim()
        coroutineScope.launch {
            cueScoreLookupInProgress = true
            cueScoreLookupMessage = null
            if (offset == 0) {
                cueScoreSearchResults = null
                cueScoreNextOffset = null
            }
            try {
                when (val result = viewModel.searchCueScorePlayers(query, offset)) {
                    is CueScorePlayerSearchResult.Found -> {
                        cueScoreSearchResults = if (offset == 0) result.players else {
                            (cueScoreSearchResults.orEmpty() + result.players).distinctBy { it.id }
                        }
                        cueScoreNextOffset = result.nextOffset
                    }
                    CueScorePlayerSearchResult.Unavailable -> {
                        cueScoreLookupMessage = context.getString(R.string.onboarding_cuescore_lookup_failed)
                    }
                }
            } finally {
                cueScoreLookupInProgress = false
            }
        }
    }
    // Default to the existing profile value so Next without a selection
    // changes nothing.
    val currentVideo = settings.selectedStreamProfile()?.video
    var presetIndex by rememberSaveable {
        mutableIntStateOf(
            defaultStreamPresets.indexOfFirst {
                it.width == currentVideo?.width && it.height == currentVideo.height && it.fps == currentVideo.fps
            }.coerceAtLeast(0),
        )
    }

    fun granted(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
    var permissionTick by remember { mutableIntStateOf(0) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { permissionTick++ }

    val serverFilled = serverUrl.isNotBlank()
    // Parsed link awaits confirmation, as in Import/export: do not silently
    // apply someone else's servers.
    var pendingImport by remember { mutableStateOf<SharedConfig?>(null) }

    fun finish() {
        val server = if (serverFilled) {
            ServerProfile(
                id = Ids.newId(),
                name = serverUrl.substringAfter("://").substringBefore('/').substringBefore('?')
                    .substringBefore(':').ifBlank { "Server" },
                type = serverType,
                baseUrl = serverUrl.trim(),
                streamId = if (serverType == ServerType.WHIP) "" else serverKey.trim(),
            )
        } else {
            null
        }
        viewModel.completeOnboarding(
            server,
            defaultStreamPresets[presetIndex],
            verifiedCueScorePlayerId,
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding()
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        // Header: step number and Skip all. Skipping also marks completion,
        // otherwise the wizard would greet users on every launch.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp),
        ) {
            Text(
                stringResource(R.string.onboarding_step, stepIndex + 1, OnboardingStep.entries.size),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (step != OnboardingStep.DONE &&
                step != OnboardingStep.WELCOME &&
                step != OnboardingStep.CUESCORE
            ) {
                TextButton(shape = BrixButtonShape, onClick = {
                    viewModel.completeOnboarding(null, null, verifiedCueScorePlayerId)
                }) {
                    Text(stringResource(R.string.onboarding_skip_all), maxLines = 1)
                }
            }
        }
        StepProgress(current = stepIndex, total = OnboardingStep.entries.size)
        Spacer(Modifier.height(20.dp))

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (step) {
                OnboardingStep.WELCOME -> {
                    StepTitle(stringResource(R.string.onboarding_welcome_title))
                    StepText(stringResource(R.string.onboarding_welcome_p1))
                    StepText(stringResource(R.string.onboarding_welcome_p2))
                    StepText(stringResource(R.string.onboarding_welcome_p3))
                    StepText(stringResource(R.string.onboarding_welcome_p4))
                }

                OnboardingStep.CUESCORE -> {
                    StepTitle(stringResource(R.string.onboarding_cuescore_title))
                    StepText(stringResource(R.string.onboarding_cuescore_text))
                    BrixCard {
                        if (cueScoreEditing) {
                            OutlinedTextField(
                                value = cueScoreSearchQuery,
                                onValueChange = {
                                    cueScoreSearchQuery = it
                                    cueScoreSearchResults = null
                                    cueScoreNextOffset = null
                                    cueScoreLookupMessage = null
                                },
                                label = { Text(stringResource(R.string.onboarding_cuescore_search_name)) },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                                singleLine = true,
                                enabled = !cueScoreLookupInProgress,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            FilledTonalButton(
                                shape = BrixButtonShape,
                                onClick = { searchPlayers() },
                                enabled = cueScoreSearchQuery.trim().let { it.codePointCount(0, it.length) >= 2 } &&
                                    !cueScoreLookupInProgress,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    stringResource(
                                        if (cueScoreLookupInProgress) {
                                            R.string.onboarding_cuescore_searching
                                        } else {
                                            R.string.onboarding_cuescore_search
                                        },
                                    ),
                                )
                            }
                        }
                        if (!cueScoreEditing) {
                            verifiedCueScorePlayerId?.let { id ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    CueScorePlayerRow(
                                        CueScorePlayer(id, cueScorePlayerName, cueScorePlayerImageUrl, cueScoreCountryImageUrl, cueScoreCountryName),
                                        modifier = Modifier.weight(1f),
                                    )
                                    IconButton(onClick = { cueScoreEditing = true }) {
                                        Icon(
                                            Icons.Default.Edit,
                                            contentDescription = stringResource(R.string.onboarding_cuescore_edit),
                                        )
                                    }
                                }
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    QuickActionButton(
                                        action = ButtonAction.CUESCORE_SCOREBOARD,
                                        wide = false,
                                        active = cueScoreExampleEnabled,
                                        onClick = { cueScoreExampleEnabled = !cueScoreExampleEnabled },
                                    )
                                    Text(
                                        stringResource(R.string.onboarding_cuescore_toggle_help),
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            }
                        }
                        cueScoreSearchResults?.let { players ->
                            if (players.isEmpty() && cueScoreNextOffset == null) {
                                StepText(stringResource(R.string.onboarding_cuescore_not_found))
                            }
                            players.forEach { player ->
                                CueScorePlayerRow(
                                    player,
                                    modifier = Modifier.clickable(enabled = !cueScoreLookupInProgress) {
                                        verifiedCueScorePlayerId = player.id
                                        cueScorePlayerName = player.name
                                        cueScorePlayerImageUrl = player.imageUrl
                                        cueScoreCountryImageUrl = player.countryImageUrl
                                        cueScoreCountryName = player.countryName
                                        viewModel.rememberCueScorePlayer(player)
                                        cueScoreEditing = false
                                        cueScoreSearchResults = null
                                        cueScoreNextOffset = null
                                    },
                                )
                            }
                        }
                        cueScoreNextOffset?.let { offset ->
                            TextButton(
                                onClick = { searchPlayers(offset) },
                                enabled = !cueScoreLookupInProgress,
                                shape = BrixButtonShape,
                            ) {
                                Text(stringResource(R.string.onboarding_cuescore_more))
                            }
                        }
                        cueScoreLookupMessage?.let { message ->
                            Text(message, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }

                OnboardingStep.PERMISSIONS -> {
                    StepTitle(stringResource(R.string.onboarding_permissions_title))
                    StepText(stringResource(R.string.onboarding_permissions_text))
                    // Tick forces permission statuses to refresh after the system response.
                    val statuses = remember(permissionTick) { ONBOARDING_PERMISSIONS.associateWith(::granted) }
                    BrixCard {
                        statuses.forEach { (perm, ok) -> PermissionRow(perm, ok) }
                    }
                    if (!statuses.values.all { it }) {
                        Button(
                            shape = BrixButtonShape,
                            onClick = { permissionLauncher.launch(ONBOARDING_PERMISSIONS) },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(stringResource(R.string.onboarding_permissions_grant)) }
                    }
                }

                OnboardingStep.SERVER -> {
                    StepTitle(stringResource(R.string.onboarding_server_title))
                    StepText(stringResource(R.string.onboarding_server_text))
                    BrixCard {
                        BrixSegmentRow(
                            title = stringResource(R.string.field_type),
                            options = listOf(
                                ServerType.SRTLA to "SRT(LA)",
                                ServerType.RTMP to "RTMP",
                                ServerType.WHIP to "WHIP",
                            ),
                            selected = serverType,
                            divider = false,
                        ) { serverType = it }
                        OutlinedTextField(
                            value = serverUrl,
                            onValueChange = { serverUrl = it },
                            label = { Text(stringResource(R.string.field_url)) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        // Hint as separate text, not the field's supportingText:
                        // internal field padding made it noticeably narrower
                        // than the rest of the screen text.
                        if (serverType == ServerType.SRTLA) {
                            FieldHint(stringResource(R.string.field_url_hint_srtla))
                        }
                        if (serverType != ServerType.WHIP) {
                            val isRtmp = serverType == ServerType.RTMP
                            Spacer(Modifier.height(4.dp))
                            OutlinedTextField(
                                value = serverKey,
                                onValueChange = { serverKey = it },
                                label = {
                                    Text(
                                        stringResource(
                                            if (isRtmp) R.string.field_stream_key else R.string.onboarding_stream_id,
                                        ),
                                    )
                                },
                                // As in the server editor: the key grants access to the channel.
                                visualTransformation = if (keyVisible) {
                                    VisualTransformation.None
                                } else {
                                    PasswordVisualTransformation()
                                },
                                trailingIcon = {
                                    IconButton(onClick = { keyVisible = !keyVisible }) {
                                        Icon(
                                            if (keyVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                            contentDescription = stringResource(R.string.field_stream_key_show),
                                        )
                                    }
                                },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            FieldHint(
                                stringResource(
                                    if (isRtmp) R.string.field_stream_key_hint else R.string.onboarding_stream_id_hint,
                                ),
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                    }
                    ImportCard(onImported = { pendingImport = it })
                }

                OnboardingStep.QUALITY -> {
                    StepTitle(stringResource(R.string.onboarding_quality_title))
                    StepText(stringResource(R.string.onboarding_quality_text))
                    val caps = rememberDeviceCapabilities()
                    defaultStreamPresets.forEachIndexed { i, preset ->
                        if (!caps.supports(app.brix.core.Codec.H264, app.brix.streaming.VideoMode(preset.width, preset.height, preset.fps))) {
                            return@forEachIndexed
                        }
                        PresetTile(
                            preset = preset,
                            note = PRESET_NOTES.getOrNull(i)?.let { stringResource(it) },
                            selected = i == presetIndex,
                            onClick = { presetIndex = i },
                        )
                    }
                }

                OnboardingStep.DONE -> {
                    StepTitle(stringResource(R.string.onboarding_done_title))
                    StepText(stringResource(R.string.onboarding_done_text))
                    StepText(
                        stringResource(
                            if (serverFilled) R.string.onboarding_done_server else R.string.onboarding_done_no_server,
                        ),
                    )
                    StepText(stringResource(R.string.onboarding_done_scenes))
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        // Single-line labels: wrapping Skip step made its button taller,
        // causing the adjacent Back button to jump.
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            if (stepIndex > 0) {
                OutlinedButton(shape = BrixButtonShape, onClick = { stepIndex-- }, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.btn_back), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Button(
                shape = BrixButtonShape,
                onClick = { if (step == OnboardingStep.DONE) finish() else stepIndex++ },
                enabled = step != OnboardingStep.CUESCORE ||
                    verifiedCueScorePlayerId != null,
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    stringResource(
                        when {
                            step == OnboardingStep.DONE -> R.string.onboarding_start
                            step == OnboardingStep.SERVER && !serverFilled -> R.string.onboarding_skip_step
                            else -> R.string.btn_next
                        },
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }

    pendingImport?.let { config ->
        BrixDialog(
            title = stringResource(R.string.deep_link_import_title),
            onDismiss = { pendingImport = null },
            dismissLabel = stringResource(R.string.btn_cancel),
            confirmLabel = stringResource(R.string.btn_import),
            onConfirm = {
                pendingImport = null
                // Imported settings already contain server and quality;
                // later steps would overwrite them, so finish the wizard here.
                viewModel.completeOnboardingWithImport(config, verifiedCueScorePlayerId)
            },
        ) {
            StepText(
                stringResource(
                    R.string.deep_link_import_message,
                    config.streamProfiles.size,
                    config.serverProfiles.size,
                ),
            )
        }
    }
}

@Composable
private fun StepProgress(current: Int, total: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
        repeat(total) { i ->
            Box(
                Modifier
                    .weight(1f)
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(
                        if (i <= current) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.outlineVariant
                        },
                    ),
            )
        }
    }
}

@Composable
private fun StepTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
private fun StepText(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun FieldHint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
    )
}

/**
 * Quality preset tile. Custom rather than BrixNavRow, whose arrow indicates
 * navigation rather than selection and whose press highlight was clipped by
 * card padding. The entire tile is clickable, with an edge-to-edge highlight.
 */
@Composable
private fun PresetTile(preset: StreamPreset, note: String?, selected: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(4.dp),
        color = if (selected) scheme.primary.copy(alpha = 0.12f) else scheme.surfaceContainer,
        border = BorderStroke(
            if (selected) 1.5.dp else 0.5.dp,
            if (selected) scheme.primary else scheme.outlineVariant,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    presetTitle(preset),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (selected) scheme.primary else scheme.onSurface,
                )
                if (note != null) {
                    Text(note, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                }
            }
            Text(
                stringResource(R.string.onboarding_bitrate, preset.videoBitrateKbps),
                style = MaterialTheme.typography.bodyMedium,
                color = if (selected) scheme.primary else scheme.onSurfaceVariant,
            )
        }
    }
}

/** "720p@30", "4K@30". Core preset names ("720p30 · 2.5M") are unsuitable
 *  for users: the owner read "2.5M" as meters. */
private fun presetTitle(p: StreamPreset): String {
    val res = if (p.height >= 2160) "4K" else "${p.height}p"
    return "$res@${p.fps}"
}

@Composable
private fun PermissionRow(permission: String, granted: Boolean) {
    val label = when (permission) {
        Manifest.permission.CAMERA -> R.string.onboarding_perm_camera
        Manifest.permission.RECORD_AUDIO -> R.string.onboarding_perm_mic
        else -> R.string.onboarding_perm_notifications
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
    ) {
        Text(
            stringResource(label),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Text(
            if (granted) "✓" else "—",
            color = if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ImportCard(onImported: (SharedConfig) -> Unit) {
    val context = LocalContext.current
    var link by rememberSaveable { mutableStateOf("") }
    var invalid by remember { mutableStateOf(false) }
    BrixCard {
        Column(Modifier.padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.onboarding_import_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            StepText(stringResource(R.string.onboarding_import_text))
            OutlinedTextField(
                value = link,
                onValueChange = {
                    link = it
                    invalid = false
                },
                label = { Text(stringResource(R.string.config_field_link)) },
                placeholder = { Text("brix:// · moblin:// · larix://", maxLines = 1) },
                isError = invalid,
                // Paste as an in-field icon: a separate button beside Import
                // did not fit and broke the labels.
                trailingIcon = {
                    IconButton(onClick = {
                        val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
                        clipboard?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.let {
                            link = it.toString().trim()
                            invalid = false
                        }
                    }) {
                        Icon(
                            Icons.Filled.ContentPaste,
                            contentDescription = stringResource(R.string.btn_paste_clipboard),
                        )
                    }
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (invalid) {
                Text(
                    stringResource(R.string.deep_link_import_invalid),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            FilledTonalButton(
                shape = BrixButtonShape,
                onClick = {
                    val trimmed = link.trim()
                    val config = SettingsDeepLink.decodeAny(trimmed)
                    if (config == null) invalid = true else onImported(config)
                },
                enabled = link.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.btn_import), maxLines = 1) }
        }
    }
}
