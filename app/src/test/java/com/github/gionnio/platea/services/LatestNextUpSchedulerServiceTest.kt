package com.github.gionnio.platea.services

import androidx.appcompat.app.AppCompatActivity
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.work.WorkManager
import com.github.gionnio.platea.data.CurrentUser
import com.github.gionnio.platea.data.ServerRepository
import com.github.gionnio.platea.util.PlateaDispatchers
import com.github.gionnio.platea.util.configure
import com.github.gionnio.platea.util.reset
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LatestNextUpSchedulerServiceTest {
    @get:Rule val instantTaskExecutorRule = InstantTaskExecutorRule()
    private val testDispatcher = StandardTestDispatcher()
    private val currentUser = MutableStateFlow<CurrentUser?>(null)
    private val mockActivity = mockk<AppCompatActivity>(relaxed = true)
    private val mockServerRepository = mockk<ServerRepository>(relaxed = true)
    private val mockWorkManager = mockk<WorkManager>(relaxed = true)
    private val lifecycleRegistry = LifecycleRegistry(mockk<LifecycleOwner>(relaxed = true))

    @Before
    fun setUp() {
        PlateaDispatchers.configure(testDispatcher)
        every { mockActivity.lifecycle } returns lifecycleRegistry
        every { mockServerRepository.current } returns currentUser
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    @After
    fun tearDown() = PlateaDispatchers.reset()

    private fun createService() =
        LatestNextUpSchedulerService(
            context = mockActivity,
            serverRepository = mockServerRepository,
            workManager = mockWorkManager,
        ).also {
            it.dispatcher = testDispatcher
        }

    @Test
    fun cancels_latestNextUp_work_when_user_null() =
        runTest(testDispatcher) {
            createService()

            currentUser.value = null
            advanceUntilIdle()

            verify { mockWorkManager.cancelUniqueWork(LatestNextUpWorker.WORK_NAME) }
        }
}
