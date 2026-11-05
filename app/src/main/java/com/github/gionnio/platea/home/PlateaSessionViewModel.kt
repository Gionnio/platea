package com.github.gionnio.platea.home

import androidx.lifecycle.ViewModel
import com.github.gionnio.platea.data.ServerRepository
import com.github.gionnio.platea.services.SetupNavigationManager
import com.github.gionnio.platea.services.SetupDestination
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/** Leaving the current session from the top bar: back to the user list or to the server list. */
@HiltViewModel
class PlateaSessionViewModel
    @Inject
    constructor(
        private val serverRepository: ServerRepository,
        private val setupNavigationManager: SetupNavigationManager,
    ) : ViewModel() {
        val userName: String get() = serverRepository.currentUser?.name.orEmpty()

        val serverName: String? get() = serverRepository.currentServer?.let { it.name ?: it.url }

        fun switchUser() {
            cameFrom = serverRepository.current.value
            val server = serverRepository.currentServer
            setupNavigationManager.navigateTo(if (server != null) SetupDestination.UserList(server) else SetupDestination.ServerList)
        }

        fun switchServer() {
            cameFrom = serverRepository.current.value
            setupNavigationManager.navigateTo(SetupDestination.ServerList)
        }

        /** True when the sign-in screens were opened from the app: Back can then return to it. */
        val canReturn: Boolean get() = cameFrom != null

        /** Back on the sign-in screens: return to the session they were opened from, untouched. */
        fun returnToApp() {
            val session = cameFrom ?: return
            cameFrom = null
            setupNavigationManager.navigateTo(SetupDestination.AppContent(session))
        }

        /** A user or server has been chosen: the old session is no longer the one to return to. */
        fun forgetReturn() {
            cameFrom = null
        }

        companion object {
            // Only set from the avatar menu, never at app start: Back must not skip a PIN-protected sign-in
            private var cameFrom: com.github.gionnio.platea.data.CurrentUser? = null
        }
    }
