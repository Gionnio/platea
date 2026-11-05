package com.github.gionnio.platea.playback

import android.net.Uri
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import timber.log.Timber
import java.io.File

/**
 * Debug builds only: makes the video source slow on demand, to reproduce a busy server or NAS without touching
 * them. Controlled by the file `files/platea/slow-source` (written with `adb shell run-as`):
 * - `stall <ms>`: the next read waits that long, once (the file is deleted);
 * - `rate <KB/s>`: reads are throttled to that speed until the file is removed.
 */
@OptIn(UnstableApi::class)
class SlowSourceSimulator(
    private val upstream: DataSource,
    private val control: File,
) : DataSource by upstream {
    private var checkedAt = 0L
    private var rateBytesPerSecond = 0L
    private var windowStart = 0L
    private var windowBytes = 0L

    override fun read(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        checkControl()
        val rate = rateBytesPerSecond
        val read = upstream.read(buffer, offset, if (rate > 0) minOf(length, (rate / 10).toInt().coerceAtLeast(1)) else length)
        if (rate > 0 && read > 0) {
            val now = SystemClock.elapsedRealtime()
            if (now - windowStart >= 1_000) {
                windowStart = now
                windowBytes = 0
            }
            windowBytes += read
            val dueAt = windowStart + windowBytes * 1_000 / rate
            if (dueAt > now) Thread.sleep(dueAt - now)
        }
        return read
    }

    private fun checkControl() {
        val now = SystemClock.elapsedRealtime()
        if (now - checkedAt < 500) return
        checkedAt = now
        val command = runCatching { control.takeIf { it.exists() }?.readText()?.trim() }.getOrNull()
        if (command == null) {
            rateBytesPerSecond = 0
            return
        }
        val parts = command.split(Regex("\\s+"))
        val value = parts.getOrNull(1)?.toLongOrNull() ?: return
        when (parts[0]) {
            "stall" -> {
                control.delete()
                Timber.w("Slow source: stall %d ms", value)
                Thread.sleep(value)
            }

            "rate" -> {
                if (rateBytesPerSecond != value * 1024) Timber.w("Slow source: %d KB/s", value)
                rateBytesPerSecond = value * 1024
            }
        }
    }

    override fun addTransferListener(transferListener: TransferListener) = upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long = upstream.open(dataSpec)

    override fun getUri(): Uri? = upstream.uri

    override fun close() = upstream.close()

    class Factory(
        private val upstream: DataSource.Factory,
        private val control: File,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource = SlowSourceSimulator(upstream.createDataSource(), control)
    }
}
