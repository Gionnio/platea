package com.github.gionnio.platea.services

import android.content.Context
import android.content.pm.PackageInfo
import android.os.Build
import androidx.core.content.edit
import androidx.datastore.core.DataStore
import androidx.work.WorkManager
import androidx.preference.PreferenceManager
import com.github.gionnio.platea.data.JellyfinServerDao
import com.github.gionnio.platea.preferences.AppPreference
import com.github.gionnio.platea.preferences.AppPreferences
import com.github.gionnio.platea.preferences.ExperimentalPreference
import com.github.gionnio.platea.preferences.ScreensaverPreference
import com.github.gionnio.platea.preferences.resetSubtitles
import com.github.gionnio.platea.preferences.update
import com.github.gionnio.platea.preferences.updateAdvancedPreferences
import com.github.gionnio.platea.preferences.updateExperimentalPreferences
import com.github.gionnio.platea.preferences.updateHomePagePreferences
import com.github.gionnio.platea.preferences.updateInterfacePreferences
import com.github.gionnio.platea.preferences.updatePlaybackOverrides
import com.github.gionnio.platea.preferences.updatePlaybackPreferences
import com.github.gionnio.platea.preferences.updateScreensaverPreferences
import com.github.gionnio.platea.preferences.updateSubtitlePreferences
import com.github.gionnio.platea.ui.preferences.subtitle.SubtitleSettings
import com.github.gionnio.platea.ui.preferences.subtitle.shouldEnableSeparateHdrToggle
import com.github.gionnio.platea.util.Version
import dagger.hilt.android.qualifiers.ApplicationContext
import org.jellyfin.sdk.model.api.BaseItemKind
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles any changes needed when the app in upgraded on the device such as setting new preferences
 */
@Singleton
class AppUpgradeHandler
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val appPreferences: DataStore<AppPreferences>,
        private val serverDao: JellyfinServerDao,
    ) {
        val pkgInfo: PackageInfo get() = context.packageManager.getPackageInfo(context.packageName, 0)
        val currentVersion: Version get() = Version.fromString(pkgInfo.versionName!!)

        fun needUpgrade(): Boolean {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            val previousVersion = prefs.getString(VERSION_NAME_CURRENT_KEY, null)
            val previousVersionCode = prefs.getLong(VERSION_CODE_CURRENT_KEY, -1)

            val newVersion = pkgInfo.versionName!!
            val newVersionCode =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    pkgInfo.longVersionCode
                } else {
                    pkgInfo.versionCode.toLong()
                }
            Timber.i(
                "App versions: $previousVersion=>$newVersion, $previousVersionCode=>$newVersionCode",
            )
            return newVersion != previousVersion || newVersionCode != previousVersionCode
        }

        suspend fun run() {
            Timber.i("App upgrade started")
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            val previousVersion = prefs.getString(VERSION_NAME_CURRENT_KEY, null)
            val previousVersionCode = prefs.getLong(VERSION_CODE_CURRENT_KEY, -1)

            val newVersion = pkgInfo.versionName!!
            val newVersionCode =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    pkgInfo.longVersionCode
                } else {
                    pkgInfo.versionCode.toLong()
                }
            // Store the previous and new version info
            prefs.edit(true) {
                putString(VERSION_NAME_PREVIOUS_KEY, previousVersion)
                putLong(VERSION_CODE_PREVIOUS_KEY, previousVersionCode)
                putString(VERSION_NAME_CURRENT_KEY, newVersion)
                putLong(VERSION_CODE_CURRENT_KEY, newVersionCode)
            }
            cancelLegacyWork()
            try {
                upgradeApp(
                    Version.fromString(previousVersion ?: "0.0.0"),
                    Version.fromString(newVersion),
                    appPreferences,
                )
            } catch (ex: Exception) {
                Timber.e(ex, "Exception during app upgrade")
            }
            try {
                plateaDefaults(previousVersion)
            } catch (ex: Exception) {
                Timber.e(ex, "Exception while setting the Platea defaults")
            }
            Timber.i("App upgrade complete")
        }

        /**
         * Background work scheduled before the package was renamed points at classes that no longer exist
         * (WorkManager tags every request with its worker class): cancel it, the schedulers enqueue it again
         * under the new names.
         */
        private fun cancelLegacyWork() {
            try {
                val workManager = WorkManager.getInstance(context)
                LEGACY_WORKERS.forEach { workManager.cancelAllWorkByTag(it) }
                workManager.pruneWork()
            } catch (ex: Exception) {
                Timber.w(ex, "Could not cancel the work scheduled by older versions")
            }
        }

        /** Clean-up of what earlier versions left on the device. */
        private fun plateaDefaults(previousVersion: String?) {
            // Cache of the online theme archive, removed in 1.2.0
            if (previousVersion != null) java.io.File(context.cacheDir, "platea-themes").deleteRecursively()
        }

        companion object {
            const val VERSION_NAME_PREVIOUS_KEY = "version.previous.name"
            const val VERSION_CODE_PREVIOUS_KEY = "version.previous.code"
            const val VERSION_NAME_CURRENT_KEY = "version.current.name"
            const val VERSION_CODE_CURRENT_KEY = "version.current.code"

            private val LEGACY_WORKERS =
                listOf(
                    "com.github.damontecres.wholphin.services.LatestNextUpWorker",
                    "com.github.damontecres.wholphin.services.SuggestionsWorker",
                    "com.github.damontecres.wholphin.services.tvprovider.TvProviderWorker",
                )
        }

        /**
         * Perform any needed upgrades
         */
        suspend fun upgradeApp(
            previous: Version,
            current: Version,
            appPreferences: DataStore<AppPreferences>,
        ) {
            if (previous.isEqualOrBefore(Version.fromString("0.1.0-2-g0"))) {
                appPreferences.updateData {
                    it.updatePlaybackOverrides {
                        ac3Supported = AppPreference.Ac3Supported.defaultValue
                        downmixStereo = AppPreference.DownMixStereo.defaultValue
//                        directPlayAss = AppPreference.DirectPlayAss.defaultValue
                        directPlayPgs = AppPreference.DirectPlayPgs.defaultValue
                    }
                }
            }
            if (previous.isEqualOrBefore(Version.fromString("0.2.5-11-g0"))) {
                appPreferences.updateData {
                    it.updateInterfacePreferences {
                        showClock = AppPreference.ShowClock.defaultValue
                    }
                }
            }
            if (previous.isEqualOrBefore(Version.fromString("0.2.7-1-g0"))) {
                appPreferences.updateData { it.updateSubtitlePreferences { resetSubtitles() } }
            }
            if (previous.isEqualOrBefore(Version.fromString("0.3.2-4-g0"))) {
                appPreferences.updateData {
                    it.updateSubtitlePreferences {
                        margin = SubtitleSettings.Margin.defaultValue.toInt()
                    }
                }
            }

            if (previous.isEqualOrBefore(Version.fromString("0.3.4"))) {
                appPreferences.updateData {
                    it.updateAdvancedPreferences {
                        if (imageDiskCacheSizeBytes < (AppPreference.ImageDiskCacheSize.min * AppPreference.MEGA_BIT)) {
                            imageDiskCacheSizeBytes =
                                AppPreference.ImageDiskCacheSize.defaultValue * AppPreference.MEGA_BIT
                        }
                    }
                }
            }

            if (previous.isEqualOrBefore(Version.fromString("0.3.4-4-g0"))) {
                appPreferences.updateData {
                    it.update {
                        signInAutomatically = AppPreference.SignInAuto.defaultValue
                    }
                }
            }

            if (previous.isEqualOrBefore(Version.fromString("0.3.5-0-g0"))) {
                appPreferences.updateData {
                    it.updateSubtitlePreferences {
                        if (edgeThickness < 1) {
                            edgeThickness = SubtitleSettings.EdgeThickness.defaultValue.toInt()
                        }
                    }
                }
            }
            if (previous.isEqualOrBefore(Version.fromString("0.4.1-6-g0"))) {
                appPreferences.updateData {
                    it.updateInterfacePreferences {
                        subtitlesPreferences =
                            subtitlesPreferences
                                .toBuilder()
                                .apply {
                                    imageSubtitleOpacity =
                                        SubtitleSettings.ImageOpacity.defaultValue.toInt()
                                }.build()
                        // Copy current subtitle prefs as HDR ones
                        hdrSubtitlesPreferences = subtitlesPreferences.toBuilder().build()
                    }
                }
            }

            if (previous.isEqualOrBefore(Version.fromString("0.4.1-14-g0"))) {
                appPreferences.updateData {
                    it.updateHomePagePreferences {
                        maxDaysNextUp = AppPreference.MaxDaysNextUp.defaultValue.toInt()
                    }
                }
            }

            if (previous.isEqualOrBefore(Version.fromString("0.5.3-0-g0"))) {
                appPreferences.updateData {
                    it.updateScreensaverPreferences {
                        startDelay = ScreensaverPreference.DEFAULT_START_DELAY
                        duration = ScreensaverPreference.DEFAULT_DURATION
                        animate = ScreensaverPreference.Animate.defaultValue
                        maxAgeFilter = ScreensaverPreference.DEFAULT_MAX_AGE
                        clearItemTypes()
                        addItemTypes(BaseItemKind.MOVIE.serialName)
                        addItemTypes(BaseItemKind.SERIES.serialName)
                    }
                }
            }

            if (previous.isEqualOrBefore(Version.fromString("0.6.2-1-g0"))) {
                appPreferences.updateData {
                    it.updatePlaybackOverrides {
                        assPlaybackMode = AppPreference.AssSubtitleMode.defaultValue
                    }
                }
            }

            if (previous.isEqualOrBefore(Version.fromString("0.6.2-18-g0"))) {
                appPreferences.updateData {
                    it.updatePlaybackPreferences { cinemaMode = AppPreference.CinemaMode.defaultValue }
                }
            }

            if (previous.isEqualOrBefore(Version.fromString("1.0.2-8-g0"))) {
                appPreferences.updateData {
                    it.updateSubtitlePreferences {
                        useSeparateHdr = it.interfacePreferences.shouldEnableSeparateHdrToggle()
                    }
                }
            }

            if (previous.isEqualOrBefore(Version.fromString("1.0.6-0-g0"))) {
                appPreferences.updateData {
                    it.updateScreensaverPreferences {
                        dimPercent = ScreensaverPreference.DimPercentage.defaultValue.toInt()
                    }
                }
            }

            if (previous.isEqualOrBefore(Version.fromString("1.0.6-7-g0"))) {
                // preferAc3Surround was mistaken enabled by default, reset it only if the user hasn't enabled experimental settings
                appPreferences.updateData {
                    if (!it.experimentalPreferences.enabled) {
                        it.updateExperimentalPreferences {
                            preferAc3Surround =
                                ExperimentalPreference.PreferAc3ForSurround.defaultValue
                        }
                    } else {
                        it
                    }
                }
            }
        }
    }
