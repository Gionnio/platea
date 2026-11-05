package com.github.gionnio.platea.engine

/**
 * Spots a player that says it is playing while the position stands still and almost nothing is buffered ahead.
 *
 * On Fire OS an audio passthrough track (E-AC3, AC3) that runs dry is paused by the system and is not resumed
 * until new data is written, while the player still sees those pending audio frames and stays "ready" instead of
 * buffering. With a source that stops completely (busy server or NAS) the two wait for each other until the
 * player gives up with "stuck playing". Seeking to the same position flushes the audio and turns that into an
 * ordinary rebuffer, which resumes on its own when data comes back.
 */
class StallWatchdog(
    private val stallAfterMs: Long = 2_000,
    private val maxAheadMs: Long = 3_000,
    private val cooldownMs: Long = 8_000,
) {
    private var lastPositionMs = -1L
    private var standingSince = 0L
    private var lastActionAt = Long.MIN_VALUE / 2

    /** Whether the position stood still at the last check (the player is not moving). */
    var standing = false
        private set

    /** @return true when the player should be unstuck now */
    fun check(
        nowMs: Long,
        isPlaying: Boolean,
        positionMs: Long,
        bufferedPositionMs: Long,
    ): Boolean {
        if (!isPlaying || positionMs != lastPositionMs) {
            lastPositionMs = if (isPlaying) positionMs else -1L
            standingSince = nowMs
            standing = false
            return false
        }
        standing = true
        val starving = bufferedPositionMs - positionMs <= maxAheadMs
        if (starving && nowMs - standingSince >= stallAfterMs && nowMs - lastActionAt >= cooldownMs) {
            lastActionAt = nowMs
            standingSince = nowMs
            return true
        }
        return false
    }

    fun reset() {
        lastPositionMs = -1L
        standing = false
    }
}
