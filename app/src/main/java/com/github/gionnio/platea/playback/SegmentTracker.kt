package com.github.gionnio.platea.playback

import com.github.gionnio.platea.data.model.PlaylistItem
import com.github.gionnio.platea.preferences.PlaybackPreferences
import com.github.gionnio.platea.preferences.ShowNextUpWhen
import com.github.gionnio.platea.preferences.SkipSegmentBehavior
import com.github.gionnio.platea.ui.launchDefault
import com.github.gionnio.platea.ui.launchIO
import com.github.gionnio.platea.ui.playback.MediaSegmentState
import com.github.gionnio.platea.ui.playback.PlaybackState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.mediaSegmentsApi
import org.jellyfin.sdk.model.api.MediaSegmentDto
import org.jellyfin.sdk.model.api.MediaSegmentType
import org.jellyfin.sdk.model.extensions.inWholeTicks
import org.jellyfin.sdk.model.extensions.ticks
import timber.log.Timber
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds

/**
 * Follows the position of the current title through its segments (intro, credits, recap...): skips them or
 * offers to, and shows the next title during the credits, as the settings say.
 */
class SegmentTracker(
    private val scope: CoroutineScope,
    private val api: ApiClient,
    private val state: MutableStateFlow<PlaybackState>,
    private val preferences: () -> PlaybackPreferences,
    private val currentItemId: () -> UUID,
    /** Position of the player in milliseconds. */
    private val position: suspend () -> Long,
    private val seekTo: suspend (Long) -> Unit,
) {
    var job: Job? = null
        private set

    // Segments already skipped or dismissed, and credits that already showed the next title
    private val handled = mutableSetOf<UUID>()
    private val creditsShown = mutableSetOf<UUID>()

    /** Stops following and forgets what was done for the previous title. */
    fun reset() {
        job?.cancel()
        handled.clear()
        creditsShown.clear()
        state.update { it.copy(currentSegment = null) }
    }

    fun follow(itemId: UUID) {
        job?.cancel()
        job =
            scope.launchIO {
                val segments =
                    try {
                        api.mediaSegmentsApi.getItemSegments(itemId).content.items
                    } catch (ex: CancellationException) {
                        throw ex
                    } catch (ex: Exception) {
                        // No segments only means nothing to skip
                        Timber.w(ex, "Could not read the segments of %s", itemId)
                        return@launchIO
                    }
                if (segments.isEmpty()) return@launchIO
                while (isActive) {
                    delay(POLL_MS)
                    val ticks = position().milliseconds.inWholeTicks
                    val segment =
                        segments.firstOrNull {
                            it.type != MediaSegmentType.UNKNOWN && ticks >= it.startTicks && ticks < it.endTicks
                        }
                    if (segment == null) {
                        state.update { it.copy(currentSegment = null) }
                    } else if (segment.itemId == currentItemId()) {
                        inside(segment)
                    }
                }
            }
    }

    private suspend fun inside(segment: MediaSegmentDto) {
        val prefs = preferences()
        val now = state.value
        if (segment.id != now.currentSegment?.segment?.id) {
            Timber.d("Found media segment for %s: %s, %s", segment.itemId, segment.id, segment.type)
        }
        if (segment.type == MediaSegmentType.OUTRO &&
            prefs.showNextUpWhen == ShowNextUpWhen.DURING_CREDITS &&
            now.hasNext &&
            creditsShown.add(segment.id)
        ) {
            val next = now.nextItem()
            if (next is PlaylistItem.Media) state.update { it.copy(nextUp = next.item) }
            return
        }
        val behavior =
            when (segment.type) {
                MediaSegmentType.COMMERCIAL -> prefs.skipCommercials
                MediaSegmentType.PREVIEW -> prefs.skipPreviews
                MediaSegmentType.RECAP -> prefs.skipRecaps
                MediaSegmentType.OUTRO -> prefs.skipOutros
                MediaSegmentType.INTRO -> prefs.skipIntros
                MediaSegmentType.UNKNOWN -> SkipSegmentBehavior.IGNORE
            }
        val shown =
            when (behavior) {
                SkipSegmentBehavior.AUTO_SKIP -> {
                    if (handled.add(segment.id)) seekTo(segment.end)
                    MediaSegmentState(segment, true)
                }

                SkipSegmentBehavior.ASK_TO_SKIP -> {
                    MediaSegmentState(segment, handled.contains(segment.id))
                }

                else -> {
                    null
                }
            }
        state.update { it.copy(currentSegment = shown) }
    }

    /** The viewer answered the skip button: [dismissed] leaves the segment playing, otherwise it is skipped. */
    fun answer(
        segmentId: UUID?,
        dismissed: Boolean,
    ) {
        scope.launchDefault {
            val segment = state.value.currentSegment?.segment
            if (segment != null && segment.id == segmentId) {
                handled.add(segment.id)
                if (dismissed) {
                    state.update { it.copy(currentSegment = it.currentSegment?.copy(interacted = true)) }
                } else {
                    state.update { it.copy(currentSegment = null) }
                    seekTo(segment.end)
                }
            }
        }
    }

    private val MediaSegmentDto.end: Long get() = endTicks.ticks.inWholeMilliseconds + 1

    private companion object {
        const val POLL_MS = 500L
    }
}
