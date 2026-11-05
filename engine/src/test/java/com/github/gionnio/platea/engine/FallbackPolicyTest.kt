package com.github.gionnio.platea.engine

import androidx.media3.common.PlaybackException
import com.github.gionnio.platea.engine.FallbackPolicy.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FallbackPolicyTest {
    @Test
    fun readErrorsAreRetriedInDirectPlay() {
        val code = PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
        assertTrue(FallbackPolicy.onDirectPlayError(code, 0, autoTranscode = true) is Action.Retry)
        assertTrue(FallbackPolicy.onDirectPlayError(code, 2, autoTranscode = true) is Action.Retry)
        // Retries exhausted: a slow source is not fixed by converting it, so no server fallback
        assertTrue(FallbackPolicy.onDirectPlayError(code, 3, autoTranscode = true) is Action.ShowError)
        assertTrue(FallbackPolicy.onDirectPlayError(code, 3, autoTranscode = false) is Action.ShowError)
    }

    @Test
    fun retriesWaitLongerEachTime() {
        val code = PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
        val delays = (0..2).map { (FallbackPolicy.onDirectPlayError(code, it, autoTranscode = true) as Action.Retry).delayMs }
        assertEquals(listOf(1_000L, 3_000L, 6_000L), delays)
    }

    @Test
    fun decoderErrorsGoToTheServerOnlyIfAllowed() {
        val code = PlaybackException.ERROR_CODE_DECODER_INIT_FAILED
        assertTrue(FallbackPolicy.onDirectPlayError(code, 0, autoTranscode = true) is Action.ServerTranscode)
        assertTrue(FallbackPolicy.onDirectPlayError(code, 0, autoTranscode = false) is Action.ShowError)
    }

    @Test
    fun conversionRetriesReadErrorsThenShowsTheError() {
        val read = PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
        assertTrue(FallbackPolicy.onConversionError(read, 0) is Action.Retry)
        assertTrue(FallbackPolicy.onConversionError(read, 3) is Action.ShowError)
        assertTrue(FallbackPolicy.onConversionError(PlaybackException.ERROR_CODE_DECODING_FAILED, 0) is Action.ShowError)
    }

    @Test
    fun retriesComeBackAfterAHealthyStretch() {
        var clock = 0L
        val budget = RetryBudget(recoverAfterMs = 60_000) { clock }
        budget.spend()
        clock += 10_000
        budget.spend()
        assertEquals(2, budget.used)
        clock += 59_999
        assertEquals(2, budget.used)
        clock += 1
        assertEquals(0, budget.used)
        budget.spend()
        assertEquals(1, budget.used)
        budget.reset()
        assertEquals(0, budget.used)
    }
}
