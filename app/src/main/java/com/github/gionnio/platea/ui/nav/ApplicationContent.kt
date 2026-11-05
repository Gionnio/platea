package com.github.gionnio.platea.ui.nav

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import com.github.gionnio.platea.data.model.JellyfinServer
import com.github.gionnio.platea.data.model.JellyfinUser
import com.github.gionnio.platea.preferences.UserPreferences
import com.github.gionnio.platea.services.NavigationManager

/**
 * The root of the app once a user is signed in: shows the page on top of the navigation back stack.
 * Every page is full screen and draws its own top bar and background.
 */
@Composable
fun ApplicationContent(
    server: JellyfinServer,
    user: JellyfinUser,
    navigationManager: NavigationManager,
    preferences: UserPreferences,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        NavDisplay(
            backStack = navigationManager.backStack,
            onBack = { navigationManager.goBack() },
            entryDecorators =
                listOf(
                    rememberSaveableStateHolderNavEntryDecorator(),
                    rememberViewModelStoreNavEntryDecorator(),
                ),
            entryProvider = { key ->
                key as Destination
                // A page belongs to the signed-in user: switching user rebuilds it
                NavEntry(key, contentKey = "${key}_${server.id}_${user.id}") {
                    DestinationContent(
                        destination = key,
                        preferences = preferences,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            },
        )
    }
}
