package com.github.gionnio.platea.engine

/**
 * How much must be buffered before playback resumes after running dry. A source that is only a little too
 * slow makes the player resume as soon as a couple of seconds are in, run dry again and stutter (play 2 s,
 * wait 5 s...). Each rebuffer within [windowMs] of the previous ones asks for a longer buffer, so a slow
 * patch becomes a few clean pauses instead.
 */
class RebufferPolicy(
    private val windowMs: Long = 120_000,
    private val stepsMs: LongArray = longArrayOf(5_000, 10_000, 20_000),
) {
    private val resumes = ArrayDeque<Long>()

    /** Buffer needed now to resume after a rebuffer. */
    fun requiredMs(nowMs: Long): Long {
        while (resumes.isNotEmpty() && nowMs - resumes.first() > windowMs) resumes.removeFirst()
        return stepsMs[minOf(resumes.size, stepsMs.lastIndex)]
    }

    /** Playback resumed after a rebuffer. */
    fun resumed(nowMs: Long) {
        resumes.addLast(nowMs)
    }
}
