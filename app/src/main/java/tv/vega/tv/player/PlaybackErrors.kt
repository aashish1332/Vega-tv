package tv.vega.tv.player

import androidx.media3.common.PlaybackException
import androidx.media3.datasource.HttpDataSource

/**
 * One sentence for a source that failed to play.
 *
 * `PlayerView`'s default is `errorCodeName + ": " + cause`, which is how
 * "ERROR_CODE_IO_BAD_HTTP_STATUS: InvalidResponseCodeException: Response code:
 * 403" ended up on a TV. Each class of failure has a different answer, and the
 * viewer only needs that answer: a 403 wants a different server, a decoder
 * failure wants a different quality, a timeout wants a retry. DRM has its own
 * mapper in [drmErrorMessage] so the wording stays consistent with the
 * pre-playback DRM check.
 *
 * The raw exception still reaches logcat from the caller; this is display text.
 */
fun playbackErrorMessage(error: PlaybackException): String {
    drmErrorMessage(error.errorCode)?.let { return it }
    val status = httpStatus(error)
    return when (error.errorCode) {
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
        PlaybackException.ERROR_CODE_TIMEOUT,
        ->
            "The video server did not answer. Check the connection, then retry or choose another server."

        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> when {
            status == 401 || status == 403 ->
                "The video server refused this stream" + suffix(status) +
                    ". Choose another server."
            status == 404 || status == 410 ->
                "This video is no longer on the server" + suffix(status) +
                    ". Choose another server."
            status == 429 ->
                "The video server is busy or rate-limiting" + suffix(status) +
                    ". Wait a moment, or choose another server."
            status != null && status in 500..599 ->
                "The video server is having trouble" + suffix(status) +
                    ". Choose another server."
            else ->
                "The video server rejected the request" + suffix(status) +
                    ". Choose another server."
        }

        PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE ->
            "The server sent a web page instead of a video. Choose another server."
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ->
            "The video file is no longer on the server. Choose another server."
        PlaybackException.ERROR_CODE_IO_NO_PERMISSION ->
            "The server would not allow this video. Choose another server."
        PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED ->
            "This server uses an insecure http:// link, which Android blocks. Choose another server."
        PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE ->
            "The server sent an incomplete video file. Choose another server."

        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
        ->
            "This video file is damaged. Choose another server."

        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
        ->
            "This stream's format is not supported by the player. Choose another server."

        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED,
        ->
            "This device's video decoder could not play this file. Choose another server or quality."

        PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
        PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED,
        PlaybackException.ERROR_CODE_AUDIO_TRACK_OFFLOAD_INIT_FAILED,
        PlaybackException.ERROR_CODE_AUDIO_TRACK_OFFLOAD_WRITE_FAILED,
        ->
            "The audio track could not start on this device. Choose another server or quality."

        PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW ->
            "The live stream moved ahead of the player. Retry to rejoin it."

        PlaybackException.ERROR_CODE_NOT_AVAILABLE_IN_REGION ->
            "This stream is not available in your region. Choose another server."
        PlaybackException.ERROR_CODE_AUTHENTICATION_EXPIRED ->
            "This stream needs a fresh sign-in. Choose another server."

        else ->
            "This server could not start the video. Retry, or choose another server."
    }
}

private fun suffix(status: Int?): String = status?.let { " (HTTP $it)" }.orEmpty()

/** The HTTP status inside a Media3 IO failure, if it carries one. */
private fun httpStatus(error: PlaybackException): Int? {
    var cause: Throwable? = error.cause
    var depth = 0
    while (cause != null && depth < 4) {
        if (cause is HttpDataSource.InvalidResponseCodeException) return cause.responseCode
        cause = cause.cause
        depth++
    }
    return null
}
