package com.github.gionnio.platea.test

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.datastore.core.DataStore
import com.github.gionnio.platea.MainActivityViewModel
import com.github.gionnio.platea.data.CurrentUser
import com.github.gionnio.platea.data.JellyfinServerDao
import com.github.gionnio.platea.data.ServerRepository
import com.github.gionnio.platea.data.model.JellyfinServerUsers
import com.github.gionnio.platea.preferences.AppPreferences
import com.github.gionnio.platea.preferences.update
import com.github.gionnio.platea.services.AppUpgradeHandler
import com.github.gionnio.platea.services.DeviceProfileService
import com.github.gionnio.platea.services.IntentResult
import com.github.gionnio.platea.services.IntentService
import com.github.gionnio.platea.services.NavigationManager
import com.github.gionnio.platea.services.SetupDestination
import com.github.gionnio.platea.services.SetupNavigationManager
import com.github.gionnio.platea.ui.nav.Destination
import com.github.gionnio.platea.ui.toServerString
import com.github.gionnio.platea.util.PlateaDispatchers
import com.github.gionnio.platea.util.configure
import com.github.gionnio.platea.util.reset
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.jellyfin.sdk.model.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TestMainActivityViewModel {
    private val testDispatcher = StandardTestDispatcher()

    private val context: Context = mockk()
    private val preferences: DataStore<AppPreferences> = mockk()
    private val serverRepository: ServerRepository = mockk()
    private val setupNavigationManager: SetupNavigationManager = mockk(relaxed = true)
    private val navigationManager: NavigationManager = mockk(relaxed = true)
    private val deviceProfileService: DeviceProfileService = mockk()
    private val appUpgradeHandler: AppUpgradeHandler = mockk()
    private val intentService: IntentService = mockk()
    private val serverDao: JellyfinServerDao = mockk()

    val viewModel =
        MainActivityViewModel(
            context,
            preferences,
            serverRepository,
            setupNavigationManager,
            navigationManager,
            deviceProfileService,
            appUpgradeHandler,
            intentService,
        )

    private val serverId = UUID.randomUUID()
    private val userId = UUID.randomUUID()
    private val currentUser = currentUser(serverId, userId)

    private val protectedCurrentUser =
        CurrentUser(
            server(serverId),
            user(serverId, userId).copy(pin = "1234"),
        )

    @Before
    fun setUp() {
        PlateaDispatchers.configure(testDispatcher)
        every { appUpgradeHandler.needUpgrade() } returns false
        every { serverRepository.serverDao } returns serverDao
        coEvery { serverDao.getServer(serverId) } returns
            JellyfinServerUsers(
                currentUser.server,
                // User list is unused
                emptyList(),
            )
        mockkStatic(Toast::class)
        val mockToast = mockk<Toast>()
        every { Toast.makeText(any(), any<CharSequence>(), any()) } returns mockToast
        every { mockToast.show() } returns Unit
    }

    @After
    fun tearDown() {
        PlateaDispatchers.reset()
        unmockkStatic(Toast::class)
    }

    private fun setupPreferences(block: AppPreferences.Builder.() -> Unit) {
        every { preferences.data } returns
            flow {
                emit(
                    AppPreferences.getDefaultInstance().update(block),
                )
            }
    }

    @Test
    fun `Test auto sign in with unprotected profile`() =
        runTest(testDispatcher) {
            setupPreferences {
                signInAutomatically = true
                currentServerId = serverId.toServerString()
                currentUserId = userId.toServerString()
            }
            every { serverRepository.current } returns MutableStateFlow(currentUser)
            coEvery { serverRepository.restoreSession(serverId, userId) } returns currentUser

            viewModel.appStart(null)
            advanceUntilIdle()

            val slot = slot<SetupDestination>()
            verify { setupNavigationManager.navigateTo(capture(slot)) }
            slot.captured.let { destination ->
                assertTrue("destination is ${destination::class}", destination is SetupDestination.AppContent)
                destination as SetupDestination.AppContent
                assertEquals(currentUser, destination.current)
            }
        }

    @Test
    fun `Test auto sign disabled in with current server`() =
        runTest {
            setupPreferences {
                signInAutomatically = false
                currentServerId = serverId.toServerString()
                currentUserId = userId.toServerString()
            }
            every { serverRepository.current } returns MutableStateFlow<CurrentUser?>(currentUser)
//            coEvery { serverRepository.restoreSession(serverId, userId) } returns currentUser

            viewModel.appStart(null)
            advanceUntilIdle()

            verify(exactly = 1) { serverDao.getServer(serverId) }
            coVerify(exactly = 0) { serverRepository.restoreSession(any(), any()) }
            val args = mutableListOf<SetupDestination>()
            verify { setupNavigationManager.navigateTo(capture(args)) }
            assertEquals(2, args.size)
            args[0].let { destination ->
                assertTrue("destination is ${destination::class}", destination is SetupDestination.Loading)
            }
            args[1].let { destination ->
                assertTrue("destination is ${destination::class}", destination is SetupDestination.UserList)
                destination as SetupDestination.UserList
                assertEquals(currentUser.server, destination.server)
            }
        }

    @Test
    fun `Test auto sign in, hot load, with protected profile`() =
        runTest {
            setupPreferences {
                signInAutomatically = true
                currentServerId = serverId.toServerString()
                currentUserId = userId.toServerString()
            }
            every { serverRepository.current } returns MutableStateFlow<CurrentUser?>(protectedCurrentUser)
            coEvery { serverRepository.restoreSession(serverId, userId) } returns protectedCurrentUser

            viewModel.appStart(null)
            advanceUntilIdle()

            coVerify(exactly = 0) { serverRepository.restoreSession(serverId, userId) }
            verify { serverDao.getServer(serverId) }
            val args = mutableListOf<SetupDestination>()
            verify { setupNavigationManager.navigateTo(capture(args)) }
            assertEquals(2, args.size)
            args[0].let { destination ->
                assertTrue("destination is ${destination::class}", destination is SetupDestination.Loading)
            }
            args[1].let { destination ->
                assertTrue("destination is ${destination::class}", destination is SetupDestination.UserList)
                destination as SetupDestination.UserList
                assertEquals(currentUser.server, destination.server)
            }
        }

    @Test
    fun `Test auto sign in, cold load, with protected profile`() =
        runTest {
            setupPreferences {
                signInAutomatically = true
                currentServerId = serverId.toServerString()
                currentUserId = userId.toServerString()
            }
            every { serverRepository.current } returns MutableStateFlow<CurrentUser?>(null)
            coEvery { serverRepository.restoreSession(serverId, userId) } returns protectedCurrentUser

            viewModel.appStart(null)
            advanceUntilIdle()

            coVerify(exactly = 1) { serverRepository.restoreSession(serverId, userId) }
            val slot = slot<SetupDestination>()
            verify { setupNavigationManager.navigateTo(capture(slot)) }
            slot.captured.let { destination ->
                assertTrue("destination is ${destination::class}", destination is SetupDestination.UserList)
                destination as SetupDestination.UserList
                assertEquals(currentUser.server, destination.server)
            }
        }

    @Test
    fun `Test auto sign in, no current user`() =
        runTest {
            setupPreferences {
                signInAutomatically = true
                currentServerId = ""
                currentUserId = ""
            }
            every { serverRepository.current } returns MutableStateFlow<CurrentUser?>(null)
            coEvery { serverRepository.restoreSession(null, null) } returns null

            viewModel.appStart(null)
            advanceUntilIdle()

            coVerify(exactly = 1) { serverRepository.restoreSession(null, null) }
            val slot = slot<SetupDestination>()
            verify { setupNavigationManager.navigateTo(capture(slot)) }
            slot.captured.let { destination ->
                assertTrue("destination is ${destination::class}", destination is SetupDestination.ServerList)
            }
        }

    @Test
    fun `Test intent result is noop`() =
        runTest(testDispatcher) {
            setupPreferences {
                signInAutomatically = true
                currentServerId = serverId.toServerString()
                currentUserId = userId.toServerString()
            }
            every { serverRepository.current } returns MutableStateFlow(currentUser)
            coEvery { serverRepository.restoreSession(serverId, userId) } returns currentUser
            coEvery { intentService.parseIntent(any()) } returns IntentResult.NoOp

            viewModel.appStart(Intent())
            advanceUntilIdle()

            val slot = slot<SetupDestination>()
            verify { setupNavigationManager.navigateTo(capture(slot)) }
            slot.captured.let { destination ->
                assertTrue("destination is ${destination::class}", destination is SetupDestination.AppContent)
                destination as SetupDestination.AppContent
                assertEquals(currentUser, destination.current)
            }
        }

    @Test
    fun `Test intent result is error, has server`() =
        runTest(testDispatcher) {
            setupPreferences {
                signInAutomatically = true
                currentServerId = serverId.toServerString()
                currentUserId = userId.toServerString()
            }
            every { serverRepository.current } returns MutableStateFlow(currentUser)
            coEvery { serverRepository.restoreSession(serverId, userId) } returns currentUser
            coEvery { intentService.parseIntent(any()) } returns IntentResult.Error("Error")

            viewModel.appStart(Intent())
            advanceUntilIdle()

            val slot = slot<SetupDestination>()
            verify { setupNavigationManager.navigateTo(capture(slot)) }
            slot.captured.let { destination ->
                // Platea: a link that cannot be opened keeps the user signed in (it used to go to the user list)
                assertTrue("destination is ${destination::class}", destination is SetupDestination.AppContent)
                destination as SetupDestination.AppContent
                assertEquals(currentUser.server, destination.current.server)
            }
        }

    @Test
    fun `Test intent result is error, does not have server`() =
        runTest(testDispatcher) {
            setupPreferences {
                signInAutomatically = true
                currentServerId = serverId.toServerString()
                currentUserId = userId.toServerString()
            }
            every { serverRepository.current } returns MutableStateFlow(null)
            coEvery { serverRepository.restoreSession(serverId, userId) } returns currentUser
            coEvery { intentService.parseIntent(any()) } returns IntentResult.Error("Error")

            viewModel.appStart(Intent())
            advanceUntilIdle()

            val slot = slot<SetupDestination>()
            verify { setupNavigationManager.navigateTo(capture(slot)) }
            slot.captured.let { destination ->
                assertTrue("destination is ${destination::class}", destination is SetupDestination.ServerList)
            }
        }

    @Test
    fun `Test intent result is target, has server`() =
        runTest(testDispatcher) {
            setupPreferences {
                signInAutomatically = true
                currentServerId = serverId.toServerString()
                currentUserId = userId.toServerString()
            }
            every { serverRepository.current } returns MutableStateFlow(currentUser)
            coEvery { serverRepository.restoreSession(serverId, userId) } returns currentUser
            coEvery { intentService.parseIntent(any()) } returns
                IntentResult.Target(
                    destinations = listOf(Destination.Search()),
                    addHomeToBackStack = true,
                )
            val backstack = mutableListOf<Destination>()
            every { navigationManager.backStack } returns backstack

            viewModel.appStart(Intent())
            advanceUntilIdle()

            val slot = slot<SetupDestination>()
            verify { setupNavigationManager.navigateTo(capture(slot)) }
            verify { navigationManager.reloadHome() }
            slot.captured.let { destination ->
                assertTrue("destination is ${destination::class}", destination is SetupDestination.AppContent)
                destination as SetupDestination.AppContent
                assertEquals(currentUser, destination.current)
            }
            // Note: navigation manager internally handles Home destination, so it won't be in this list
            assertEquals(1, backstack.size)
            backstack[0].let { destination ->
                assertTrue("destination is ${destination::class}", destination is Destination.Search)
            }
        }

    @Test
    fun `Test intent result is target, has server, no home backstack`() =
        runTest(testDispatcher) {
            setupPreferences {
                signInAutomatically = true
                currentServerId = serverId.toServerString()
                currentUserId = userId.toServerString()
            }
            every { serverRepository.current } returns MutableStateFlow(currentUser)
            coEvery { serverRepository.restoreSession(serverId, userId) } returns currentUser
            coEvery { intentService.parseIntent(any()) } returns
                IntentResult.Target(
                    destinations = listOf(Destination.Search()),
                    addHomeToBackStack = false,
                )
            val backstack = mutableListOf<Destination>()
            every { navigationManager.backStack } returns backstack

            viewModel.appStart(Intent())
            advanceUntilIdle()

            val slot = slot<SetupDestination>()
            verify { setupNavigationManager.navigateTo(capture(slot)) }
            verify(exactly = 0) { navigationManager.reloadHome() }
            slot.captured.let { destination ->
                assertTrue("destination is ${destination::class}", destination is SetupDestination.AppContent)
                destination as SetupDestination.AppContent
                assertEquals(currentUser, destination.current)
            }
            assertEquals(1, backstack.size)
            backstack[0].let { destination ->
                assertTrue("destination is ${destination::class}", destination is Destination.Search)
            }
        }

    @Test
    fun `Test intent result is target, has no server`() =
        runTest(testDispatcher) {
            setupPreferences {
                signInAutomatically = true
                currentServerId = serverId.toServerString()
                currentUserId = userId.toServerString()
            }
            every { serverRepository.current } returns MutableStateFlow(null)
            coEvery { serverRepository.restoreSession(serverId, userId) } returns currentUser
            coEvery { intentService.parseIntent(any()) } returns
                IntentResult.Target(
                    destinations = listOf(Destination.Search()),
                    addHomeToBackStack = true,
                )
            val backstack = mutableListOf<Destination>()
            every { navigationManager.backStack } returns backstack

            viewModel.appStart(Intent())
            advanceUntilIdle()

            val slot = slot<SetupDestination>()
            verify { setupNavigationManager.navigateTo(capture(slot)) }
            verify(exactly = 0) { navigationManager.reloadHome() }
            slot.captured.let { destination ->
                assertTrue("destination is ${destination::class}", destination is SetupDestination.ServerList)
            }
        }
}
