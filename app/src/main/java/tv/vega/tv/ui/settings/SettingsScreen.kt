package tv.vega.tv.ui.settings

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Switch
import androidx.tv.material3.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tv.vega.tv.data.ServiceLocator
import tv.vega.tv.data.net.NetClients
import tv.vega.tv.data.settings.SubtitleStyle
import tv.vega.tv.data.update.UpdateApi

/**
 * Full parity with web Preference/Appearance/SubtitleSettings/About (TV
 * adapted): playback, subtitles, network (DoH), TMDB key, sync, data
 * management, about/update. Single scroll, D-pad focusable controls.
 */
@OptIn(coil.annotation.ExperimentalCoilApi::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val useExternal by ServiceLocator.settings.useExternalPlayer
        .collectAsStateWithLifecycle(initialValue = false)
    val excluded by ServiceLocator.settings.excludedQualities
        .collectAsStateWithLifecycle(initialValue = emptySet())
    val subStyle by ServiceLocator.settings.subtitleStyle
        .collectAsStateWithLifecycle(initialValue = SubtitleStyle())
    val doh by ServiceLocator.settings.doh
        .collectAsStateWithLifecycle(initialValue = "off" to "")
    val tmdb by ServiceLocator.settings.tmdbApiKey
        .collectAsStateWithLifecycle(initialValue = "")
    val autoCheck by ServiceLocator.settings.autoCheckUpdate
        .collectAsStateWithLifecycle(initialValue = true)
    val preferBest by ServiceLocator.settings.preferBestQuality
        .collectAsStateWithLifecycle(initialValue = true)
    val maxFps by ServiceLocator.settings.maxFps
        .collectAsStateWithLifecycle(initialValue = "auto")

    var tmdbEdit by remember(tmdb) { mutableStateOf(tmdb) }
    var dohUrlEdit by remember(doh) { mutableStateOf(doh.second) }
    var status by remember { mutableStateOf<String?>(null) }
    var updateInfo by remember { mutableStateOf<String?>(null) }
    var releasesUrl by remember { mutableStateOf<String?>(null) }

    val syncPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                ctx.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            scope.launch {
                ServiceLocator.sync.setSharedRoot(uri.toString())
                status = "Sync folder set ✓"
                ServiceLocator.syncNow()
            }
        }
    }

    val version = remember {
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "1.0"
    }

    LazyColumn(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item(key = "head") { Text("Settings", style = MaterialTheme.typography.headlineSmall) }
        status?.let { item(key = "status") { Text(it, color = MaterialTheme.colorScheme.primary) } }

        item(key = "pb-h") { Section("Playback") }
        item(key = "pb-ext") {
            SwitchRow("External player", "Open streams in VLC/MX instead of built-in", useExternal) {
                scope.launch { ServiceLocator.settings.setExternal(it) }
            }
        }
        item(key = "pb-best") {
            SwitchRow(
                "Best quality by default",
                "Start on the highest rendition the provider offers, not the first one listed",
                preferBest,
            ) { scope.launch { ServiceLocator.settings.setPreferBestQuality(it) } }
        }
        item(key = "pb-fps") {
            Text("Frame rate cap", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                listOf("auto" to "Auto", "24" to "24", "30" to "30", "50" to "50", "60" to "60")
                    .forEach { (v, l) ->
                        Button(onClick = { scope.launch { ServiceLocator.settings.setMaxFps(v) } }) {
                            Text((if (maxFps == v) "✓ " else "") + l)
                        }
                    }
            }
            Text(
                "Auto lets the source's own rate through. Cap only if the box cannot keep up.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item(key = "pb-q") {
            Text("Excluded qualities", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                listOf("360p", "480p", "720p").forEach { q ->
                    val on = q in excluded
                    Button(onClick = {
                        scope.launch {
                            ServiceLocator.settings.setExcludedQualities(
                                if (on) excluded - q else excluded + q,
                            )
                        }
                    }) { Text((if (on) "✓ " else "") + q) }
                }
            }
        }

        item(key = "sub-h") { Section("Subtitles") }
        item(key = "sub-scale") {
            Text("Size", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                listOf(0.75f to "S", 1f to "M", 1.25f to "L", 1.5f to "XL").forEach { (v, l) ->
                    Button(onClick = { scope.launch { ServiceLocator.settings.setSubtitleStyle(subStyle.copy(scale = v)) } }) {
                        Text((if (subStyle.scale == v) "✓ " else "") + l)
                    }
                }
            }
        }
        item(key = "sub-color") {
            Text("Color", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                listOf("#FFFFFF" to "White", "#FFFF00" to "Yellow", "#00FFFF" to "Cyan", "#00FF00" to "Green").forEach { (v, l) ->
                    Button(onClick = { scope.launch { ServiceLocator.settings.setSubtitleStyle(subStyle.copy(color = v)) } }) {
                        Text((if (subStyle.color == v) "✓ " else "") + l)
                    }
                }
            }
        }
        item(key = "sub-edge") {
            Text("Edge", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                listOf("outline", "dropShadow", "raised", "depressed", "none").forEach { v ->
                    Button(onClick = { scope.launch { ServiceLocator.settings.setSubtitleStyle(subStyle.copy(edge = v)) } }) {
                        Text((if (subStyle.edge == v) "✓ " else "") + v)
                    }
                }
            }
        }

        item(key = "net-h") { Section("Network") }
        item(key = "net-check") {
            Text("Connection test", style = MaterialTheme.typography.titleMedium)
            Text(
                "Checks DNS, TLS and the native provider bridge before blaming a provider.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            Button(
                onClick = {
                    scope.launch { status = "Testing…"; status = runNetCheck(ctx) }
                },
                modifier = Modifier.padding(top = 8.dp),
            ) { Text("Run test") }
        }
        item(key = "net-doh") {
            Text("DNS over HTTPS", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                listOf("off" to "Off", "cloudflare" to "Cloudflare", "google" to "Google", "custom" to "Custom").forEach { (v, l) ->
                    Button(onClick = {
                        scope.launch {
                            ServiceLocator.settings.setDoh(v, dohUrlEdit)
                            NetClients.refresh(ctx)
                            status = "DNS updated ✓"
                        }
                    }) { Text((if (doh.first == v) "✓ " else "") + l) }
                }
            }
            if (doh.first == "custom") {
                OutlinedTextField(
                    value = dohUrlEdit,
                    onValueChange = { dohUrlEdit = it },
                    placeholder = { Text("https://…/dns-query") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                Button(onClick = {
                    scope.launch {
                        ServiceLocator.settings.setDoh("custom", dohUrlEdit)
                        NetClients.refresh(ctx)
                        status = "DNS updated ✓"
                    }
                }, modifier = Modifier.padding(top = 8.dp)) { Text("Save") }
            }
            Text(
                "WARP/ByeDPI need device VPN apps on TV — use Android Private DNS for the same effect.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }

        item(key = "meta-h") { Section("Metadata") }
        item(key = "meta-tmdb") {
            Text("TMDB API key (enables title/synopsis enrichment)", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = tmdbEdit,
                onValueChange = { tmdbEdit = it },
                placeholder = { Text("Paste key or leave empty") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                Button(onClick = {
                    scope.launch { ServiceLocator.settings.setTmdbKey(tmdbEdit); status = "Key saved ✓" }
                }) { Text("Save") }
                Button(onClick = {
                    scope.launch { ServiceLocator.settings.setTmdbKey(""); status = "Key cleared" }
                }) { Text("Clear") }
            }
        }

        item(key = "sync-h") { Section("Sync") }
        item(key = "sync-b") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { syncPicker.launch(null) }) { Text("Shared folder") }
                Button(onClick = {
                    scope.launch { ServiceLocator.syncNow(); status = "Syncing…" }
                }) { Text("Sync now") }
            }
        }

        item(key = "data-h") { Section("Data") }
        item(key = "data-b") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    scope.launch {
                        runCatching {
                            coil.Coil.imageLoader(ctx).diskCache?.clear()
                            coil.Coil.imageLoader(ctx).memoryCache?.clear()
                            java.io.File(ctx.cacheDir, "subs").deleteRecursively()
                        }
                        status = "Cache cleared ✓"
                    }
                }) { Text("Clear cache") }
                Button(onClick = {
                    scope.launch {
                        status = runCatching {
                            eraseAll(ctx)
                            "Erased — restart the app"
                        }.getOrElse { "Failed" }
                    }
                }) { Text("Erase all data") }
            }
        }

        item(key = "about-h") { Section("About") }
        item(key = "about-v") {
            Text("Vega TV v$version", style = MaterialTheme.typography.titleMedium)
            SwitchRow("Check for updates on start", "", autoCheck) {
                scope.launch { ServiceLocator.settings.setAutoCheck(it) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                Button(onClick = {
                    scope.launch {
                        updateInfo = "Checking…"
                        releasesUrl = null
                        runCatching { UpdateApi.latest() }.onSuccess { r ->
                            if (UpdateApi.isNewer(r.tag, "v$version")) {
                                updateInfo = "Update available: ${r.tag} ${r.name}"
                                releasesUrl = r.htmlUrl
                            } else updateInfo = "Up to date ✓"
                        }.onFailure { updateInfo = "Check failed" }
                    }
                }) { Text("Check now") }
                releasesUrl?.let { u ->
                    Button(onClick = {
                        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(u))) }
                    }) { Text("Open releases") }
                }
            }
            updateInfo?.let {
                Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
            }
        }

        item(key = "back") {
            Spacer(Modifier.height(8.dp))
            Button(onClick = onBack) { Text("Back") }
        }
    }
}

@Composable
private fun Section(t: String) {
    Text(t, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
}

/**
 * Layered connectivity probe. Reports the first layer that fails so a provider
 * problem isn't mistaken for a network one (or the reverse). Uses the same OkHttp
 * client as the provider bridge, so a pass here means the bridge's transport works.
 */
private suspend fun runNetCheck(ctx: android.content.Context): String {
    val client = NetClients.http
    suspend fun probe(label: String, url: String) =
        withContext(Dispatchers.IO) {
            runCatching {
                client.newCall(okhttp3.Request.Builder().url(url).build()).execute().use { r ->
                    "$label → HTTP ${r.code}"
                }
            }.getOrElse { "$label → FAILED: ${it.javaClass.simpleName}: ${it.message}" }
        }
    val lines = mutableListOf<String>()
    lines += probe("DNS/TLS (github raw)", "https://raw.githubusercontent.com/Zenda-Cross/vega-providers/refs/heads/main/urls.json")
    lines += probe("Provider site (vegamovies)", "https://vegamovies.gallery/page/1/")
    // The native bridge is what provider JS actually uses; exercise it directly.
    lines += runCatching {
        val r = tv.vega.tv.data.providers.ProviderHttp()
            .fetch("""{"url":"https://vegamovies.gallery/page/1/","method":"GET","headers":{}}""")
        if (r.contains("\"ok\":true")) "Native bridge → OK" else "Native bridge → ${r.take(120)}"
    }.getOrElse { "Native bridge → FAILED: ${it.message}" }
    return lines.joinToString("   |   ")
}

@Composable
private fun SwitchRow(title: String, desc: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (desc.isNotBlank()) {
                Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

private suspend fun eraseAll(ctx: android.content.Context) {
    // DataStore Preferences are NOT in shared_prefs — they live in
    // files/datastore/<name>.preferences_pb. Deleting only shared_prefs left the
    // installed-provider and active-provider index intact, so "Erase all data"
    // came back with Zenda-Cross still listed as installed. Clear the real
    // location, and the classic mirror some OEM builds keep.
    listOf(
        java.io.File(ctx.applicationInfo.dataDir, "shared_prefs"),
        java.io.File(ctx.filesDir, "datastore"),
        java.io.File(ctx.applicationInfo.dataDir, "datastore"),
    ).forEach { dir -> dir.listFiles()?.forEach { runCatching { it.delete() } } }
    java.io.File(ctx.filesDir, "providers").deleteRecursively()
    java.io.File(ctx.filesDir, ".vega-sync").deleteRecursively()
    ctx.cacheDir.deleteRecursively()
}
