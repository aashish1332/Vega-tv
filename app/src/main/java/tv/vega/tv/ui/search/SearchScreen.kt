package tv.vega.tv.ui.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import androidx.compose.material3.OutlinedTextField
import tv.vega.tv.data.model.VegaPost
import tv.vega.tv.ui.common.ErrorScreen
import tv.vega.tv.ui.common.EmptyState
import tv.vega.tv.ui.common.LoadingScreen
import tv.vega.tv.ui.common.UiState
import tv.vega.tv.ui.common.VegaRow
import tv.vega.tv.ui.common.WafError

/**
 * Web Search twin: query field (initial focus) + per-provider results rows.
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
            is UiState.Success -> if (s.data.isEmpty()) {
                // VegaRow's own "No content found" is a bare Text, which is not
                // focusable — on a TV that leaves the results area unable to take
                // focus at all. Route through the shared empty state instead.
                EmptyState(
                    message = "No results for \"$searchedQuery\"",
                    actionLabel = "Search again",
                    onAction = { vm.search(query) },
                )
            } else {
                VegaRow(
                    // Not a LazyColumn: the results are always exactly one row,
                    // and a lazy list holding a single item traps DOWN — focus
                    // lands on the only child, the list cannot scroll, and the
                    // key press is swallowed. A plain row lets UP return to the
                    // search field and the Search button.
                    title = "Results for \"$searchedQuery\"",
                    posts = s.data,
                    onOpenPost = onOpenPost,
                    onSeeAll = null,
                    showSeeAll = false,
                )
            }
        }
        // Idle state (never searched) falls through as empty Success.
    }
}
