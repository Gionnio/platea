package com.github.gionnio.platea.engine

import com.github.gionnio.platea.engine.DolbyVisionCheck.Shown
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DolbyVisionCheckTest {
    private fun caps(
        tv: Boolean = true,
        decoders: List<String> = listOf("OMX.MTK.VIDEO.DECODER.DVHE.STN"),
    ) = DeviceCapabilities(
        model = "AFTKRT",
        firmware = "x",
        video = VideoCapabilities(true, true, true, true, true, false, false, false, decoders, 3840, 2160),
        display = DisplayCapabilities(dolbyVision = tv, hdr10 = true, hdr10Plus = true, hlg = true, modes = emptyList()),
        audio = AudioCapabilities(true, true, true, false, false, false, 8),
        appliedOverrides = emptyList(),
        computedAtMillis = 0,
    )

    private val hevc = "OMX.MTK.VIDEO.DECODER.HEVC"

    @Test
    fun dolbyVisionDecoderIsFine() {
        assertNull(DolbyVisionCheck.lost("DOVIWithHDR10", true, false, caps(), "omx.mtk.video.decoder.dvhe.stn"))
    }

    @Test
    fun baseLayerDecoderIsReported() {
        assertEquals(Shown.HDR10, DolbyVisionCheck.lost("DOVIWithHDR10", true, false, caps(), hevc))
        assertEquals(Shown.HDR10, DolbyVisionCheck.lost("DOVIWithEL", true, false, caps(), hevc))
        assertEquals(Shown.HLG, DolbyVisionCheck.lost("DOVIWithHLG", true, false, caps(), hevc))
        assertEquals(Shown.SDR, DolbyVisionCheck.lost("DOVIWithSDR", true, false, caps(), hevc))
        assertEquals(Shown.WRONG_COLOURS, DolbyVisionCheck.lost("DOVI", true, false, caps(), hevc))
        // Dolby Vision data found in a file the server calls HDR10
        assertEquals(Shown.HDR10, DolbyVisionCheck.lost("HDR10", true, false, caps(), hevc))
    }

    @Test
    fun nothingToReportWhenDolbyVisionWasNeverPossible() {
        assertNull(DolbyVisionCheck.lost("DOVIWithHDR10", true, false, caps(tv = false), hevc))
        assertNull(DolbyVisionCheck.lost("DOVIWithHDR10", true, false, caps(decoders = emptyList()), hevc))
        assertNull(DolbyVisionCheck.lost("DOVIWithHDR10", true, false, null, hevc))
        assertNull(DolbyVisionCheck.lost("DOVIWithHDR10", true, converting = true, caps(), hevc))
        assertNull(DolbyVisionCheck.lost("HDR10", false, false, caps(), hevc))
    }
}
