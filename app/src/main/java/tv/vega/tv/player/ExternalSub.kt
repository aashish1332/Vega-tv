package tv.vega.tv.player

/** Downloaded/imported subtitle file handed to ExoPlayer as sidecar. */
data class ExternalSub(
    val uri: String,
    val mime: String = "text/vtt",
    val lang: String = "und",
    val label: String = "",
)
