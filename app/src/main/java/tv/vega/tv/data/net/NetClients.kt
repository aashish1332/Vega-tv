package tv.vega.tv.data.net

import android.content.Context
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import tv.vega.tv.data.settings.AppSettings
import java.util.concurrent.TimeUnit

/**
 * Shared HTTP stack honoring the DoH setting (mirrors web DnsPreference for
 * DNS-over-HTTPS; WARP/ByeDPI are device VPN binaries and stay out of the
 * lightweight TV build — system Private DNS covers the same need).
 */
object NetClients {
    @Volatile
    var http: OkHttpClient = build(null)

    fun build(doh: Pair<String, String>?): OkHttpClient {
        val b = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
        val mode = doh?.first ?: "off"
        val custom = doh?.second.orEmpty()
        val url = when (mode) {
            "cloudflare" -> "https://cloudflare-dns.com/dns-query"
            "google" -> "https://dns.google/dns-query"
            "custom" -> custom
            else -> ""
        }
        if (url.startsWith("https://")) {
            runCatching {
                val bootstrap = OkHttpClient.Builder().build()
                b.dns(
                    DnsOverHttps.Builder().client(bootstrap)
                        .url(url.toHttpUrl()).build(),
                )
            }
        }
        return b.build()
    }

    suspend fun refresh(context: Context) {
        runCatching {
            http = build(AppSettings(context.applicationContext).snapshotDoh())
        }
    }
}
