package tv.vega.tv.ui.extensions

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.foundation.shape.RoundedCornerShape
import coil.compose.AsyncImage
import coil.request.ImageRequest
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import tv.vega.tv.data.ServiceLocator
import tv.vega.tv.data.providers.ProviderExtension
import tv.vega.tv.data.providers.ProviderManager
import tv.vega.tv.data.providers.ProviderSource
import tv.vega.tv.data.providers.isNewerProviderVersion
import tv.vega.tv.ui.common.ErrorScreen
import tv.vega.tv.ui.common.LoadingScreen
import tv.vega.tv.ui.common.UiState
import tv.vega.tv.ui.common.VegaSpinner

/**
 * Everything the provider manager owns, in one snapshot: the sources the user
 * has added, the providers each source offers, what is installed, what is
 * active, and which installed providers have a newer version.
 */
data class ExtData(
    val sources: List<ProviderSource> = emptyList(),
    val selectedSource: String? = null,
    val installed: List<ProviderExtension> = emptyList(),
    val available: List<ProviderExtension> = emptyList(),
    val activeRef: String? = null,
    val updates: Set<String> = emptySet(),
) {
    /** "author:value" — the identity used everywhere in the store. */
    private fun keyOf(e: ProviderExtension) = "${e.source.author}:${e.value}"
    fun isActive(e: ProviderExtension) = keyOf(e) == activeRef
    fun hasUpdate(e: ProviderExtension) = keyOf(e) in updates
    fun isInstalled(e: ProviderExtension) = installed.any { keyOf(it) == keyOf(e) }
}

class ExtensionsViewModel : ViewModel() {
    private val _state = MutableStateFlow<UiState<ExtData>>(UiState.Loading)
    val state: StateFlow<UiState<ExtData>> = _state

    private val _busy = MutableStateFlow<String?>(null)
    val busy: StateFlow<String?> = _busy

    private val _result = MutableStateFlow<String?>(null)
    val result: StateFlow<String?> = _result

    private var selected: String? = null
    private var loadJob: kotlinx.coroutines.Job? = null

    /**
     * Provider ref whose primary button should take focus after the next
     * rebuild. Installing moves a row from "Available" to "Installed"; without
     * this the rebuild dropped focus and the screen sent it back to the search
     * field at the top.
     */
    private val _focusRequest = MutableStateFlow<String?>(null)
    val focusRequest: StateFlow<String?> = _focusRequest

    fun consumeFocusRequest() {
        _focusRequest.value = null
    }

    init { load() }

    fun load() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch { loadNow() }
    }

    private suspend fun loadNow() {
        // A rebuild after Install/Activate must not replace the list with a
        // skeleton: that is what lost focus and made a slow source feel like a
        // freeze. The old rows stay until the new snapshot is ready.
        if (_state.value !is UiState.Success) _state.value = UiState.Loading
        try {
            _state.value = UiState.Success(snapshot())
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            if (_state.value !is UiState.Success) {
                _state.value = UiState.Error(e.message ?: "Failed")
            }
        }
    }

    private suspend fun snapshot(): ExtData {
        val pm = ServiceLocator.providers
        pm.ensureSeeded()
        val sources = pm.sources()
        val installed = pm.store.installed()

        // Keep the reader's position: stay on the source they chose,
        // and only fall back when it no longer exists.
        val activeAuthor = selected?.takeIf { a -> sources.any { it.author == a } }
            ?: sources.firstOrNull()?.author
        selected = activeAuthor
        val src = sources.firstOrNull { it.author == activeAuthor }

        // Show the cache immediately, then try the network. A TV on a
        // flaky link still gets a usable list rather than an error page.
        val cached = src?.let { pm.store.available(it.author) }.orEmpty()
        var avail = cached
        if (src != null) {
            avail = try {
                pm.refreshSource(src, force = false)
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Leaving the screen is not "the source is unreachable".
                throw e
            } catch (_: Exception) {
                cached
            }.filter { !it.disabled }
        }
        val upd = avail.filter { a ->
            installed.any { i ->
                i.value == a.value && i.source.author == a.source.author &&
                    isNewerProviderVersion(a.version, i.version)
            }
        }.map { "${it.source.author}:${it.value}" }.toSet()
        return ExtData(sources, activeAuthor, installed, avail, pm.store.activeRef(), upd)
    }

    fun selectSource(author: String) {
        selected = author
        load()
    }

    private fun run(
        label: String,
        focusRef: String? = null,
        fn: suspend () -> Unit,
    ) {
        viewModelScope.launch {
            _busy.value = label
            try {
                fn()
                if (_result.value == null) _result.value = label.removeSuffix("…") + " ✓"
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                _result.value = "Error: ${e.message?.take(200)}"
            }
            _busy.value = null
            loadNow()
            _focusRequest.value = focusRef
        }
    }

    fun addSource(input: String) = run("Adding source…") {
        val n = ServiceLocator.providers.addSource(input)
        // Land the user on what they just added instead of the first source.
        _result.value = "Source added — $n providers available"
    }

    fun removeSource(author: String) = run("Removing source…") {
        ServiceLocator.providers.removeSource(author)
    }

    fun refreshSource() = run("Refreshing…") {
        val pm = ServiceLocator.providers
        val src = pm.sources().firstOrNull { it.author == selected } ?: return@run
        pm.refreshSource(src, force = true)
    }

    private fun refOf(ext: ProviderExtension): String = "${ext.source.author}:${ext.value}"

    fun install(ext: ProviderExtension) =
        run("Installing ${ext.display_name}…", focusRef = refOf(ext)) {
        val pm = ServiceLocator.providers
        // Distinguish a fresh install from an update: updating must not switch
        // away from a different provider the user is currently using.
        val wasInstalled = pm.store.installed().any {
            it.value == ext.value && it.source.author == ext.source.author
        }
        pm.install(ext)
        if (!wasInstalled) {
            val cur = pm.active()
            if (cur == null || cur.ext.value != ext.value) {
                pm.setActive(ext.copy(installed = true))
                _result.value = "${ext.display_name} installed and activated"
            }
        }
    }

    fun uninstall(ext: ProviderExtension) = run("Removing ${ext.display_name}…") {
        val pm = ServiceLocator.providers
        pm.store.setInstalled(
            pm.store.installed().filterNot { it.value == ext.value && it.source.author == ext.source.author },
        )
        if (pm.store.activeRef() == "${ext.source.author}:${ext.value}") {
            val next = pm.store.installed().firstOrNull()
            if (next != null) pm.setActive(next) else pm.store.setActiveRef("")
        }
    }

    fun activate(ext: ProviderExtension) =
        run("Activating ${ext.display_name}…", focusRef = refOf(ext)) {
        ServiceLocator.providers.setActive(ext)
    }

    /**
     * Staged diagnostics: catalog→posts→meta→stream. The stage is tracked so a
     * failure names the exact step that broke — the most useful signal when
     * triaging on a TV with no logs.
     */
    fun test(ext: ProviderExtension) = run("Testing ${ext.display_name}…") {
        val pm = ServiceLocator.providers
        val src = pm.sources().firstOrNull { it.author == ext.source.author }
            ?: error("No source for ${ext.source.author}")
        val a = ProviderManager.Active(ext, src)
        var stage = "catalog"
        try {
            _busy.value = "Testing ${ext.display_name}: catalog…"
            val (cat, _) = pm.getCatalog(a)

            stage = "posts"
            _busy.value = "Testing ${ext.display_name}: posts…"
            val posts = pm.getPosts(a, cat.firstOrNull()?.filter.orEmpty(), 1)
            require(posts.isNotEmpty()) { "no posts returned" }

            stage = "meta"
            _busy.value = "Testing ${ext.display_name}: meta…"
            val meta = pm.getMeta(a, posts.first().link)
            require(meta.linkList.isNotEmpty()) { "no seasons or links" }

            stage = "stream"
            _busy.value = "Testing ${ext.display_name}: stream…"
            val first = meta.linkList.first()
            val epLink = first.directLinks.firstOrNull()?.link
                ?: first.episodesLink?.let { pm.getEpisodes(a, it).firstOrNull()?.link }
                ?: error("no playable link")
            val streams = pm.getStream(a, epLink, meta.type.ifBlank { "movie" })
            require(streams.isNotEmpty()) { "no streams returned" }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            // A provider that ran out its own budget is a failed stage, not a
            // viewer walking away: say which stage stopped instead of leaving
            // the busy banner up.
            _result.value = "✗ ${ext.display_name} failed at $stage: ${e.message?.take(140)}"
            return@run
        } catch (e: kotlinx.coroutines.CancellationException) {
            // The viewer left mid-test: not a failed stage.
            throw e
        } catch (e: Exception) {
            _result.value = "✗ ${ext.display_name} failed at $stage: ${e.message?.take(140)}"
            return@run
        }
        _result.value = "✓ ${ext.display_name} working"
    }
}

/**
 * Provider manager. The mobile app lets you add a source, browse everything it
 * publishes, and install/activate/test per provider — so this does the same,
 * with a type-to-add field and no pre-seeded source.
 *
 * D-pad: one scroll column; the source switcher is a focusable LazyRow, every
 * action is a plain focusable Button. No nested scrolling, no sheets.
 */
@Composable
fun ExtensionsScreen(
    onOpenSettings: (() -> Unit)? = null,
    vm: ExtensionsViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val result by vm.result.collectAsStateWithLifecycle()
    val focusRequest by vm.focusRequest.collectAsStateWithLifecycle()
    var sourceInput by remember { mutableStateOf("") }
    var showTrace by remember { mutableStateOf(false) }
    // One requester per provider identity, shared by the Available and
    // Installed rows: install moves the item between the two lists, and the
    // focused button must move with it.
    val requesters = remember { mutableMapOf<String, FocusRequester>() }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    // A TV install keeps focus on the Install button, but some IMEs leave the
    // soft keyboard on screen when focus leaves the source field. Hiding it
    // explicitly on focus loss — and again when the row's button is pressed —
    // is the only reliable order across launchers. The window insets
    // controller is the second shot: it reaches the IME even when Compose's
    // software-keyboard controller was never attached to a view.
    val keyboard = LocalSoftwareKeyboardController.current
    val view = LocalView.current
    val hideKeyboard: () -> Unit = {
        keyboard?.hide()
        runCatching {
            val window = view.context.findActivity()?.window ?: return@runCatching
            WindowInsetsControllerCompat(window, view)
                .hide(WindowInsetsCompat.Type.ime())
        }
    }
    // Installing rebuilds the provider list; the row the user pressed is
    // removed, focus flickers, and the IME can reappear over the result. Hide
    // on both edges of every long action.
    LaunchedEffect(busy) { hideKeyboard() }

    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp)) {
        Text("Providers", style = MaterialTheme.typography.headlineSmall)

        busy?.let {
            Row(
                Modifier.padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                VegaSpinner(diameter = 18.dp)
                Spacer(Modifier.width(10.dp))
                Text(it, color = MaterialTheme.colorScheme.primary)
            }
        }
        // Persist the outcome of install/test so it survives navigation away.
        result?.let {
            Text(
                it,
                color = if (it.startsWith("Error") || it.startsWith("✗")) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        // Last raw runtime failure, if any — the most precise diagnostic available.
        ServiceLocator.providers.js.lastError?.let {
            Text(
                "Last error: $it",
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        // ---- add a source (the only way one appears; nothing is pre-seeded) ----
        Spacer(Modifier.height(12.dp))
        Text("Add provider source", style = MaterialTheme.typography.titleMedium)
        Text(
            "Type a GitHub author (e.g. @Zenda-Cross) or paste a provider repo URL.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = sourceInput,
                onValueChange = { sourceInput = it },
                placeholder = { Text("@Zenda-Cross") },
                singleLine = true,
                modifier = Modifier.weight(1f).onFocusChanged {
                    if (!it.isFocused) hideKeyboard()
                },
            )
            Spacer(Modifier.width(12.dp))
            Button(
                onClick = {
                    hideKeyboard()
                    if (sourceInput.isNotBlank()) {
                        vm.addSource(sourceInput)
                        sourceInput = ""
                    }
                },
                enabled = sourceInput.isNotBlank(),
            ) { Text("Add") }
        }

        when (val s = state) {
            is UiState.Loading -> LoadingScreen(Modifier.fillMaxSize())
            is UiState.Error -> ErrorScreen(s.message, vm::load, Modifier.fillMaxSize())
            is UiState.Success -> {
                val d = s.data
                // Restore focus to the provider whose button was pressed.
                // Installing moves the row into the Installed section, so
                // scroll that section into view first; the requester is keyed
                // by provider identity and survives the move.
                LaunchedEffect(focusRequest, d) {
                    val target = focusRequest ?: return@LaunchedEffect
                    // Focus is about to leave the source field for a provider
                    // button; do not let the IME reappear over the list.
                    hideKeyboard()
                    val at = d.installed.indexOfFirst {
                        "${it.source.author}:${it.value}" == target
                    }
                    if (at >= 0) runCatching { listState.scrollToItem(at + 1) }
                    repeat(2) {
                        withFrameNanos { }
                        if (runCatching { requesters[target]?.requestFocus() }.isSuccess) {
                            return@repeat
                        }
                    }
                    // The field may have held focus for a frame during the
                    // move; hide again now that the Install button owns it.
                    hideKeyboard()
                    vm.consumeFocusRequest()
                }
                if (d.sources.isEmpty()) {
                    Spacer(Modifier.height(24.dp))
                    Text(
                        "No provider source yet. Type one above — for example @Zenda-Cross — " +
                            "then install a provider from the list that appears.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    // ---- source switcher ----
                    Spacer(Modifier.height(16.dp))
                    Text("Sources (${d.sources.size})", style = MaterialTheme.typography.titleMedium)
                    LazyRow(
                        Modifier.fillMaxWidth().padding(top = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(d.sources, key = { "src:${it.author}" }) { src ->
                            val sel = src.author == d.selectedSource
                            Button(onClick = { vm.selectSource(src.author) }) {
                                Text((if (sel) "▶ " else "") + src.author)
                            }
                        }
                        item(key = "src-actions") {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { vm.refreshSource() }) {
                                    if (busy != null) {
                                        VegaSpinner(diameter = 16.dp)
                                        Spacer(Modifier.width(8.dp))
                                    }
                                    Text(if (busy != null) "Working…" else "Refresh")
                                }
                                d.sources.firstOrNull { it.author == d.selectedSource }?.let { src ->
                                    Button(onClick = { vm.removeSource(src.author) }) { Text("Remove source") }
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(16.dp))
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        state = listState,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        if (d.installed.isNotEmpty()) {
                            item(key = "ih") { GroupHeader("Installed (${d.installed.size})") }
                            items(
                                d.installed,
                                key = { "p:${it.source.author}:${it.value}" },
                            ) { ext ->
                                val ref = "${ext.source.author}:${ext.value}"
                                ExtRow(
                                    ext = ext,
                                    badge = if (d.isActive(ext)) "ACTIVE" else null,
                                    hasUpdate = d.hasUpdate(ext),
                                    onSettings = if (d.isActive(ext) && onOpenSettings != null) {
                                        { onOpenSettings() }
                                    } else {
                                        null
                                    },
                                    onActivate = if (d.isActive(ext)) null else ({ vm.activate(ext) }),
                                    onPrimary = {
                                        hideKeyboard()
                                        vm.install(ext)
                                    },
                                    primaryLabel = if (d.hasUpdate(ext)) "Update" else "Reinstall",
                                    onTest = { vm.test(ext) },
                                    onRemove = { vm.uninstall(ext) },
                                    primaryFocus = requesters.getOrPut(ref) { FocusRequester() },
                                )
                            }
                        }
                        val notInstalled = d.available.filter { a -> !d.isInstalled(a) }
                        if (notInstalled.isNotEmpty()) {
                            item(key = "ah") {
                                GroupHeader("Available from ${d.selectedSource} (${notInstalled.size})")
                            }
                            items(
                                notInstalled,
                                key = { "p:${it.source.author}:${it.value}" },
                            ) { ext ->
                                val ref = "${ext.source.author}:${ext.value}"
                                ExtRow(
                                    ext = ext, badge = null, hasUpdate = false,
                                    onSettings = null,
                                    onActivate = null,
                                    onPrimary = {
                                        hideKeyboard()
                                        vm.install(ext)
                                    },
                                    primaryLabel = "Install",
                                    onTest = { vm.test(ext) }, onRemove = null,
                                    primaryFocus = requesters.getOrPut(ref) { FocusRequester() },
                                )
                            }
                        }
                        if (d.installed.isEmpty() && notInstalled.isEmpty()) {
                            item(key = "none") {
                                Text(
                                    "This source published no providers. Check the repo URL.",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }

                // ---- diagnostics: provider console ----
                val trace = ServiceLocator.providers.js.traceSnapshot()
                if (trace.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { showTrace = !showTrace }) {
                        Text(if (showTrace) "Hide log" else "Log (${trace.size})")
                    }
                    if (showTrace) {
                        Column(Modifier.padding(top = 6.dp)) {
                            trace.takeLast(24).forEach {
                                Text(
                                    it,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupHeader(t: String) {
    Text(t, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun ExtRow(
    ext: ProviderExtension,
    badge: String?,
    hasUpdate: Boolean,
    onSettings: (() -> Unit)?,
    onActivate: (() -> Unit)?,
    onPrimary: (() -> Unit)?,
    primaryLabel: String?,
    onTest: () -> Unit,
    onRemove: (() -> Unit)?,
    primaryFocus: FocusRequester? = null,
) {
    val ctx = LocalContext.current
    /* The manifest carries a display_name ("VMovies", "4KHDHub", "MoviesDrive")
       and a square icon per provider. Showing `type` — which is the *category*,
       one of global/india/anime — as the row's identity is what made the list
       read as three near-identical "Zenda-Cross" entries with no way to tell
       which is which. The mobile app leads with the name and its logo; so
       does this, or the user is picking a provider blind. */
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (ext.icon.isNotBlank()) {
            AsyncImage(
                model = ImageRequest.Builder(ctx).data(ext.icon)
                    // Decoded small and fixed: these are 1–2 KB provider marks
                    // in a scrolling list, and a full-size decode per row is
                    // what makes a list of providers stutter on weak TV
                    // hardware. The shared ImageLoader fades them in briefly.
                    .size(96, 96).allowHardware(true).build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                // Fall back to `value` so a provider missing display_name is
                // still identifiable rather than blank.
                (ext.display_name.ifBlank { ext.value }) + " v" + ext.version +
                    (badge?.let { " · $it" }.orEmpty()) +
                    (if (hasUpdate) " · UPDATE" else ""),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
            )
            Text(
                // `value` is the module directory the invoke reads, so it is
                // the one identifier worth showing alongside the category.
                "${ext.value} · ${ext.type}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (onSettings != null) Button(onClick = onSettings) { Text("Settings") }
            if (onActivate != null) Button(onClick = onActivate) { Text("Activate") }
            if (primaryLabel != null && onPrimary != null) {
                Button(
                    onClick = onPrimary,
                    modifier = if (primaryFocus != null) {
                        Modifier.focusRequester(primaryFocus)
                    } else {
                        Modifier
                    },
                ) { Text(primaryLabel) }
            }
            Button(onClick = onTest) { Text("Test") }
            if (onRemove != null) Button(onClick = onRemove) { Text("Remove") }
        }
    }
}

/** Compose's view context is usually a wrapper, not the Activity itself. */
private tailrec fun android.content.Context.findActivity(): android.app.Activity? =
    when (this) {
        is android.app.Activity -> this
        is android.content.ContextWrapper -> baseContext.findActivity()
        else -> null
    }
