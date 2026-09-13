package com.meshdrop.core.offer

import com.meshdrop.core.util.Format

/**
 * How long an incoming offer stays on screen.
 *
 * The handshake is a long poll: the sender is blocked, waiting for a human. That is fine
 * for two minutes and bad forever, so the offer has a deadline the same length as the
 * sender's patience — when it passes, the receiver answers "no" and the sender is told
 * plainly instead of hanging on a socket.
 *
 * The window is also a piece of UI: a card that never changes is a card people forget is
 * waiting, so [state] turns the countdown into three stages and the receive screen fades
 * the card as it closes.
 */
data class OfferWindow(
    val arrivedAtMillis: Long,
    val durationMillis: Long = OfferPolicy.DEFAULT_WINDOW_MILLIS,
) {

    val deadlineMillis: Long get() = arrivedAtMillis + durationMillis

    /** Milliseconds left, never negative. */
    fun remainingMillis(nowMillis: Long): Long = (deadlineMillis - nowMillis).coerceAtLeast(0L)

    /** 1.0 when the offer arrives, 0.0 when it expires. Drives the fade on the card. */
    fun fractionRemaining(nowMillis: Long): Float {
        if (durationMillis <= 0L) return 0f
        return (remainingMillis(nowMillis).toFloat() / durationMillis.toFloat()).coerceIn(0f, 1f)
    }

    fun isExpired(nowMillis: Long): Boolean = remainingMillis(nowMillis) <= 0L

    fun state(nowMillis: Long): OfferPolicy.Stage = OfferPolicy.stage(fractionRemaining(nowMillis))

    /** `1:59` — a countdown people can read without doing arithmetic. */
    fun countdown(nowMillis: Long): String {
        val totalSeconds = remainingMillis(nowMillis) / 1_000L
        val minutes = totalSeconds / 60L
        val seconds = totalSeconds % 60L
        return "$minutes:${seconds.toString().padStart(2, '0')}"
    }

    /** Plain language for the notification and the card footer. */
    fun countdownLabel(nowMillis: Long): String = when (state(nowMillis)) {
        OfferPolicy.Stage.CLOSED -> "Expired"
        else -> "${countdown(nowMillis)} left to answer"
    }
}

/**
 * The rules around an unanswered offer.
 *
 * Kept separate from [OfferWindow] because they are policy, not arithmetic: how long to
 * wait, when to nudge, and what to tell the person on the other end.
 */
object OfferPolicy {

    /** Mirrors TransferProtocol.HANDSHAKE_TIMEOUT_MS — the sender stops waiting at the same moment. */
    const val DEFAULT_WINDOW_MILLIS = 120_000L

    /** Below this fraction the card starts to look like it is closing. */
    const val CLOSING_AT = 0.25f

    /** One reminder, at the halfway point, and only if notifications are allowed. */
    const val REMINDER_AT = 0.5f

    enum class Stage(val label: String) {
        /** Plenty of time; the card looks normal. */
        OPEN("Waiting for you"),

        /** Under a quarter of the window left; the card dims and the countdown shows. */
        CLOSING("Closing soon"),

        /** Answered too late: the sender has been told and the files are not coming. */
        CLOSED("Expired"),
    }

    fun stage(fractionRemaining: Float): Stage = when {
        fractionRemaining <= 0f -> Stage.CLOSED
        fractionRemaining <= CLOSING_AT -> Stage.CLOSING
        else -> Stage.OPEN
    }

    /**
     * True once, when the countdown passes the halfway mark.
     *
     * [lastReminderAtMillis] is whatever the caller last acted on, so the function stays
     * pure and the caller keeps the state.
     */
    fun shouldRemind(window: OfferWindow, nowMillis: Long, lastReminderAtMillis: Long?): Boolean {
        if (window.isExpired(nowMillis)) return false
        if (window.fractionRemaining(nowMillis) > REMINDER_AT) return false
        return lastReminderAtMillis == null || lastReminderAtMillis < window.arrivedAtMillis
    }

    /**
     * How much earlier the receiver answers than the sender stops waiting.
     *
     * Both sides count the same two minutes, and if they hit zero together the outcome is a
     * coin toss: sometimes the sender's socket dies mid-answer and it shows "connection
     * lost" when the truth is "nobody tapped Accept". Answering five seconds early makes the
     * honest message the one that wins.
     */
    const val ANSWER_MARGIN_MILLIS = 5_000L

    /** Deadline for the *receiver's* decision, as opposed to the sender's patience. */
    fun answerByMillis(arrivedAtMillis: Long, durationMillis: Long = DEFAULT_WINDOW_MILLIS): Long =
        arrivedAtMillis + durationMillis - ANSWER_MARGIN_MILLIS

    /** What the sender is told when nobody answered. */
    const val TIMEOUT_MESSAGE = "No answer — the other device did not respond in time"

    /** What the receiver's own log says. */
    fun expiredNotice(peerName: String, waitedMillis: Long): String =
        "No answer to $peerName after ${Format.duration(waitedMillis) ?: "the wait"}"
}
