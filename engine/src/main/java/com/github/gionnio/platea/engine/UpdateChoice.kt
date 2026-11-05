package com.github.gionnio.platea.engine

/** One APK of a Platea release published on the NAS. */
data class UpdateApk(
    val file: String,
    val sha256: String,
    val size: Long,
)

/** The release described by `latest.json` in the updates folder of the NAS. */
data class UpdateRelease(
    val versionCode: Long,
    val versionName: String,
    /** By ABI (`armeabi-v7a`, `arm64-v8a`, …), plus [UpdateChoice.UNIVERSAL] for the APK with every ABI. */
    val apks: Map<String, UpdateApk>,
)

/** Which APK of a release, if any, this device should install. */
object UpdateChoice {
    const val UNIVERSAL = "universal"

    /**
     * The APK for the first of [supportedAbis] (the device's preferred ABI comes first) that the release has,
     * else the universal one; null when the release is not newer than [installedCode] or nothing fits.
     */
    fun pick(
        release: UpdateRelease,
        installedCode: Long,
        supportedAbis: List<String>,
    ): UpdateApk? {
        if (release.versionCode <= installedCode) return null
        val apk = supportedAbis.firstNotNullOfOrNull { release.apks[it] } ?: release.apks[UNIVERSAL] ?: return null
        return apk.takeIf { safeName(it.file) && it.sha256.length == 64 && it.size > 0 }
    }

    /** A plain file name inside the updates folder: no folders, no hidden files. */
    fun safeName(file: String): Boolean = file.isNotBlank() && '/' !in file && '\\' !in file && !file.startsWith(".")
}
