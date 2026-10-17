package tv.vega.tv.data.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.settingsStore by preferencesDataStore("vega_settings")

/** Full mirror of web SettingsStorage keys relevant to TV. */
class AppSettings(private val context: Context) {
    private val useExternal = booleanPreferencesKey("use_external_player")
    private val excludedQ = stringPreferencesKey("excluded_qualities_csv")
    private val tmdbKey = stringPreferencesKey("tmdb_api_key")
    private val dohMode = stringPreferencesKey("doh_mode") // off|cloudflare|google|custom
    private val dohUrl = stringPreferencesKey("doh_custom_url")
    private val subScale = floatPreferencesKey("sub_scale")
    private val subColor = stringPreferencesKey("sub_color")
    private val subEdge = stringPreferencesKey("sub_edge")
    private val autoUpdate = booleanPreferencesKey("auto_check_update")
    private val preferBest = booleanPreferencesKey("prefer_best_quality")
    private val maxFpsKey = stringPreferencesKey("max_fps")

    val useExternalPlayer: Flow<Boolean> =
        context.settingsStore.data.map { it[useExternal] ?: false }

    val excludedQualities: Flow<Set<String>> =
        context.settingsStore.data.map {
            it[excludedQ]?.split(",")?.filter { q -> q.isNotBlank() }?.toSet() ?: emptySet()
        }

    val tmdbApiKey: Flow<String> =
        context.settingsStore.data.map { it[tmdbKey].orEmpty() }

    val doh: Flow<Pair<String, String>> =
        context.settingsStore.data.map { (it[dohMode] ?: "off") to (it[dohUrl].orEmpty()) }

    val subtitleStyle: Flow<SubtitleStyle> =
        context.settingsStore.data.map {
            SubtitleStyle(
                scale = it[subScale] ?: 1.0f,
                color = it[subColor] ?: "#FFFFFF",
                edge = it[subEdge] ?: "outline",
            )
        }

    val autoCheckUpdate: Flow<Boolean> =
        context.settingsStore.data.map { it[autoUpdate] ?: true }

    /**
     * Start playback on the highest quality the provider offered, instead of on
     * whatever happened to come first in the list.
     *
     * On by default. A provider returning 480p/720p/1080p and nothing else means
     * the first entry is the one most likely to be the host site's *default*
     * rendition rather than its best, and "first in the list" is a rule with no
     * relationship to what the user asked for.
     */
    val preferBestQuality: Flow<Boolean> =
        context.settingsStore.data.map { it[preferBest] ?: true }

    /**
     * Ceiling on rendered frame rate, as a whole number of frames per second.
     *
     * "Auto" (the default) lets the source's own frame rate through, which is
     * what a TV wants: a 24fps film must stay 24fps or it judders, and a 60fps
     * source should not be throttled just because the box is weak. The explicit
     * values exist to *cap* — a mislabelled or upscaled 120fps source that the
     * hardware cannot sustain is the case where dropping frames beats stalling.
     */
    val maxFps: Flow<String> =
        context.settingsStore.data.map { it[maxFpsKey] ?: "auto" }

    suspend fun snapshotDoh(): Pair<String, String> = doh.first()
    suspend fun snapshotKey(): String = tmdbApiKey.first()

    suspend fun setExternal(v: Boolean) {
        context.settingsStore.edit { it[useExternal] = v }
    }

    suspend fun setExcludedQualities(q: Set<String>) {
        context.settingsStore.edit { it[excludedQ] = q.joinToString(",") }
    }

    suspend fun setTmdbKey(v: String) {
        context.settingsStore.edit { it[tmdbKey] = v.trim() }
    }

    suspend fun setDoh(mode: String, url: String = "") {
        context.settingsStore.edit { it[dohMode] = mode; it[dohUrl] = url.trim() }
    }

    suspend fun setSubtitleStyle(s: SubtitleStyle) {
        context.settingsStore.edit { it[subScale] = s.scale; it[subColor] = s.color; it[subEdge] = s.edge }
    }

    suspend fun setAutoCheck(v: Boolean) {
        context.settingsStore.edit { it[autoUpdate] = v }
    }

    suspend fun setPreferBestQuality(v: Boolean) {
        context.settingsStore.edit { it[preferBest] = v }
    }

    suspend fun setMaxFps(v: String) {
        context.settingsStore.edit { it[maxFpsKey] = v }
    }
}

data class SubtitleStyle(
    val scale: Float = 1.0f,
    val color: String = "#FFFFFF",
    val edge: String = "outline", // outline|dropShadow|raised|depressed|none
)
