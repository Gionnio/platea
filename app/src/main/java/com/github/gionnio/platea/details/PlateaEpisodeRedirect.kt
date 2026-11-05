package com.github.gionnio.platea.details

import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import com.github.gionnio.platea.services.NavigationManager
import com.github.gionnio.platea.ui.nav.Destination
import com.github.gionnio.platea.util.PlateaDispatchers
import com.github.gionnio.platea.ui.PlateaLoading
import com.github.gionnio.platea.ui.theme.PlateaColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.model.api.BaseItemKind
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class PlateaEpisodeRedirectViewModel
    @Inject
    constructor(
        private val api: ApiClient,
        private val navigationManager: NavigationManager,
    ) : ViewModel() {
        /** Replaces the episode destination with its series page (or goes back if it cannot be found). */
        suspend fun redirect(destination: Destination.MediaItem) {
            val seriesId =
                withContext(PlateaDispatchers.IO) {
                    runCatching { api.userLibraryApi.getItem(destination.itemId).content.seriesId }
                        .onFailure { Timber.w(it, "Episode %s", destination.itemId) }
                        .getOrNull()
                }
            val stack = navigationManager.backStack
            val index = stack.lastIndexOf(destination)
            when {
                index < 0 -> Unit
                seriesId != null -> stack[index] = Destination.MediaItem(seriesId, BaseItemKind.SERIES, initialEpisodeId = destination.itemId)
                else -> navigationManager.goBack()
            }
        }
    }

/**
 * Platea has no page for a single episode: a link to one (from outside the app) opens the page of its
 * series on that episode (its season, cursor on it).
 */
@Composable
fun PlateaEpisodeRedirect(
    destination: Destination.MediaItem,
    modifier: Modifier = Modifier,
    viewModel: PlateaEpisodeRedirectViewModel = hiltViewModel(),
) {
    LaunchedEffect(destination) { viewModel.redirect(destination) }
    PlateaLoading(modifier.background(PlateaColors.Ground))
}
