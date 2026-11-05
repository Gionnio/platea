package com.github.gionnio.platea

import io.mockk.every
import io.mockk.mockk
import org.jellyfin.sdk.model.api.MediaStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ForeignAudioSubtitlesTest {
    private fun sub(
        index: Int,
        lang: String?,
        forced: Boolean = false,
        title: String? = null,
    ): MediaStream =
        mockk(relaxed = true) {
            every { this@mockk.index } returns index
            every { language } returns lang
            every { isForced } returns forced
            every { this@mockk.title } returns title
            every { isHearingImpaired } returns false
        }

    @Test
    fun japaneseAudioPicksItalianFullSubtitles() {
        val subs = listOf(sub(3, "eng"), sub(4, "ita", title = "SDH"), sub(5, "ita"))
        assertEquals(5, ForeignAudioSubtitles.pick(subs, audioLanguage = "jpn", userLanguage = "ita")?.index)
    }

    @Test
    fun englishWhenNoItalian() {
        val subs = listOf(sub(3, "fre"), sub(4, "eng"))
        assertEquals(4, ForeignAudioSubtitles.pick(subs, audioLanguage = "jpn", userLanguage = null)?.index)
    }

    @Test
    fun italianAudioNeedsNothing() {
        assertNull(ForeignAudioSubtitles.pick(listOf(sub(3, "ita")), audioLanguage = "it", userLanguage = "ita"))
    }

    @Test
    fun unknownAudioLanguageIsNotGuessed() {
        assertNull(ForeignAudioSubtitles.pick(listOf(sub(3, "ita")), audioLanguage = "und", userLanguage = "ita"))
    }

    @Test
    fun forcedTracksAreNotUsedAsFullSubtitles() {
        assertNull(ForeignAudioSubtitles.pick(listOf(sub(3, "ita", forced = true)), audioLanguage = "jpn", userLanguage = "ita"))
    }
}
