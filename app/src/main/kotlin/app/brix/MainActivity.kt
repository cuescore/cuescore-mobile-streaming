package app.brix

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import app.brix.core.SettingsDeepLink
import app.brix.core.SettingsStore
import app.brix.core.normalizeLanguageTag
import app.brix.ui.R
import app.brix.ui.BrixIntro
import app.brix.ui.OnboardingScreen
import app.brix.ui.SettingsViewModel
import app.brix.ui.StreamScreen
import app.brix.ui.theme.BrixTheme
import java.util.Locale

/** Wraps [this] with a Configuration pinned to [tag], or returns [this]
 *  unchanged for an empty tag (system locale, resource qualifiers already
 *  handle it with no extra code). */
private fun Context.withLocale(tag: String): Context {
    if (tag.isEmpty()) return this
    val locale = Locale.forLanguageTag(tag)
    Locale.setDefault(locale)
    val config = Configuration(resources.configuration)
    config.setLocale(locale)
    return createConfigurationContext(config)
}

class MainActivity : ComponentActivity() {

    companion object {
        // Set right before we call recreate() for a language change (see
        // AppRoot below) so the attachBaseContext() that recreate() triggers
        // reuses the value we already have in memory instead of a redundant
        // synchronous disk read + JSON parse on the main thread for what the
        // user experiences as a simple settings toggle. Null on a genuine
        // cold start (fresh process), where a disk read is unavoidable
        // anyway — nothing has loaded yet at this point in the lifecycle.
        @Volatile
        internal var cachedLanguage: String? = null

        private const val KEY_LANDSCAPE = "brix.landscape"
    }

    // Runs before onCreate/Compose — settings aren't loaded into a ViewModel
    // yet at this point, so read just the language field directly off disk.
    // A small synchronous read of a few-KB JSON file at this exact lifecycle
    // point is the standard pattern for per-app locale override.
    override fun attachBaseContext(newBase: Context) {
        val language = cachedLanguage ?: runCatching {
            SettingsStore.create(newBase).load().getOrNull()?.appearance?.language
        }.getOrNull()?.ifBlank { "en" } ?: "en"
        cachedLanguage = language
        super.attachBaseContext(newBase.withLocale(normalizeLanguageTag(language)))
    }

    // Holds the most recent brix://settings deep link (see
    // core/SettingsDeepLink.kt) so Compose can show an import-confirmation
    // dialog — set from both onCreate (cold start via the link) and
    // onNewIntent (already running, singleTask routes here instead of
    // spawning a second Activity on top of a live camera/stream session).
    private val pendingDeepLink = mutableStateOf<String?>(null)


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        pendingDeepLink.value = intent?.dataString
        if (savedInstanceState?.getBoolean(KEY_LANDSCAPE) == true) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
        setContent {
            val settingsViewModel: SettingsViewModel = viewModel()
            val settings by settingsViewModel.settings.collectAsState()
            BrixTheme(appearance = settings.appearance) {
                var introDone by rememberSaveable { mutableStateOf(false) }
                val settingsLoaded by settingsViewModel.settingsLoaded.collectAsState()
                // Real settings, not the placeholder before disk loading:
                // its onboardingDone = false would show the wizard to everyone.
                val real = settingsLoaded && !settingsViewModel.isPlaceholder(settings)
                val needWizard = real && settings.onboardingDone == false
                val livePhase = introDone && real && !needWizard

                // Orientation follows the phase: intro and wizard are portrait,
                // streaming is landscape (the owner, 16.09). Create the streaming
                // screen only when the window is ALREADY landscape, so the camera
                // starts once in its own orientation. On 15.09 the intro ran over
                // a live camera, with rotation breaking preview three times
                // (commit 0f190e1). The new ordering costs a second of empty screen
                // while the camera starts.
                LaunchedEffect(livePhase) {
                    landscape = livePhase
                    requestedOrientation = if (livePhase) {
                        ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                    } else {
                        ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    }
                }
                LaunchedEffect(settings.onboardingDone) {
                    if (settings.onboardingDone == false) {
                        introDone = false
                    }
                }
                LanguageRecreateEffect(settings = settings, settingsLoaded = settingsLoaded)
                // Detect landscape from actual dimensions, not LocalConfiguration:
                // on 16.09 the window rotated but composition's orientation stayed
                // portrait, leaving an empty background instead of the streaming
                // screen. We override configuration for language (attachBaseContext);
                // actual dimensions cannot lie.
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    if (livePhase && maxWidth > maxHeight) {
                        StreamScreen(settings = settings, settingsViewModel = settingsViewModel)
                        DeepLinkImportDialog(pendingDeepLink, settingsViewModel::importSharedConfig)
                    }
                }
                if (introDone && needWizard) {
                    OnboardingScreen(viewModel = settingsViewModel)
                    // A brix:// or moblin:// link on a fresh install answers the
                    // wizard: import it in full and close the wizard.
                    DeepLinkImportDialog(pendingDeepLink, settingsViewModel::completeOnboardingWithImport)
                }
                // rememberSaveable prevents replaying the intro when language
                // changes or rotation recreate Activity.
                if (!introDone) {
                    BrixIntro(onFinished = { introDone = true })
                }
            }
        }
    }

    // Recreation (language change) opens the manifest's portrait window.
    // If streaming was active, request landscape immediately without extra rotation.
    private var landscape = false

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_LANDSCAPE, landscape)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingDeepLink.value = intent.dataString
    }

    // Camera-preview freeze diagnostics: several rounds of guessing at the
    // trigger (pinch zoom, lens taps, double-tap) failed to hold up, and a
    // recent capture showed the window losing visibility (visible=false)
    // with no explanation — we never logged our own lifecycle at all, so
    // there was no way to tell a real background/foreground transition
    // (onPause called) from something merely drawing on top while still
    // resumed (onWindowFocusChanged(false) without onPause).
    override fun onPause() {
        super.onPause()
        Log.w("BrixLifecycle", "onPause")
    }

    override fun onResume() {
        super.onResume()
        Log.w("BrixLifecycle", "onResume")
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        Log.w("BrixLifecycle", "onWindowFocusChanged hasFocus=$hasFocus")
    }
}

@Composable
private fun LanguageRecreateEffect(
    settings: app.brix.core.AppSettings,
    settingsLoaded: Boolean,
) {
    // attachBaseContext() only runs on (re)creation — picking a new language
    // in Settings needs an explicit recreate() to actually re-read it.
    // rememberSaveable survives that recreate() (SavedStateRegistry), so this
    // only fires once per real language change, not on every recreate.
    //
    // settingsLoaded gates this: SettingsViewModel.settings starts at
    // defaultAppSettings() (language "") and only reaches the real persisted
    // value asynchronously. Without the gate, that placeholder -> real-value
    // transition looks exactly like a language change on every cold start
    // for anyone not on "Auto" — triggering a needless recreate() (camera/
    // permissions/preview all restart) even though attachBaseContext()
    // already applied the correct locale before Compose ever ran. It also
    // double-fires after a real recreate() restores appliedLanguage from
    // SavedStateRegistry before the fresh ViewModel's own load completes.
    val context = LocalContext.current
    var appliedLanguage by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(settingsLoaded, settings.appearance.language) {
        if (!settingsLoaded) return@LaunchedEffect
        val current = appliedLanguage
        if (current == null) {
            // First real settings observed this process — already applied
            // by attachBaseContext(), just record the baseline.
            appliedLanguage = settings.appearance.language
        } else if (normalizeLanguageTag(settings.appearance.language) != normalizeLanguageTag(current)) {
            // Compare NORMALIZED tags, the same form attachBaseContext() and
            // LanguageScreen use. On raw strings, an upgrading user whose
            // settings still hold a legacy enum value ("AUTO"/"RU"/"EN") got a
            // full Activity rebuild — camera, permissions and preview — the
            // first time they re-picked the row that was already ticked, for a
            // locale that did not actually change.
            appliedLanguage = settings.appearance.language
            MainActivity.cachedLanguage = settings.appearance.language
            (context as? Activity)?.recreate()
        }
    }
}

/** Server URL scheme and host, without path, query or credentials that may
 *  contain keys. For display in the import dialog. */
private fun serverHostOf(url: String): String {
    val scheme = url.substringBefore("://", "")
    val host = url.substringAfter("://")
        .takeWhile { it != '/' && it != '?' && it != '#' }
        .substringAfterLast('@')
    return if (scheme.isEmpty()) host else "$scheme://$host"
}

/** Shows an import-confirmation dialog for an incoming `brix://`/`moblin://`
 *  link — decoded via [SettingsDeepLink], never applied silently. Clears
 *  [pendingLink] on both accept and dismiss so re-showing the same Activity
 *  (e.g. a config change recreating it) doesn't re-prompt for a link already
 *  handled. */
@Composable
private fun DeepLinkImportDialog(
    pendingLink: androidx.compose.runtime.MutableState<String?>,
    onImport: (app.brix.core.SharedConfig) -> Unit,
) {
    val link = pendingLink.value ?: return
    val config = SettingsDeepLink.decodeAny(link)
    if (config == null) {
        // Do not log the link itself: it may contain stream keys (audit 23.09).
        Log.w("BrixDeepLink", "unrecognized or malformed link, len=${link.length}")
        pendingLink.value = null
        return
    }
    AlertDialog(
        onDismissRequest = { pendingLink.value = null },
        title = { Text(stringResource(R.string.deep_link_import_title)) },
        text = {
            androidx.compose.foundation.layout.Column {
                Text(
                    stringResource(
                        R.string.deep_link_import_message,
                        config.streamProfiles.size,
                        config.serverProfiles.size,
                    ),
                )
                // Show the broadcast destination before Import. Previously users
                // decided blindly from counts, and a link with a substituted
                // server could arrive as "here is my config" (audit 23.09).
                // Show scheme and host, without path or key.
                config.serverProfiles.forEach { server ->
                    Text(
                        text = "${server.name} — ${serverHostOf(server.baseUrl)}",
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(shape = androidx.compose.foundation.shape.RoundedCornerShape(androidx.compose.ui.unit.Dp(4f)), onClick = {
                onImport(config)
                pendingLink.value = null
            }) {
                Text(stringResource(R.string.btn_import))
            }
        },
        dismissButton = {
            TextButton(shape = androidx.compose.foundation.shape.RoundedCornerShape(androidx.compose.ui.unit.Dp(4f)), onClick = { pendingLink.value = null }) {
                Text(stringResource(R.string.btn_cancel))
            }
        },
    )
}
