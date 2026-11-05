package com.github.gionnio.platea.ui.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import com.github.gionnio.platea.preferences.PlaybackPreferences
import com.github.gionnio.platea.test.TestTracks
import com.github.gionnio.platea.ui.nav.Destination
import com.github.gionnio.platea.ui.successResponse
import com.github.gionnio.platea.util.LoadingState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.verify
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.jellyfin.sdk.model.api.MediaSourceInfo
import org.jellyfin.sdk.model.api.PlayMethod
import org.jellyfin.sdk.model.api.PlaybackErrorCode
import org.jellyfin.sdk.model.api.PlaybackInfoDto
import org.jellyfin.sdk.model.api.PlaybackInfoResponse
import org.junit.Assert
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

/**
 * How a title is served (direct play, remux, conversion), what happens on a player error and on a track change.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30])
class PlaybackDecisionTests : PlaybackViewModelTestBase() {
    private val transcodeUrl = "http://localhost:8096/videos/master.m3u8"

    private fun serverAnswers(source: MediaSourceInfo?) {
        coEvery { mockMediaInfoApi.getPostedPlaybackInfo(any(), any()) } returns
            successResponse(
                PlaybackInfoResponse(
                    mediaSources = listOfNotNull(source),
                    playSessionId = playSessionId,
                ),
            )
        every { mockApi.createUrl(any(), any(), any(), any()) } returns transcodeUrl
    }

    private suspend fun TestScope.start(block: PlaybackPreferences.Builder.() -> Unit = {}): PlaybackViewModel {
        setupPreferences(block)
        coEvery { mockUserLibraryApi.getItem(movie.id) } returns successResponse(movie)
        return createViewModel(Destination.Playback(movie.id, 0L))
    }

    private fun playedItem(): MediaItem {
        val items = mutableListOf<MediaItem>()
        verify { mockPlayer.setMediaItem(capture(items), any<Long>()) }
        return items.last()
    }

    private fun ioError() = PlaybackException("read failed", null, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)

    private fun decoderError() = PlaybackException("decoder failed", null, PlaybackException.ERROR_CODE_DECODER_INIT_FAILED)

    @Test
    fun `Direct play uses the file as it is`() =
        runTest(testDispatcher) {
            val viewModel = start()

            Assert.assertEquals(videoStreamUrl, playedItem().localConfiguration?.uri.toString())
            Assert.assertEquals(PlayMethod.DIRECT_PLAY, viewModel.state.value.currentPlayback?.playMethod)
            Assert.assertEquals(LoadingState.Success, viewModel.state.value.loading)
            verify(exactly = 1) { mockPlayer.prepare() }
            verify(exactly = 1) { mockPlayer.play() }
        }

    @Test
    fun `Remux when the server cannot send the file as it is`() =
        runTest(testDispatcher) {
            serverAnswers(mediaSource.copy(supportsDirectPlay = false, transcodingUrl = "/videos/stream.mkv"))

            val viewModel = start()

            Assert.assertEquals(transcodeUrl, playedItem().localConfiguration?.uri.toString())
            Assert.assertEquals(PlayMethod.DIRECT_STREAM, viewModel.state.value.currentPlayback?.playMethod)
        }

    @Test
    fun `Conversion is played as an HLS stream`() =
        runTest(testDispatcher) {
            serverAnswers(
                mediaSource.copy(
                    supportsDirectPlay = false,
                    supportsDirectStream = false,
                    transcodingUrl = "/videos/master.m3u8",
                    container = "hls",
                ),
            )

            val viewModel = start()

            Assert.assertEquals(MimeTypes.APPLICATION_M3U8, playedItem().localConfiguration?.mimeType)
            Assert.assertEquals(PlayMethod.TRANSCODE, viewModel.state.value.currentPlayback?.playMethod)
        }

    @Test
    fun `Server refusal is shown as an error`() =
        runTest(testDispatcher) {
            coEvery { mockMediaInfoApi.getPostedPlaybackInfo(any(), any()) } returns
                successResponse(
                    PlaybackInfoResponse(
                        mediaSources = emptyList(),
                        errorCode = PlaybackErrorCode.NOT_ALLOWED,
                    ),
                )

            val viewModel = start()

            Assert.assertTrue(viewModel.state.value.loading is LoadingState.Error)
            verify(exactly = 0) { mockPlayer.setMediaItem(any(), any<Long>()) }
        }

    @Test
    fun `No stream address is shown as an error`() =
        runTest(testDispatcher) {
            serverAnswers(mediaSource.copy(supportsDirectPlay = false, transcodingUrl = null))

            val viewModel = start()

            Assert.assertTrue(viewModel.state.value.loading is LoadingState.Error)
        }

    @Test
    fun `Read error retries direct play without asking the server again`() =
        runTest(testDispatcher) {
            val viewModel = start()

            viewModel.onPlayerError(ioError())
            testScheduler.advanceUntilIdle()

            verify(exactly = 2) { mockPlayer.prepare() }
            coVerify(exactly = 1) { mockMediaInfoApi.getPostedPlaybackInfo(any(), any()) }
            Assert.assertEquals(LoadingState.Success, viewModel.state.value.loading)
        }

    @Test
    fun `Repeated read errors end in the error, never in server conversion`() =
        runTest(testDispatcher) {
            val viewModel = start()

            repeat(3) {
                viewModel.onPlayerError(ioError())
                testScheduler.advanceUntilIdle()
                Assert.assertEquals(LoadingState.Success, viewModel.state.value.loading)
            }
            viewModel.onPlayerError(ioError())
            testScheduler.advanceUntilIdle()

            // A slow source stays slow when the server converts it: only the first request, then the error
            coVerify(exactly = 1) { mockMediaInfoApi.getPostedPlaybackInfo(any(), any()) }
            Assert.assertTrue(viewModel.state.value.loading is LoadingState.Error)
        }

    @Test
    fun `Decoder error goes to server conversion at once`() =
        runTest(testDispatcher) {
            val viewModel = start()

            viewModel.onPlayerError(decoderError())
            testScheduler.advanceUntilIdle()

            coVerify(exactly = 1) { mockMediaInfoApi.getPostedPlaybackInfo(any(), match { it.enableDirectPlay == false }) }
        }

    @Test
    fun `Decoder error is shown when automatic conversion is off`() =
        runTest(testDispatcher) {
            val viewModel = start { overrides = overrides.toBuilder().setPlateaNoAutoTranscode(true).build() }

            viewModel.onPlayerError(decoderError())
            testScheduler.advanceUntilIdle()

            coVerify(exactly = 1) { mockMediaInfoApi.getPostedPlaybackInfo(any(), any()) }
            Assert.assertTrue(viewModel.state.value.loading is LoadingState.Error)
        }

    @Test
    fun `Error during conversion is shown`() =
        runTest(testDispatcher) {
            serverAnswers(mediaSource.copy(supportsDirectPlay = false, supportsDirectStream = false, transcodingUrl = "/videos/master.m3u8"))
            val viewModel = start()

            viewModel.onPlayerError(decoderError())
            testScheduler.advanceUntilIdle()

            Assert.assertTrue(viewModel.state.value.loading is LoadingState.Error)
            coVerify(exactly = 1) { mockMediaInfoApi.getPostedPlaybackInfo(any(), any()) }
        }

    @Test
    fun `Audio change in direct play does not restart the stream`() =
        runTest(testDispatcher) {
            val tracks = TestTracks.fromMediaSourceInfo(mediaSource).buildForExoPlayer().toTracks()
            every { mockPlayer.currentTracks } returns tracks
            every { mockPlayer.trackSelectionParameters } returns TrackSelectionParameters.Builder().build()
            coEvery { mockStreamChoiceService.resolveSubtitleIndex(any(), any(), any(), any(), any()) } returns 2
            val viewModel = start()

            viewModel.changeAudioStream(1)
            testScheduler.advanceUntilIdle()

            coVerify(exactly = 1) { mockMediaInfoApi.getPostedPlaybackInfo(any(), any()) }
            verify(exactly = 1) { mockPlayer.setMediaItem(any(), any<Long>()) }
            verify(atLeast = 1) { mockPlayer.trackSelectionParameters = any() }
            Assert.assertEquals(1, viewModel.state.value.currentPlayback?.audioIndex)
        }

    @Test
    fun `Track the player does not have is asked to the server`() =
        runTest(testDispatcher) {
            every { mockPlayer.currentTracks } returns Tracks.EMPTY
            every { mockPlayer.trackSelectionParameters } returns TrackSelectionParameters.Builder().build()
            coEvery { mockStreamChoiceService.resolveSubtitleIndex(any(), any(), any(), any(), any()) } returns 2
            val viewModel = start()

            viewModel.changeAudioStream(1)
            testScheduler.advanceUntilIdle()

            coVerify(exactly = 2) { mockMediaInfoApi.getPostedPlaybackInfo(any(), match { it.audioStreamIndex == 1 }) }
        }

    @Test
    fun `Server down at start shows an error instead of loading forever`() =
        runTest(testDispatcher) {
            setupPreferences { }
            coEvery { mockUserLibraryApi.getItem(movie.id) } throws IOException("connection refused")

            val viewModel = createViewModel(Destination.Playback(movie.id, 0L))

            Assert.assertTrue(viewModel.state.value.loading is LoadingState.Error)
        }

    @Test
    fun `Server down while asking how to play shows an error`() =
        runTest(testDispatcher) {
            coEvery { mockMediaInfoApi.getPostedPlaybackInfo(any(), any()) } throws IOException("timeout")

            val viewModel = start()

            Assert.assertTrue(viewModel.state.value.loading is LoadingState.Error)
        }

    @Test
    fun `Answer without a file is shown as an error`() =
        runTest(testDispatcher) {
            serverAnswers(null)

            val viewModel = start()

            Assert.assertTrue(viewModel.state.value.loading is LoadingState.Error)
        }

    @Test
    fun `Retry after a failed start plays the title`() =
        runTest(testDispatcher) {
            coEvery { mockMediaInfoApi.getPostedPlaybackInfo(any(), any()) } throws IOException("timeout")
            val viewModel = start()
            Assert.assertTrue(viewModel.state.value.loading is LoadingState.Error)

            serverAnswers(mediaSource)
            viewModel.retry()
            testScheduler.advanceUntilIdle()

            Assert.assertEquals(LoadingState.Success, viewModel.state.value.loading)
            Assert.assertEquals(movie.id.toString(), playedItem().mediaId)
        }

    @Test
    fun `A queue that cannot be built does not stop the title`() =
        runTest(testDispatcher) {
            coEvery { mockPlaylistCreator.createFrom(any(), any(), any(), any(), any(), any()) } throws IOException("timeout")

            val viewModel = start()

            Assert.assertEquals(LoadingState.Success, viewModel.state.value.loading)
            verify(exactly = 1) { mockPlayer.play() }
        }

    @Test
    fun `Read error during conversion is retried before the error is shown`() =
        runTest(testDispatcher) {
            serverAnswers(mediaSource.copy(supportsDirectPlay = false, supportsDirectStream = false, transcodingUrl = "/videos/master.m3u8"))
            val viewModel = start()

            viewModel.onPlayerError(ioError())
            testScheduler.advanceUntilIdle()
            Assert.assertEquals(LoadingState.Success, viewModel.state.value.loading)
            verify(exactly = 2) { mockPlayer.prepare() }

            repeat(3) {
                viewModel.onPlayerError(ioError())
                testScheduler.advanceUntilIdle()
            }
            Assert.assertTrue(viewModel.state.value.loading is LoadingState.Error)
        }

    @Test
    fun `An end long before the duration resumes the title instead of finishing it`() =
        runTest(testDispatcher) {
            val viewModel = start()
            io.mockk.every { mockPlayer.currentPosition } returns 120_000L
            io.mockk.every { mockPlayer.duration } returns 1_200_000L

            viewModel.onPlaybackStateChanged(androidx.media3.common.Player.STATE_ENDED)
            testScheduler.advanceUntilIdle()

            verify { mockPlayer.seekTo(120_000L) }
            Assert.assertNull(viewModel.state.value.nextUp)
        }
}
