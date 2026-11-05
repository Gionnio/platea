package com.github.gionnio.platea.playback

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Counts what really comes over the network for the video player: bytes and connections opened. The playback
 * trace samples it to tell a slow source (few bytes while the player wants data) from a player that stopped
 * asking (no bytes and not loading).
 */
@OptIn(UnstableApi::class)
object StreamMeter : TransferListener {
    val bytes = AtomicLong()
    val opens = AtomicInteger()

    override fun onTransferInitializing(
        source: DataSource,
        dataSpec: DataSpec,
        isNetwork: Boolean,
    ) = Unit

    override fun onTransferStart(
        source: DataSource,
        dataSpec: DataSpec,
        isNetwork: Boolean,
    ) {
        if (isNetwork) opens.incrementAndGet()
    }

    override fun onBytesTransferred(
        source: DataSource,
        dataSpec: DataSpec,
        isNetwork: Boolean,
        bytesTransferred: Int,
    ) {
        if (isNetwork) bytes.addAndGet(bytesTransferred.toLong())
    }

    override fun onTransferEnd(
        source: DataSource,
        dataSpec: DataSpec,
        isNetwork: Boolean,
    ) = Unit
}
