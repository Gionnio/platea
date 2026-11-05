package com.github.gionnio.platea.details

import android.content.Context
import com.github.gionnio.platea.R
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.MediaStreamType
import org.jellyfin.sdk.model.api.VideoRangeType

/** Quality badges of a title (4K, DOLBY VISION, 5.1…), from its first media source. */
object MediaBadges {
    fun of(
        context: Context,
        item: BaseItemDto?,
    ): List<String> {
        val streams = item?.mediaStreams ?: item?.mediaSources?.firstOrNull()?.mediaStreams ?: return emptyList()
        val video = streams.firstOrNull { it.type == MediaStreamType.VIDEO }
        val audio = streams.firstOrNull { it.type == MediaStreamType.AUDIO && it.isDefault == true } ?: streams.firstOrNull { it.type == MediaStreamType.AUDIO }
        return buildList {
            val width = video?.width ?: 0
            when {
                width >= 3000 -> add("4K")
                width >= 1200 -> add("HD")
            }
            when (video?.videoRangeType) {
                VideoRangeType.DOVI, VideoRangeType.DOVI_WITH_HDR10, VideoRangeType.DOVI_WITH_HLG, VideoRangeType.DOVI_WITH_SDR -> add("DOLBY VISION")
                VideoRangeType.HDR10_PLUS -> add("HDR10+")
                VideoRangeType.HDR10 -> add("HDR10")
                VideoRangeType.HLG -> add("HLG")
                else -> if (video?.videoRangeType?.serialName?.startsWith("DOVI") == true) add("DOLBY VISION")
            }
            val profile = audio?.profile.orEmpty() + " " + audio?.displayTitle.orEmpty()
            when {
                profile.contains("Atmos", ignoreCase = true) -> add("DOLBY ATMOS")
                (audio?.channels ?: 0) >= 8 -> add("7.1")
                (audio?.channels ?: 0) >= 6 -> add("5.1")
                (audio?.channels ?: 0) in 1..2 -> add(context.getString(R.string.platea_badge_stereo))
            }
        }
    }
}
