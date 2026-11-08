package app.brix.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.brix.core.ConnectionPriority
import app.brix.core.defaultConnectionPriorities

@Composable
fun ChannelPrioritiesScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val settings by viewModel.settings.collectAsState()
    val profileId = settings.selectedStreamProfileId
    val profile = settings.streamProfiles.firstOrNull { it.id == profileId }
    // Fall back to defaults so the screen is always editable, even when a profile
    // predates the channel-priority model (H15).
    val channels = profile?.srtConnectionPriorities.orEmpty()
        .ifEmpty { defaultConnectionPriorities() }
    val enabledWeights = channels.filter { it.enabled && it.weight > 0 }.map { it.weight }
    val total = enabledWeights.sum()

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            SettingsTopBar(
                "${stringResource(R.string.settings_stream)} · ${stringResource(R.string.settings_channel_priorities)}",
                onBack,
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding.verticalOnly())
                // Phone links plus Moblink relays exceed the screen height.
                // Without scrolling the bottom items were clipped:
                // Ethernet (USB) was completely unreachable.
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            // Edit priorities for the SELECTED profile, not all profiles.
            // Without its name, users with two profiles think edits are global,
            // then report "settings do not apply".
            profile?.let {
                Text(
                    text = stringResource(R.string.settings_channel_priorities_profile, it.name),
                    style = MaterialTheme.typography.titleSmall,
                )
                Spacer(Modifier.height(4.dp))
            }
            Text(
                text = stringResource(R.string.settings_channel_priorities_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            channels.forEachIndexed { index, channel ->
                ChannelPriorityRow(
                    channel = channel,
                    sharePercent = if (total > 0 && channel.enabled && channel.weight > 0) {
                        channel.weight * 100 / total
                    } else {
                        0
                    },
                    onChange = { enabled, weight, commit ->
                        if (profileId != null) {
                            viewModel.updateConnectionPriority(
                                profileId = profileId,
                                name = channel.name,
                                enabled = enabled,
                                weight = weight,
                                commit = commit,
                            )
                        }
                    },
                )
                if (index != channels.lastIndex) {
                    HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}

@Composable
private fun ChannelPriorityRow(
    channel: ConnectionPriority,
    sharePercent: Int,
    onChange: (enabled: Boolean, weight: Int, commit: Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // Three phone links plus Moblink relays. Previously each row took
            // three tiers (name, caption, full-width slider), fitting only two
            // and making Ethernet (USB) clipped and unreachable.
            // Now one line: name with numbers, slider, toggle.
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.width(150.dp)) {
            Text(
                text = channelDisplayName(channel.name),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                color = if (channel.enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Text(
                // Previously "Weight · 62%" was misleading: the label named
                // weight but showed bandwidth share. These differ: the slider
                // sets weight; share derives from all enabled link weights.
                // Do not confuse them.
                text = stringResource(
                    R.string.settings_priority_weight_share,
                    channel.weight,
                    sharePercent,
                ),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Slider(
            value = channel.weight.toFloat(),
            onValueChange = { raw ->
                val w = raw.toInt().coerceIn(0, 10)
                // Live UI update only — every drag step used to hit disk
                // (full serialize + fsync + .bak copy) up to a hundred
                // times per drag. The actual write waits for
                // onValueChangeFinished below.
                if (w != channel.weight) onChange(channel.enabled, w, false)
            },
            onValueChangeFinished = { onChange(channel.enabled, channel.weight, true) },
            // Rank 1..10, not 0..100: SrtlaConnection.score() expects small
            // numbers near one, and nobody needs a hundred slider steps.
            // Retain zero to mean "disabled".
            valueRange = 0f..10f,
            steps = 9,
            modifier = Modifier.weight(1f),
            enabled = channel.enabled,
        )
        Spacer(Modifier.width(12.dp))
        BrixToggle(
            checked = channel.enabled,
            onCheckedChange = { enabled -> onChange(enabled, channel.weight, true) },
        )
    }
}

private fun channelDisplayName(name: String): String = when (name.uppercase()) {
    "WIFI" -> "Wi-Fi"
    "CELLULAR" -> "Cellular"
    "ETHERNET" -> "Ethernet (USB)"
    else -> name
}
