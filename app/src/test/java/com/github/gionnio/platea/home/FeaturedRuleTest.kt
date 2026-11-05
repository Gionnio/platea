package com.github.gionnio.platea.home

import com.github.gionnio.platea.home.FeaturedCandidate.Kind
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.util.UUID

class FeaturedRuleTest {
    private val names = mutableMapOf<UUID, String>()

    private fun c(
        name: String,
        kind: Kind,
        date: String? = null,
        is4k: Boolean = true,
        played: Boolean = false,
        inProgress: Boolean = false,
        group: UUID? = null,
    ): FeaturedCandidate {
        val id = UUID.randomUUID()
        names[id] = name
        return FeaturedCandidate(id, group ?: id, kind, date?.let { Instant.parse(it + "T12:00:00Z") }, is4k, played, inProgress)
    }

    private fun List<FeaturedCandidate>.names() = map { names.getValue(it.id) }

    /** The library on 2026-10-08. */
    @Test
    fun realLibrary() {
        val nextUp =
            listOf(
                c("The Big Bang Theory 7x11", Kind.NEXT_EPISODE, is4k = false),
                c("Modern Family 8x7", Kind.NEXT_EPISODE, is4k = false, inProgress = true),
            )
        val novelties =
            listOf(
                c("883", Kind.SERIES, "2026-10-06", is4k = false),
                c("La valle dell'Eden", Kind.SERIES, "2026-10-03"),
                c("L'Impero colpisce ancora", Kind.MOVIE, "2026-10-02"),
                c("Loki", Kind.SERIES, "2026-10-01"),
                c("Disclosure Day", Kind.MOVIE, "2026-09-27"),
                c("Oceania", Kind.MOVIE, "2026-09-26"),
                c("Demon Slayer", Kind.MOVIE, "2026-09-22", is4k = false),
            )
        val picked = FeaturedRule.pick(nextUp, novelties, emptyList())
        assertEquals(
            listOf("The Big Bang Theory 7x11", "La valle dell'Eden", "L'Impero colpisce ancora", "Loki", "Disclosure Day"),
            picked.names(),
        )
    }

    @Test
    fun startedAndSeenTitlesAreNeverFeatured() {
        val picked =
            FeaturedRule.pick(
                nextUp = emptyList(),
                novelties =
                    listOf(
                        c("Seen", Kind.MOVIE, "2026-10-05", played = true),
                        c("Started", Kind.MOVIE, "2026-10-04", inProgress = true),
                        c("New", Kind.MOVIE, "2026-10-03"),
                    ),
                topRatedUnseen = emptyList(),
            )
        assertEquals(listOf("New"), picked.names())
    }

    @Test
    fun oneSlotPerSeriesAndTopRatedFillsTheRest() {
        val series = UUID.randomUUID()
        val picked =
            FeaturedRule.pick(
                nextUp = listOf(c("Andor 2x5", Kind.NEXT_EPISODE, group = series)),
                novelties = listOf(c("Andor", Kind.SERIES, "2026-10-01", group = series)),
                topRatedUnseen = listOf(c("Il padrino", Kind.MOVIE), c("Parasite", Kind.MOVIE)),
                max = 3,
            )
        assertEquals(listOf("Andor 2x5", "Il padrino", "Parasite"), picked.names())
    }
}
