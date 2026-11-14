package tv.vega.tv.data.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import tv.vega.tv.data.net.NetClients
import java.io.File

/**
 * Downloads a release APK from the project's own GitHub releases and hands it
 * to the system installer.
 *
 * The viewer stays in charge: nothing installs without the standard Android
 * confirmation screen, and on API 26+ the OS asks for the per-app
 * unknown-sources permission the first time (the caller routes to that
 * setting and retries on return).
 */
object ApkUpdater {

    /** Download [url] into `cache/updates`; [onProgress] gets (read, total). */
    suspend fun download(
        ctx: Context,
        url: String,
        fileName: String,
        onProgress: (read: Long, total: Long) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
        // One download at a time: leftovers from an earlier attempt are noise.
        dir.listFiles()?.forEach { runCatching { it.delete() } }
        val safeName = fileName.ifBlank { "vega-tv-update.apk" }
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
        val dest = File(dir, safeName)
        val req = Request.Builder().url(url).build()
        NetClients.http.newCall(req).execute().use { r ->
            require(r.isSuccessful) { "HTTP ${r.code}" }
            val body = r.body ?: error("Empty response")
            val total = body.contentLength()
            body.byteStream().use { ins ->
                dest.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var read = 0L
                    while (true) {
                        val n = ins.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        read += n
                        onProgress(read, total)
                    }
                }
            }
        }
        dest
    }

    /** Hand the downloaded APK to the system package installer. */
    fun install(ctx: Context, apk: File) {
        val uri: Uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.startActivity(intent)
    }

    /** "12.3 MB" for the update card; blank when the size is unknown. */
    fun sizeLabel(bytes: Long): String =
        if (bytes <= 0) "" else "%.1f MB".format(bytes / 1024.0 / 1024.0)
}
