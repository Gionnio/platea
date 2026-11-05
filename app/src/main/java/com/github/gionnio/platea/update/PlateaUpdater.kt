package com.github.gionnio.platea.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import androidx.core.content.edit
import androidx.core.content.pm.PackageInfoCompat
import androidx.datastore.core.DataStore
import com.github.gionnio.platea.engine.UpdateApk
import com.github.gionnio.platea.engine.UpdateChoice
import com.github.gionnio.platea.engine.UpdateRelease
import com.github.gionnio.platea.preferences.AppPreferences
import com.github.gionnio.platea.services.hilt.StandardOkHttpClient
import com.github.gionnio.platea.util.PlateaDispatchers
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/** Where the self-update of the release build stands. */
sealed interface UpdateState {
    data object Idle : UpdateState

    data object UpToDate : UpdateState

    data class Available(
        val release: UpdateRelease,
        val apk: UpdateApk,
    ) : UpdateState

    data class Downloading(
        val release: UpdateRelease,
        val percent: Int,
    ) : UpdateState

    data class Installing(
        val release: UpdateRelease,
    ) : UpdateState

    data class Failed(
        val message: String,
    ) : UpdateState
}

/**
 * Self-update of the release build from the GitHub releases: `scripts/publish-update.sh` publishes each
 * release with `latest.json` and one APK per ABI; Platea reads the latest one, checks size and SHA-256 and
 * installs the APK over itself with Android's package installer, which also refuses an APK signed with
 * another key. Development builds never update themselves.
 */
@Singleton
class PlateaUpdater
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val preferences: DataStore<AppPreferences>,
        @param:StandardOkHttpClient private val http: OkHttpClient,
    ) {
        private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
        val state: StateFlow<UpdateState> = _state

        /** Only the release build updates itself; the development one is installed with ADB. */
        val available: Boolean get() = !context.packageName.endsWith(".debug")

        private val prefs = context.getSharedPreferences("platea_update", Context.MODE_PRIVATE)
        private var lastCheck = 0L

        val installedVersion: String get() = packageInfo().versionName.orEmpty()

        /**
         * Looks for a newer release. Automatic checks (home) run at most every few hours and only when the
         * setting is on; [manual] (settings) always runs and also reports "up to date" and errors.
         */
        suspend fun check(manual: Boolean = false): UpdateState =
            withContext(PlateaDispatchers.IO) {
                val now = System.currentTimeMillis()
                when {
                    !available -> return@withContext _state.value
                    _state.value is UpdateState.Downloading || _state.value is UpdateState.Installing -> return@withContext _state.value
                    !manual && preferences.data.first().interfacePreferences.plateaNoAutoUpdate -> return@withContext _state.value
                    !manual && now - lastCheck < CHECK_EVERY_MS -> return@withContext _state.value
                }
                lastCheck = now
                val next =
                    runCatching {
                        val release = readRelease()
                        val apk = UpdateChoice.pick(release, installedCode(), Build.SUPPORTED_ABIS.toList())
                        if (apk == null) UpdateState.UpToDate else UpdateState.Available(release, apk)
                    }.getOrElse {
                        Timber.w(it, "Update check failed")
                        if (manual) UpdateState.Failed(it.message ?: it.javaClass.simpleName) else UpdateState.Idle
                    }
                _state.value = next
                next
            }

        /** "Later" on the home: the same release is not offered again until Platea restarts. */
        fun postpone() {
            (_state.value as? UpdateState.Available)?.let { dismissed = it.release.versionCode }
            _state.value = UpdateState.Idle
        }

        var dismissed = 0L
            private set

        /** Downloads the APK, checks size and SHA-256, then hands it to the package installer. */
        suspend fun install(
            release: UpdateRelease,
            apk: UpdateApk,
        ) = withContext(PlateaDispatchers.IO) {
            if (!available) return@withContext
            _state.value = UpdateState.Downloading(release, 0)
            runCatching {
                val file = download(apk) { _state.value = UpdateState.Downloading(release, it) }
                _state.value = UpdateState.Installing(release)
                prefs.edit { putLong(KEY_STARTED, System.currentTimeMillis()) }
                commit(file)
            }.onFailure {
                Timber.w(it, "Update install failed")
                _state.value = UpdateState.Failed(it.message ?: it.javaClass.simpleName)
            }
        }

        fun clearFailed() {
            if (_state.value is UpdateState.Failed) _state.value = UpdateState.Idle
        }

        /** Result of the installer (see [UpdateReceiver]) when it did not succeed. */
        fun installFailed(message: String) {
            _state.value = UpdateState.Failed(message)
        }

        /** True once, right after Platea was replaced by an update it started itself (to reopen it). */
        fun consumeJustUpdated(): Boolean {
            val started = prefs.getLong(KEY_STARTED, 0)
            prefs.edit { remove(KEY_STARTED) }
            return started > 0 && System.currentTimeMillis() - started < REOPEN_WINDOW_MS
        }

        private fun packageInfo() = context.packageManager.getPackageInfo(context.packageName, 0)

        private fun installedCode(): Long = PackageInfoCompat.getLongVersionCode(packageInfo())

        private fun readRelease(): UpdateRelease {
            val text = open(MANIFEST).use { it.readBytes().toString(Charsets.UTF_8) }
            val json = JSONObject(text)
            val apks = json.getJSONObject("apks")
            return UpdateRelease(
                versionCode = json.getLong("versionCode"),
                versionName = json.getString("versionName"),
                apks =
                    apks.keys().asSequence().associateWith { abi ->
                        val a = apks.getJSONObject(abi)
                        UpdateApk(a.getString("file"), a.getString("sha256").lowercase(), a.getLong("size"))
                    },
            )
        }

        private fun download(
            apk: UpdateApk,
            progress: (Int) -> Unit,
        ): File {
            val dir = File(context.cacheDir, "update").apply { deleteRecursively(); mkdirs() }
            val file = File(dir, "platea.apk")
            val digest = MessageDigest.getInstance("SHA-256")
            var done = 0L
            var shown = -1
            open(apk.file).use { input ->
                file.outputStream().use { out ->
                    val buffer = ByteArray(1 shl 16)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        digest.update(buffer, 0, n)
                        done += n
                        val percent = (done * 100 / apk.size).toInt().coerceIn(0, 100)
                        if (percent != shown) {
                            shown = percent
                            progress(percent)
                        }
                    }
                }
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            if (done != apk.size || hash != apk.sha256) {
                file.delete()
                throw IOException("APK damaged: $done of ${apk.size} bytes, SHA-256 $hash")
            }
            return file
        }

        /** A file of the latest GitHub release: `releases/latest/download/<file>` is a plain download (no API, no account, no rate limit). */
        private fun open(name: String): InputStream {
            val response = http.newCall(Request.Builder().url(GITHUB_LATEST + Uri.encode(name)).build()).execute()
            if (!response.isSuccessful) {
                response.close()
                throw IOException("GitHub: HTTP ${response.code}")
            }
            return response.body.byteStream()
        }

        private fun commit(file: File) {
            val installer = context.packageManager.packageInstaller
            val params =
                PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                    setAppPackageName(context.packageName)
                    setSize(file.length())
                    // Android 12+: once Platea is the installer of record, its own updates need no confirmation
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                }
            val id = installer.createSession(params)
            installer.openSession(id).use { session ->
                session.openWrite("platea.apk", 0, file.length()).use { out ->
                    file.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
                val intent = Intent(context, UpdateReceiver::class.java).setAction(UpdateReceiver.ACTION_STATUS)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
                session.commit(PendingIntent.getBroadcast(context, id, intent, flags).intentSender)
            }
        }

        companion object {
            const val MANIFEST = "latest.json"
            const val GITHUB_LATEST = "https://github.com/Gionnio/platea/releases/latest/download/"
            private const val KEY_STARTED = "install_started"
            private const val CHECK_EVERY_MS = 6 * 60 * 60 * 1000L
            private const val REOPEN_WINDOW_MS = 10 * 60 * 1000L
        }
    }
