package tv.vega.tv.player

import android.util.Base64
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager
import androidx.media3.exoplayer.drm.DrmSessionManager
import androidx.media3.exoplayer.drm.ExoMediaDrm
import androidx.media3.exoplayer.drm.FrameworkMediaDrm
import androidx.media3.exoplayer.drm.HttpMediaDrmCallback
import androidx.media3.exoplayer.drm.LocalMediaDrmCallback
import androidx.media3.exoplayer.drm.MediaDrmCallback
import tv.vega.tv.data.model.StreamDrm
import tv.vega.tv.data.model.VegaStream

/*
 * Native DRM for the provider contract added in vega-providers `docs/provider-drm.md`.
 *
 * Providers may attach a `drm` object to a stream. Inline ClearKey (live DASH
 * from DurbinLive / LiveTG) is converted to the local JWK licence response
 * Media3's `LocalMediaDrmCallback` expects; Widevine/PlayReady use a normal
 * HTTP licence request. FairPlay is Apple-only and is reported as unsupported
 * so playback can fail over to a clear server instead of surfacing Media3's
 * raw `ERROR_CODE_DRM_SCHEME_UNSUPPORTED`.
 */

private const val CLEARKEY_MISSING =
    "This server is DRM-protected but sent no ClearKey keys. Choose another server."
private const val LICENCE_MISSING =
    "This server is DRM-protected but sent no licence server. Choose another server."
private const val FAIRPLAY =
    "This server uses FairPlay DRM, which Android TV cannot play. Choose another server."
private const val INVALID =
    "This server sent an invalid DRM configuration. Choose another server."

/** Human-readable reason this stream cannot be decrypted here, or null if it can. */
fun VegaStream.drmUnsupportedReason(): String? = drm?.unsupportedReason()

fun StreamDrm.unsupportedReason(): String? {
    val kind = type.trim().lowercase()
    return when (kind) {
        "clearkey" -> when {
            !clearKeys.isNullOrEmpty() -> null
            !licenseServer.isNullOrBlank() -> null
            else -> CLEARKEY_MISSING
        }
        "widevine", "playready" ->
            if (licenseServer.isNullOrBlank()) LICENCE_MISSING else null
        "fairplay" -> FAIRPLAY
        "" -> INVALID
        else -> "This server uses unsupported $kind DRM. Choose another server."
    }
}

/**
 * Build the session manager Media3 needs for this stream, or null when the
 * configuration is unsupported/incomplete. The caller treats null as "do not
 * prepare this server" and moves to the next one.
 */
fun StreamDrm.createDrmSessionManager(dataSourceFactory: DataSource.Factory): DrmSessionManager? {
    if (unsupportedReason() != null) return null
    val kind = type.trim().lowercase()
    val uuid = when (kind) {
        "clearkey" -> C.CLEARKEY_UUID
        "widevine" -> C.WIDEVINE_UUID
        "playready" -> C.PLAYREADY_UUID
        else -> return null
    }
    // Some TV firmware ships no plugin for a scheme. Checking first turns a
    // mid-playback 6001 into an upfront failover.
    if (!runCatching { FrameworkMediaDrm.isCryptoSchemeSupported(uuid) }.getOrDefault(false)) {
        return null
    }

    val callback: MediaDrmCallback = when {
        kind == "clearkey" && !clearKeys.isNullOrEmpty() ->
            LocalMediaDrmCallback(clearKeyJwk(clearKeys) ?: return null)
        else -> {
            val url = licenseServer?.trim().orEmpty()
            if (url.isEmpty()) return null
            HttpMediaDrmCallback(url, dataSourceFactory).apply {
                headers?.forEach { (name, value) ->
                    if (name.isNotBlank()) setKeyRequestProperty(name, value)
                }
            }
        }
    }
    return runCatching {
        DefaultDrmSessionManager.Builder()
            .setUuidAndExoMediaDrmProvider(
                uuid,
                ExoMediaDrm.Provider { mediaDrmUuid -> FrameworkMediaDrm.newInstance(mediaDrmUuid) },
            )
            .setMultiSession(multiDrm == true)
            .build(callback)
    }.getOrNull()
}

/**
 * ClearKey license response in the EME JWK shape:
 * `{"keys":[{"kty":"oct","kid":"<b64url>","k":"<b64url>"}],"type":"temporary"}`.
 *
 * Key IDs and keys arrive as 32-char hex; MediaDrm expects the raw 16 bytes
 * base64url-encoded without padding.
 */
private fun clearKeyJwk(keys: Map<String, String>): ByteArray? {
    val entries = ArrayList<String>(keys.size)
    keys.forEach { (keyIdHex, keyHex) ->
        val keyId = keyIdHex.hex16() ?: return@forEach
        val key = keyHex.hex16() ?: return@forEach
        entries += "\"kty\":\"oct\",\"kid\":\"${b64Url(keyId)}\",\"k\":\"${b64Url(key)}\""
    }
    if (entries.isEmpty()) return null
    return """{"keys":[{${entries.joinToString(",")}}],"type":"temporary"}"""
        .toByteArray(Charsets.UTF_8)
}

private fun String.hex16(): ByteArray? {
    val text = trim()
    if (text.length != 32) return null
    val out = ByteArray(16)
    for (i in 0 until 16) {
        val high = text[i * 2].digitToIntOrNull(16) ?: return null
        val low = text[i * 2 + 1].digitToIntOrNull(16) ?: return null
        out[i] = ((high shl 4) or low).toByte()
    }
    return out
}

private fun b64Url(bytes: ByteArray): String =
    Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

/**
 * Map Media3 DRM error codes to a sentence a viewer can act on. Returns null
 * for non-DRM errors so their existing handling is untouched.
 */
fun drmErrorMessage(errorCode: Int): String? = when (errorCode) {
    androidx.media3.common.PlaybackException.ERROR_CODE_DRM_SCHEME_UNSUPPORTED ->
        "This server is DRM-protected and this device cannot decrypt it. Choose another server."
    androidx.media3.common.PlaybackException.ERROR_CODE_DRM_PROVISIONING_FAILED ->
        "This device could not set up DRM playback. Choose another server."
    androidx.media3.common.PlaybackException.ERROR_CODE_DRM_CONTENT_ERROR ->
        "The DRM keys for this stream are not valid. Choose another server."
    androidx.media3.common.PlaybackException.ERROR_CODE_DRM_LICENSE_ACQUISITION_FAILED ->
        "The provider's DRM licence could not be loaded. Choose another server."
    androidx.media3.common.PlaybackException.ERROR_CODE_DRM_DISALLOWED_OPERATION ->
        "This stream's DRM does not allow playback on this device. Choose another server."
    androidx.media3.common.PlaybackException.ERROR_CODE_DRM_SYSTEM_ERROR ->
        "DRM playback failed on this device. Choose another server."
    androidx.media3.common.PlaybackException.ERROR_CODE_DRM_LICENSE_EXPIRED ->
        "The DRM licence expired. Retry to request a new one."
    androidx.media3.common.PlaybackException.ERROR_CODE_DRM_UNSPECIFIED ->
        "DRM playback failed for this stream. Choose another server."
    else -> null
}
