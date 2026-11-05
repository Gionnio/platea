package com.github.gionnio.platea.engine

import org.jellyfin.sdk.model.api.MediaSourceInfo

/** How the server offers to serve a file, read from its PlaybackInfo answer. */
object PlayMethods {
    /** Direct play first, then remux, then conversion; `null` when the server offers nothing. */
    fun of(source: MediaSourceInfo): PlayMethod? =
        when {
            source.supportsDirectPlay -> PlayMethod.DIRECT_PLAY
            source.supportsDirectStream -> PlayMethod.REMUX
            source.supportsTranscoding -> PlayMethod.TRANSCODE
            else -> null
        }
}
