package tv.vega.tv.player

/**
 * Activity-level key hooks. Player overlays get first chance at BACK/MENU.
 *
 * [onKey] is the reason this is not just "the surface has focus": an `AndroidView`
 * inside Compose only receives hardware keys when it happens to hold *Android*
 * focus, and nothing about a player screen guarantees it does. The player screen
 * installs a handler here instead, so play/pause and seek work from the remote
 * even when focus has drifted to a Compose overlay or nowhere at all.
 *
 * Handlers return true only for a key they actually consumed, so anything they
 * do not want still reaches the focused view.
 */
object PlayerKeyBus {
    var onBack: (() -> Boolean)? = null
    var onMenu: (() -> Unit)? = null
    var onKey: ((keyCode: Int) -> Boolean)? = null
}
