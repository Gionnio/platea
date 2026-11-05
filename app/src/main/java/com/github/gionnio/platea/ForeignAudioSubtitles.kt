package com.github.gionnio.platea

import com.github.gionnio.platea.util.isForcedTrack
import org.jellyfin.sdk.model.api.MediaStream
import java.util.Locale
import java.util.MissingResourceException

/**
 * Platea subtitle rule: when the audio is not in the user's language and there are no forced subtitles,
 * show full subtitles in the user's language (Italian by default), otherwise in English.
 */
object ForeignAudioSubtitles {
    private const val DEFAULT_LANGUAGE = "ita"
    private const val FALLBACK_LANGUAGE = "eng"

    fun pick(
        candidates: List<MediaStream>,
        audioLanguage: String?,
        userLanguage: String?,
    ): MediaStream? {
        val primary = userLanguage?.takeIf { it.isNotBlank() } ?: DEFAULT_LANGUAGE
        // Unknown audio language: do not guess
        if (audioLanguage.isNullOrBlank() || audioLanguage.equals("und", true) || sameLanguage(audioLanguage, primary)) return null
        return listOf(primary, FALLBACK_LANGUAGE).firstNotNullOfOrNull { lang ->
            val inLang = candidates.filter { !it.isForcedTrack && sameLanguage(it.language, lang) }
            // Plain subtitles before SDH/hearing impaired ones
            inLang.firstOrNull { it.title?.contains("SDH", true) != true && it.isHearingImpaired != true } ?: inLang.firstOrNull()
        }
    }

    /** "ita", "it", "Italian" are the same language. */
    fun sameLanguage(
        a: String?,
        b: String?,
    ): Boolean {
        if (a.isNullOrBlank() || b.isNullOrBlank()) return false
        return iso3(a) == iso3(b)
    }

    private fun iso3(code: String): String =
        try {
            Locale.forLanguageTag(code).isO3Language.ifBlank { code.lowercase() }
        } catch (_: MissingResourceException) {
            code.lowercase()
        }
}
