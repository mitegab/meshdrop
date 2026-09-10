package com.meshdrop.ui.navigation

/**
 * Destinations.
 *
 * The MVP is three screens; History and Settings are one tap from the hub but are not part
 * of the send/receive flow, so they sit beside it rather than inside it.
 */
sealed class Route(val path: String) {
    data object Home : Route("home")
    data object Send : Route("send")
    data object Receive : Route("receive")
    data object History : Route("history")
    data object Settings : Route("settings")

    companion object {
        /** `send?autoPick=true` jumps straight into the file picker after picking a device. */
        const val ARG_AUTO_PICK = "autoPick"
        const val SEND_ROUTE = "send?autoPick={autoPick}"
    }
}
