package tv.vega.tv.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

/**
 * An empty state that a D-pad can actually leave.
 *
 * A bare `Text` is not focusable, so on a TV a screen showing one is a dead end:
 * DOWN does nothing, and the only way out is BACK — and even BACK feels broken
 * because the user pressed a direction key and nothing acknowledged it. Every
 * empty list in this app therefore routes through here, which guarantees two
 * things a plain `Text` never did:
 *
 *  - there is something on screen to focus, and it is focused on arrival, so the
 *    user sees a selection ring rather than an apparently frozen UI;
 *  - there is a real action to perform, so the direction keys lead somewhere.
 *
 * The action is optional because some empty states are genuinely terminal (a
 * downloads list with nothing in it and no series open). Those still get the
 * focusable label — see [EmptyState] with no action — so the screen is legible
 * to a D-pad even with nothing to do.
 */
@Composable
fun EmptyState(
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val focus = FocusRequester()
    LaunchedEffect(Unit) {
        // Take focus on arrival. Without this the user has to press a direction
        // key blind to find out whether the screen is alive.
        runCatching { focus.requestFocus() }
    }
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
    ) {
        Text(
            message,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.widthIn(max = 640.dp),
        )
        if (actionLabel != null && onAction != null) {
            Button(
                onClick = onAction,
                modifier = Modifier.focusRequester(focus),
            ) { Text(actionLabel) }
        } else {
            // Nothing to offer, but the D-pad still needs a resting place. A
            // focusable Text is the lightest thing that will take focus without
            // implying an action that does not exist.
            Text(
                "Back to return",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.focusRequester(focus),
            )
        }
    }
}
