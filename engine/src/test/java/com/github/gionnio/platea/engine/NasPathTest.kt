package com.github.gionnio.platea.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NasPathTest {
    private val nas = NasPath("/mnt/nas", "smb://192.168.1.20/media")
    private val superman = "/mnt/nas/Shared Videos/Films/4K/Superman (2025) [tmdbid-1061474]/Superman (2025) [tmdbid-1061474].mkv"

    @Test
    fun serverPathBecomesTheNasAddress() {
        assertEquals(
            "smb://192.168.1.20/media/Shared%20Videos/Films/4K/Superman%20%282025%29%20%5Btmdbid-1061474%5D/" +
                "Superman%20%282025%29%20%5Btmdbid-1061474%5D.mkv",
            nas.toSmb(superman),
        )
    }

    @Test
    fun addressSplitsBackIntoShareAndPath() {
        val parts = NasPath.parse(nas.toSmb(superman)!!)!!
        assertEquals("192.168.1.20", parts.host)
        assertEquals("media", parts.share)
        assertEquals("Shared Videos\\Films\\4K\\Superman (2025) [tmdbid-1061474]\\Superman (2025) [tmdbid-1061474].mkv", parts.path)
    }

    @Test
    fun accentsAndApostrophesSurvive() {
        val path = "/mnt/nas/Shared Videos/Films/4K/L'été à Paris (2024)/L'été à Paris.mkv"
        assertEquals("Shared Videos\\Films\\4K\\L'été à Paris (2024)\\L'été à Paris.mkv", NasPath.parse(nas.toSmb(path)!!)!!.path)
    }

    @Test
    fun trailingSlashesInTheSettingsDoNotMatter() {
        val loose = NasPath("/mnt/nas/", "smb://192.168.1.20/media/")
        assertEquals(nas.toSmb(superman), loose.toSmb(superman))
    }

    @Test
    fun filesOutsideThePrefixOrBadSettingsAreLeftToTheServer() {
        assertNull(nas.toSmb("/mnt/other/film.mkv"))
        assertNull(nas.toSmb("/mnt/nasX/film.mkv"))
        assertNull(nas.toSmb(null))
        assertNull(NasPath("", "smb://192.168.1.20/media").toSmb(superman))
        assertNull(NasPath("/mnt/nas", "http://192.168.1.20/media").toSmb(superman))
        assertNull(NasPath.parse("smb://192.168.1.20"))
    }
}
