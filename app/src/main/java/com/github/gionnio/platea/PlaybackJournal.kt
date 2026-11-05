package com.github.gionnio.platea

import android.content.Context
import android.net.Uri
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.jellyfin.sdk.model.api.MediaSourceInfo
import org.jellyfin.sdk.model.api.MediaStreamType
import java.io.File
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Local diary of playbacks: method chosen by the server and why, video decoder, fallbacks.
 * One JSON object per line, read with `scripts/journal.sh`.
 */
@Singleton
class PlaybackJournal
    @Inject
    constructor(
        @param:ApplicationContext context: Context,
    ) {
        // Written twice: the external copy is readable with adb from optimized builds, but Fire OS denies it to
        // adb; the private copy is readable with `run-as` from debug builds on every device.
        private val files =
            listOfNotNull(context.getExternalFilesDir(null), context.filesDir)
                .distinct()
                .map { File(it, "platea/playback-journal.jsonl") }

        /** The server decision for a title. */
        fun decision(
            itemName: String?,
            source: MediaSourceInfo,
            method: String,
        ) {
            val video = source.mediaStreams?.firstOrNull { it.type == MediaStreamType.VIDEO }
            val audio = source.mediaStreams?.firstOrNull { it.type == MediaStreamType.AUDIO && it.index == source.defaultAudioStreamIndex }
            val conversion = source.transcodingUrl?.let { Uri.parse("http://x$it") }
            val reasons = conversion?.getQueryParameter("TranscodeReasons").orEmpty()
            // What the server will send when it converts: codecs, container, and whether it copies the streams
            val output =
                conversion?.let { url ->
                    listOf("Container", "SegmentContainer", "VideoCodec", "AudioCodec", "AllowVideoStreamCopy", "AllowAudioStreamCopy", "AudioStreamIndex")
                        .mapNotNull { key -> url.getQueryParameter(key)?.let { "$key=$it" } }
                        .joinToString(" ")
                }
            write(
                "decision",
                "item" to itemName,
                "container" to source.container,
                "video" to listOfNotNull(video?.codec, video?.videoRangeType?.serialName, video?.width?.let { "${it}x${video.height}" }).joinToString(" "),
                "audio" to listOfNotNull(audio?.codec, audio?.channels?.let { "${it}ch" }).joinToString(" "),
                "method" to method,
                "reasons" to reasons,
                "output" to output,
            )
        }

        /** The video decoder chosen by ExoPlayer; flags a Dolby Vision file decoded without a DV decoder. */
        fun decoder(
            name: String,
            sampleMimeType: String?,
            rangeType: String?,
        ) {
            val dvFile = rangeType?.startsWith("DOVI") == true
            val dvDecoder = name.contains("DV", ignoreCase = false) || name.contains("dolby", ignoreCase = true)
            val note = if (dvFile && !dvDecoder) "Dolby Vision file on the base layer decoder" else null
            if (note != null) Log.w(TAG, "$note: $name ($sampleMimeType)")
            write("decoder", "name" to name, "mime" to sampleMimeType, "range" to rangeType, "note" to note)
        }

        /** Display mode chosen for a title: content frame rate, current and target mode, modes the TV offers. */
        fun display(info: Map<String, String>) = write("display", *info.map { it.key to it.value }.toTypedArray())

        /** How long the title took to start, step by step, up to the first picture. */
        fun start(
            itemName: String?,
            steps: Map<String, String>,
        ) = write("start", "item" to itemName, *steps.map { it.key to it.value }.toTypedArray())

        fun fallback(
            action: String,
            reason: String,
        ) = write("fallback", "action" to action, "reason" to reason)

        /** A sample of the playback trace taken while little was buffered ahead. */
        fun trace(line: String) = write("trace", "sample" to line)

        @Synchronized
        private fun write(
            event: String,
            vararg fields: Pair<String, String?>,
        ) {
            val line =
                buildJsonObject {
                    put("time", JsonPrimitive(Instant.now().toString()))
                    put("event", JsonPrimitive(event))
                    fields.forEach { (key, value) -> if (!value.isNullOrBlank()) put(key, JsonPrimitive(value)) }
                }
            val text = Json.encodeToString(line)
            Log.i(TAG, "Journal $text")
            files.forEach { file ->
                runCatching {
                    file.parentFile?.mkdirs()
                    file.appendText(text + "\n")
                    // Keep the last ~2000 events
                    if (file.length() > MAX_BYTES) file.writeText(file.readLines().takeLast(2000).joinToString("\n", postfix = "\n"))
                }.onFailure { Log.w(TAG, "Cannot write the playback journal", it) }
            }
        }

        companion object {
            private const val TAG = "Platea"
            private const val MAX_BYTES = 1_000_000L
        }
    }
