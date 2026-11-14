package tv.vega.tv.ui.update

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tv.vega.tv.data.providers.WafCoordinator
import tv.vega.tv.data.update.ApkUpdater
import tv.vega.tv.data.update.ReleaseInfo
import tv.vega.tv.data.update.UpdateApi
import tv.vega.tv.player.PlaybackSession
import tv.vega.tv.ui.theme.ScrimHeavy
import tv.vega.tv.ui.theme.VegaPrimary
import java.io.File

/**
 * Checks this project's GitHub releases once per launch and offers the APK.
 *
 * The TV has no Play Store, so a sideloaded build has no other way to learn
 * about a newer one. The check is deliberately quiet: it waits for the splash
 * and feed to settle, never runs while a video is playing (a dialog over a
 * film is worse than a stale version), and treats every network failure as
 * "no update" instead of nagging on a bad connection.
 *
 * The viewer still confirms: Android's installer is the only party that can
 * replace the package, so "Update now" downloads and opens it.
 */
@Composable
fun AutoUpdatePrompt() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val version = remember {
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }
            .getOrNull() ?: "1.0"
    }

    var release by remember { mutableStateOf<ReleaseInfo?>(null) }
    var dismissed by remember { mutableStateOf(false) }
    var downloading by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<Pair<Long, Long>?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    var pendingInstall by remember { mutableStateOf<File?>(null) }

    val playing by PlaybackSession.playerActiveFlow.collectAsStateWithLifecycle()
    val waf by WafCoordinator.pending.collectAsStateWithLifecycle()

    fun requestInstall(file: File) {
        if (Build.VERSION.SDK_INT >= 26 && !ctx.packageManager.canRequestPackageInstalls()) {
            pendingInstall = file
            note = "Allow installs for Vega TV — the installer continues automatically"
            runCatching {
                ctx.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                        .setData(Uri.parse("package:${ctx.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }.onFailure { note = "Couldn't open Settings: ${it.message}" }
        } else {
            runCatching { ApkUpdater.install(ctx, file) }
                .onFailure { note = "Install failed: ${it.message}" }
        }
    }

    // Coming back from the unknown-sources screen finishes the install.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event != Lifecycle.Event.ON_RESUME) return@LifecycleEventObserver
            val file = pendingInstall ?: return@LifecycleEventObserver
            val allowed = Build.VERSION.SDK_INT < 26 ||
                ctx.packageManager.canRequestPackageInstalls()
            if (allowed) {
                pendingInstall = null
                note = null
                runCatching { ApkUpdater.install(ctx, file) }
                    .onFailure { note = "Install failed: ${it.message}" }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(Unit) {
        // Let the splash, the feed and the first focus settle first; an update
        // dialog competing with the home screen is exactly the kind of popup a
        // TV app should not have.
        delay(6_000)
        val latest = runCatching { UpdateApi.latest() }.getOrNull() ?: return@LaunchedEffect
        if (!UpdateApi.isNewer(latest.tag, "v$version")) return@LaunchedEffect
        if (latest.apkUrl.isNullOrBlank()) return@LaunchedEffect
        release = latest
    }

    val offer = release
    if (offer == null || dismissed) return
    // Never cover playback or a provider sign-in challenge. The release stays
    // pending and appears when the viewer is back on a browse screen.
    if (playing || PlaybackSession.playbackRequested || waf != null) return

    val focus = remember(offer.tag) { FocusRequester() }
    LaunchedEffect(offer.tag) { runCatching { focus.requestFocus() } }
    val downloaded = progress

    Dialog(
        onDismissRequest = { if (!downloading) dismissed = true },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            Modifier
                .width(560.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(ScrimHeavy)
                .padding(28.dp),
        ) {
            Text(
                "Update available",
                style = MaterialTheme.typography.headlineSmall,
                color = VegaPrimary,
            )
            Text(
                "${offer.tag}  ·  ${offer.name.ifBlank { "Vega TV" }}",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 8.dp),
            )
            if (offer.apkSize > 0) {
                Text(
                    ApkUpdater.sizeLabel(offer.apkSize),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Text(
                "Installed: v$version. The download comes from this project's GitHub release.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 10.dp),
            )
            if (downloaded != null) {
                val (read, total) = downloaded
                val label = if (total > 0) {
                    "${ApkUpdater.sizeLabel(read)} / ${ApkUpdater.sizeLabel(total)}"
                } else {
                    ApkUpdater.sizeLabel(read)
                }
                Text(
                    "Downloading… $label",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
            note?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = VegaPrimary,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 22.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = {
                        if (downloading) return@Button
                        val url = offer.apkUrl ?: return@Button
                        downloading = true
                        note = null
                        scope.launch {
                            progress = 0L to 0L
                            runCatching {
                                ApkUpdater.download(ctx, url, offer.apkName) { read, total ->
                                    progress = read to total
                                }
                            }.onSuccess { file ->
                                progress = null
                                downloading = false
                                requestInstall(file)
                            }.onFailure {
                                progress = null
                                downloading = false
                                note = "Download failed: ${it.message}"
                            }
                        }
                    },
                    modifier = Modifier.focusRequester(focus),
                ) {
                    Text(if (downloading) "Downloading…" else "Update now", maxLines = 1)
                }
                Button(onClick = { if (!downloading) dismissed = true }) {
                    Text(if (downloading) "Hide" else "Later", maxLines = 1)
                }
            }
        }
    }
}
