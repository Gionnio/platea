package com.github.gionnio.platea.services

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.github.gionnio.platea.data.model.BaseItem
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.imageApi
import org.jellyfin.sdk.model.UUID
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageFormat
import org.jellyfin.sdk.model.api.ImageType
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Provides image URLs for items with several convenience methods
 *
 * This is available in compose UI code via [com.github.gionnio.platea.ui.LocalImageUrlService]
 */
@Singleton
class ImageUrlService
    @Inject
    constructor(
        private val api: ApiClient,
    ) {
        fun getItemImageUrl(
            itemId: UUID,
            itemType: BaseItemKind,
            seriesId: UUID?,
            useSeriesForPrimary: Boolean,
            imageType: ImageType,
            imageTags: Map<ImageType, String?>,
            backdropTags: List<String>,
            parentThumbId: UUID? = null,
            parentBackdropId: UUID? = null,
            fillWidth: Int? = null,
            fillHeight: Int? = null,
        ): String? =
            when (imageType) {
                ImageType.LOGO -> {
                    if (seriesId != null && (itemType == BaseItemKind.EPISODE || itemType == BaseItemKind.SEASON)) {
                        getItemImageUrl(
                            itemId = seriesId,
                            imageType = imageType,
                            fillWidth = fillWidth,
                            fillHeight = fillHeight,
                        )
                    } else {
                        getItemImageUrl(
                            itemId = itemId,
                            imageType = imageType,
                            fillWidth = fillWidth,
                            fillHeight = fillHeight,
                        )
                    }
                }

                ImageType.BACKDROP,
                -> {
                    if (seriesId != null && (itemType == BaseItemKind.EPISODE || itemType == BaseItemKind.SEASON)) {
                        getItemImageUrl(
                            itemId = seriesId,
                            imageType = imageType,
                            fillWidth = fillWidth,
                            fillHeight = fillHeight,
                        )
                    } else if (backdropTags.isNotEmpty()) {
                        getItemImageUrl(
                            itemId = itemId,
                            imageType = imageType,
                            fillWidth = fillWidth,
                            fillHeight = fillHeight,
                        )
                    } else {
                        null
                    }
                }

                ImageType.THUMB -> {
                    if (useSeriesForPrimary && parentThumbId != null &&
                        (itemType == BaseItemKind.EPISODE || itemType == BaseItemKind.SEASON)
                    ) {
                        // Use parent's thumb
                        getItemImageUrl(
                            itemId = parentThumbId,
                            imageType = imageType,
                            fillWidth = fillWidth,
                            fillHeight = fillHeight,
                        )
                    } else if (useSeriesForPrimary && parentBackdropId != null &&
                        (itemType == BaseItemKind.EPISODE || itemType == BaseItemKind.SEASON)
                    ) {
                        // No parent thumb, so use backdrop instead
                        getItemImageUrl(
                            itemId = parentBackdropId,
                            imageType = ImageType.BACKDROP,
                            fillWidth = fillWidth,
                            fillHeight = fillHeight,
                        )
                    } else if (parentThumbId != null && itemType == BaseItemKind.SEASON && imageType !in imageTags) {
                        getItemImageUrl(
                            itemId = parentThumbId,
                            imageType = imageType,
                            fillWidth = fillWidth,
                            fillHeight = fillHeight,
                        )
                    } else if (useSeriesForPrimary &&
                        parentThumbId == null &&
                        itemType == BaseItemKind.EPISODE &&
                        imageType !in imageTags
                    ) {
                        // Workaround to fall back to episode image if no parent thumb
                        getItemImageUrl(
                            itemId = itemId,
                            imageType = ImageType.PRIMARY,
                            fillWidth = fillWidth,
                            fillHeight = fillHeight,
                        )
                    } else if (imageType !in imageTags && backdropTags.isNotEmpty()) {
                        // If no thumb, use backdrop if available
                        getItemImageUrl(
                            itemId = itemId,
                            imageType = ImageType.BACKDROP,
                            fillWidth = fillWidth,
                            fillHeight = fillHeight,
                        )
                    } else {
                        getItemImageUrl(
                            itemId = itemId,
                            imageType = imageType,
                            fillWidth = fillWidth,
                            fillHeight = fillHeight,
                        )
                    }
                }

                ImageType.PRIMARY,
                ImageType.BANNER,
                -> {
                    if (useSeriesForPrimary && seriesId != null &&
                        (itemType == BaseItemKind.EPISODE || itemType == BaseItemKind.SEASON)
                    ) {
                        getItemImageUrl(
                            itemId = seriesId,
                            imageType = imageType,
                            fillWidth = fillWidth,
                            fillHeight = fillHeight,
                        )
                    } else if (seriesId != null && itemType == BaseItemKind.SEASON && imageType !in imageTags) {
                        getItemImageUrl(
                            itemId = seriesId,
                            imageType = imageType,
                            fillWidth = fillWidth,
                            fillHeight = fillHeight,
                        )
                    } else {
                        getItemImageUrl(
                            itemId = itemId,
                            imageType = imageType,
                            fillWidth = fillWidth,
                            fillHeight = fillHeight,
                        )
                    }
                }

                else -> {
                    getItemImageUrl(
                        itemId = itemId,
                        imageType = imageType,
                        fillWidth = fillWidth,
                        fillHeight = fillHeight,
                    )
                }
            }

        fun getItemImageUrl(
            item: BaseItem?,
            imageType: ImageType,
            fillWidth: Int? = null,
            fillHeight: Int? = null,
            useSeriesForPrimary: Boolean? = null,
        ): String? =
            if (item != null) {
                withTag(
                    item,
                    getItemImageUrl(
                    itemId = item.id,
                    itemType = item.type,
                    seriesId = item.data.seriesId,
                    useSeriesForPrimary = useSeriesForPrimary ?: item.useSeriesForPrimary,
                    imageTags = item.data.imageTags.orEmpty(),
                    imageType = imageType,
                    parentThumbId = item.data.parentThumbItemId,
                    parentBackdropId = item.data.parentBackdropItemId,
                    backdropTags = item.data.backdropImageTags.orEmpty(),
                    fillWidth = fillWidth,
                    fillHeight = fillHeight,
                    ),
                )
            } else {
                null
            }

        /**
         * Platea: adds the image's tag (its fingerprint on the server) to the address, so the device cache
         * notices when a poster or logo is replaced instead of showing the old one forever.
         */
        private fun withTag(
            item: BaseItem,
            url: String?,
        ): String? {
            if (url == null || "tag=" in url) return url
            val match = IMAGE_PATH.find(url) ?: return url
            val id = match.groupValues[1].replace("-", "")
            val type = match.groupValues[2]
            val dto = item.data
            val tag =
                when (id) {
                    item.id.toString().replace("-", "") ->
                        if (type.equals("Backdrop", true)) dto.backdropImageTags?.firstOrNull() else dto.imageTags?.entries?.firstOrNull { it.key.serialName.equals(type, true) }?.value
                    dto.seriesId?.toString()?.replace("-", "") ->
                        when (type.lowercase()) {
                            "primary" -> dto.seriesPrimaryImageTag
                            "logo" -> dto.parentLogoImageTag
                            "thumb" -> dto.parentThumbImageTag
                            "backdrop" -> dto.parentBackdropImageTags?.firstOrNull()
                            else -> null
                        }
                    else -> null
                } ?: return url
            return url + (if ("?" in url) "&" else "?") + "tag=" + tag
        }

        /**
         * An image of the item itself, by type, with its tag. Null when the item has no such image, so the page
         * shows its fallback at once instead of asking the server for something that is not there.
         */
        fun getOwnImageUrl(
            item: BaseItem,
            imageType: ImageType,
            maxWidth: Int? = null,
            maxHeight: Int? = null,
        ): String? {
            val tag =
                if (imageType == ImageType.BACKDROP) item.data.backdropImageTags?.firstOrNull() else item.data.imageTags?.get(imageType)
            return tag?.let { getItemImageUrl(itemId = item.id, imageType = imageType, maxWidth = maxWidth, maxHeight = maxHeight, tag = it) }
        }

        fun getItemImageUrl(
            itemId: UUID,
            imageType: ImageType,
            maxWidth: Int? = null,
            maxHeight: Int? = null,
            width: Int? = null,
            height: Int? = null,
            quality: Int? = QUALITY,
            fillWidth: Int? = null,
            fillHeight: Int? = null,
            tag: String? = null,
            format: ImageFormat? = null,
            percentPlayed: Double? = null,
            unplayedCount: Int? = null,
            blur: Int? = null,
            backgroundColor: String? = null,
            foregroundLayer: String? = null,
            imageIndex: Int? = null,
        ): String? {
            if (api.baseUrl.isNullOrBlank()) return null
            return api.imageApi.getItemImageUrl(
                itemId = itemId,
                imageType = imageType,
                maxWidth = maxWidth,
                maxHeight = maxHeight,
                width = width?.takeIf { it > 0 },
                height = height?.takeIf { it > 0 },
                quality = quality,
                fillWidth = fillWidth?.takeIf { it > 0 },
                fillHeight = fillHeight?.takeIf { it > 0 },
                tag = tag,
                format = format,
                percentPlayed = percentPlayed,
                unplayedCount = unplayedCount,
                blur = blur,
                backgroundColor = backgroundColor,
                foregroundLayer = foregroundLayer,
                imageIndex = imageIndex,
            )
        }

        fun getUserImageUrl(userId: UUID) = api.imageApi.getUserImageUrl(userId)

        /**
         * Just a convenient way to get the image URL and remember it
         */
        @Composable
        fun rememberImageUrl(
            item: BaseItem?,
            imageType: ImageType = ImageType.PRIMARY,
        ) = remember(item, imageType) {
            if (item != null) {
                getItemImageUrl(item, imageType)
            } else {
                null
            }
        }

        companion object {
            private const val QUALITY = 96
            private val IMAGE_PATH = Regex("/Items/([0-9a-fA-F-]{32,36})/Images/([A-Za-z]+)")
        }
    }
