package com.github.gionnio.platea.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdateChoiceTest {
    private val hash = "a".repeat(64)
    private val v7a = UpdateApk("Platea-armeabi-v7a.apk", hash, 10)
    private val arm64 = UpdateApk("Platea-arm64-v8a.apk", hash, 11)
    private val universal = UpdateApk("Platea.apk", hash, 30)
    private val release = UpdateRelease(1960, "1.1.0-1960", mapOf("armeabi-v7a" to v7a, "arm64-v8a" to arm64, UpdateChoice.UNIVERSAL to universal))

    @Test
    fun picksThePreferredAbi() {
        assertEquals(arm64, UpdateChoice.pick(release, 1956, listOf("arm64-v8a", "armeabi-v7a")))
        assertEquals(v7a, UpdateChoice.pick(release, 1956, listOf("armeabi-v7a", "armeabi")))
    }

    @Test
    fun fallsBackToUniversal() {
        assertEquals(universal, UpdateChoice.pick(release, 1956, listOf("x86")))
        assertNull(UpdateChoice.pick(release.copy(apks = mapOf("arm64-v8a" to arm64)), 1956, listOf("x86")))
    }

    @Test
    fun onlyNewerReleases() {
        assertNull(UpdateChoice.pick(release, 1960, listOf("armeabi-v7a")))
        assertNull(UpdateChoice.pick(release, 1970, listOf("armeabi-v7a")))
    }

    @Test
    fun rejectsBadEntries() {
        val bad = release.copy(apks = mapOf("armeabi-v7a" to v7a.copy(file = "../x.apk")))
        assertNull(UpdateChoice.pick(bad, 1, listOf("armeabi-v7a")))
        val noHash = release.copy(apks = mapOf("armeabi-v7a" to v7a.copy(sha256 = "")))
        assertNull(UpdateChoice.pick(noHash, 1, listOf("armeabi-v7a")))
    }
}
