package com.github.gionnio.platea.services

import androidx.compose.runtime.mutableStateListOf
import androidx.navigation3.runtime.NavKey
import com.github.gionnio.platea.data.CurrentUser
import com.github.gionnio.platea.data.model.JellyfinServer
import kotlinx.serialization.Serializable
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages navigating for setup
 */
@Singleton
class SetupNavigationManager
    @Inject
    constructor(
        private val navigationManager: NavigationManager,
    ) {
        var backStack: MutableList<SetupDestination> = mutableStateListOf(SetupDestination.Loading)

        /**
         * Go to the specified [SetupDestination]
         */
        fun navigateTo(destination: SetupDestination) {
            backStack[0] = destination
            log()
            if (destination !is SetupDestination.AppContent) {
                navigationManager.reloadHome()
            }
        }

        private fun log() {
            val dest = backStack.lastOrNull().toString()
            Timber.i("Current setup destination: %s", dest)
        }
    }

@Serializable
sealed interface SetupDestination : NavKey {
    @Serializable
    data object Loading : SetupDestination

    @Serializable
    data object ServerList : SetupDestination

    @Serializable
    data class UserList(
        val server: JellyfinServer,
    ) : SetupDestination

    @Serializable
    data class AppContent(
        val current: CurrentUser,
    ) : SetupDestination
}
