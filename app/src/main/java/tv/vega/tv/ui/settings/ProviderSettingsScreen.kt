package tv.vega.tv.ui.settings

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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Switch
import androidx.tv.material3.Text
import tv.vega.tv.data.providers.SettingsField
import tv.vega.tv.ui.common.EmptyState
import tv.vega.tv.ui.common.ErrorScreen
import tv.vega.tv.ui.common.LoadingScreen
import tv.vega.tv.ui.common.UiState

/**
 * The provider's own configuration screen, built from the schema its
 * `settings.js` declares.
 *
 * Everything on it is the provider's to decide: labels, defaults, the option
 * list. The app contributes only the five control types upstream defines and a
 * way to get in and back out, because a setting the provider declared but the
 * app cannot render is a setting the user can never set.
 *
 * D-pad: every control is focusable, BACK returns from the Providers screen, and
 * the empty state is focusable rather than inert text — see [EmptyState].
 */
@Composable
fun ProviderSettingsScreen(
    onBack: () -> Unit,
    providerValue: String = "",
    vm: ProviderSettingsViewModel = viewModel(key = "provider-settings:$providerValue"),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val saved by vm.saved.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()

    when (val s = state) {
        is UiState.Loading -> LoadingScreen(Modifier.fillMaxSize())
        is UiState.Error -> ErrorScreen(s.message, vm::load, Modifier.fillMaxSize())
        is UiState.Success -> {
            val data = s.data
            if (data.controls.isEmpty()) {
                // Reachable and expected: most providers ship no settings
                // module at all, and `getSettingsSchema` answers that with an
                // empty list rather than an error. So this is not a failure
                // state — it needs a way out, not a retry.
                EmptyState(
                    message = "${data.provider} has no settings to configure.",
                    actionLabel = "Back to return",
                    onAction = onBack,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                SettingsList(data, vm, saved, status, onBack)
            }
        }
    }
}

@Composable
private fun SettingsList(
    data: ProviderSettingsData,
    vm: ProviderSettingsViewModel,
    saved: String?,
    status: String?,
    onBack: () -> Unit,
) {
    // Editable values live here rather than in the ViewModel so a keypress on a
    // text field does not push a new state object through the whole screen on
    // every character.
    val drafts = remember(data.controls) { mutableStateMapOf<String, String>() }
    val firstFocus = remember { FocusRequester() }

    LaunchedEffect(data) { runCatching { firstFocus.requestFocus() } }

    LazyColumn(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item(key = "head") {
            Text(
                "${data.provider} settings",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.focusRequester(firstFocus),
            )
            Text(
                "Saved here, and read by the provider on its next request.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            if (saved != null) {
                Text(
                    "Saved: $saved",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            if (status != null) {
                Text(
                    status,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }

        itemsIndexed(data.controls, key = { _, c -> "f:${c.field.key}" }) { _, c ->
            val known = c.field.isKnownType
            Column(Modifier.fillMaxWidth()) {
                Text(
                    c.field.label + if (c.field.key in data.modified) " · edited" else "",
                    style = MaterialTheme.typography.titleSmall,
                )
                c.field.description?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (!known) {
                    // A bundle from a newer app can declare a control type this
                    // version has never heard of. Say which one, and keep the
                    // value visible, rather than rendering a blank row that
                    // looks like a broken screen.
                    Text(
                        "Unsupported setting type \"${c.field.type}\" — value: " +
                            (c.value.ifBlank { "—" }),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                } else {
                    when (c.field.type) {
                        SettingsField.TOGGLE -> ToggleControl(c, vm)
                        SettingsField.SELECT -> SelectControl(c, vm)
                        SettingsField.MULTISELECT -> MultiSelectControl(c, vm)
                        SettingsField.NUMBER -> NumberControl(c, drafts, vm)
                        else -> TextControl(c, drafts, vm)
                    }
                }
            }
        }

        item(key = "foot") {
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = vm::clearAll) { Text("Reset all") }
                Button(onClick = onBack) { Text("Done") }
            }
        }
    }
}

@Composable
private fun ToggleControl(c: SettingsControl, vm: ProviderSettingsViewModel) {
    val on = c.value == "true"
    Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Switch(checked = on, onCheckedChange = { vm.set(c.copy(value = it.toString())) })
        Spacer(Modifier.width(12.dp))
        Text(if (on) "On" else "Off", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun SelectControl(c: SettingsControl, vm: ProviderSettingsViewModel) {
    // A select is a single focusable control: opening it replaces the label
    // with a row of options, so BACK closes the row and returns focus to the
    // button rather than leaving the screen.
    var open by remember(c.field.key) { mutableStateOf(false) }
    val current = c.field.options.firstOrNull { it.value == c.value }
    Column {
        Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { open = !open }) { Text(current?.label ?: c.value.ifBlank { "Choose" }) }
            Spacer(Modifier.width(12.dp))
            Button(onClick = { vm.set(c.copy(value = c.field.defaultAsString().orEmpty())) }) { Text("Default") }
        }
        if (open) {
            Spacer(Modifier.height(6.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(c.field.options, key = { "o:${it.value}" }) { o ->
                    Button(onClick = { open = false; vm.set(c.copy(value = o.value)) }) {
                        Text(if (o.value == c.value) "▶ ${o.label}" else o.label)
                    }
                }
            }
        }
    }
}

@Composable
private fun MultiSelectControl(c: SettingsControl, vm: ProviderSettingsViewModel) {
    var open by remember(c.field.key) { mutableStateOf(false) }
    val chosen = c.selected
    Column {
        Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { open = !open }) {
                Text(
                    when {
                        chosen.isEmpty() -> "None selected"
                        chosen.size == 1 -> chosen.first()
                        else -> "${chosen.size} selected"
                    },
                )
            }
            Spacer(Modifier.width(12.dp))
            Button(
                onClick = { vm.set(c.copy(selected = c.field.defaultAsStringList())) },
                enabled = chosen.isNotEmpty(),
            ) { Text("Default") }
        }
        if (open) {
            Spacer(Modifier.height(6.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(c.field.options, key = { "o:${it.value}" }) { o ->
                    val on = o.value in chosen
                    Button(onClick = {
                        // Toggle rather than replace: a multiselect that
                        // dropped the previous pick on every press could never
                        // be used to choose more than one option.
                        val next = if (on) chosen - o.value else chosen + o.value
                        vm.set(c.copy(selected = next))
                    }) {
                        Text(if (on) "☑ ${o.label}" else "☐ ${o.label}")
                    }
                }
            }
        }
    }
}

@Composable
private fun TextControl(c: SettingsControl, drafts: MutableMap<String, String>, vm: ProviderSettingsViewModel) {
    val draft = drafts[c.field.key]
    val shown = draft ?: c.value
    OutlinedTextField(
        value = shown,
        // Commit on done, not per keystroke: a value stored on every character
        // is a DataStore write per character, and on a weak TV that is visible
        // as the keyboard lagging behind the remote.
        onValueChange = { drafts[c.field.key] = it },
        placeholder = { Text(c.field.placeholder ?: "") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
    )
    Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = { vm.set(c.copy(value = shown)); drafts.remove(c.field.key) },
            enabled = shown != c.value,
        ) { Text("Save") }
        Button(onClick = { vm.reset(c); drafts.remove(c.field.key) }, enabled = shown != c.value) {
            Text("Revert")
        }
    }
}

@Composable
private fun NumberControl(c: SettingsControl, drafts: MutableMap<String, String>, vm: ProviderSettingsViewModel) {
    val draft = drafts[c.field.key]
    val shown = draft ?: c.value
    val num = shown.toDoubleOrNull()
    val bad = shown.isNotEmpty() && num == null
    val below = c.field.min != null && num != null && num < c.field.min
    val above = c.field.max != null && num != null && num > c.field.max
    Column {
        OutlinedTextField(
            value = shown,
            onValueChange = { drafts[c.field.key] = it },
            // A remote has no numeric keypad, so anything but digits is a
            // typo; filtering it here beats a "not a number" error later.
            // `0` and `.` are allowed so negative and fractional input is
            // possible where the schema allows it.
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
            ),
            singleLine = true,
            isError = bad || below || above,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )
        val msg = when {
            bad -> "Enter a number"
            below -> "Must be at least ${c.field.min}"
            above -> "Must be at most ${c.field.max}"
            else -> null
        }
        msg?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { vm.set(c.copy(value = shown)); drafts.remove(c.field.key) },
                enabled = num != null && !below && !above && shown != c.value,
            ) { Text("Save") }
            Button(onClick = { vm.reset(c); drafts.remove(c.field.key) }, enabled = shown != c.value) {
                Text("Revert")
            }
        }
    }
}
