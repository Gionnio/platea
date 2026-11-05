package com.github.gionnio.platea

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.datastore.core.DataStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import androidx.navigation3.runtime.NavBackStack
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
import com.github.gionnio.platea.data.ServerRepository
import com.github.gionnio.platea.preferences.AppPreferences
import com.github.gionnio.platea.services.AppUpgradeHandler
import com.github.gionnio.platea.services.DatePlayedInvalidationService
import com.github.gionnio.platea.services.DeviceProfileService
import com.github.gionnio.platea.services.ImageUrlService
import com.github.gionnio.platea.services.IntentResult
import com.github.gionnio.platea.services.IntentService
import com.github.gionnio.platea.services.LatestNextUpSchedulerService
import com.github.gionnio.platea.services.NavigationManager
import com.github.gionnio.platea.services.PlaybackLifecycleObserver
import com.github.gionnio.platea.services.RefreshRateService
import com.github.gionnio.platea.services.ScreensaverService
import com.github.gionnio.platea.services.ServerEventListener
import com.github.gionnio.platea.services.SetupDestination
import com.github.gionnio.platea.services.SetupNavigationManager
import com.github.gionnio.platea.services.UserPreferencesService
import com.github.gionnio.platea.services.UserSwitchListener
import com.github.gionnio.platea.services.hilt.AuthOkHttpClient
import com.github.gionnio.platea.services.tvprovider.TvProviderSchedulerService
import com.github.gionnio.platea.ui.CoilConfig
import com.github.gionnio.platea.ui.LocalImageUrlService
import com.github.gionnio.platea.ui.collectLatestIn
import com.github.gionnio.platea.ui.launchDefault
import com.github.gionnio.platea.ui.nav.Destination
import com.github.gionnio.platea.ui.showToast
import com.github.gionnio.platea.ui.theme.PlateaAppTheme
import com.github.gionnio.platea.ui.util.ProvideLocalClock
import com.github.gionnio.platea.util.DebugLogTree
import com.github.gionnio.platea.util.ExceptionHandler
import com.github.gionnio.platea.util.PlateaDispatchers
import com.github.gionnio.platea.util.requestSerializersModule
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
import timber.log.Timber

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    private val viewModel: MainActivityViewModel by viewModels()

    @Inject
    lateinit var userPreferencesDataStore: DataStore<AppPreferences>

    @Inject
    lateinit var userPreferencesService: UserPreferencesService

    @AuthOkHttpClient
    @Inject
    lateinit var okHttpClient: OkHttpClient

    @Inject
    lateinit var navigationManager: NavigationManager

    @Inject
    lateinit var setupNavigationManager: SetupNavigationManager

    @Inject
    lateinit var playbackLifecycleObserver: PlaybackLifecycleObserver

    @Inject
    lateinit var imageUrlService: ImageUrlService

    @Inject
    lateinit var refreshRateService: RefreshRateService

    @Inject
    lateinit var userSwitchListener: UserSwitchListener

    @Inject
    lateinit var tvProviderSchedulerService: TvProviderSchedulerService

    @Inject
    lateinit var latestNextUpSchedulerService: LatestNextUpSchedulerService

    // Note: unused but injected to ensure it is created
    @Inject
    lateinit var serverEventListener: ServerEventListener

    // Note: unused but injected to ensure it is created
    @Inject
    lateinit var datePlayedInvalidationService: DatePlayedInvalidationService

    @Inject
    lateinit var screensaverService: ScreensaverService

    @Inject
    lateinit var intentService: IntentService

    private var signInAuto = true

    private val json =
        Json {
            classDiscriminator = "_type"
            serializersModule = requestSerializersModule
        }

    @OptIn(ExperimentalTvMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instance = this
        Timber.i("MainActivity.onCreate: savedInstanceState is null=${savedInstanceState == null}")
        lifecycle.addObserver(playbackLifecycleObserver)

        val backStackStr = savedInstanceState?.getString(KEY_BACK_STACK)
        val restoredBackStack =
            if (backStackStr != null) {
                try {
                    json.decodeFromString<List<Destination>>(backStackStr)
                } catch (ex: Exception) {
                    // Best-effort: a previously persisted back stack we can no longer decode
                    // must not crash startup; fall back to a fresh start destination.
                    Timber.w(ex, "Could not restore back stack; starting fresh")
                    null
                }
            } else {
                null
            }
        if (restoredBackStack != null) {
            Timber.d("Restoring back stack")
            var backStack: List<Destination> = restoredBackStack
            if (backStack.lastOrNull().isPlayback) {
                Timber.v("Restoring back stack without playback")
                backStack = backStack.dropLast(1)
            }
            navigationManager.backStack = NavBackStack(*backStack.toTypedArray())
        } else {
            navigationManager.backStack = NavBackStack(Destination.Home())
        }

        viewModel.serverRepository.currentUserFlow
            .onEach { user ->
                withContext(PlateaDispatchers.Main) {
                    if (user?.hasPin == true) {
                        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    } else {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    }
                }
            }.catch { ex ->
                Timber.e(ex, "Error with settings flag secure")
            }.launchIn(lifecycleScope)

        screensaverService.keepScreenOn
            .onEach { keepScreenOn ->
                Timber.v("keepScreenOn: %s", keepScreenOn)
                withContext(PlateaDispatchers.Main) {
                    if (keepScreenOn) {
                        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    } else {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                }
            }.catch { ex ->
                Timber.e(ex, "Error with keepScreenOn")
            }.launchIn(lifecycleScope)

        userPreferencesDataStore.data.collectLatestIn(lifecycleScope) { prefs ->
            signInAuto = prefs.signInAutomatically
        }

        viewModel.appStart(intent)
        setContent {
            PlateaAppTheme {
                Surface(Modifier.fillMaxSize()) {
                    val userPreferences by userPreferencesService.flow.collectAsState(null)
                    if (userPreferences == null) {
                        com.github.gionnio.platea.ui.PlateaSplash()
                    } else {
                        userPreferences?.let { userPreferences ->
                            val appPreferences = userPreferences.appPreferences
                            CoilConfig(
                                prefs = appPreferences,
                                okHttpClient = okHttpClient,
                                debugLogging = false,
                                enableCache = true,
                            )
                            LaunchedEffect(appPreferences.debugLogging) {
                                DebugLogTree.INSTANCE.enabled = appPreferences.debugLogging
                            }
                            CompositionLocalProvider(LocalImageUrlService provides imageUrlService) {
                                run {
                                    ProvideLocalClock {
                                        MainContent(
                                            backStack = setupNavigationManager.backStack,
                                            navigationManager = navigationManager,
                                            userPreferences = userPreferences,
                                            screensaverService = screensaverService,
                                            modifier = Modifier.fillMaxSize(),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (screensaverService.state.value.run { show || showDim }) {
            screensaverService.stop(false)
            screensaverService.pulse()
            return true
        } else {
            screensaverService.pulse()
            return super.dispatchKeyEvent(event)
        }
    }

    override fun onResume() {
        super.onResume()
        Timber.d("onResume")
        viewModel.appResume()
        lifecycleScope.launchDefault {
            screensaverService.pulse()
        }
    }

    override fun onRestart() {
        super.onRestart()
        Timber.d("onRestart")
        viewModel.appStart(null)
        run {
            // If restarting during playback that is not external, go back a page
            val lastDest = navigationManager.backStack.lastOrNull()
            if (lastDest.isPlayback) {
                Timber.v("onRestart: go back from playback")
                navigationManager.goBack()
            }
        }
    }

    override fun onStop() {
        super.onStop()
        Timber.d("onStop")
        screensaverService.stop(true)
        tvProviderSchedulerService.launchOneTimeRefresh()
    }

    override fun onPause() {
        super.onPause()
        Timber.d("onPause")
    }

    override fun onStart() {
        super.onStart()
        Timber.d("onStart")
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        Timber.d("onSaveInstanceState")
        try {
            val str = json.encodeToString(navigationManager.backStack.toList())
            outState.putString(KEY_BACK_STACK, str)
        } catch (ex: Exception) {
            // Best-effort: some destinations carry types we can't serialize; skip persisting
            // the back stack rather than crashing in onSaveInstanceState.
            Timber.w(ex, "Could not persist back stack; skipping")
        }
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        Timber.d("onRestoreInstanceState")
    }

    override fun onDestroy() {
        super.onDestroy()
        Timber.d("onDestroy")
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        Timber.d("onConfigurationChanged: newConfig=%s", newConfig)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        Timber.v("onNewIntent")
        setIntent(intent)
        viewModel.appStart(intent)
    }

    fun changeDisplayMode(modeId: Int) {
        lifecycleScope.launch(PlateaDispatchers.Main + ExceptionHandler(autoToast = true)) {
            try {
                val attrs = window.attributes
                if (attrs.preferredDisplayModeId != modeId) {
                    Timber.d("Switch preferredDisplayModeId to %s", modeId)
                    window.attributes = attrs.apply { preferredDisplayModeId = modeId }
                }
            } catch (ex: Exception) {
                Timber.e(ex, "Error switching preferredDisplayModeId to %s", modeId)
                Toast
                    .makeText(this@MainActivity, "Error changing display mode", Toast.LENGTH_SHORT)
                    .show()
            }
        }
    }

    companion object {
        private const val KEY_BACK_STACK = "backStack"

        lateinit var instance: MainActivity
            private set
    }
}

@HiltViewModel
class MainActivityViewModel
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val preferences: DataStore<AppPreferences>,
        val serverRepository: ServerRepository,
        private val setupNavigationManager: SetupNavigationManager,
        private val navigationManager: NavigationManager,
        private val deviceProfileService: DeviceProfileService,
        private val appUpgradeHandler: AppUpgradeHandler,
        private val intentService: IntentService,
    ) : ViewModel() {
        private val mutex = Mutex()

        fun appStart(intent: Intent?) {
            viewModelScope.launchDefault {
                mutex.withLock {
                    try {
                        val result = intent?.let { intentService.parseIntent(intent) }
                        when (result) {
                            is IntentResult.Error -> {
                                val current = serverRepository.current.value
                                val destination =
                                    if (current != null) {
                                        // Platea: a link to a title that no longer exists must not sign the user out
                                        SetupDestination.AppContent(current)
                                    } else {
                                        SetupDestination.ServerList
                                    }
                                setupNavigationManager.navigateTo(destination)
                                Timber.e("Error parsing intent: %s", result.message)
                                showToast(context, result.message)
                                return@withLock
                            }

                            is IntentResult.Target -> {
                                val current = serverRepository.current.value
                                if (current != null) {
                                    Timber.i("Received valid intent, switching to AppContent")

                                    if (result.addHomeToBackStack) {
                                        navigationManager.reloadHome()
                                    } else {
                                        navigationManager.backStack.clear()
                                    }
                                    navigationManager.backStack.addAll(result.destinations)

                                    setupNavigationManager.navigateTo(
                                        SetupDestination.AppContent(current),
                                    )
                                } else {
                                    // This should never happen, but just reset app state if it does
                                    setupNavigationManager.navigateTo(SetupDestination.ServerList)
                                    Timber.e("Error parsing intent, no user is active")
                                    showToast(context, "An error occurred parsing the intent")
                                }
                                return@withLock
                            }

                            IntentResult.NoOp,
                            null,
                            -> {
                                // No-op, proceed below
                            }
                        }
                    } catch (ex: Exception) {
                        Timber.e(ex, "Error parsing intent")
                    }

                    try {
                        val needUpgrade = appUpgradeHandler.needUpgrade()
                        if (needUpgrade) {
                            showToast(
                                context,
                                context.getString(
                                    R.string.updated_toast,
                                    appUpgradeHandler.currentVersion.toString(),
                                ),
                            )
                            appUpgradeHandler.run()
                        }
                        val prefs =
                            preferences.data.firstOrNull() ?: AppPreferences.getDefaultInstance()
                        val profileProtected =
                            serverRepository.current.value
                                ?.user
                                ?.isProtected == true
                        if (prefs.signInAutomatically && !profileProtected) {
                            val current =
                                serverRepository.restoreSession(
                                    prefs.currentServerId?.toUUIDOrNull(),
                                    prefs.currentUserId?.toUUIDOrNull(),
                                )
                            if (current != null) {
                                if (current.user.isProtected) {
                                    setupNavigationManager.navigateTo(
                                        SetupDestination.UserList(
                                            current.server,
                                        ),
                                    )
                                } else {
                                    // Restored
                                    setupNavigationManager.navigateTo(
                                        SetupDestination.AppContent(
                                            current,
                                        ),
                                    )
                                }
                            } else {
                                // Did not restore
                                setupNavigationManager.navigateTo(SetupDestination.ServerList)
                            }
                        } else {
                            setupNavigationManager.navigateTo(SetupDestination.Loading)
                            val currentServerId = prefs.currentServerId?.toUUIDOrNull()
                            if (currentServerId != null) {
                                val currentServer =
                                    serverRepository.serverDao.getServer(currentServerId)?.server
                                if (currentServer != null) {
                                    setupNavigationManager.navigateTo(
                                        SetupDestination.UserList(
                                            currentServer,
                                        ),
                                    )
                                } else {
                                    setupNavigationManager.navigateTo(SetupDestination.ServerList)
                                }
                            } else {
                                setupNavigationManager.navigateTo(SetupDestination.ServerList)
                            }
                        }
                    } catch (ex: Exception) {
                        Timber.e(ex, "Error during appStart")
                        setupNavigationManager.navigateTo(SetupDestination.ServerList)
                    }
                }
            }
            viewModelScope.launchDefault {
                // Create the mediaCodecCapabilitiesTest if needed
                deviceProfileService.mediaCodecCapabilitiesTest.supportsAVC()
            }
        }

        fun appResume() {
            viewModelScope.launchDefault {
                mutex.withLock {
                    try {
                        Timber.v("Updating userDto")
                        serverRepository.updateUserDto()
                    } catch (ex: Exception) {
                        Timber.w(ex, "Error during appResume")
                    }
                }
            }
        }
    }

private val Destination?.isPlayback: Boolean
    get() =
        this is Destination.Playback ||
            this is Destination.PlaybackList
