package com.github.gionnio.platea.details

import org.jellyfin.sdk.model.api.MediaStream
import java.util.Locale

/** Compact, localized descriptions of media tracks, shared by the details pages, the track panel and the player. */
object TrackFormat {
    /** "Italiano", "English"… in the app language; null for unknown languages. */
    fun language(code: String?): String? =
        code?.takeIf { it.isNotBlank() && it != "und" }?.let { c ->
            Locale
                .forLanguageTag(c)
                .getDisplayLanguage(Locale.getDefault())
                .replaceFirstChar { it.titlecase(Locale.getDefault()) }
                .takeIf { it.isNotBlank() && it != c } ?: c
        }

    fun isAtmos(a: MediaStream) = (a.profile.orEmpty() + " " + a.displayTitle.orEmpty() + " " + a.title.orEmpty()).contains("Atmos", ignoreCase = true)

    /** "E-AC3 Atmos 5.1", "TrueHD 7.1", "DTS-HD MA 5.1", "AAC 2.0". */
    fun audioFormat(a: MediaStream): String {
        val codec =
            when (a.codec?.lowercase()) {
                "eac3" -> "E-AC3"
                "ac3" -> "AC3"
                "truehd" -> "TrueHD"
                "dts" -> if (a.profile?.contains("MA", ignoreCase = true) == true) "DTS-HD MA" else "DTS"
                "mlp" -> "MLP"
                else -> a.codec?.uppercase()
            }?.let { if (isAtmos(a)) "$it Atmos" else it }
        val channels =
            when (a.channels) {
                1 -> "1.0"
                2 -> "2.0"
                6 -> "5.1"
                8 -> "7.1"
                null -> null
                else -> "${a.channels}ch"
            }
        return listOfNotNull(codec, channels).joinToString(" ")
    }

    /** "SRT", "PGS", "ASS", "VobSub"… */
    fun subtitleFormat(s: MediaStream): String =
        when (s.codec?.lowercase()) {
            "subrip", "srt" -> "SRT"
            "ass", "ssa" -> "ASS"
            "pgssub", "pgs", "hdmv_pgs_subtitle" -> "PGS"
            "dvdsub", "vobsub" -> "VobSub"
            "dvbsub" -> "DVB"
            "webvtt", "vtt" -> "VTT"
            "mov_text" -> "TX3G"
            else -> s.codec?.uppercase().orEmpty()
        }

    /** Track title worth showing next to the language (skips titles that only repeat language or flags). */
    fun extraTitle(s: MediaStream): String? {
        val t = s.title?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val redundant =
            listOfNotNull(language(s.language), s.language, "Forced", "Forzati", "Default", "Italiano", "English")
                .any { t.equals(it, ignoreCase = true) }
        return t.takeUnless { redundant }
    }
}
