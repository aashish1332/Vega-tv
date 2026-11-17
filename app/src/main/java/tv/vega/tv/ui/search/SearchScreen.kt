package tv.vega.tv.ui.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.compose.material3.OutlinedTextField
import tv.vega.tv.data.model.VegaPost
import tv.vega.tv.ui.common.EmptyState
import tv.vega.tv.ui.common.LoadingScreen
import tv.vega.tv.ui.common.UiState
import tv.vega.tv.ui.common.VegaRow
import tv.vega.tv.ui.common.WafError

/**
 * Web Search twin: query field (initial focus) + per-provider results rows.
 *
 * Search fans out across every installed provider (the active one first), so a
 * title missing on one site is still found on another. The result row names the
 * query that produced it, and a partial failure is stated next to the results
 * rather than replacing them: "2 of 3 providers did not answer" is information,
 * not an error screen.
 *
 * Weak-TV: no live debounce suggestions (IME + network on every key is
 * expensive); search runs on OK/Search action only.
 * Note: tv-material 1.0.0 has no TextField, so we use material3
 * OutlinedTextField (focusable via D-pad, IME-safe) styled to match.
 */
@Composable
fun SearchScreen(
    onOpenPost: (VegaPost) -> Unit,
    onSolveWaf: (String) -> Unit = {},
    vm: SearchViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val wafUrl by vm.wafUrl.collectAsStateWithLifecycle()
    val searchedQuery by vm.searchedQuery.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    val fieldFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { fieldFocus.requestFocus() } }

    Column(Modifier.fillMaxSize().padding(32.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Search movies, shows…") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { vm.search(query) }),
            modifier = Modifier.fillMaxWidth().focusRequester(fieldFocus),
        )
        Button(
            onClick = { vm.search(query) },
            modifier = Modifier.padding(top = 12.dp),
        ) { Text("Search") }

        when (val s = state) {
            is UiState.Loading -> LoadingScreen(Modifier.fillMaxSize())
            is UiState.Error -> WafError(
                message = s.message,
                wafUrl = wafUrl,
                onRetry = { vm.search(query) },
                onSolveWaf = onSolveWaf,
                modifier = Modifier.fillMaxSize(),
            )
            is UiState.Success -> {
                val result = s.data
                val providersLabel =
                    if (result.providerCount == 1) "1 provider" else "${result.providerCount} providers"
                when {
                    // Idle: no search yet. Showing "No results for \"\"" on
                    // arrival read like the app had already failed at a search
                    // nobody asked for. Deliberately not an EmptyState: that
                    // requests focus, which would steal it from the query field
                    // on arrival. The field and button above are the actions.
                    searchedQuery.isBlank() -> Column(
                        Modifier.fillMaxWidth().padding(top = 32.dp, start = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            "Search every installed provider at once",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            "Type a title above and press Search. Results from all " +
                                "providers appear together, best match first.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }

                    result.posts.isEmpty() -> {
                        val challenge = wafUrl
                        if (!challenge.isNullOrBlank()) {
                            // Every provider that answered was blocked, or the
                            // blocked ones were the only ones with the title. A
                            // plain "no results" plus Retry would hide the one
                            // action that clears the block.
                            WafError(
                                message = result.firstError
                                    ?: "No results for \"$searchedQuery\" yet. A provider needs a challenge solved.",
                                wafUrl = challenge,
                                onRetry = { vm.search(query) },
                                onSolveWaf = onSolveWaf,
                                modifier = Modifier.fillMaxSize(),
                            )
                        } else {
                            val allFailed =
                                result.providerCount > 0 && result.failedCount >= result.providerCount
                            val message = when {
                                result.providerCount == 0 ->
                                    result.firstError
                                        ?: "No provider is installed. Add one on the Providers screen."
                                allFailed ->
                                    result.firstError
                                        ?: "No provider answered. Check the connection, then retry."
                                result.failedCount > 0 ->
                                    "No results for \"$searchedQuery\". " +
                                        "${result.failedCount} of $providersLabel did not answer" +
                                        result.firstError?.let { ": $it" }.orEmpty()
                                else ->
                                    "No results for \"$searchedQuery\" from $providersLabel."
                            }
                            EmptyState(
                                message = message,
                                actionLabel = if (result.providerCount == 0) null else "Try again",
                                onAction = if (result.providerCount == 0) {
                                    null
                                } else {
                                    ({ vm.search(query) })
                                },
                            )
                        }
                    }

                    else -> Column {
                        if (result.failedCount > 0) {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Text(
                                    text = "${result.failedCount} of $providersLabel did not answer" +
                                        result.firstError?.let { " — $it" }.orEmpty(),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f),
                                )
                                val challenge = wafUrl
                                if (!challenge.isNullOrBlank()) {
                                    Button(onClick = { onSolveWaf(challenge) }) {
                                        Text("Solve challenge")
                                    }
                                }
                            }
                        }
                        VegaRow(
                            // Not a LazyColumn: the results are always exactly one row,
                            // and a lazy list holding a single item traps DOWN — focus
                            // lands on the only child, the list cannot scroll, and the
                            // key press is swallowed. A plain row lets UP return to the
                            // search field and the Search button.
                            title = "Results for \"$searchedQuery\" (${result.posts.size})",
                            posts = result.posts,
                            onOpenPost = onOpenPost,
                            onSeeAll = null,
                            showSeeAll = false,
                        )
                    }
                }
            }
        }
    }
}
