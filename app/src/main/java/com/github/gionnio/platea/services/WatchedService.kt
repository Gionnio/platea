package com.github.gionnio.platea.services

import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.playStateApi
import org.jellyfin.sdk.model.api.UserItemDataDto
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Marks titles as watched or not watched on the server
 */
@Singleton
class WatchedService
    @Inject
    constructor(
        private val api: ApiClient,
        private val datePlayedService: DatePlayedService,
    ) {
        suspend fun setWatched(
            itemId: UUID,
            played: Boolean,
        ): UserItemDataDto {
            datePlayedService.invalidate(itemId)
            return if (played) {
                api.playStateApi.markPlayedItem(itemId).content
            } else {
                api.playStateApi.markUnplayedItem(itemId).content
            }
        }
    }
