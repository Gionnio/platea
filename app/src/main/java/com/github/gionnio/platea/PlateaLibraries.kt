package com.github.gionnio.platea

import com.github.gionnio.platea.home.NavLibrary
import com.github.gionnio.platea.ui.nav.Destination
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.userViewsApi
import org.jellyfin.sdk.model.api.CollectionType
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The libraries of the top bar, asked to the server once and shared by the pages (home, library, search).
 * Only the kinds Platea has a page for; no Live TV lookup.
 */
@Singleton
class PlateaLibraries
    @Inject
    constructor(
        private val api: ApiClient,
    ) {
        private val mutex = Mutex()
        private var cachedFor: UUID? = null
        private var cachedAt = 0L
        private var cached: List<NavLibrary> = emptyList()

        suspend fun get(
            userId: UUID,
            refresh: Boolean = false,
        ): List<NavLibrary> =
            mutex.withLock {
                val fresh = cachedFor == userId && System.currentTimeMillis() - cachedAt < MAX_AGE_MS
                if (!fresh || refresh) {
                    cached =
                        api.userViewsApi
                            .getUserViews(userId = userId)
                            .content.items
                            .filter { it.collectionType in ORDER }
                            .sortedBy { ORDER.indexOf(it.collectionType) }
                            .map { NavLibrary(it.name.orEmpty(), Destination.MediaItem(it.id, it.type, it.collectionType)) }
                    cachedFor = userId
                    cachedAt = System.currentTimeMillis()
                }
                cached
            }

        companion object {
            // (Collections are a view inside the movie library, not a top bar entry.)
            private val ORDER = listOf(CollectionType.MOVIES, CollectionType.TVSHOWS, CollectionType.HOMEVIDEOS)
            private val SUPPORTED = ORDER + CollectionType.BOXSETS
            private const val MAX_AGE_MS = 10 * 60 * 1000L

            /** Whether the library page can show a library of this kind. */
            fun isSupported(type: CollectionType?): Boolean = type in SUPPORTED
        }
    }
