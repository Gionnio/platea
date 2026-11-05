package com.github.gionnio.platea.details

import com.github.gionnio.platea.util.isForcedTrack
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.gionnio.platea.R
import com.github.gionnio.platea.data.ChosenStreams
import com.github.gionnio.platea.data.model.BaseItem
import com.github.gionnio.platea.services.ImageUrlService
import com.github.gionnio.platea.util.PlateaDispatchers
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.MediaStream
import java.util.Locale
import java.util.UUID
import javax.inject.Inject

/** Formatting and playback prediction for the Platea details pages. */
@HiltViewModel
class PlateaDetailsViewModel
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val imageUrlService: ImageUrlService,
        private val predictor: PlaybackPredictor,
    ) : ViewModel() {
        private val _prediction = MutableStateFlow<PlaybackPrediction?>(null)
        val prediction: StateFlow<PlaybackPrediction?> = _prediction
        private var job: Job? = null

        /** Element that opened another screen (trailer, person, player): focus returns there on back. */
        val focusMemory = com.github.gionnio.platea.ui.FocusMemory()

        fun predict(
            itemId: UUID,
            chosen: ChosenStreams?,
        ) {
            job?.cancel()
            job =
                viewModelScope.launch(PlateaDispatchers.IO) {
                    _prediction.value =
                        predictor.predict(
                            itemId = itemId,
                            mediaSourceId = chosen?.source?.id,
                            audioIndex = chosen?.audioStream?.index,
                            subtitleIndex = if (chosen?.subtitlesDisabled == true) -1 else chosen?.subtitleStream?.index,
                        )
                }
        }

        fun backdrop(item: BaseItem) = imageUrlService.getOwnImageUrl(item, ImageType.BACKDROP, maxWidth = 1920)

        fun logo(item: BaseItem) = imageUrlService.getOwnImageUrl(item, ImageType.LOGO, maxHeight = 260)

        fun meta(item: BaseItem): List<String> =
            listOfNotNull(
                item.data.productionYear?.toString(),
                item.data.runTimeTicks?.let { duration(it) },
                item.data.genres?.take(2)?.joinToString(", ")?.takeIf { it.isNotBlank() },
                item.data.officialRating?.substringAfter('-'),
            )

        fun timeLeft(item: BaseItem): String {
            val runtime = item.data.runTimeTicks ?: 0
            val position = item.data.userData?.playbackPositionTicks ?: 0
            return duration((runtime - position).coerceAtLeast(0))
        }

        fun predictionText(p: PlaybackPrediction): String =
            when (p.method) {
                PlaybackPrediction.Method.DIRECT_PLAY ->
                    if (p.dolbyVision) context.getString(R.string.platea_direct_play_dv) else context.getString(R.string.platea_direct_play)
                PlaybackPrediction.Method.REMUX -> context.getString(R.string.platea_remux)
                PlaybackPrediction.Method.TRANSCODE -> context.getString(R.string.platea_transcode)
            } + (p.reasons.firstOrNull()?.let { if (p.method == PlaybackPrediction.Method.DIRECT_PLAY) "" else " · $it" } ?: "")

        /** One cell of the technical sheet (two columns, as in the concept). */
        data class TechCell(
            val label: String,
            val value: String,
            val muted: Boolean = false,
        )

        /** Cells of the technical sheet, from the streams Platea will play. */
        fun techCells(chosen: ChosenStreams): List<TechCell> {
            val v = chosen.videoStream
            val a = chosen.audioStream
            val s = chosen.subtitleStream
            val source = chosen.source
            return listOf(
                TechCell(context.getString(R.string.platea_tech_video), v?.let { video(it) } ?: "—"),
                TechCell(context.getString(R.string.platea_tech_hdr), v?.let { hdr(it) } ?: "SDR"),
                TechCell(context.getString(R.string.platea_tech_audio), a?.let { audio(it) } ?: "—"),
                TechCell(
                    context.getString(R.string.platea_tech_subtitles),
                    when {
                        chosen.subtitlesDisabled || s == null -> context.getString(R.string.platea_none)
                        s.isForcedTrack -> listOfNotNull(context.getString(R.string.platea_forced_only), language(s.language)).joinToString(" · ")
                        else -> listOfNotNull(language(s.language), s.title?.takeIf { it.contains("SDH", true) }?.let { "SDH" }).joinToString(" · ")
                    },
                ),
                TechCell(
                    context.getString(R.string.platea_tech_file),
                    listOfNotNull(
                        source.container?.uppercase(),
                        source.size?.let { String.format(Locale.ITALY, "%.1f GB", it / 1e9) },
                        source.bitrate?.let { String.format(Locale.ITALY, "%.1f Mbps", it / 1e6) },
                    ).joinToString(" · "),
                ),
            )
        }

        /** e.g. "E-AC3 5.1 · Italiano", as in the concept. */
        private fun audio(a: MediaStream): String {
            val atmos = (a.profile.orEmpty() + " " + a.displayTitle.orEmpty()).contains("Atmos", ignoreCase = true)
            val codec =
                when (a.codec?.lowercase()) {
                    "eac3" -> "E-AC3"
                    "ac3" -> "AC3"
                    "truehd" -> "TrueHD"
                    "dts" -> if (a.profile?.contains("MA", ignoreCase = true) == true) "DTS-HD MA" else "DTS"
                    else -> a.codec?.uppercase()
                }?.let { if (atmos) "$it Atmos" else it }
            val channels =
                when (a.channels) {
                    1 -> "1.0"
                    2 -> "2.0"
                    6 -> "5.1"
                    8 -> "7.1"
                    null -> null
                    else -> "${a.channels}ch"
                }
            return listOfNotNull(listOfNotNull(codec, channels).joinToString(" ").takeIf { it.isNotBlank() }, language(a.language)).joinToString(" · ")
        }

        private fun language(code: String?): String? =
            code?.takeIf { it.isNotBlank() && it != "und" }?.let { c ->
                Locale.forLanguageTag(c).getDisplayLanguage(Locale.getDefault()).replaceFirstChar { it.titlecase(Locale.getDefault()) }.takeIf { it.isNotBlank() && it != c } ?: c
            }

        private fun video(v: MediaStream) =
            listOfNotNull(
                if (v.width != null && v.height != null) "${v.width}×${v.height}" else null,
                listOfNotNull(v.codec?.uppercase(), v.bitDepth?.let { "$it bit" }).joinToString(" ").takeIf { it.isNotBlank() },
            ).joinToString(" · ")

        private fun hdr(v: MediaStream): String? {
            val dovi = v.videoDoViTitle
            val range = v.videoRangeType.serialName
            return when {
                dovi != null -> {
                    val profile = Regex("""(\d+(\.\d+)?)""").find(dovi.substringAfter("Profile", dovi))?.value
                    (if (profile != null) "Dolby Vision $profile" else "Dolby Vision") + if (range.contains("HDR10Plus")) " · HDR10+" else ""
                }
                range == "SDR" || range == "Unknown" -> null
                range == "HDR10Plus" -> "HDR10+"
                else -> range
            }
        }

        private fun duration(ticks: Long): String {
            val totalMin = (ticks / 600_000_000L).toInt()
            val h = totalMin / 60
            val m = totalMin % 60
            return if (h > 0) "$h h $m min" else "$m min"
        }
    }
