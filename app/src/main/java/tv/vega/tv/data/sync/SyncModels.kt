package tv.vega.tv.data.sync

import kotlinx.serialization.Serializable

const val SYNC_SCHEMA = 1
const val SYNC_DIR = ".vega-sync"

@Serializable
data class SyncedHistory(
    val id: String = "",
    val title: String = "",
    val poster: String = "",
    val provider: String = "",
    val link: String = "",
    val duration: Long = 0L,
    val currentTime: Long = 0L,
    val episodeTitle: String = "",
    val episode: Int = 0,
    val type: String = "",
    val updatedAt: Long = 0L,
)

@Serializable
data class SyncedWatchItem(
    val title: String = "",
    val poster: String = "",
    val link: String = "",
    val provider: String = "",
    val updatedAt: Long = 0L,
)

@Serializable
data class Tombstone(
    val kind: String = "", // history|watchlist (legacy "download" values are ignored)
    val id: String = "",
    val mediaKey: String = "",
    val deletedAt: Long = 0L,
)

@Serializable
data class SyncManifest(
    val schemaVersion: Int = SYNC_SCHEMA,
    val deviceId: String = "",
    val revision: Long = 0L,
    val generatedAt: Long = 0L,
    val history: List<SyncedHistory> = emptyList(),
    val watchlist: List<SyncedWatchItem> = emptyList(),
    val tombstones: List<Tombstone> = emptyList(),
)
