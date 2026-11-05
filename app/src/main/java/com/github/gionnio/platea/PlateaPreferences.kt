package com.github.gionnio.platea

import com.github.gionnio.platea.preferences.AppPreferences
import com.github.gionnio.platea.preferences.AppSwitchPreference
import com.github.gionnio.platea.preferences.updateInterfacePreferences
import com.github.gionnio.platea.preferences.updatePlaybackOverrides

/** Platea settings, shown in the ExoPlayer options. Every one can be switched off. */
object PlateaPreferences {
    val PlateaProfile =
        AppSwitchPreference<AppPreferences>(
            title = R.string.platea_profile,
            defaultValue = true,
            getter = { !it.playbackPreferences.overrides.plateaUseUpstreamProfile },
            setter = { prefs, value ->
                prefs.updatePlaybackOverrides { plateaUseUpstreamProfile = !value }
            },
            summaryOn = R.string.platea_profile_on,
            summaryOff = R.string.platea_profile_off,
        )

    val ExcludeDoviHdr10Plus =
        AppSwitchPreference<AppPreferences>(
            title = R.string.platea_exclude_dovi_hdr10plus,
            defaultValue = false,
            getter = { it.playbackPreferences.overrides.plateaExcludeDoviHdr10Plus },
            setter = { prefs, value ->
                prefs.updatePlaybackOverrides { plateaExcludeDoviHdr10Plus = value }
            },
            summary = R.string.platea_exclude_dovi_hdr10plus_summary,
        )

    val HomeCache =
        AppSwitchPreference<AppPreferences>(
            title = R.string.platea_home_cache,
            defaultValue = true,
            getter = { !it.interfacePreferences.plateaNoHomeCache },
            setter = { prefs, value ->
                prefs.updateInterfacePreferences { plateaNoHomeCache = !value }
            },
            summary = R.string.platea_home_cache_summary,
        )

    val AutoUpdate =
        AppSwitchPreference<AppPreferences>(
            title = R.string.platea_auto_update,
            defaultValue = true,
            getter = { !it.interfacePreferences.plateaNoAutoUpdate },
            setter = { prefs, value ->
                prefs.updateInterfacePreferences { plateaNoAutoUpdate = !value }
            },
            summary = R.string.platea_auto_update_summary,
        )

    val ForeignAudioSubtitles =
        AppSwitchPreference<AppPreferences>(
            title = R.string.platea_foreign_audio_subtitles,
            defaultValue = true,
            getter = { !it.playbackPreferences.overrides.plateaNoForeignAudioSubtitles },
            setter = { prefs, value ->
                prefs.updatePlaybackOverrides { plateaNoForeignAudioSubtitles = !value }
            },
            summary = R.string.platea_foreign_audio_subtitles_summary,
        )

    val AutoTranscode =
        AppSwitchPreference<AppPreferences>(
            title = R.string.platea_auto_transcode,
            defaultValue = true,
            getter = { !it.playbackPreferences.overrides.plateaNoAutoTranscode },
            setter = { prefs, value ->
                prefs.updatePlaybackOverrides { plateaNoAutoTranscode = !value }
            },
            summary = R.string.platea_auto_transcode_summary,
        )
}
