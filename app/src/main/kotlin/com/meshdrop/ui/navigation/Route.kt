package com.meshdrop.ui.navigation

/**
 * Destinations.
 *
 * Three, and the split is deliberate:
 *
 *  * [Drop] is the product. Sending and receiving are the same act seen from two sides, so
 *    they are one screen — the sheet that appears when somebody offers you files is part of
 *    Drop, not a place you navigate to.
 *  * [Activity] is the archive: what has already moved, searchable.
 *  * [Settings] is where the promises live: encryption, verification, the vault, and the
 *    statement that there is no server.
 *
 * There used to be five: Home, Send, Receive, History, Settings. The two that went (Send and
 * Receive) were the two that asked the user to declare, up front, which end of a conversation
 * they were on — before the other person had even answered.
 */
sealed class Route(val path: String) {
    data object Drop : Route("drop")
    data object Activity : Route("activity")
    data object Settings : Route("settings")
}
