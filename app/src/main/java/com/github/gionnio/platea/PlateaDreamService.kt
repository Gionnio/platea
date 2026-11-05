package com.github.gionnio.platea

import android.service.dreams.DreamService
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.datastore.core.DataStore
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.github.gionnio.platea.data.ServerRepository
import com.github.gionnio.platea.preferences.AppPreferences
import com.github.gionnio.platea.services.ScreensaverService
import com.github.gionnio.platea.services.hilt.AuthOkHttpClient
import com.github.gionnio.platea.ui.CoilConfig
import com.github.gionnio.platea.ui.components.AppScreensaverContent
import com.github.gionnio.platea.ui.launchDefault
import com.github.gionnio.platea.ui.theme.PlateaAppTheme
import com.github.gionnio.platea.ui.util.ProvideLocalClock
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
import timber.log.Timber

@AndroidEntryPoint
class PlateaDreamService :
    DreamService(),
    SavedStateRegistryOwner {
    @Inject
    lateinit var serverRepository: ServerRepository

    @Inject
    lateinit var screensaverService: ScreensaverService

    @Inject
    lateinit var preferencesDataStore: DataStore<AppPreferences>

    @AuthOkHttpClient
    @Inject
    lateinit var okHttpClient: OkHttpClient

    private val lifecycleRegistry = LifecycleRegistry(this)

    private val savedStateRegistryController =
        SavedStateRegistryController.create(this).apply {
            performAttach()
        }

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry

    override fun onCreate() {
        super.onCreate()
        Timber.d("onCreate")

        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        lifecycleScope.launchDefault {
            if (serverRepository.current.value == null) {
                val prefs = preferencesDataStore.data.first()
                serverRepository.restoreSession(prefs.currentServerId.toUUIDOrNull(), prefs.currentUserId.toUUIDOrNull())
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        Timber.d("onAttachedToWindow")
        val itemFlow = screensaverService.createItemFlow(lifecycleScope)
        setContentView(
            ComposeView(this).apply {
                setViewTreeLifecycleOwner(this@PlateaDreamService)
                setViewTreeSavedStateRegistryOwner(this@PlateaDreamService)
                setContent {
                    val user by serverRepository.currentUserFlow.collectAsState(null)
                    if (user != null) {
                        var prefs by remember { mutableStateOf<AppPreferences?>(null) }
                        LaunchedEffect(Unit) {
                            preferencesDataStore.data.collectLatest { prefs = it }
                        }
                        prefs?.let { prefs ->
                            CoilConfig(
                                prefs = prefs,
                                okHttpClient = okHttpClient,
                                debugLogging = false,
                                enableCache = true,
                            )
                            PlateaAppTheme {
                                ProvideLocalClock {
                                    val screensaverPrefs = prefs.interfacePreferences.screensaverPreference
                                    val currentItem by itemFlow.collectAsState(null)
                                    Box(Modifier.fillMaxSize()) {
                                        AppScreensaverContent(
                                            currentItem = currentItem,
                                            showClock = screensaverPrefs.showClock,
                                            duration = screensaverPrefs.duration.milliseconds,
                                            animate = screensaverPrefs.animate,
                                            modifier = Modifier.fillMaxSize(),
                                        )
                                        if (screensaverPrefs.dimEnabled) {
                                            val alpha = screensaverPrefs.dimPercent / 100f
                                            Box(
                                                Modifier
                                                    .fillMaxSize()
                                                    .background(Color.Black.copy(alpha = alpha)),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            },
        )
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        Timber.d("onDreamingStarted")
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
    }

    override fun onDreamingStopped() {
        super.onDreamingStopped()
        Timber.d("onDreamingStopped")
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
    }
}
