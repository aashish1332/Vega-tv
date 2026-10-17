package tv.vega.tv.data.providers

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.extStore by preferencesDataStore("provider_extensions")

/**
 * DataStore port of web extensionStorage (MMKV). Same key semantics so a
 * shared-folder sync later stays compatible. Module JS can be large (2MB
 * cap like web), so modules live in files, DataStore holds the index.
 */
class ExtensionStore(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true }
    private val sourcesKey = stringPreferencesKey("sources_json")
    private val installedKey = stringPreferencesKey("installed_json")
    private val activeKey = stringPreferencesKey("active_provider_ref")
    private val availPrefix = "available_"
    private val manifestPrefix = "manifest_"
    private val manifestTimePrefix = "manifest_time_"
    private val modulesIndexKey = stringPreferencesKey("modules_index_json")

    // -- sources --
    suspend fun sources(): List<ProviderSource> =
        context.extStore.data.map { it[sourcesKey]?.let { s -> runCatching { json.decodeFromString<List<ProviderSource>>(s) }.getOrNull() } ?: emptyList() }.first()

    /**
     * True once the sources key has ever been written, even as an empty list.
     * The default source is seeded only when it was never written: a stored
     * empty list means the user deliberately removed their sources.
     */
    suspend fun sourcesKeyPresent(): Boolean =
        context.extStore.data.map { it.contains(sourcesKey) }.first()

    suspend fun addSource(author: String, url: String) {
        context.extStore.edit { p ->
            val cur = p[sourcesKey]?.let { runCatching { json.decodeFromString<MutableList<ProviderSource>>(it) }.getOrNull() } ?: mutableListOf()
            val normUrl = url.trim().trimEnd('/')
            val i = cur.indexOfFirst { it.author == author }
            if (i >= 0) cur[i] = cur[i].copy(url = normUrl)
            else cur.add(ProviderSource(author = author, url = normUrl, isDefault = cur.isEmpty()))
            if (cur.none { it.isDefault } && cur.isNotEmpty()) cur[0] = cur[0].copy(isDefault = true)
            p[sourcesKey] = json.encodeToString(cur)
        }
    }

    suspend fun setSources(list: List<ProviderSource>) {
        context.extStore.edit { p ->
            // The first source is the implicit default, so the flag must follow
            // the list or defaultSource() resolves to a removed author.
            val norm = list.mapIndexed { i, s -> s.copy(isDefault = i == 0) }
            p[sourcesKey] = json.encodeToString(norm)
        }
    }

    suspend fun defaultSource(): ProviderSource? {
        val all = sources()
        if (all.isEmpty()) return null
        return all.firstOrNull { it.isDefault } ?: all[0]
    }

    // -- installed / available --
    suspend fun installed(): List<ProviderExtension> =
        context.extStore.data.map { it[installedKey]?.let { s -> runCatching { json.decodeFromString<List<ProviderExtension>>(s) }.getOrNull() } ?: emptyList() }.first()

    suspend fun setInstalled(list: List<ProviderExtension>) {
        context.extStore.edit { it[installedKey] = json.encodeToString(list) }
    }

    /** Active provider ref "author:value" (mirrors contentStore.provider). */
    suspend fun activeRef(): String? =
        context.extStore.data.map { it[activeKey] }.first()

    suspend fun setActiveRef(ref: String) {
        context.extStore.edit { it[activeKey] = ref }
    }

    suspend fun install(ext: ProviderExtension) {
        val cur = installed().toMutableList()
        val i = cur.indexOfFirst { it.value == ext.value && it.source.author == ext.source.author }
        val now = System.currentTimeMillis()
        if (i >= 0) cur[i] = cur[i].copy(version = ext.version, lastUpdated = now)
        else cur.add(ext.copy(installed = true, installedAt = now, lastUpdated = now))
        setInstalled(cur)
    }

    suspend fun available(author: String): List<ProviderExtension> =
        context.extStore.data.map { p ->
            p[stringPreferencesKey(availPrefix + author)]?.let { s -> runCatching { json.decodeFromString<List<ProviderExtension>>(s) }.getOrNull() } ?: emptyList()
        }.first()

    suspend fun setAvailable(author: String, list: List<ProviderExtension>) {
        context.extStore.edit { it[stringPreferencesKey(availPrefix + author)] = json.encodeToString(list) }
    }

    suspend fun manifestCache(author: String): List<ProviderExtension> =
        context.extStore.data.map { p ->
            p[stringPreferencesKey(manifestPrefix + author)]?.let { s -> runCatching { json.decodeFromString<List<ProviderExtension>>(s) }.getOrNull() } ?: emptyList()
        }.first()

    suspend fun setManifestCache(author: String, list: List<ProviderExtension>) {
        context.extStore.edit {
            it[stringPreferencesKey(manifestPrefix + author)] = json.encodeToString(list)
            it[longPreferencesKey(manifestTimePrefix + author)] = System.currentTimeMillis()
        }
    }

    suspend fun manifestExpired(author: String): Boolean =
        context.extStore.data.map { it[longPreferencesKey(manifestTimePrefix + author)] ?: 0L }.first()
            .let { System.currentTimeMillis() - it > 24 * 60 * 60 * 1000L }

    // -- modules (index in DataStore, code in files) --
    suspend fun moduleIndex(): List<ProviderModules> =
        context.extStore.data.map { it[modulesIndexKey]?.let { s -> runCatching { json.decodeFromString<List<ProviderModules>>(s) }.getOrNull() } ?: emptyList() }.first()

    suspend fun getModule(value: String, author: String?): ProviderModules? {
        val all = moduleIndex().filter { it.value == value }
        if (all.isEmpty()) return null
        if (author != null) return all.firstOrNull { it.sourceAuthor == author } ?: all.firstOrNull { it.sourceAuthor.isEmpty() }
        val def = defaultSource()?.author
        return all.firstOrNull { it.sourceAuthor == def } ?: all.maxByOrNull { it.cachedAt }
    }

    suspend fun putModule(meta: ProviderModules) {
        val cur = moduleIndex().toMutableList()
        val i = cur.indexOfFirst { it.value == meta.value && it.sourceAuthor == meta.sourceAuthor }
        if (i >= 0) cur[i] = meta else cur.add(meta)
        context.extStore.edit { it[modulesIndexKey] = json.encodeToString(cur) }
    }

    fun moduleFile(value: String, author: String, kind: String) =
        java.io.File(context.filesDir, "providers/${author}_${value}_$kind.js")

    suspend fun readModuleCode(value: String, author: String, kind: String): String? {
        val f = moduleFile(value, author, kind)
        return if (f.exists()) runCatching { f.readText() }.getOrNull() else null
    }

    suspend fun writeModuleCode(value: String, author: String, kind: String, code: String) {
        val f = moduleFile(value, author, kind)
        f.parentFile?.mkdirs()
        f.writeText(code)
    }
}
