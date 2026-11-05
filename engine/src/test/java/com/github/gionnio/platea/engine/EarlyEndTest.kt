package com.github.gionnio.platea.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EarlyEndTest {
    private val episode = 20 * 60_000L

    @Test
    fun anEndFarFromTheDurationIsNotTheEnd() {
        assertTrue(EarlyEnd.isEarly(positionMs = 2 * 60_000L, durationMs = episode))
        assertTrue(EarlyEnd.isEarly(positionMs = 1_000L, durationMs = episode))
    }

    @Test
    fun theRealEndOrUnknownDurationIsLeftAlone() {
        assertFalse(EarlyEnd.isEarly(positionMs = episode - 30_000L, durationMs = episode))
        assertFalse(EarlyEnd.isEarly(positionMs = episode, durationMs = episode))
        // Duration unknown (C.TIME_UNSET is negative) or a very short clip
        assertFalse(EarlyEnd.isEarly(positionMs = 1_000L, durationMs = Long.MIN_VALUE + 1))
        assertFalse(EarlyEnd.isEarly(positionMs = 1_000L, durationMs = 60_000L))
    }
}
