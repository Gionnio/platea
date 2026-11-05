package com.github.gionnio.platea.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StallWatchdogTest {
    @Test
    fun standingStillWithNothingAheadIsUnstuck() {
        val watchdog = StallWatchdog()
        assertFalse(watchdog.check(0, isPlaying = true, positionMs = 72_207, bufferedPositionMs = 73_280))
        assertFalse(watchdog.check(1_500, isPlaying = true, positionMs = 72_207, bufferedPositionMs = 73_280))
        assertTrue(watchdog.check(2_000, isPlaying = true, positionMs = 72_207, bufferedPositionMs = 73_280))
        // Waits before trying again
        assertFalse(watchdog.check(4_500, isPlaying = true, positionMs = 72_207, bufferedPositionMs = 73_280))
        assertTrue(watchdog.check(10_000, isPlaying = true, positionMs = 72_207, bufferedPositionMs = 73_280))
    }

    @Test
    fun movingOrPausedOrWellBufferedIsLeftAlone() {
        val watchdog = StallWatchdog()
        var position = 0L
        for (now in 0L..10_000L step 500) {
            position += 500
            assertFalse(watchdog.check(now, isPlaying = true, positionMs = position, bufferedPositionMs = position + 1_000))
        }
        // Paused: the position stands still on purpose
        for (now in 10_000L..20_000L step 500) {
            assertFalse(watchdog.check(now, isPlaying = false, positionMs = position, bufferedPositionMs = position))
        }
        // Plenty buffered ahead: a decoder problem, not a starving source; left to the error handling
        for (now in 20_000L..30_000L step 500) {
            assertFalse(watchdog.check(now, isPlaying = true, positionMs = position, bufferedPositionMs = position + 30_000))
        }
    }
}
