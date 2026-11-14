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
    private val preferBest = booleanPreferencesKey("prefer_best_quality")
    private val maxFpsKey = stringPreferencesKey("max_fps")
    private val audioLanguageKey = stringPreferencesKey("audio_language")
    private val providerFilterKey = stringPreferencesKey("provider_filter")
    private val familyFilterKey = booleanPreferencesKey("family_filter")

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

    /**
     * Preferred audio language for every stream: "hi" (Hindi, the default) or
     * "en" (English).
     *
     * Media3 applies this when it builds the audio track list, so it also
     * covers HLS/DASH streams whose languages are only known after the
     * manifest loads. If no matching track exists the source's default plays;
     * the viewer can still override it from MENU -> Audio.
     *
     * Older builds stored "auto" (and installed users keep that value), so it
     * is migrated to Hindi on read rather than silently keeping source
     * default. A migration write would race the first player launch.
     */
    val audioLanguage: Flow<String> =
        context.settingsStore.data.map {
            val stored = it[audioLanguageKey]?.trim().orEmpty()
            if (stored.isBlank() || stored.equals("auto", ignoreCase = true)) "hi" else stored
        }

    /**
     * On by default. Provider listings cannot identify every adult title and
     * cannot inspect spoken language, so this is a best-effort safety filter,
     * not a parental-control guarantee.
     */
    val familyFilter: Flow<Boolean> =
        context.settingsStore.data.map { it[familyFilterKey] ?: true }

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

    suspend fun setPreferBestQuality(v: Boolean) {
        context.settingsStore.edit { it[preferBest] = v }
    }

    suspend fun setMaxFps(v: String) {
        context.settingsStore.edit { it[maxFpsKey] = v }
    }

    suspend fun setAudioLanguage(v: String) {
        context.settingsStore.edit { it[audioLanguageKey] = v }
    }

    suspend fun setFamilyFilter(v: Boolean) {
        context.settingsStore.edit { it[familyFilterKey] = v }
    }

    /**
     * Which provider the home page shows: "" is the default mixed view, a
     * non-blank value is an "author:value" ref. Persisted so the sidebar
     * selection survives a restart.
     */
    val providerFilter: Flow<String> =
        context.settingsStore.data.map { it[providerFilterKey].orEmpty() }

    suspend fun setProviderFilter(v: String) {
        context.settingsStore.edit { it[providerFilterKey] = v }
    }
}

data class SubtitleStyle(
    val scale: Float = 1.0f,
    val color: String = "#FFFFFF",
    val edge: String = "outline", // outline|dropShadow|raised|depressed|none
)
