package com.github.gionnio.platea.data.model

import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import com.github.gionnio.platea.R
import com.github.gionnio.platea.PlateaApplication
import com.github.gionnio.platea.ui.abbreviateNumber
import com.github.gionnio.platea.ui.dot
import com.github.gionnio.platea.ui.formatDateTime
import com.github.gionnio.platea.ui.formatDuration
import com.github.gionnio.platea.ui.formatEpisodeNumber
import com.github.gionnio.platea.ui.getDateFormatter
import com.github.gionnio.platea.ui.joinNotBlank
import com.github.gionnio.platea.ui.nav.Destination
import com.github.gionnio.platea.ui.nav.SeasonEpisodeIds
import com.github.gionnio.platea.ui.playback.playable
import com.github.gionnio.platea.ui.roundMinutes
import com.github.gionnio.platea.ui.seasonEpisode
import com.github.gionnio.platea.ui.seasonEpisodePadded
import com.github.gionnio.platea.ui.seriesProductionYears
import com.github.gionnio.platea.ui.timeRemaining
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.extensions.ticks
import java.util.Locale
import kotlin.time.Duration

/**
 * Wrapper for [BaseItemDto] with shortcuts for various UI elements
 */
@Serializable
@Stable
data class BaseItem(
    val data: BaseItemDto,
    val useSeriesForPrimary: Boolean = false,
    val imageUrlOverride: String? = null,
    val destinationOverride: Destination? = null,
) {
    val id get() = data.id

    val gridId get() = id.toString()

    val playable: Boolean
        get() = type.playable

    val sortName: String
        get() = data.alphabetSortName

    val type get() = data.type

    val name get() = data.name

    val title get() = if (type == BaseItemKind.EPISODE) data.seriesName else name

    val subtitle: String? by lazy {
        when (type) {
            BaseItemKind.EPISODE -> listOf(data.seasonEpisode, name).joinNotBlank(" - ")
            BaseItemKind.SERIES -> data.seriesProductionYears
            BaseItemKind.AUDIO -> listOf(data.album, data.artists?.joinToString(", ")).joinNotBlank(" - ")
            else -> data.productionYear?.toString()
        }
    }

    val subtitleLong: String? by lazy {
        if (type == BaseItemKind.EPISODE) {
            buildList {
                add(data.seasonEpisodePadded)
                add(data.name)
                add(data.premiereDate?.let { formatDateTime(it) })
            }.joinNotBlank(" - ")
        } else {
            data.productionYear?.toString()
        }
    }

    val canDelete: Boolean get() = data.canDelete == true

    val aspectRatio: Float? get() = data.primaryImageAspectRatio?.toFloat()?.takeIf { it > 0 }

    val indexNumber get() = data.indexNumber

    val playbackPosition get() = data.userData?.playbackPositionTicks?.ticks ?: Duration.ZERO

    val resumeMs get() = playbackPosition.inWholeMilliseconds

    val played get() = data.userData?.played ?: false

    val favorite get() = data.userData?.isFavorite ?: false

    val timeRemainingOrRuntime: Duration?
        get() =
            when (type) {
                BaseItemKind.PROGRAM -> null
                else -> data.timeRemaining ?: data.runTimeTicks?.ticks
            }

    /**
     * Contains pre computed UI elements that would be expensive to create on the main thread
     */
    @Transient
    val ui =
        BaseItemUi(
            episodeCornerText =
                data.indexNumber?.let { formatEpisodeNumber(it) }
                    ?: data.premiereDate?.let(::formatDateTime),
            episodeUnplayedCornerText =
                if (type == BaseItemKind.SERIES ||
                    type == BaseItemKind.SEASON ||
                    type == BaseItemKind.EPISODE ||
                    type == BaseItemKind.BOX_SET
                ) {
                    data.indexNumber?.let { formatEpisodeNumber(it) }
                        ?: data.userData
                            ?.unplayedItemCount
                            ?.takeIf { it > 0 }
                            ?.let { abbreviateNumber(it) }
                } else {
                    null
                },
            quickDetails =
                QuickDetailsData(
                    basic =
                        buildAnnotatedString {
                            val details =
                                buildList {
                                    if (type == BaseItemKind.EPISODE) {
                                        data.seasonEpisode?.let(::add)
                                        data.premiereDate?.let { add(getDateFormatter().format(it)) }
                                    } else if (type == BaseItemKind.SERIES) {
                                        data.seriesProductionYears?.let(::add)
                                    } else if (type == BaseItemKind.PHOTO) {
                                        if (data.productionYear != null) {
                                            add(data.productionYear!!.toString())
                                        } else if (data.premiereDate != null) {
                                            add(data.premiereDate!!.toLocalDate().toString())
                                        }
                                    } else if (type == BaseItemKind.BOX_SET) {
                                        data.productionYear?.let { add(it.toString()) }
                                        data.childCount?.let { add("$it items") }
                                    } else if (type == BaseItemKind.PROGRAM) {
                                        data.channelName?.let(::add)
                                        if (data.isSeries == true) {
                                            // TV episode
                                            data.seasonEpisode?.let(::add)
                                            data.premiereDate?.let {
                                                add(getDateFormatter().format(it))
                                            }
                                        } else {
                                            data.productionYear?.let { add(it.toString()) }
                                        }
                                    } else {
                                        data.productionYear?.let { add(it.toString()) }
                                    }
                                    data.runTimeTicks
                                        ?.ticks
                                        ?.roundMinutes
                                        ?.takeIf { it > Duration.ZERO }
                                        ?.let { add(PlateaApplication.instance.resources.formatDuration(it)) }
                                    data.timeRemaining
                                        ?.roundMinutes
                                        ?.let {
                                            val resources = PlateaApplication.instance.resources
                                            add(
                                                resources.getString(
                                                    R.string.time_left,
                                                    resources.formatDuration(it),
                                                ),
                                            )
                                        }
                                }
                            details.forEachIndexed { index, string ->
                                append(string)
                                if (index != details.lastIndex) {
                                    dot()
                                }
                            }
                        },
                    officialRating =
                        data.officialRating?.let {
                            buildAnnotatedString {
                                dot()
                                append(it)
                            }
                        },
                    communityRating =
                        data.communityRating?.let {
                            buildAnnotatedString {
                                dot()
                                append(String.format(Locale.getDefault(), "%.1f", it))
                                appendInlineContent(id = "star")
                            }
                        },
                    criticRating =
                        data.criticRating?.let {
                            buildAnnotatedString {
                                dot()
                                append("${it.toInt()}%")
                                if (it >= 60f) {
                                    appendInlineContent(id = "fresh")
                                } else {
                                    appendInlineContent(id = "rotten")
                                }
                            }
                        },
                ),
        )

    private fun dateAsIndex(): Int? =
        data.premiereDate
            ?.let {
                it.year.toString() +
                    it.monthValue.toString().padStart(2, '0') +
                    it.dayOfMonth.toString().padStart(2, '0')
            }?.toIntOrNull()

    /**
     * Convert this [BaseItem] into a [Destination] to navigate to its page in the app
     */
    fun destination(index: Int? = null): Destination {
        if (destinationOverride != null) return destinationOverride
        if (data.extraType != null || type == BaseItemKind.TRAILER) {
            // Extras including trailers should always play directly
            return Destination.Playback(id, 0)
        }
        val result =
            // Redirect episodes & seasons to their series if possible
            when (type) {
                BaseItemKind.EPISODE -> {
                    val seasonId = data.seasonId
                    val seriesId = data.seriesId
                    if (seasonId != null && seriesId != null) {
                        Destination.SeriesOverview(
                            seriesId,
                            BaseItemKind.SERIES,
                            SeasonEpisodeIds(seasonId, data.parentIndexNumber, id, indexNumber),
                        )
                    } else {
                        Destination.MediaItem(this)
                    }
                }

                BaseItemKind.SEASON -> {
                    // A season the server lists without its series opens as it is instead of stopping the app
                    data.seriesId?.let { seriesId ->
                        Destination.SeriesOverview(
                            seriesId,
                            BaseItemKind.SERIES,
                            SeasonEpisodeIds(id, indexNumber, null, null),
                        )
                    } ?: Destination.MediaItem(this)
                }

                BaseItemKind.TV_CHANNEL -> {
                    Destination.Playback(
                        itemId = id,
                        positionMs = 0L,
                    )
                }

                BaseItemKind.PROGRAM -> {
                    val channelId = data.channelId
                    if (channelId != null) {
                        Destination.Playback(
                            itemId = channelId,
                            positionMs = 0L,
                        )
                    } else {
                        Destination.MediaItem(this)
                    }
                }

                BaseItemKind.AUDIO -> {
                    data.albumId?.let { albumId ->
                        Destination.MediaItem(
                            itemId = albumId,
                            type = BaseItemKind.MUSIC_ALBUM,
                            initialSongId = id,
                        )
                    } ?: Destination.MediaItem(this)
                }

                else -> {
                    Destination.MediaItem(this)
                }
            }
        return result
    }

    companion object {
        @Deprecated("Use regular constructor instead")
        fun from(
            dto: BaseItemDto,
            api: ApiClient,
            useSeriesForPrimary: Boolean = false,
        ): BaseItem =
            BaseItem(
                dto,
                useSeriesForPrimary,
            )
    }
}

val BaseItemDto.aspectRatioFloat: Float? get() = width?.let { w -> height?.let { h -> w.toFloat() / h.toFloat() } }

/** The server strips leading articles (eg "The ") from [BaseItemDto.sortName]. */
val BaseItemDto.alphabetSortName: String get() = sortName ?: name ?: ""

@Immutable
data class BaseItemUi(
    val episodeCornerText: String?,
    val episodeUnplayedCornerText: String?,
    val quickDetails: QuickDetailsData,
)

data class QuickDetailsData(
    val basic: AnnotatedString,
    val officialRating: AnnotatedString? = null,
    val criticRating: AnnotatedString? = null,
    val communityRating: AnnotatedString? = null,
)
