package tv.vega.tv.data.providers

import android.util.Log
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

private const val TAG = "VegaProviderError"

/**
 * Turn a provider-layer throw into one sentence a viewer can act on.
 *
 * Provider modules are user-installed third-party scripts. Their failures used
 * to reach the TV verbatim — "Provider getStream timed out after 45000ms (the
 * provider fetch never returned)", "returned object, expected an array", a raw
 * HTML error page quoted back in the message. On a 10-foot screen those read
 * as crashes: the viewer cannot tell a dead provider from a dead app, and the
 * one thing they need to know ("retry", "another server", "reinstall it") is
 * not in the text.
 *
 * So every provider failure gets exactly one of a small set of answers, chosen
 * by what actually happened. The original exception is written to logcat, not
 * to the screen: it is diagnostic data, not a message.
 *
 * [fallback] is used when the throwable is something this function has no
 * specific answer for (a programming error, a storage failure). Callers that
 * know more about the operation can pass a better sentence.
 */
fun readableProviderError(
    e: Throwable,
    fallback: String = "The provider could not answer. Try again in a moment.",
): String {
    if (e is ProviderManager.WafRequired) {
        return "Cloudflare is blocking " +
            (e.host.ifBlank { "the provider" }) +
            ". Solve the check, then retry."
    }
    val raw = e.message.orEmpty().trim()
    val lower = raw.lowercase()
    Log.w(TAG, "Provider failure: ${e.javaClass.simpleName}: ${raw.take(600)}", e)

    if (raw.isBlank()) return when (e) {
        is UnknownHostException, is ConnectException ->
            "The provider's site could not be reached. Check the connection, then retry."
        is SocketTimeoutException ->
            "The provider took too long to answer. Try again in a moment."
        else -> fallback
    }

    // The sandbox timeout is budgeted deliberately (45 s for getStream), so a
    // timeout means the provider's own fetch never returned — not that this
    // box is slow. Retrying once is worthwhile; waiting forever is not.
    if (lower.contains("timed out") ||
        lower.contains("timeout") ||
        lower.contains("etimedout")
    ) {
        return "The provider took too long to answer. Try again in a moment."
    }

    if (lower.contains("waf_required")) {
        return "Cloudflare is blocking the provider. Solve the check, then retry."
    }

    if (lower.contains("returned no result") || lower.contains("returned null")) {
        return "The provider returned nothing for this title. It may be offline, or the title may have moved. Try again."
    }

    if (lower.contains("none had a playable link")) {
        return "The provider returned servers but none had a playable link. " +
            "It may need a sign-in for this title — try another server or title."
    }

    if (lower.contains("expected an array") ||
        lower.contains("returned object") ||
        lower.contains("returned string") ||
        lower.contains("returned number") ||
        lower.contains("returned boolean") ||
        lower.contains("invalid data")
    ) {
        return "The provider sent data this app cannot read. Update it on the Providers screen, then retry."
    }

    if (lower.contains("bad invoke envelope") ||
        lower.contains("provider runtime") ||
        lower.contains("runtime failed")
    ) {
        return "The provider's runtime failed to answer. Retry once; if it keeps happening, reinstall the provider."
    }

    if (lower.contains("module load failed") ||
        lower.contains("missing ") && lower.contains(" module") ||
        lower.contains("module failed")
    ) {
        return "The provider's code could not be loaded. Reinstall it on the Providers screen, then retry."
    }

    if (lower.contains("rate limit") || lower.contains("rate-limit")) {
        return "The provider's update host is rate-limiting requests. Wait a few minutes, then retry."
    }

    // HTTP-shaped failures are common in provider `__error` text ("Request
    // failed with status code 403", "HTTP 503"). Map the status before the
    // generic network branch, because a 403 is not a broken Wi-Fi connection.
    httpStatus(raw)?.let { code ->
        return when (code) {
            401, 403 ->
                "The provider's site refused the request (HTTP $code). Try again later" +
                    ", or solve a challenge if one appears."
            404, 410 ->
                "The provider no longer has this title (HTTP $code). Try another title or provider."
            429 ->
                "The provider is rate-limiting requests (HTTP $code). Wait a minute, then retry."
            in 500..599 ->
                "The provider's site is having trouble (HTTP $code). Try again in a few minutes."
            else ->
                "The provider's site rejected the request (HTTP $code). Try again, or use another provider."
        }
    }

    if (lower.contains("failed to fetch") ||
        lower.contains("network request failed") ||
        lower.contains("unable to resolve host") ||
        lower.contains("econnrefused") ||
        lower.contains("enotfound") ||
        lower.contains("socket") ||
        lower.contains("connection")
    ) {
        return "The provider's site could not be reached. Check the connection, then retry."
    }

    if (lower.contains("not found") || lower.contains("no result")) {
        return "The provider could not find this title. Try another title or provider."
    }

    return fallback
}

/** First HTTP status in a provider error string, if it names one. */
private fun httpStatus(raw: String): Int? {
    STATUS.find(raw)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
    return CODE.find(raw)?.groupValues?.getOrNull(1)?.toIntOrNull()
}

private val STATUS = Regex("""\b(?:HTTP|status(?: code)?)\s*[:=]?\s*(\d{3})\b""", RegexOption.IGNORE_CASE)
private val CODE = Regex("""\bcode\s*[:=]\s*(\d{3})\b""", RegexOption.IGNORE_CASE)
