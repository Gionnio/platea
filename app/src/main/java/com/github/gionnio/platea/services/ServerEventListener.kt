package com.github.gionnio.platea.services

import android.content.Context
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.github.gionnio.platea.data.ServerRepository
import com.github.gionnio.platea.data.model.JellyfinServer
import com.github.gionnio.platea.data.model.JellyfinUser
import com.github.gionnio.platea.ui.collectLatestIn
import com.github.gionnio.platea.ui.launchIO
import com.github.gionnio.platea.ui.showToast
import dagger.hilt.android.qualifiers.ActivityContext
import dagger.hilt.android.scopes.ActivityScoped
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.sessionApi
import org.jellyfin.sdk.api.sockets.subscribe
import org.jellyfin.sdk.model.api.GeneralCommandMessage
import org.jellyfin.sdk.model.api.GeneralCommandType
import org.jellyfin.sdk.model.api.MediaType
import org.jellyfin.sdk.model.api.UserUpdatedMessage
import timber.log.Timber
import javax.inject.Inject

/**
 * Listens for basic messages from the server such as messages
 */
@ActivityScoped
class ServerEventListener
    @Inject
    constructor(
        @param:ActivityContext private val context: Context,
        private val api: ApiClient,
        private val serverRepository: ServerRepository,
    ) : DefaultLifecycleObserver {
        private val activity = (context as AppCompatActivity)

        private var listenJob: Job? = null

        init {
            activity.lifecycle.addObserver(this)
            serverRepository.current.collectLatestIn(activity.lifecycleScope) {
                Timber.d("New user/server: %s", it)
                listenJob?.cancel()
                if (it != null) {
                    init(it.server, it.user)
                }
            }
        }

        fun init(
            server: JellyfinServer?,
            user: JellyfinUser?,
        ) {
            if (server != null && user != null && api.baseUrl != null && api.accessToken != null) {
                (context as AppCompatActivity).lifecycleScope.launchIO {
                    api.sessionApi.postCapabilities(
                        playableMediaTypes = listOf(MediaType.VIDEO),
                        supportedCommands =
                            listOf(
                                GeneralCommandType.DISPLAY_MESSAGE,
                                GeneralCommandType.SEND_STRING,
                            ),
                        supportsMediaControl = true,
                    )
                    setupListeners()
                }
            }
        }

        fun setupListeners() {
            Timber.v("Subscribing to WebSocket")
            listenJob?.cancel()
            listenJob =
                activity.lifecycleScope.launch {
                    // Messages shown on screen (sent from the Jellyfin dashboard)
                    keepSubscribed("Server messages", subscribe = { api.webSocket.subscribe<GeneralCommandMessage>() }) { message ->
                        if (message.data?.name in setOf(GeneralCommandType.DISPLAY_MESSAGE, GeneralCommandType.SEND_STRING)) {
                            val header = message.data?.arguments["Header"]
                            val text = message.data?.arguments["Text"] ?: message.data?.arguments["String"]
                            val toast = listOfNotNull(header, text).joinToString("\n")
                            if (toast.isNotBlank()) showToast(context, toast, Toast.LENGTH_LONG)
                        }
                    }
                    // The user's preferences changed on the server (languages, subtitle mode): use them at once
                    keepSubscribed("User updates", subscribe = { api.webSocket.subscribe<UserUpdatedMessage>() }) { message ->
                        message.data?.let { serverRepository.updateUserDto(it) }
                    }
                }
        }

        override fun onResume(owner: LifecycleOwner) {
            serverRepository.current.value?.let { init(it.server, it.user) }
        }

        override fun onPause(owner: LifecycleOwner) {
            Timber.v("Cancelling WebSocket")
            listenJob?.cancel()
        }

        override fun onStop(owner: LifecycleOwner) {
            Timber.v("Cancelling WebSocket")
            listenJob?.cancel()
        }
    }
