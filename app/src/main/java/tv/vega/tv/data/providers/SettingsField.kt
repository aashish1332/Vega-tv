package tv.vega.tv.data.providers

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * One control in a provider's settings screen, as declared by its own
 * `settings.js` module. Field names and the five [type] values are upstream's
 * (`src/lib/providers/types.ts`); a provider bundle is decoded into this by
 * `ProviderManager.getSettingsSchema`, so renaming anything here breaks real
 * providers.
 *
 * `unknownKeys = 0` at the decode site is deliberate: bundles in the wild ship
 * fields this version has never heard of, and a new provider should render the
 * controls we do understand rather than fail wholesale.
 */
@Serializable
data class SettingsField(
    val key: String,
    val label: String,
    val type: String,
    val description: String? = null,
    val placeholder: String? = null,
    val defaultValue: JsonElement? = null,
    val options: List<SelectOption> = emptyList(),
    val min: Double? = null,
    val max: Double? = null,
    val step: Double? = null,
) {
    val isKnownType: Boolean get() = type in KNOWN_TYPES

    /**
     * The provider's own default, read for the control that will display it.
     *
     * Kept as a raw [JsonElement] rather than a sealed union on purpose: the wire
     * format is a bare `"foo"` / `true` / `3` / `["a","b"]`, and a polymorphic
     * serializer would wrap every one of those in a `{"type":…,"value":…}`
     * envelope. Decoding as written and converting per-control here keeps the
     * value the provider actually sent.
     */
    fun defaultAsString(): String? = (defaultValue as? JsonPrimitive)?.contentOrNull

    fun defaultAsBool(): Boolean? = (defaultValue as? JsonPrimitive)?.booleanOrNull

    fun defaultAsDouble(): Double? = (defaultValue as? JsonPrimitive)?.doubleOrNull

    fun defaultAsStringList(): List<String> =
        (defaultValue as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()

    companion object {
        const val TEXT = "text"
        const val TOGGLE = "toggle"
        const val SELECT = "select"
        const val MULTISELECT = "multiselect"
        const val NUMBER = "number"

        val KNOWN_TYPES = setOf(TEXT, TOGGLE, SELECT, MULTISELECT, NUMBER)
    }
}

@Serializable
data class SelectOption(
    val label: String,
    val value: String,
)
