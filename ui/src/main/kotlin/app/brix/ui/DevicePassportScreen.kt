package app.brix.ui

import android.content.Intent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.brix.core.diagnostics.Diagnostics
import app.brix.streaming.DeviceReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Device passport: what THIS hardware can do—and a button to share it with us.
 *
 * **Why this screen exists.** Everything we know about the app's behavior comes
 * from one Samsung S21. Two conclusions behind half the decisions in the code—
 * "no encoder holds CBR" and "the capsules are called bottom/back"—are true for
 * this phone, but we don't know whether they're true for others. Asking about it
 * in an issue is useless: people shouldn't have to know how to read `dumpsys`.
 * So we collect the data ourselves and let them share it with one tap.
 *
 * **Why the full report is shown instead of sent silently.** This is a requirement
 * in [DeviceReport]'s own KDoc, and it's not a formality: we're asking someone to
 * send us data about their device, and the only honest way is to show exactly what
 * will be sent before sending it. There is no way to send without this screen.
 */
@Composable
fun DevicePassportScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var report by remember { mutableStateOf<String?>(null) }

    // Querying MediaCodecList and all cameras is blocking and slow: the S21 has
    // about ten physical sensors, each with its own mode list. On the UI thread,
    // this causes a noticeable freeze when opening the screen.
    LaunchedEffect(Unit) {
        report = withContext(Dispatchers.IO) {
            runCatching { DeviceReport.render(context) }.getOrElse { "" }
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            SettingsTopBar(
                crumb(R.string.settings_about, R.string.settings_device_passport),
                onBack,
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding.verticalOnly())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text(
                text = stringResource(R.string.device_passport_info),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))

            val text = report
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    // Indented JSON is only readable in monospace, and wrapping it
                    // at word boundaries would push lines horizontally.
                    text = text ?: stringResource(R.string.device_passport_collecting),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    softWrap = false,
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                )
            }

            Spacer(Modifier.height(8.dp))
            FilledTonalButton(
                shape = BrixButtonShape,
                enabled = !text.isNullOrEmpty(),
                onClick = {
                    val body = text ?: return@FilledTonalButton
                    // Write the file off the UI thread, as with the diagnostics export.
                    scope.launch(Dispatchers.IO) {
                        val uri = Diagnostics.exportText(context, "device_passport", body)
                        withContext(Dispatchers.Main) {
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(intent, null))
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.device_passport_share))
            }
        }
    }
}
