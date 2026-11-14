package tv.vega.tv

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import tv.vega.tv.data.ServiceLocator
import tv.vega.tv.data.diag.DiagLog
import tv.vega.tv.data.net.NetClients

class App : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        ServiceLocator.init(this)
        // Record uncaught Java failures before the process dies.
        DiagLog.install(this)
        // Apply stored DoH/network prefs to the shared HTTP stack.
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { NetClients.refresh(this@App) }
        }
    }

    /**
     * One image pipeline for the whole app. Weak-TV tuning: a bounded bitmap
     * cache (a rail that scrolls back must never re-decode, but low-RAM boxes
     * cannot hold an unbounded one), a disk cache so posters survive process
     * death, and a short crossfade so late images fade in instead of popping.
     */
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.15)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(96L * 1024 * 1024)
                    .build()
            }
            .crossfade(true)
            .build()
}
