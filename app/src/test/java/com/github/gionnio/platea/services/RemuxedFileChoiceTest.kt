package com.github.gionnio.platea.services

import com.github.gionnio.platea.data.PlaybackLanguageChoiceDao
import com.github.gionnio.platea.data.ServerRepository
import com.github.gionnio.platea.data.model.ItemPlayback
import com.github.gionnio.platea.data.model.JellyfinUserPreferences
import com.github.gionnio.platea.preferences.AppPreferences
import com.github.gionnio.platea.preferences.DefaultUserConfiguration
import com.github.gionnio.platea.preferences.SubtitleModePreference
import com.github.gionnio.platea.preferences.UserPreferences
import com.github.gionnio.platea.preferences.UserProfileSettings
import io.mockk.every
import io.mockk.mockk
import org.jellyfin.sdk.model.UUID
import org.jellyfin.sdk.model.api.MediaStream
import org.jellyfin.sdk.model.api.MediaStreamType
import org.jellyfin.sdk.model.api.SubtitlePlaybackMode
import org.jellyfin.sdk.model.api.UserDto
import org.junit.Assert.assertEquals
import org.junit.Test

/** A file remuxed after a track was chosen: the saved track numbers point elsewhere or nowhere. */
class RemuxedFileChoiceTest {
    private val service =
        StreamChoiceService(
            mockk<ServerRepository>().also {
                every { it.currentUserDto } returns
                    UserDto(
                        id = UUID.randomUUID(),
                        hasPassword = true,
                        hasConfiguredPassword = true,
                        hasConfiguredEasyPassword = true,
                        configuration =
                            DefaultUserConfiguration.copy(
                                audioLanguagePreference = "ita",
                                subtitleMode = SubtitlePlaybackMode.ONLY_FORCED,
                                subtitleLanguagePreference = "ita",
                            ),
                    )
            },
            mockk<PlaybackLanguageChoiceDao>(),
        )

    private val prefs =
        UserPreferences(
            AppPreferences.getDefaultInstance(),
            JellyfinUserPreferences(
                preferredAudioLanguage = UserProfileSettings.USE_USER_PROFILE,
                preferredSubtitleLanguage = UserProfileSettings.USE_USER_PROFILE,
                subtitleMode = SubtitleModePreference.USE_USER_PROFILE,
            ),
        )

    private fun saved(
        audio: Int = -1,
        subtitle: Int = -1,
    ) = ItemPlayback(rowId = 1, userId = 1, itemId = UUID.randomUUID(), sourceId = UUID.randomUUID(), audioIndex = audio, subtitleIndex = subtitle)

    private fun audio(
        index: Int,
        lang: String,
    ) = MediaStream(
        type = MediaStreamType.AUDIO,
        language = lang,
        isDefault = false,
        isForced = false,
        isHearingImpaired = false,
        isInterlaced = false,
        index = index,
        isExternal = false,
        isTextSubtitleStream = false,
        supportsExternalStream = false,
    )

    // Superman after the remux: 0 video, 1 Italian AC3, 2 Italian forced subtitles
    private val audioAfterRemux = listOf(audio(1, "ita"))
    private val subtitlesAfterRemux = listOf(subtitle(2, "ita", title = "Forced"))

    @Test
    fun savedAudioThatNoLongerExistsFallsBackToTheLanguageRule() {
        // Before the remux the Italian audio was track 3
        val chosen = service.chooseAudioStream(audioAfterRemux, saved(audio = 3), null, prefs)
        assertEquals(1, chosen?.index)
    }

    @Test
    fun savedAudioThatStillExistsIsKept() {
        val tracks = listOf(audio(1, "eng"), audio(2, "ita"))
        assertEquals(1, service.chooseAudioStream(tracks, saved(audio = 1), null, prefs)?.index)
    }

    @Test
    fun savedSubtitleThatNoLongerExistsFallsBackToTheModeRule() {
        val chosen = service.chooseSubtitleStream("ita", subtitlesAfterRemux, saved(subtitle = 7), null, prefs)
        assertEquals(2, chosen?.index)
    }

    @Test
    fun forcedTrackIsRecognisedByItsTitleWhenTheFlagIsMissing() {
        val chosen = service.chooseSubtitleStream("ita", subtitlesAfterRemux, null, null, prefs)
        assertEquals(2, chosen?.index)
    }
}
