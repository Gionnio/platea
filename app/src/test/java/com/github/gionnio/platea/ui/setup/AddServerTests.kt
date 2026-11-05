package com.github.gionnio.platea.ui.setup

import android.content.Context
import com.github.gionnio.platea.data.JellyfinServerDao
import com.github.gionnio.platea.data.ServerRepository
import com.github.gionnio.platea.data.model.JellyfinServer
import com.github.gionnio.platea.services.SetupDestination
import com.github.gionnio.platea.services.SetupNavigationManager
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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.jellyfin.sdk.Jellyfin
import org.jellyfin.sdk.discovery.DiscoveryService
import org.jellyfin.sdk.discovery.RecommendedServerInfo
import org.jellyfin.sdk.discovery.RecommendedServerInfoScore
import org.jellyfin.sdk.discovery.RecommendedServerIssue
import org.jellyfin.sdk.model.api.PublicSystemInfo
import org.junit.After
import org.junit.Assert
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.UUID

/**
 * Adding a server from the Platea access page: the page hands the typed or discovered address to
 * [SwitchServerViewModel.addServer] and shows what its state says.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30])
class AddServerTests {
    private val testDispatcher = StandardTestDispatcher()
    private val context = mockk<Context>(relaxed = true)
    private val discovery = mockk<DiscoveryService>()
    private val jellyfin = mockk<Jellyfin> { every { discovery } returns this@AddServerTests.discovery }
    private val serverRepository = mockk<ServerRepository>(relaxed = true)
    private val navigation = mockk<SetupNavigationManager>(relaxed = true)

    private val viewModel by lazy {
        SwitchServerViewModel(context, jellyfin, serverRepository, mockk<JellyfinServerDao>(relaxed = true), navigation)
    }

    @Before
    fun setUp() = PlateaDispatchers.configure(testDispatcher)

    @After
    fun tearDown() = PlateaDispatchers.reset()

    private fun candidate(
        id: String?,
        wizardCompleted: Boolean = true,
        score: RecommendedServerInfoScore = RecommendedServerInfoScore.GREAT,
        issues: List<RecommendedServerIssue> = emptyList(),
    ) = RecommendedServerInfo(
        address = "http://localhost:8096",
        responseTime = 50,
        score = score,
        issues = issues,
        systemInfo = Result.success(PublicSystemInfo(id = id, serverName = "Home", version = "10.11.0", startupWizardCompleted = wizardCompleted)),
    )

    @Test
    fun `A valid address is saved and opens the user list`() =
        runTest(testDispatcher) {
            val id = UUID.randomUUID()
            coEvery { discovery.getRecommendedServers("localhost") } returns listOf(candidate(id.toString()))

            viewModel.addServer("localhost", showToast = false)
            testScheduler.advanceUntilIdle()

            Assert.assertEquals(LoadingState.Success, viewModel.state.value.addServerState)
            val saved = slot<JellyfinServer>()
            coVerify(exactly = 1) { serverRepository.addAndChangeServer(capture(saved)) }
            Assert.assertEquals(id, saved.captured.id)
            Assert.assertEquals("http://localhost:8096", saved.captured.url)
            val destination = slot<SetupDestination>()
            verify(exactly = 1) { navigation.navigateTo(capture(destination)) }
            Assert.assertTrue(destination.captured is SetupDestination.UserList)
        }

    @Test
    fun `A server without an id is refused`() =
        runTest(testDispatcher) {
            coEvery { discovery.getRecommendedServers("localhost") } returns listOf(candidate(id = null))

            viewModel.addServer("localhost", showToast = false)
            testScheduler.advanceUntilIdle()

            Assert.assertTrue(viewModel.state.value.addServerState is LoadingState.Error)
            coVerify(exactly = 0) { serverRepository.addAndChangeServer(any()) }
            verify(exactly = 0) { navigation.navigateTo(any()) }
        }

    @Test
    fun `A server still in its setup wizard is refused`() =
        runTest(testDispatcher) {
            coEvery { discovery.getRecommendedServers("localhost") } returns
                listOf(candidate(UUID.randomUUID().toString(), wizardCompleted = false))

            viewModel.addServer("localhost", showToast = false)
            testScheduler.advanceUntilIdle()

            Assert.assertTrue(viewModel.state.value.addServerState is LoadingState.Error)
            coVerify(exactly = 0) { serverRepository.addAndChangeServer(any()) }
        }

    @Test
    fun `An address nobody answers at is an error`() =
        runTest(testDispatcher) {
            coEvery { discovery.getRecommendedServers("nowhere") } returns
                listOf(
                    candidate(
                        id = null,
                        score = RecommendedServerInfoScore.BAD,
                        issues = listOf(RecommendedServerIssue.MissingVersion),
                    ),
                )

            viewModel.addServer("nowhere", showToast = false)
            testScheduler.advanceUntilIdle()

            Assert.assertTrue(viewModel.state.value.addServerState is LoadingState.Error)
            verify(exactly = 0) { navigation.navigateTo(any()) }
        }

    @Test
    fun `A failure while looking for the server is an error, not a crash`() =
        runTest(testDispatcher) {
            coEvery { discovery.getRecommendedServers("localhost") } throws IOException("network down")

            viewModel.addServer("localhost", showToast = false)
            testScheduler.advanceUntilIdle()

            Assert.assertTrue(viewModel.state.value.addServerState is LoadingState.Error)
        }
}
