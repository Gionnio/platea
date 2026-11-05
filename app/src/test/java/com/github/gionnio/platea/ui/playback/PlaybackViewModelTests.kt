package com.github.gionnio.platea.ui.playback

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.github.gionnio.platea.data.ItemPlaybackDao
import com.github.gionnio.platea.data.ItemPlaybackRepository
import com.github.gionnio.platea.data.ServerRepository
import com.github.gionnio.platea.data.model.BaseItem
import com.github.gionnio.platea.data.model.JellyfinServer
import com.github.gionnio.platea.data.model.JellyfinUser
import com.github.gionnio.platea.data.model.Playlist
import com.github.gionnio.platea.data.model.PlaylistItem
import com.github.gionnio.platea.preferences.AppPreferences
import com.github.gionnio.platea.preferences.PlaybackPreferences
import com.github.gionnio.platea.preferences.ShowNextUpWhen
import com.github.gionnio.platea.preferences.SkipSegmentBehavior
import com.github.gionnio.platea.preferences.UserPreferences
import com.github.gionnio.platea.preferences.updatePlaybackPreferences
import com.github.gionnio.platea.services.DatePlayedService
import com.github.gionnio.platea.services.DeviceProfileService
import com.github.gionnio.platea.services.ImageUrlService
import com.github.gionnio.platea.services.NavigationManager
import com.github.gionnio.platea.services.PlayerCreation
import com.github.gionnio.platea.services.PlayerFactory
import com.github.gionnio.platea.services.PlaylistCreationResult
import com.github.gionnio.platea.services.PlaylistCreator
import com.github.gionnio.platea.services.RefreshRateService
import com.github.gionnio.platea.services.ScreensaverService
import com.github.gionnio.platea.services.StreamChoiceService
import com.github.gionnio.platea.services.UserPreferencesService
import com.github.gionnio.platea.test.TestTracks
import com.github.gionnio.platea.test.movie
import com.github.gionnio.platea.test.playlist
import com.github.gionnio.platea.ui.nav.Destination
import com.github.gionnio.platea.ui.successQueryResult
import com.github.gionnio.platea.ui.successResponse
import com.github.gionnio.platea.util.LoadingState
import com.github.gionnio.platea.util.PlateaDispatchers
import com.github.gionnio.platea.util.configure
import com.github.gionnio.platea.util.reset
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.mockk.verifyOrder
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.mediaInfoApi
import org.jellyfin.sdk.api.client.extensions.mediaSegmentsApi
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.api.client.extensions.videosApi
import org.jellyfin.sdk.api.operations.MediaInfoApi
import org.jellyfin.sdk.api.operations.MediaSegmentsApi
import org.jellyfin.sdk.api.operations.UserLibraryApi
import org.jellyfin.sdk.api.operations.VideosApi
import org.jellyfin.sdk.model.DeviceInfo
import org.jellyfin.sdk.model.UUID
import org.jellyfin.sdk.model.api.MediaSegmentDto
import org.jellyfin.sdk.model.api.MediaSegmentDtoQueryResult
import org.jellyfin.sdk.model.api.MediaSegmentType
import org.jellyfin.sdk.model.api.MediaStreamType
import org.jellyfin.sdk.model.api.PlaybackInfoResponse
import org.jellyfin.sdk.model.api.UserDto
import org.jellyfin.sdk.model.extensions.inWholeTicks
import org.jellyfin.sdk.model.extensions.ticks
import org.junit.After
import org.junit.Assert
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30])
class PlaybackViewModelTests : PlaybackViewModelTestBase() {
    @Test
    fun `Play intro first`() =
        runTest(testDispatcher) {
            setupPreferences {
                cinemaMode = true
            }
            coEvery { mockUserLibraryApi.getItem(movie.id) } returns successResponse(movie)
            coEvery { mockUserLibraryApi.getIntros(movie.id, any()) } returns
                successQueryResult(listOf(intro))

            val viewModel = createViewModel(Destination.Playback(movie.id, 0L))

            val mediaItem = slot<MediaItem>()
            verify(exactly = 1) { mockPlayer.setMediaItem(capture(mediaItem), any<Long>()) }
            // Should be playing the intro
            Assert.assertEquals(intro.id.toString(), mediaItem.captured.mediaId)
            val state = viewModel.state.value
            Assert.assertEquals(LoadingState.Success, state.loading)
            Assert.assertEquals(mediaSource.id, state.currentMediaInfo.sourceId)
        }

    @Test
    fun `Play two intros first`() =
        runTest(testDispatcher) {
            val movie = movie()
            val intro = movie()
            val intro2 = movie()
            setupPreferences {
                cinemaMode = true
            }
            coEvery { mockUserLibraryApi.getItem(movie.id) } returns successResponse(movie)
            coEvery { mockUserLibraryApi.getIntros(movie.id, any()) } returns
                successQueryResult(listOf(intro, intro2))

            val viewModel = createViewModel(Destination.Playback(movie.id, 0L))

            val mediaItem = slot<MediaItem>()
            verify(exactly = 1) { mockPlayer.setMediaItem(capture(mediaItem), any<Long>()) }
            // Should be playing the first intro
            Assert.assertEquals(intro.id.toString(), mediaItem.captured.mediaId)
            val state = viewModel.state.value
            Assert.assertEquals(LoadingState.Success, state.loading)
            Assert.assertEquals(mediaSource.id, state.currentMediaInfo.sourceId)
            val playlist = state.playlist.items
            Assert.assertEquals(2, playlist.size)
            Assert.assertEquals(intro.id, playlist[0].id)
            Assert.assertEquals(intro2.id, playlist[1].id)
        }

    @Test
    fun `Don't play intro`() =
        runTest(testDispatcher) {
            val movie = movie()
            setupPreferences {
                cinemaMode = false
            }
            coEvery { mockUserLibraryApi.getItem(movie.id) } returns successResponse(movie)

            val viewModel = createViewModel(Destination.Playback(movie.id, 0L))

            coVerify(exactly = 0) { mockUserLibraryApi.getIntros(any(), any()) }
            val mediaItem = slot<MediaItem>()
            verify(exactly = 1) { mockPlayer.setMediaItem(capture(mediaItem), any<Long>()) }
            Assert.assertEquals(movie.id.toString(), mediaItem.captured.mediaId)
            viewModel.state.value.also { state ->
                Assert.assertEquals(LoadingState.Success, state.loading)
                Assert.assertEquals(mediaSource.id, state.currentMediaInfo.sourceId)
            }
        }

    @Test
    fun `Play next up`() =
        runTest(testDispatcher) {
            setupForPlaylist()

            val viewModel = createViewModel(Destination.PlaybackList(playlist.id))

            viewModel.state.value.also { state ->
                Assert.assertEquals(playlistItems.items, state.playlist.items)
                Assert.assertEquals(0, state.playlistIndex)
            }

            viewModel.playNextUp()
            testScheduler.advanceUntilIdle()

            viewModel.state.value.also { state ->
                Assert.assertEquals(1, state.playlistIndex)
            }

            val mediaItem = slot<MediaItem>()
            val mediaItem2 = slot<MediaItem>()
            verifyOrder {
                mockPlayer.setMediaItem(capture(mediaItem), any<Long>())
                mockPlayer.setMediaItem(capture(mediaItem2), any<Long>())
            }
            Assert.assertEquals(movie.id.toString(), mediaItem.captured.mediaId)
            Assert.assertEquals(movie2.id.toString(), mediaItem2.captured.mediaId)
        }

    @Test
    fun `Play previous`() =
        runTest(testDispatcher) {
            setupForPlaylist()

            val viewModel = createViewModel(Destination.PlaybackList(playlist.id))

            viewModel.state.value.also { state ->
                Assert.assertEquals(playlistItems.items, state.playlist.items)
                Assert.assertEquals(0, state.playlistIndex)
            }

            viewModel.playNextUp()
            testScheduler.advanceUntilIdle()
            viewModel.playNextUp()
            testScheduler.advanceUntilIdle()

            viewModel.state.value.also { state ->
                Assert.assertEquals(2, state.playlistIndex)
            }

            viewModel.playPrevious()
            testScheduler.advanceUntilIdle()

            viewModel.state.value.also { state ->
                Assert.assertEquals(1, state.playlistIndex)
            }

            val mediaItems = mutableListOf<MediaItem>()
            verify(exactly = 4) {
                mockPlayer.setMediaItem(withArg { mediaItems.add(it) }, any<Long>())
            }
            Assert.assertEquals(4, mediaItems.size)
            Assert.assertEquals(movie.id.toString(), mediaItems[0].mediaId)
            Assert.assertEquals(movie2.id.toString(), mediaItems[1].mediaId)
            Assert.assertEquals(movie3.id.toString(), mediaItems[2].mediaId)
            Assert.assertEquals(movie2.id.toString(), mediaItems[3].mediaId)
        }

    @OptIn(InternalCoroutinesApi::class)
    @Test
    fun `Show next up at end of playback`() =
        runTest(testDispatcher) {
            setupForPlaylist()
            setupPreferences {
                showNextUpWhen = ShowNextUpWhen.END_OF_PLAYBACK
            }

            val viewModel = createViewModel(Destination.PlaybackList(playlist.id))

            viewModel.state.value.also { state ->
                Assert.assertEquals(playlistItems.items, state.playlist.items)
                Assert.assertEquals(0, state.playlistIndex)
                Assert.assertNull(state.nextUp)
            }

            viewModel.onPlaybackStateChanged(Player.STATE_ENDED)
            testScheduler.advanceUntilIdle()

            viewModel.state.value.also { state ->
                Assert.assertEquals(movie2, state.nextUp?.data)
            }
        }

    @Test
    fun `Show next up never with automatic play`() =
        runTest(testDispatcher) {
            setupForPlaylist()
            setupPreferences {
                showNextUpWhen = ShowNextUpWhen.NEXT_UP_NEVER
                autoPlayNext = true
            }

            val viewModel = createViewModel(Destination.PlaybackList(playlist.id))

            viewModel.state.value.also { state ->
                Assert.assertEquals(playlistItems.items, state.playlist.items)
                Assert.assertEquals(0, state.playlistIndex)
                Assert.assertNull(state.nextUp)
            }

            viewModel.onPlaybackStateChanged(Player.STATE_ENDED)
            testScheduler.advanceUntilIdle()

            viewModel.state.value.also { state ->
                Assert.assertNull(state.nextUp)
                Assert.assertTrue(state.hasNext)
            }
            verify(exactly = 0) { mockNavigationManager.goBack() }
        }

    @Test
    fun `Show next up never without automatic play`() =
        runTest(testDispatcher) {
            setupForPlaylist()
            setupPreferences {
                showNextUpWhen = ShowNextUpWhen.NEXT_UP_NEVER
                autoPlayNext = false
            }

            val viewModel = createViewModel(Destination.PlaybackList(playlist.id))

            viewModel.state.value.also { state ->
                Assert.assertEquals(playlistItems.items, state.playlist.items)
                Assert.assertEquals(0, state.playlistIndex)
                Assert.assertNull(state.nextUp)
            }

            viewModel.onPlaybackStateChanged(Player.STATE_ENDED)
            testScheduler.advanceUntilIdle()

            viewModel.state.value.also { state ->
                Assert.assertNull(state.nextUp)
                Assert.assertTrue(state.hasNext)
            }
            verify(exactly = 1) { mockNavigationManager.goBack() }
        }

    @Test
    fun `Playback ends with no next up`() =
        runTest(testDispatcher) {
            setupForPlaylist()
            setupPreferences {
                showNextUpWhen = ShowNextUpWhen.END_OF_PLAYBACK
                autoPlayNext = false
            }

            val viewModel = createViewModel(Destination.PlaybackList(playlist.id))

            viewModel.state.value.also { state ->
                Assert.assertEquals(playlistItems.items, state.playlist.items)
                Assert.assertEquals(0, state.playlistIndex)
                Assert.assertNull(state.nextUp)
            }
            viewModel.playNextUp()
            viewModel.playNextUp()
            testScheduler.advanceUntilIdle()

            viewModel.onPlaybackStateChanged(Player.STATE_ENDED)
            testScheduler.advanceUntilIdle()

            viewModel.state.value.also { state ->
                Assert.assertNull(state.nextUp)
                Assert.assertFalse(state.hasNext)
            }
            verify(exactly = 1) { mockNavigationManager.goBack() }
        }

    @Test
    fun `Intro playback ends always play next`() =
        runTest(testDispatcher) {
            setupPreferences {
                cinemaMode = true
                showNextUpWhen = ShowNextUpWhen.NEXT_UP_NEVER
                autoPlayNext = false
            }
            setupForPlaylist()
            coEvery { mockUserLibraryApi.getItem(movie.id) } returns successResponse(movie)
            coEvery { mockUserLibraryApi.getIntros(movie.id, any()) } returns
                successQueryResult(listOf(intro))

            val viewModel = createViewModel(Destination.PlaybackList(playlist.id))

            viewModel.state.value.also { state ->
                Assert.assertEquals(
                    listOf(PlaylistItem.Intro(BaseItem(intro))) + playlistItems.items,
                    state.playlist.items,
                )
                Assert.assertEquals(0, state.playlistIndex)
                Assert.assertNull(state.nextUp)
            }

            viewModel.onPlaybackStateChanged(Player.STATE_ENDED)
            testScheduler.advanceUntilIdle()

            viewModel.state.value.also { state ->
                Assert.assertEquals(1, state.playlistIndex)
                Assert.assertNull(state.nextUp)
            }
            verify(exactly = 0) { mockNavigationManager.goBack() }

            val mediaItems = mutableListOf<MediaItem>()
            verify(exactly = 2) {
                mockPlayer.setMediaItem(withArg { mediaItems.add(it) }, any<Long>())
            }
            Assert.assertEquals(2, mediaItems.size)
            Assert.assertEquals(intro.id.toString(), mediaItems[0].mediaId)
            Assert.assertEquals(movie.id.toString(), mediaItems[1].mediaId)
        }

    @Test
    fun playItemInPlaylist() =
        runTest(testDispatcher) {
            setupForPlaylist()
            setupPreferences {
            }

            val viewModel = createViewModel(Destination.PlaybackList(playlist.id))

            viewModel.state.value.also { state ->
                Assert.assertEquals(playlistItems.items, state.playlist.items)
                Assert.assertEquals(0, state.playlistIndex)
                Assert.assertNull(state.nextUp)
            }

            viewModel.playItemInPlaylist(BaseItem(movie3))
            testScheduler.advanceUntilIdle()

            viewModel.state.value.also { state ->
                Assert.assertEquals(2, state.playlistIndex)
                Assert.assertNull(state.nextUp)
            }
            val mediaItem = slot<MediaItem>()
            val mediaItem2 = slot<MediaItem>()
            verifyOrder {
                mockPlayer.setMediaItem(capture(mediaItem), any<Long>())
                mockPlayer.setMediaItem(capture(mediaItem2), any<Long>())
            }
            Assert.assertEquals(movie.id.toString(), mediaItem.captured.mediaId)
            Assert.assertEquals(movie3.id.toString(), mediaItem2.captured.mediaId)
        }

    @Test
    fun `Media segments auto skip`() =
        runTest(testDispatcher, timeout = 8.seconds) {
            setupForPlaylist()
            setupPreferences {
                skipIntros = SkipSegmentBehavior.AUTO_SKIP
            }

            coEvery { mockMediaSegmentsApi.getItemSegments(movie.id) } returns
                successResponse(MediaSegmentDtoQueryResult(emptyList(), 0, 0))
            coEvery { mockMediaSegmentsApi.getItemSegments(movie2.id) } returns
                successResponse(segments)

            val viewModel = createViewModel(Destination.PlaybackList(playlist.id))

            viewModel.state.value.also { state ->
                Assert.assertNull(state.currentSegment)
            }

            every { mockPlayer.currentPosition } returns 5.5.seconds.inWholeMilliseconds

            try {
                // TODO better testability
                // This will start an infinite loop
                viewModel.playNextUp()
                testScheduler.advanceTimeBy(1500.milliseconds)

                viewModel.state.value.also { state ->
                    Assert.assertEquals(introSegmentMovie2, state.currentSegment?.segment)
                    Assert.assertTrue(state.currentSegment?.interacted == true)
                }
                verify(exactly = 1) {
                    // Verify only skipping a single time
                    mockPlayer.seekTo(introSegmentMovie2.endTicks.ticks.inWholeMilliseconds + 1)
                }
            } finally {
                viewModel.segmentJob?.cancel()
            }
        }

    @Test
    fun `Media segment ask to skip`() =
        runTest(testDispatcher, timeout = 8.seconds) {
            setupForPlaylist()
            setupPreferences {
                skipIntros = SkipSegmentBehavior.ASK_TO_SKIP
            }

            coEvery { mockMediaSegmentsApi.getItemSegments(movie.id) } returns
                successResponse(MediaSegmentDtoQueryResult(emptyList(), 0, 0))
            coEvery { mockMediaSegmentsApi.getItemSegments(movie2.id) } returns
                successResponse(segments)

            val viewModel = createViewModel(Destination.PlaybackList(playlist.id))

            viewModel.state.value.also { state ->
                Assert.assertNull(state.currentSegment)
            }

            every { mockPlayer.currentPosition } returns 5.5.seconds.inWholeMilliseconds

            try {
                // TODO better testability
                // This will start an infinite loop
                viewModel.playNextUp()
                testScheduler.advanceTimeBy(1000.milliseconds)

                viewModel.state.value.also { state ->
                    Assert.assertEquals(introSegmentMovie2, state.currentSegment?.segment)
                    Assert.assertTrue(state.currentSegment?.interacted == false)
                }
                verify(exactly = 0) { mockPlayer.seekTo(any()) }
            } finally {
                viewModel.segmentJob?.cancel()
            }
        }

    @Test
    fun `Media segment ask to skip and clicked`() =
        runTest(testDispatcher, timeout = 8.seconds) {
            setupForPlaylist()
            setupPreferences {
                skipIntros = SkipSegmentBehavior.ASK_TO_SKIP
            }

            coEvery { mockMediaSegmentsApi.getItemSegments(movie.id) } returns
                successResponse(MediaSegmentDtoQueryResult(emptyList(), 0, 0))
            coEvery { mockMediaSegmentsApi.getItemSegments(movie2.id) } returns
                successResponse(segments)

            val viewModel = createViewModel(Destination.PlaybackList(playlist.id))

            viewModel.state.value.also { state ->
                Assert.assertNull(state.currentSegment)
            }

            every { mockPlayer.currentPosition } returns 5.5.seconds.inWholeMilliseconds

            try {
                // TODO better testability
                // This will start an infinite loop
                viewModel.playNextUp()
                testScheduler.advanceTimeBy(1000.milliseconds)

                viewModel.state.value.also { state ->
                    Assert.assertEquals(introSegmentMovie2, state.currentSegment?.segment)
                    Assert.assertTrue(state.currentSegment?.interacted == false)
                }
                verify(exactly = 0) { mockPlayer.seekTo(any()) }

                viewModel.updateSegment(introSegmentMovie2.id, false)
                every { mockPlayer.currentPosition } returns introSegmentMovie2.endTicks.ticks.inWholeMilliseconds + 1
                testScheduler.advanceTimeBy(10.milliseconds)
                viewModel.state.value.also { state ->
                    Assert.assertNull(state.currentSegment)
                }
                verify(exactly = 1) {
                    // Verify only skipping a single time
                    mockPlayer.seekTo(introSegmentMovie2.endTicks.ticks.inWholeMilliseconds + 1)
                }
            } finally {
                viewModel.segmentJob?.cancel()
            }
        }

    @Test
    fun `Media segment ask to skip and dismissed`() =
        runTest(testDispatcher, timeout = 8.seconds) {
            setupForPlaylist()
            setupPreferences {
                skipIntros = SkipSegmentBehavior.ASK_TO_SKIP
            }

            coEvery { mockMediaSegmentsApi.getItemSegments(movie.id) } returns
                successResponse(MediaSegmentDtoQueryResult(emptyList(), 0, 0))
            coEvery { mockMediaSegmentsApi.getItemSegments(movie2.id) } returns
                successResponse(segments)

            val viewModel = createViewModel(Destination.PlaybackList(playlist.id))

            viewModel.state.value.also { state ->
                Assert.assertNull(state.currentSegment)
            }

            every { mockPlayer.currentPosition } returns 5.5.seconds.inWholeMilliseconds

            try {
                // TODO better testability
                // This will start an infinite loop
                viewModel.playNextUp()
                testScheduler.advanceTimeBy(1000.milliseconds)

                viewModel.state.value.also { state ->
                    Assert.assertEquals(introSegmentMovie2, state.currentSegment?.segment)
                    Assert.assertTrue(state.currentSegment?.interacted == false)
                }

                viewModel.updateSegment(introSegmentMovie2.id, true)
                testScheduler.advanceTimeBy(10.milliseconds)
                viewModel.state.value.also { state ->
                    Assert.assertEquals(introSegmentMovie2, state.currentSegment?.segment)
                    Assert.assertTrue(state.currentSegment?.interacted == true)
                }

                // Never skipped
                verify(exactly = 0) { mockPlayer.seekTo(any()) }
            } finally {
                viewModel.segmentJob?.cancel()
            }
        }

    @Test
    fun `Media segment ignore`() =
        runTest(testDispatcher, timeout = 8.seconds) {
            setupForPlaylist()
            setupPreferences {
                skipIntros = SkipSegmentBehavior.IGNORE
            }

            coEvery { mockMediaSegmentsApi.getItemSegments(movie.id) } returns
                successResponse(MediaSegmentDtoQueryResult(emptyList(), 0, 0))
            coEvery { mockMediaSegmentsApi.getItemSegments(movie2.id) } returns
                successResponse(segments)

            val viewModel = createViewModel(Destination.PlaybackList(playlist.id))

            viewModel.state.value.also { state ->
                Assert.assertNull(state.currentSegment)
            }

            every { mockPlayer.currentPosition } returns 5.5.seconds.inWholeMilliseconds

            try {
                // TODO better testability
                // This will start an infinite loop
                viewModel.playNextUp()
                testScheduler.advanceTimeBy(1000.milliseconds)

                viewModel.state.value.also { state ->
                    Assert.assertNull(state.currentSegment)
                }
                verify(exactly = 0) { mockPlayer.seekTo(any()) }
            } finally {
                viewModel.segmentJob?.cancel()
            }
        }

    @Test
    fun `Media segment not at current position`() =
        runTest(testDispatcher, timeout = 8.seconds) {
            setupForPlaylist()
            setupPreferences {
                skipIntros = SkipSegmentBehavior.AUTO_SKIP
            }

            coEvery { mockMediaSegmentsApi.getItemSegments(movie.id) } returns
                successResponse(MediaSegmentDtoQueryResult(emptyList(), 0, 0))
            coEvery { mockMediaSegmentsApi.getItemSegments(movie2.id) } returns
                successResponse(segments)

            val viewModel = createViewModel(Destination.PlaybackList(playlist.id))

            viewModel.state.value.also { state ->
                Assert.assertNull(state.currentSegment)
            }

            every { mockPlayer.currentPosition } returns 2.5.seconds.inWholeMilliseconds

            try {
                // TODO better testability
                // This will start an infinite loop
                viewModel.playNextUp()
                testScheduler.advanceTimeBy(1000.milliseconds)

                viewModel.state.value.also { state ->
                    Assert.assertNull(state.currentSegment)
                }
                verify(exactly = 0) { mockPlayer.seekTo(any()) }
            } finally {
                viewModel.segmentJob?.cancel()
            }
        }
}
