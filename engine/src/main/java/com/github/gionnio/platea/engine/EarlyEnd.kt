package com.github.gionnio.platea.engine

/**
 * A title that "ends" well before its duration did not end: the source stopped giving data (a read that came back
 * empty, a connection closed). Treating it as the end marks the title as watched and starts the next episode.
 */
object EarlyEnd {
    /** How far from the duration an end still counts as the real end (credits cut short, rounding). */
    const val MARGIN_MS = 120_000L

    /** Recoveries per title before an early end is accepted (a file really shorter than its metadata). */
    const val MAX_RECOVERIES = 2

    fun isEarly(
        positionMs: Long,
        durationMs: Long,
    ): Boolean = durationMs > MARGIN_MS && positionMs >= 0 && positionMs < durationMs - MARGIN_MS
}
