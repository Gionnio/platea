package com.github.gionnio.platea.ui.components

import com.github.gionnio.platea.R
import com.github.gionnio.platea.preferences.AppChoicePreference
import com.github.gionnio.platea.preferences.AppClickablePreference
import com.github.gionnio.platea.preferences.AppSliderPreference
import com.github.gionnio.platea.preferences.AppSwitchPreference
import com.github.gionnio.platea.preferences.PrefContentScale
import com.github.gionnio.platea.ui.AspectRatio
import kotlinx.serialization.Serializable
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType

@Serializable
data class ViewOptions(
    val columns: Int = 6,
    val spacing: Int = 16,
    val contentScale: PrefContentScale = PrefContentScale.FIT,
    val aspectRatio: AspectRatio = AspectRatio.TALL,
    val showDetails: Boolean = false,
    val showBackdrop: Boolean = false,
    val imageType: ViewOptionImageType = ViewOptionImageType.PRIMARY,
    val showTitles: Boolean = true,
    val type: ViewOptionsType = ViewOptionsType.GRID,
) {
    companion object {
        val ViewOptionsColumns =
            AppSliderPreference<ViewOptions>(
                title = R.string.columns,
                defaultValue = 6,
                min = 1,
                max = 12,
                interval = 1,
                getter = { it.columns.toLong() },
                setter = { prefs, value -> prefs.copy(columns = value.toInt()) },
            )
        val ViewOptionsSpacing =
            AppSliderPreference<ViewOptions>(
                title = R.string.spacing,
                defaultValue = 16,
                min = 0,
                max = 32,
                interval = 2,
                getter = { it.spacing.toLong() },
                setter = { prefs, value -> prefs.copy(spacing = value.toInt()) },
            )

        val ViewOptionsContentScale =
            AppChoicePreference<ViewOptions, PrefContentScale>(
                title = R.string.global_content_scale,
                defaultValue = PrefContentScale.FIT,
                displayValues = R.array.content_scale,
                getter = { it.contentScale },
                setter = { viewOptions, value -> viewOptions.copy(contentScale = value) },
                indexToValue = { PrefContentScale.forNumber(it) },
                valueToIndex = { it.number },
            )

        val ViewOptionsAspectRatio =
            AppChoicePreference<ViewOptions, AspectRatio>(
                title = R.string.aspect_ratio,
                defaultValue = AspectRatio.TALL,
                displayValues = R.array.aspect_ratios,
                getter = { it.aspectRatio },
                setter = { viewOptions, value -> viewOptions.copy(aspectRatio = value) },
                indexToValue = { AspectRatio.entries[it] },
                valueToIndex = { it.ordinal },
            )

        val ViewOptionsDetailHeader =
            AppSwitchPreference<ViewOptions>(
                title = R.string.show_details,
                defaultValue = false,
                getter = { it.showDetails },
                setter = { vo, value -> vo.copy(showDetails = value) },
            )
        val ViewOptionsBackdrop =
            AppSwitchPreference<ViewOptions>(
                title = R.string.show_backdrop,
                defaultValue = false,
                getter = { it.showBackdrop },
                setter = { vo, value -> vo.copy(showBackdrop = value) },
            )
        val ViewOptionsShowTitles =
            AppSwitchPreference<ViewOptions>(
                title = R.string.show_titles,
                defaultValue = true,
                getter = { it.showTitles },
                setter = { vo, value -> vo.copy(showTitles = value) },
            )

        val ViewOptionsImageType =
            AppChoicePreference<ViewOptions, ViewOptionImageType>(
                title = R.string.image_type,
                defaultValue = ViewOptionImageType.PRIMARY,
                displayValues = R.array.image_types,
                getter = { it.imageType },
                setter = { viewOptions, value ->
                    val aspectRatio =
                        when (value) {
                            ViewOptionImageType.PRIMARY -> AspectRatio.TALL
                            ViewOptionImageType.THUMB -> AspectRatio.WIDE
                        }
                    viewOptions.copy(imageType = value, aspectRatio = aspectRatio)
                },
                indexToValue = { ViewOptionImageType.entries[it] },
                valueToIndex = { it.ordinal },
            )

        val ViewOptionsTypePref =
            AppChoicePreference<ViewOptions, ViewOptionsType>(
                title = R.string.layout,
                defaultValue = ViewOptionsType.GRID,
                displayValues = R.array.view_options_types,
                getter = { it.type },
                setter = { viewOptions, value ->
                    val spacing =
                        when (value) {
                            ViewOptionsType.GRID -> 16
                            ViewOptionsType.LIST -> 4
                            ViewOptionsType.DENSE_LIST -> 2
                        }
                    viewOptions.copy(type = value, spacing = spacing)
                },
                indexToValue = { ViewOptionsType.entries[it] },
                valueToIndex = { it.ordinal },
            )

        val ViewOptionsReset =
            AppClickablePreference<ViewOptions>(
                title = R.string.reset,
            )

        val GRID_OPTIONS =
            listOf(
                ViewOptionsTypePref,
                ViewOptionsImageType,
                ViewOptionsAspectRatio,
                ViewOptionsDetailHeader,
                ViewOptionsBackdrop,
                ViewOptionsShowTitles,
                ViewOptionsColumns,
                ViewOptionsSpacing,
                ViewOptionsContentScale,
                ViewOptionsReset,
            )

        val LIST_OPTIONS =
            listOf(
                ViewOptionsTypePref,
                ViewOptionsDetailHeader,
                ViewOptionsBackdrop,
                ViewOptionsSpacing,
                ViewOptionsReset,
            )
    }
}

val ViewOptionsPoster =
    ViewOptions(
        columns = 6,
        spacing = 16,
        contentScale = PrefContentScale.FILL,
    )
val ViewOptionsWide =
    ViewOptions(
        columns = 4,
        spacing = 24,
        contentScale = PrefContentScale.CROP,
        aspectRatio = AspectRatio.WIDE,
    )
val ViewOptionsSquare =
    ViewOptions(
        columns = 6,
        spacing = 16,
        contentScale = PrefContentScale.FILL,
        aspectRatio = AspectRatio.SQUARE,
    )

enum class ViewOptionImageType(
    val imageType: ImageType,
) {
    PRIMARY(ImageType.PRIMARY),
    THUMB(ImageType.THUMB),
//    BANNER(ImageType.BANNER),
}

enum class ViewOptionsType {
    GRID,
    LIST,
    DENSE_LIST,
}

val BaseItemKind.defaultViewOptions: ViewOptions
    get() =
        when (this) {
            BaseItemKind.AUDIO,
            BaseItemKind.MUSIC_ALBUM,
            BaseItemKind.MUSIC_ARTIST,
            BaseItemKind.PLAYLIST,
            -> ViewOptionsSquare

            BaseItemKind.EPISODE,
            BaseItemKind.VIDEO,
            BaseItemKind.MUSIC_VIDEO,
            BaseItemKind.PHOTO,
            BaseItemKind.PHOTO_ALBUM,
            BaseItemKind.TV_CHANNEL,
            BaseItemKind.LIVE_TV_CHANNEL,
            BaseItemKind.PROGRAM,
            BaseItemKind.TV_PROGRAM,
            BaseItemKind.LIVE_TV_PROGRAM,
            -> ViewOptionsWide

            else -> ViewOptionsPoster
        }
