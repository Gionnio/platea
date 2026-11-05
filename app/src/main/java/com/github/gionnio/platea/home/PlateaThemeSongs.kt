package com.github.gionnio.platea.home

import androidx.datastore.core.DataStore
import com.github.gionnio.platea.preferences.AppPreferences
import com.github.gionnio.platea.preferences.ThemeSongVolume
import com.github.gionnio.platea.services.ThemeSongPlayer
import com.github.gionnio.platea.util.PlateaDispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.libraryApi
import org.jellyfin.sdk.model.serializer.UUIDSerializer
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Where the theme song of a featured series comes from. */
@Serializable
sealed interface ThemeRef {
    /** Theme song stored by Jellyfin (theme.mp3 next to the series, or a server plugin). */
    @Serializable
    data class Jellyfin(
        @Serializable(with = UUIDSerializer::class) val itemId: UUID,
    ) : ThemeRef
}

/**
 * Theme songs of the series on the Platea home and series pages, from Jellyfin. Volume follows the
 * "Theme music" setting.
 */
@Singleton
class PlateaThemeSongs
    @Inject
    constructor(
        private val themeSongPlayer: ThemeSongPlayer,
        private val preferences: DataStore<AppPreferences>,
        private val api: ApiClient,
    ) {
        /** Theme of a series, if the server has one. */
        suspend fun resolve(seriesId: UUID): ThemeRef? {
            val onServer = runCatching { api.libraryApi.getThemeSongs(seriesId).content.items.isNotEmpty() }.getOrDefault(false)
            return if (onServer) ThemeRef.Jellyfin(seriesId) else null
        }

        private val scope = CoroutineScope(SupervisorJob() + PlateaDispatchers.Main)
        private var job: Job? = null
        private var current: ThemeRef? = null

        private val _playing = MutableStateFlow(false)

        /** True while a theme song is actually audible (drives the animated icon). */
        val playing: StateFlow<Boolean> = _playing

        private var requested: ThemeRef? = null

        /**
         * Asks for [theme] (null: silence). The theme belongs to the series, not to the screen: the same theme asked
         * again while it plays (featured series -> its episode in "Continue watching" -> its page) goes on without
         * restarting. Silence comes only after [STOP_GRACE_MS], so moving across the screen does not stop and start
         * it again; a new theme starts after [START_DWELL_MS] on the same place, not while scrolling past.
         */
        fun play(theme: ThemeRef?) {
            if (theme == requested) return
            requested = theme
            job?.cancel()
            if (theme != null && theme == current) return
            job =
                scope.launch {
                    delay(if (theme == null) STOP_GRACE_MS else START_DWELL_MS)
                    silence()
                    when (theme) {
                        null -> {}

                        is ThemeRef.Jellyfin -> {
                            if (themesOn()) {
                                themeSongPlayer.playThemeFor(theme.itemId)
                                current = theme
                                _playing.value = true
                            }
                        }
                    }
                }
        }

        /** Silence after the grace period (see [play]). */
        fun stop() = play(null)

        /** Silence now: playback is starting and the theme must not play over it. */
        fun stopNow() {
            requested = null
            job?.cancel()
            job = scope.launch { silence() }
        }

        private fun silence() {
            themeSongPlayer.stop()
            _playing.value = false
            current = null
        }

        private suspend fun themesOn(): Boolean =
            when (preferences.data.first().interfacePreferences.playThemeSongs) {
                ThemeSongVolume.LOWEST, ThemeSongVolume.LOW, ThemeSongVolume.MEDIUM, ThemeSongVolume.HIGH, ThemeSongVolume.HIGHEST -> true
                else -> false
            }

        private companion object {
            const val STOP_GRACE_MS = 1_800L
            const val START_DWELL_MS = 1_000L
        }
    }
