package com.meshdrop.ui.navigation

/** The three destinations of the MVP. */
sealed class Route(val path: String) {
    data object Home : Route("home")
    data object Send : Route("send")
    data object Receive : Route("receive")
}
