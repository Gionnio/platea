package com.github.gionnio.platea

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import com.github.gionnio.platea.preferences.UserPreferences
import com.github.gionnio.platea.services.NavigationManager
import com.github.gionnio.platea.services.ScreensaverService
import com.github.gionnio.platea.services.SetupDestination
import com.github.gionnio.platea.ui.components.AppScreensaver
import com.github.gionnio.platea.ui.nav.ApplicationContent
import com.github.gionnio.platea.ui.util.InterfaceCustomization
import com.github.gionnio.platea.ui.util.LocalInterfaceCustomization

@Composable
fun MainContent(
    backStack: MutableList<SetupDestination>,
    navigationManager: NavigationManager,
    userPreferences: UserPreferences,
    screensaverService: ScreensaverService,
    modifier: Modifier = Modifier,
) {
    val preferences by rememberUpdatedState(userPreferences)
    Surface(
        modifier =
            modifier
                .background(MaterialTheme.colorScheme.background),
        shape = RectangleShape,
    ) {
//                            val backStack = rememberNavBackStack(SetupDestination.Loading)
//                            setupNavigationManager.backStack = backStack
        val interfaceCustomization =
            remember(userPreferences.appPreferences) { InterfaceCustomization(userPreferences.appPreferences) }
        CompositionLocalProvider(LocalInterfaceCustomization provides interfaceCustomization) {
            NavDisplay(
                backStack = backStack,
                onBack = { backStack.removeLastOrNull() },
                entryDecorators =
                    listOf(
                        rememberSaveableStateHolderNavEntryDecorator(),
                        rememberViewModelStoreNavEntryDecorator(),
                    ),
                entryProvider = { key ->
                    key as SetupDestination
                    NavEntry(key) {
                        when (key) {
                            SetupDestination.Loading -> {
                                com.github.gionnio.platea.ui.PlateaSplash()
                            }

                            SetupDestination.ServerList -> {
                                com.github.gionnio.platea.access.PlateaServerPage(Modifier.fillMaxSize())
                            }

                            is SetupDestination.UserList -> {
                                com.github.gionnio.platea.access.PlateaUserPage(key.server, Modifier.fillMaxSize())
                            }

                            is SetupDestination.AppContent -> {
                                val current = key.current
                                var showContent by remember {
                                    mutableStateOf(true)
                                }
                                LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
                                    if (!userPreferences.appPreferences.signInAutomatically) {
                                        showContent = false
                                    }
                                }

                                if (showContent) {
                                    ApplicationContent(
                                        user = current.user,
                                        server = current.server,
                                        navigationManager = navigationManager,
                                        preferences = preferences,
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                } else {
                                    com.github.gionnio.platea.ui.PlateaSplash()
                                }
                            }
                        }
                    }
                },
            )
            val screenSaverState by screensaverService.state.collectAsState()
            if (screenSaverState.enabled || screenSaverState.enabledTemp) {
                AnimatedVisibility(
                    visible = screenSaverState.show,
                    enter = ScreensaverService.enterAnimation,
                    exit = ScreensaverService.exitAnimation,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    AppScreensaver(userPreferences.appPreferences, Modifier.fillMaxSize())
                }
            }
            AnimatedVisibility(
                visible = screenSaverState.showDim || (screenSaverState.dimEnabled && screenSaverState.show),
                enter = ScreensaverService.enterAnimation,
                exit = ScreensaverService.exitAnimation,
                modifier = Modifier.fillMaxSize(),
            ) {
                val alpha =
                    userPreferences.appPreferences.interfacePreferences.screensaverPreference.dimPercent / 100f
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = alpha)),
                )
            }
        }
    }
}
