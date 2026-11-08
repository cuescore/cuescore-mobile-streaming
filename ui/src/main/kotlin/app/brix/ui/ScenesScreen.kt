package app.brix.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import app.brix.core.SceneAudio
import app.brix.core.SceneSource
import app.brix.core.CameraSide
import app.brix.core.Ids
import app.brix.core.Scene

/**
 * Scenes: sets of what the viewer sees, switchable with one tap.
 *
 * The `Scene` model had been in the code from the beginning but was not read by
 * anything — scenes existed in the settings file and nowhere else. Here they
 * finally become actionable.
 *
 * A scene holds the camera side and a list of overlays and widgets. Until there
 * is at least one scene, the app works as before: it shows everything enabled.
 * This matters — adding scenes must not mean “now configure everything again.”
 */
@Composable
fun ScenesScreen(
    viewModel: SettingsViewModel,
    onEdit: (String?) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val settings by viewModel.settings.collectAsState()
    var pendingDelete by remember { mutableStateOf<String?>(null) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        // A real sub-screen (opened from Stream), not a category screen — it
        // used to be called without onBack, unlike its neighboring screens
        // (ServerProfiles/StreamProfiles), so there was no back button at all.
        // The breadcrumb uses the same approach as its neighbors.
        topBar = {
            SettingsTopBar(
                "${stringResource(R.string.settings_stream)} · ${stringResource(R.string.settings_scenes)}",
                onBack,
            )
        },
    ) { inner ->
        Column(
            modifier = Modifier
                .padding(inner.verticalOnly())
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.scenes_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (settings.scenes.isEmpty()) {
                Text(
                    text = stringResource(R.string.scenes_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            } else {
                BrixCard {
                    settings.scenes.forEachIndexed { index, scene ->
                        ProfileRow(
                            title = scene.name.ifBlank { stringResource(R.string.scenes_unnamed) },
                            subtitle = sceneSummary(scene),
                            selected = settings.selectedSceneId == scene.id,
                            onSelect = { viewModel.selectScene(scene.id) },
                            onClick = { onEdit(scene.id) },
                            onDelete = { pendingDelete = scene.id },
                        )
                        if (index != settings.scenes.lastIndex) {
                            HorizontalDivider(
                                thickness = 0.5.dp,
                                color = MaterialTheme.colorScheme.outlineVariant,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(4.dp))
            FilledTonalButton(
                shape = BrixButtonShape,
                onClick = { onEdit(null) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.scenes_add))
            }
        }
    }

    if (pendingDelete != null) {
        BrixDialog(
            title = stringResource(R.string.scenes_delete_title),
            onDismiss = { pendingDelete = null },
            dismissLabel = stringResource(R.string.btn_cancel),
            confirmLabel = stringResource(R.string.btn_delete),
            destructive = true,
            onConfirm = {
                pendingDelete?.let { viewModel.deleteScene(it) }
                pendingDelete = null
            },
        )
    }
}

/**
 * The scene editor is a SEPARATE SCREEN, like the stream profile editor.
 *
 * It used to be a dialog, which was a mistake: it has as many settings as a
 * profile, and the dialog cannot fit them. Attempts to stretch it broke the
 * scrolling that AlertDialog handles itself. A screen avoids that problem and
 * also looks like the rest of the app — the same “Cancel / title / Save” header
 * and the same cards.
 */
@Composable
fun SceneEditScreen(
    viewModel: SettingsViewModel,
    sceneId: String?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val settings by viewModel.settings.collectAsState()
    val existing = sceneId?.let { id -> settings.scenes.firstOrNull { it.id == id } }

    var name by rememberSaveable(sceneId) { mutableStateOf(existing?.name ?: "") }
    var camera by rememberSaveable(sceneId) { mutableStateOf(existing?.camera ?: CameraSide.BACK) }
    var source by rememberSaveable(sceneId) { mutableStateOf(existing?.source ?: SceneSource.CAMERA) }
    var sceneAudio by rememberSaveable(sceneId) { mutableStateOf(existing?.audio ?: SceneAudio.MIC) }
    var imageUri by rememberSaveable(sceneId) { mutableStateOf(existing?.imageUri ?: "") }
    var widgetIds by rememberSaveable(sceneId) { mutableStateOf(existing?.widgetIds?.toSet() ?: emptySet()) }

    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            // Persist the permission: without it, the URI expires after an app
            // restart, and the still image silently turns back into the camera.
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            imageUri = uri.toString()
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            SettingsEditTopBar(
                title = if (sceneId == null) stringResource(R.string.scenes_new_title) else name,
                onCancel = onBack,
                onSave = {
                    viewModel.saveScene(
                        (existing ?: Scene(id = Ids.newId(), name = name)).copy(
                            name = name.trim(),
                            source = source,
                            audio = sceneAudio,
                            imageUri = imageUri,
                            camera = camera,
                            widgetIds = widgetIds.toList(),
                        ),
                    )
                    onBack()
                },
                saveLabel = stringResource(R.string.btn_save),
                saveEnabled = name.isNotBlank(),
            )
        },
    ) { inner ->
        Column(
            modifier = Modifier
                .padding(inner.verticalOnly())
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BrixCard {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.field_name)) },
                    placeholder = { Text(stringResource(R.string.scenes_name_example)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                BrixSegmentRow(
                    title = stringResource(R.string.scenes_source),
                    options = listOf(
                        SceneSource.CAMERA to stringResource(R.string.scenes_source_camera),
                        SceneSource.IMAGE to stringResource(R.string.scenes_source_image),
                        SceneSource.SCREEN to stringResource(R.string.scenes_source_screen),
                    ),
                    selected = source,
                    divider = source != SceneSource.CAMERA,
                ) { source = it }
                if (source == SceneSource.CAMERA) {
                    Spacer(Modifier.height(12.dp))
                    BrixSegmentRow(
                        title = stringResource(R.string.scenes_camera),
                        options = listOf(
                            CameraSide.BACK to stringResource(R.string.btn_rear),
                            CameraSide.FRONT to stringResource(R.string.btn_front),
                        ),
                        selected = camera,
                        divider = false,
                    ) { camera = it }
                }
                if (source == SceneSource.IMAGE) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = if (imageUri.isBlank()) {
                            stringResource(R.string.scenes_image_none)
                        } else {
                            stringResource(R.string.scenes_image_chosen)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    FilledTonalButton(
                        shape = BrixButtonShape,
                        onClick = { picker.launch(arrayOf("image/*")) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.scenes_image_pick)) }
                    Spacer(Modifier.height(8.dp))
                }
                if (source == SceneSource.SCREEN) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.scenes_screen_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }

            BrixCard {
                BrixSegmentRow(
                    title = stringResource(R.string.scenes_audio),
                    options = listOf(
                        SceneAudio.MIC to stringResource(R.string.scenes_audio_mic),
                        SceneAudio.INTERNAL to stringResource(R.string.scenes_audio_internal),
                        SceneAudio.BOTH to stringResource(R.string.scenes_audio_both),
                    ),
                    selected = sceneAudio,
                    divider = false,
                ) { sceneAudio = it }
                if (sceneAudio != SceneAudio.MIC) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.scenes_audio_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }

            val hasLayers = settings.widgets.isNotEmpty()
            BrixCard {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(
                        if (hasLayers) R.string.scenes_layers_hint else R.string.scenes_no_layers,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                settings.widgets.forEach { widget ->
                    BrixToggleRow(
                        title = widget.name.ifBlank { shortUrl(widget.displayTitle()) },
                        subtitle = stringResource(widgetKindLabel(widget.kind)),
                        checked = widget.id in widgetIds,
                        divider = true,
                    ) { on -> widgetIds = if (on) widgetIds + widget.id else widgetIds - widget.id }
                }
            }
        }
    }
}

/**
 * Host instead of the full address.
 *
 * Widget and overlay URLs are long and often contain a key. The full address is
 * not useful in the checkbox list — it does not fit and wraps the line — while
 * the host distinguishes one layer from another.
 */
private fun shortUrl(url: String): String = runCatching {
    java.net.URI(url).host ?: url
}.getOrNull() ?: url

/** What's in the scene: the source and how many layers are on top. */
@Composable
private fun sceneSummary(scene: Scene): String {
    val what = stringResource(
        when (scene.source) {
            SceneSource.IMAGE -> R.string.scenes_source_image
            SceneSource.SCREEN -> R.string.scenes_source_screen
            SceneSource.CAMERA ->
                if (scene.camera == CameraSide.FRONT) R.string.btn_front else R.string.btn_rear
        },
    )
    val layers = scene.widgetIds.size
    return if (layers == 0) what else stringResource(R.string.scenes_summary, what, layers)
}
