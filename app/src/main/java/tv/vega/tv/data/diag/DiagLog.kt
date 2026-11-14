package tv.vega.tv.data.diag

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Tiny forensic log that survives the crash it describes.
 *
 * A TV box that hard-reboots on a decoder fault never reaches a Java crash
 * handler, so the two facts that are recorded here are written *before* the
 * fault: the last uncaught Java exception, and the decoder/format that was
 * live during the last playback. Both files are small and are shown in
 * Settings → About so the evidence is reachable after a reboot.
 */
object DiagLog {
    const val LAST_CRASH = "diag_last_crash.txt"
    const val LAST_PLAYBACK = "diag_last_playback.txt"

    private const val MAX_CHARS = 2_000

    private val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    private fun file(ctx: Context, name: String) = File(ctx.applicationContext.filesDir, name)

    /** Routes every uncaught Java exception through [LAST_CRASH] first. */
    fun install(ctx: Context) {
        val app = ctx.applicationContext
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching {
                file(app, LAST_CRASH).writeText(
                    "${stamp.format(Date())}  thread=${thread.name}\n${Log.getStackTraceString(e)}",
                )
            }
            prev?.uncaughtException(thread, e)
        }
    }

    /** Written at decoder/format changes, so a rebooted box still has a clue. */
    fun notePlayback(ctx: Context, text: String) {
        runCatching { file(ctx, LAST_PLAYBACK).writeText("${stamp.format(Date())}\n$text") }
    }

    fun read(ctx: Context, name: String): String? =
        runCatching { file(ctx, name).takeIf { it.isFile }?.readText()?.take(MAX_CHARS) }.getOrNull()

    fun clear(ctx: Context, name: String) {
        runCatching { file(ctx, name).delete() }
    }
}
