package com.github.gionnio.platea

import android.app.Application
import android.os.StrictMode
import android.system.Os
import android.util.Log
import androidx.compose.runtime.Composer
import androidx.compose.runtime.ExperimentalComposeRuntimeApi
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import org.jellyfin.sdk.Jellyfin
import org.jellyfin.sdk.model.ServerVersion
import timber.log.Timber
import javax.inject.Inject

@OptIn(ExperimentalComposeRuntimeApi::class)
@HiltAndroidApp
class PlateaApplication :
    Application(),
    Configuration.Provider {
    init {
        instance = this

        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        } else {
            Timber.plant(
                object : Timber.Tree() {
                    override fun isLoggable(
                        tag: String?,
                        priority: Int,
                    ): Boolean = priority >= Log.INFO

                    override fun log(
                        priority: Int,
                        tag: String?,
                        message: String,
                        t: Throwable?,
                    ) {
                        Log.println(priority, tag ?: "Platea", message)
                    }
                },
            )
        }

        Composer.setDiagnosticStackTraceEnabled(BuildConfig.DEBUG)
    }

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy
                    .Builder()
                    .detectNetwork()
                    .penaltyLog()
                    .penaltyDeathOnNetwork()
                    .build(),
            )
//            StrictMode.setVmPolicy(
//                StrictMode.VmPolicy
//                    .Builder()
//                    .detectAll()
//                    .penaltyLog()
//                    .build(),
//            )
        }
        // Platea: the ASS renderer (libass with fontconfig) scans every system font each time it starts unless it
        // can save its font list. Its built-in configuration looks for a cache under XDG_CACHE_HOME, which
        // Android does not set: point it at the app cache, so the scan happens once and not at every title.
        runCatching { Os.setenv("XDG_CACHE_HOME", cacheDir.absolutePath, true) }
            .onFailure { Timber.w(it, "Cannot set the font cache directory") }
    }

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() =
            Configuration
                .Builder()
                .setWorkerFactory(workerFactory)
                .build()

    companion object {
        lateinit var instance: PlateaApplication
            private set

        val minimumServerVersion: ServerVersion = Jellyfin.minimumVersion
    }
}
