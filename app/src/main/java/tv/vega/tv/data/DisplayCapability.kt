package tv.vega.tv.data

import android.content.Context
import android.os.Build
import android.view.Display
import android.view.WindowManager

/**
 * What the attached screen can actually display.
 *
 * Providers publish 4K renditions whether or not the client can show them. On
 * a 1080p television a 4K choice looks identical to the 1080p one, and on a
 * mid-range box it is a decode that cannot keep up. The panel's own display
 * modes are the honest test: a television exposes a 3840x2160 (or DCI
 * 4096x2160) mode only when the HDMI link actually negotiated 4K, so a 4K
 * streaming stick on a 1080p TV reports none.
 *
 * The app's `displayMetrics` cannot answer this: Fire OS renders its UI at
 * 1080p even on a 4K panel. The supported modes can.
 */
object DisplayCapability {
    /** 3840 or 4096 wide; a 1440p mode is 2560 at most. */
    private const val FOUR_K_MIN_LONG_EDGE = 3400

    /**
     * True when the screen exposes a 4K-capable mode.
     *
     * Unknown answers return `true` (do not hide a choice) rather than
     * pretending the panel is small: an API failure must not remove renditions
     * the viewer can actually play.
     */
    fun supports4k(context: Context? = null): Boolean {
        val edge = maxLongEdge(context)
        return edge <= 0 || edge >= FOUR_K_MIN_LONG_EDGE
    }

    /**
     * Long edge of the largest mode the screen exposes, in pixels; 0 when the
     * display cannot be queried.
     */
    fun maxLongEdge(context: Context? = null): Int {
        val display = displayOf(context) ?: return 0
        return runCatching {
            if (Build.VERSION.SDK_INT >= 23) {
                display.supportedModes
                    .maxOfOrNull { maxOf(it.physicalWidth, it.physicalHeight) }
                    ?: 0
            } else {
                val size = android.graphics.Point()
                @Suppress("DEPRECATION")
                display.getRealSize(size)
                maxOf(size.x, size.y)
            }
        }.getOrDefault(0)
    }

    private fun displayOf(context: Context?): Display? {
        val ctx = context ?: runCatching { ServiceLocator.appContext }.getOrNull() ?: return null
        return runCatching {
            (ctx.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)?.defaultDisplay
        }.getOrNull()
    }
}
