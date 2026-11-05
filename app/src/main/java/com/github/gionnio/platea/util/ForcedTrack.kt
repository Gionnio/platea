package com.github.gionnio.platea.util

import org.jellyfin.sdk.model.api.MediaStream

/**
 * A forced subtitle track: the flag in the file, or a title that says so. Files remuxed by some tools keep the
 * "Forced" title but lose the flag (Jellyfin then reports isForced = false), and "only forced" would find nothing.
 */
val MediaStream.isForcedTrack: Boolean
    get() =
        isForced ||
            listOfNotNull(title, displayTitle).any {
                it.contains("forced", ignoreCase = true) || it.contains("forzat", ignoreCase = true)
            }
