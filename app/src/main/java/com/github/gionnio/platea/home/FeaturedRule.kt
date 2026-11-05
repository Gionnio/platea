package com.github.gionnio.platea.home

import java.time.Instant
import java.util.UUID

/** A title that can be shown in the featured area of the home. */
data class FeaturedCandidate(
    val id: UUID,
    /** Series id for episodes and series, item id otherwise: one featured slot per series. */
    val groupId: UUID,
    val kind: Kind,
    val dateAdded: Instant?,
    val is4k: Boolean,
    val played: Boolean,
    /** Already started (it is in "Continua a guardare" with a progress bar). */
    val inProgress: Boolean,
) {
    enum class Kind { NEXT_EPISODE, MOVIE, SERIES }
}

/**
 * Featured rotation of the Platea home:
 * 1. the next episode of the series you are following (not started yet);
 * 2. the newest titles you have not seen, 4K first;
 * 3. if still short, the top rated titles you have not seen.
 * Never a title already started, one slot per series, at most [max] titles.
 */
object FeaturedRule {
    const val MAX = 5

    fun pick(
        nextUp: List<FeaturedCandidate>,
        novelties: List<FeaturedCandidate>,
        topRatedUnseen: List<FeaturedCandidate>,
        max: Int = MAX,
    ): List<FeaturedCandidate> {
        val next = nextUp.filter { !it.inProgress }
        val fresh =
            novelties
                .filter { !it.played && !it.inProgress }
                .sortedWith(compareByDescending<FeaturedCandidate> { it.is4k }.thenByDescending { it.dateAdded })
        val fallback = topRatedUnseen.filter { !it.played && !it.inProgress }
        return (next + fresh + fallback)
            .distinctBy { it.groupId }
            .take(max)
    }
}
