package com.github.gionnio.platea.engine

import androidx.media3.common.PlaybackException

/**
 * What to do when direct play fails. Direct play is the rule: the server converts the file only when the
 * device really cannot decode it, and never for a read error. A slow or stuck source (busy server, sleeping
 * disk, network share) stays slow when the server converts, and the conversion only adds load on top.
 */
object FallbackPolicy {
    /** Read errors are retried in direct play at most this many times per title. */
    const val MAX_DIRECT_RETRIES = 3

    /** Wait before each retry, so a busy source gets time to recover instead of failing again at once. */
    private val RETRY_DELAYS_MS = longArrayOf(1_000, 3_000, 6_000)

    sealed interface Action {
        val reason: String

        /** Prepare the same stream again from the current position. */
        data class Retry(
            override val reason: String,
            val delayMs: Long = 0,
        ) : Action

        /** Ask the server to convert the file (emergency fallback). */
        data class ServerTranscode(
            override val reason: String,
        ) : Action

        /** Stop and show the error (read errors after the retries, or automatic fallback disabled). */
        data class ShowError(
            override val reason: String,
        ) : Action
    }

    /**
     * @param errorCode [PlaybackException.errorCode]
     * @param retriesSoFar direct play retries already done for this title
     * @param autoTranscode whether the user allows the automatic server fallback
     */
    fun onDirectPlayError(
        errorCode: Int,
        retriesSoFar: Int,
        autoTranscode: Boolean,
    ): Action {
        val name = PlaybackException.getErrorCodeName(errorCode)
        return when {
            isTransient(errorCode) -> retryOrShow(name, retriesSoFar)
            autoTranscode -> Action.ServerTranscode(name)
            else -> Action.ShowError(name)
        }
    }

    /**
     * An error while the server is already converting: there is nothing to fall back to, but a read error is
     * still worth the same retries before giving up.
     */
    fun onConversionError(
        errorCode: Int,
        retriesSoFar: Int,
    ): Action {
        val name = PlaybackException.getErrorCodeName(errorCode)
        return if (isTransient(errorCode)) retryOrShow(name, retriesSoFar) else Action.ShowError(name)
    }

    private fun retryOrShow(
        name: String,
        retriesSoFar: Int,
    ): Action =
        if (retriesSoFar < MAX_DIRECT_RETRIES) {
            Action.Retry("$name, retry ${retriesSoFar + 1}/$MAX_DIRECT_RETRIES", RETRY_DELAYS_MS[retriesSoFar])
        } else {
            Action.ShowError(name)
        }

    private fun isTransient(errorCode: Int) =
        errorCode in IO_ERRORS ||
            errorCode == PlaybackException.ERROR_CODE_TIMEOUT ||
            errorCode == PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED

    private val IO_ERRORS = 2000..2999
}

/**
 * Retries spent on the current title. A stream that has played without errors for [recoverAfterMs] gets its
 * retries back: two network hiccups an hour apart are not a broken file.
 */
class RetryBudget(
    private val recoverAfterMs: Long = 60_000,
    private val now: () -> Long,
) {
    private var spent = 0
    private var lastAt = 0L

    val used: Int
        get() {
            if (spent > 0 && now() - lastAt >= recoverAfterMs) spent = 0
            return spent
        }

    fun spend() {
        spent = used + 1
        lastAt = now()
    }

    fun reset() {
        spent = 0
    }
}
