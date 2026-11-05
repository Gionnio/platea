package com.github.gionnio.platea.ui.nav

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.github.gionnio.platea.R
import com.github.gionnio.platea.preferences.UserPreferences
import com.github.gionnio.platea.ui.components.LicenseInfo
import com.github.gionnio.platea.ui.playback.PlaybackPage
import com.github.gionnio.platea.ui.preferences.PreferenceScreenOption
import com.github.gionnio.platea.PlateaLibraries
import com.github.gionnio.platea.collection.PlateaCollectionPage
import com.github.gionnio.platea.details.PlateaEpisodeRedirect
import com.github.gionnio.platea.details.PlateaMovieDetails
import com.github.gionnio.platea.details.PlateaSeriesDetails
import com.github.gionnio.platea.home.PlateaHomePage
import com.github.gionnio.platea.library.PlateaLibraryPage
import com.github.gionnio.platea.person.PlateaPersonPage
import com.github.gionnio.platea.search.PlateaSearchPage
import com.github.gionnio.platea.settings.PlateaSettingsPage
import com.github.gionnio.platea.settings.PlateaSubtitleStylePage
import com.github.gionnio.platea.ui.theme.PlateaColors
import com.github.gionnio.platea.ui.theme.PlateaType
import org.jellyfin.sdk.model.api.BaseItemKind
import timber.log.Timber

/**
 * Chooses the page for the [Destination]
 */
@Composable
fun DestinationContent(
    destination: Destination,
    preferences: UserPreferences,
    modifier: Modifier = Modifier,
) {
    when (destination) {
        is Destination.Home -> {
            PlateaHomePage(modifier = modifier)
        }

        is Destination.Playback,
        is Destination.PlaybackList,
        -> {
            PlaybackPage(preferences = preferences, destination = destination, modifier = modifier)
        }

        is Destination.Settings -> {
            PlateaSettingsPage(
                preferences.appPreferences,
                modifier,
                advanced = destination.screen == PreferenceScreenOption.ADVANCED,
            )
        }

        is Destination.SubtitleSettings -> {
            PlateaSubtitleStylePage(preferences.appPreferences, destination.hdr, modifier)
        }

        is Destination.SeriesOverview -> {
            // Links to a season or an episode open the series page
            PlateaSeriesDetails(Destination.MediaItem(destination.itemId, BaseItemKind.SERIES), modifier)
        }

        is Destination.MediaItem -> {
            when (destination.type) {
                BaseItemKind.SERIES -> {
                    PlateaSeriesDetails(destination, modifier)
                }

                // A loose video (test clip, home video) uses the movie page
                BaseItemKind.MOVIE,
                BaseItemKind.VIDEO,
                BaseItemKind.MUSIC_VIDEO,
                -> {
                    PlateaMovieDetails(destination, modifier)
                }

                BaseItemKind.EPISODE -> {
                    PlateaEpisodeRedirect(destination, modifier)
                }

                BaseItemKind.BOX_SET -> {
                    PlateaCollectionPage(destination, modifier)
                }

                BaseItemKind.PERSON -> {
                    PlateaPersonPage(destination, modifier)
                }

                BaseItemKind.COLLECTION_FOLDER,
                BaseItemKind.FOLDER,
                BaseItemKind.USER_VIEW,
                -> {
                    if (PlateaLibraries.isSupported(destination.collectionType)) {
                        PlateaLibraryPage(destination, modifier)
                    } else {
                        Unsupported(modifier)
                    }
                }

                else -> {
                    Timber.w("Unsupported item type: ${destination.type}")
                    Unsupported(modifier)
                }
            }
        }

        Destination.License -> {
            // Third-party attributions (generated list), on the Platea ground
            Column(modifier.background(PlateaColors.Ground).padding(horizontal = 48.dp)) {
                Text(
                    stringResource(R.string.platea_licenses),
                    style = PlateaType.HeroTitle.copy(fontSize = 26.sp),
                    color = PlateaColors.Text,
                    modifier = Modifier.padding(top = 36.dp, bottom = 10.dp),
                )
                LicenseInfo(Modifier.fillMaxSize())
            }
        }

        is Destination.Search -> {
            PlateaSearchPage(destination.query, modifier)
        }
    }
}

/** A kind of library or title Platea has no page for (music, photos, live TV...). */
@Composable
private fun Unsupported(modifier: Modifier) {
    Box(modifier.fillMaxSize().background(PlateaColors.Ground), contentAlignment = Alignment.Center) {
        Text(stringResource(R.string.platea_unsupported_page), style = PlateaType.RowTitle, color = PlateaColors.TextSecondary)
    }
}
