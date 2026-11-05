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
import kotlin.reflect.KClass
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
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
import org.jellyfin.sdk.api.sockets.SocketApi
import org.jellyfin.sdk.model.DeviceInfo
import org.jellyfin.sdk.model.UUID
import org.jellyfin.sdk.model.api.MediaSegmentDto
import org.jellyfin.sdk.model.api.MediaSegmentDtoQueryResult
import org.jellyfin.sdk.model.api.MediaSegmentType
import org.jellyfin.sdk.model.api.MediaStreamType
import org.jellyfin.sdk.model.api.OutboundWebSocketMessage
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

/** Mocks and test data shared by the [PlaybackViewModel] tests. */
abstract class PlaybackViewModelTestBase {
    protected val testDispatcher = StandardTestDispatcher()

    protected val mockContext = mockk<Context>(relaxed = true)
    protected val mockApi = mockk<ApiClient>(relaxed = true)
    protected val mockNavigationManager = mockk<NavigationManager>(relaxed = true)
    protected val mockPlaylistCreator = mockk<PlaylistCreator>(relaxed = true)
    protected val mockItemPlaybackDao = mockk<ItemPlaybackDao>(relaxed = true)
    protected val mockServerRepository = mockk<ServerRepository>(relaxed = true)
    protected val mockItemPlaybackRepository = mockk<ItemPlaybackRepository>(relaxed = true)
    protected val mockPlayerFactory = mockk<PlayerFactory>()
    protected val mockDatePlayedService = mockk<DatePlayedService>(relaxed = true)
    protected val mockDeviceInfo = mockk<DeviceInfo>(relaxed = true)
    protected val mockDeviceProfileService = mockk<DeviceProfileService>(relaxed = true)
    protected val mockRefreshRateService = mockk<RefreshRateService>(relaxed = true)
    protected val mockStreamChoiceService = mockk<StreamChoiceService>(relaxed = true)
    protected val mockUserPreferencesService = mockk<UserPreferencesService>(relaxed = true)
    protected val mockImageUrlService = mockk<ImageUrlService>(relaxed = true)
    protected val mockScreensaverService = mockk<ScreensaverService>(relaxed = true)

    protected val mockUserLibraryApi = mockk<UserLibraryApi>()
    protected val mockMediaInfoApi = mockk<MediaInfoApi>()
    protected val mockVideosApi = mockk<VideosApi>()
    protected val mockMediaSegmentsApi = mockk<MediaSegmentsApi>()
    protected val mockPlayer = mockk<Player>(relaxed = true)

    fun create(destination: Destination): PlaybackViewModel =
        PlaybackViewModel(
            context = mockContext,
            api = mockApi,
            navigationManager = mockNavigationManager,
            playlistCreator = mockPlaylistCreator,
            itemPlaybackDao = mockItemPlaybackDao,
            serverRepository = mockServerRepository,
            itemPlaybackRepository = mockItemPlaybackRepository,
            playerFactory = mockPlayerFactory,
            datePlayedService = mockDatePlayedService,
            deviceInfo = mockDeviceInfo,
            deviceProfileService = mockDeviceProfileService,
            refreshRateService = mockRefreshRateService,
            streamChoiceService = mockStreamChoiceService,
            userPreferencesService = mockUserPreferencesService,
            imageUrlService = mockImageUrlService,
            screensaverService = mockScreensaverService,
            playbackJournal = mockk(relaxed = true),
            plateaProfiles = mockk(relaxed = true),
            nasSettings = mockk { io.mockk.every { current } returns com.github.gionnio.platea.nas.NasConfig() },
            destination = destination,
        )

    protected fun setupPreferences(block: PlaybackPreferences.Builder.() -> Unit) {
        val appPrefs = AppPreferences.getDefaultInstance().updatePlaybackPreferences(block)
        val prefs = UserPreferences(appPrefs, null)
        coEvery { mockUserPreferencesService.getCurrent() } returns prefs
    }

    protected val serverId = UUID.randomUUID()
    protected val userId = UUID.randomUUID()
    protected val server =
        JellyfinServer(serverId, "test server", "http://localhost:8096", "10.11.11")
    protected val user =
        JellyfinUser(
            rowId = 1,
            id = userId,
            serverId = serverId,
            name = "test-user",
            accessToken = "token",
            pin = "1234",
        )
    protected val userDto =
        UserDto(
            id = userId,
            name = "test-user",
            serverName = "test server",
            hasPassword = true,
            hasConfiguredPassword = true,
            hasConfiguredEasyPassword = false,
        )

    protected val mediaSource =
        TestTracks
            .Builder()
            .addVideo()
            .addAudio()
            .addSubtitle()
            .buildMediaSourceInfo()

    protected val playSessionId = "playsessionid12345"
    protected val videoStreamUrl = "http://localhost:8096/video/stream"

    @OptIn(ExperimentalCoroutinesApi::class)
    @Before
    fun setUp() {
        PlateaDispatchers.configure(testDispatcher)

        every { mockApi.userLibraryApi } returns mockUserLibraryApi
        every { mockApi.mediaInfoApi } returns mockMediaInfoApi
        every { mockApi.videosApi } returns mockVideosApi
        every { mockApi.mediaSegmentsApi } returns mockMediaSegmentsApi
        // The command stream stays open and silent: a stream that ends would be reopened forever by the tests' clock
        every { mockApi.webSocket } returns
            mockk<SocketApi> {
                every { subscribe(any<KClass<OutboundWebSocketMessage>>()) } returns flow { awaitCancellation() }
            }

        coEvery { mockMediaInfoApi.getPostedPlaybackInfo(any(), any()) } returns
            successResponse(
                PlaybackInfoResponse(
                    mediaSources = listOf(mediaSource),
                    playSessionId = playSessionId,
                ),
            )

        every {
            mockVideosApi.getVideoStreamUrl(
                itemId = any(),
                mediaSourceId = mediaSource.id,
                static = true,
                tag = mediaSource.eTag,
                playSessionId = playSessionId,
            )
        } returns videoStreamUrl

        coEvery { mockServerRepository.currentUser } returns user
        coEvery { mockServerRepository.currentUserDto } returns userDto

        coEvery { mockItemPlaybackDao.getItem(user, any()) } returns null
        coEvery { mockStreamChoiceService.chooseSource(any(), any()) } returns mediaSource
        coEvery { mockStreamChoiceService.getPlaybackLanguageChoice(any()) } returns null
        coEvery { mockPlayerFactory.createVideoPlayer(any(), any()) } returns
            PlayerCreation(mockPlayer)
        every { mockPlayerFactory.createMediaSession(any()) } returns mockk(relaxed = true)

        coEvery {
            mockStreamChoiceService.chooseAudioStream(
                source = mediaSource,
                any(),
                any(),
                any(),
                any(),
            )
        } returns mediaSource.mediaStreams!!.first { it.type == MediaStreamType.AUDIO }

        coEvery {
            mockStreamChoiceService.chooseSubtitleStream(
                source = mediaSource,
                any(),
                any(),
                any(),
                any(),
                any(),
            )
        } returns mediaSource.mediaStreams!!.first { it.type == MediaStreamType.SUBTITLE }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @After
    fun tearDown() {
        PlateaDispatchers.reset()
    }

    // Test data
    val playlist = playlist()
    val movie = movie(name = "Test Movie 1")
    val movie2 = movie(name = "Test Movie 2")
    val movie3 = movie(name = "Test Movie 3")
    val intro = movie(name = "Test Intro")
    val playlistItems =
        Playlist.fromMedia(listOf(BaseItem(movie), BaseItem(movie2), BaseItem(movie3)))
    val introSegmentMovie2 =
        MediaSegmentDto(
            id = UUID.randomUUID(),
            itemId = movie2.id,
            type = MediaSegmentType.INTRO,
            startTicks = 5.seconds.inWholeTicks,
            endTicks = 10.seconds.inWholeTicks,
        )
    val segments =
        MediaSegmentDtoQueryResult(
            items = listOf(introSegmentMovie2),
            totalRecordCount = 1,
            startIndex = 0,
        )
    // END test data

    /**
     * Create the [PlaybackViewModel] and wait for its init job to complete
     *
     * Throws an exception if the job throws one
     */
    @OptIn(InternalCoroutinesApi::class)
    protected suspend fun TestScope.createViewModel(destination: Destination): PlaybackViewModel {
        val viewModel = create(destination)
        testScheduler.advanceUntilIdle()
        viewModel.initJob.join()
        viewModel.initJob
            .getCancellationException()
            .cause
            ?.let { throw it }
        return viewModel
    }

    protected fun setupForPlaylist() {
        coEvery { mockUserLibraryApi.getItem(playlist.id, any()) } returns
            successResponse(playlist)
        coEvery {
            mockPlaylistCreator.createFrom(
                item = playlist,
                startIndex = any(),
                sortAndDirection = any(),
                shuffled = any(),
                recursive = any(),
                filter = any(),
            )
        } returns PlaylistCreationResult.Success(playlistItems)
    }
}
