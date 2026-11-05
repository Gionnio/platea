package com.github.gionnio.platea.nas

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.github.gionnio.platea.engine.NasPath
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.share.File
import android.util.Log
import java.io.IOException
import java.util.EnumSet
import java.util.concurrent.TimeUnit

/**
 * One SMB connection to the NAS, shared by every read: opening a session costs a few round trips, a film opens
 * the file many times (start, seeks, retries).
 */
object SmbConnections {
    private val client =
        SMBClient(
            SmbConfig
                .builder()
                .withTimeout(15, TimeUnit.SECONDS)
                .withSoTimeout(20, TimeUnit.SECONDS)
                .withReadBufferSize(SmbDataSource.CHUNK)
                .build(),
        )
    private var key: String? = null
    private var connection: Connection? = null
    private var session: Session? = null
    private var share: DiskShare? = null

    @Synchronized
    fun share(
        parts: NasPath.Parts,
        config: NasConfig,
    ): DiskShare {
        val wanted = "${parts.host}|${parts.share}|${config.username}"
        share?.takeIf { key == wanted && it.isConnected }?.let { return it }
        forget()
        val conn = client.connect(parts.host)
        val auth =
            if (config.username.isBlank()) {
                AuthenticationContext.guest()
            } else {
                val user = config.username.substringAfter('\\')
                val domain = config.username.substringBefore('\\', "")
                AuthenticationContext(user, config.password.toCharArray(), domain)
            }
        val s = conn.authenticate(auth)
        val disk = s.connectShare(parts.share) as? DiskShare ?: throw IOException("${parts.share} is not a disk share")
        connection = conn
        session = s
        share = disk
        key = wanted
        return disk
    }

    /** Drops the connection: settings changed, or it broke. */
    @Synchronized
    fun forget() {
        runCatching { share?.close() }
        runCatching { session?.close() }
        runCatching { connection?.close() }
        share = null
        session = null
        connection = null
        key = null
    }
}

/**
 * Reads a film straight from the NAS (`smb://host/share/path`), like Infuse or VLC, without the Jellyfin server
 * in between. Large sequential reads; a seek opens the file again at the new position.
 */
@OptIn(UnstableApi::class)
class SmbDataSource(
    private val config: () -> NasConfig,
) : BaseDataSource(true) {
    private var uri: Uri? = null
    private var file: File? = null
    private var position = 0L
    private var bytesRemaining = 0L
    private var size = 0L
    private val chunk = ByteArray(CHUNK)
    private var chunkStart = 0
    private var chunkEnd = 0
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        val parts =
            NasPath.parse(dataSpec.uri.toString())
                ?: throw DataSourceException(PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
        val f =
            smb {
                SmbConnections
                    .share(parts, config())
                    .openFile(
                        parts.path,
                        EnumSet.of(AccessMask.GENERIC_READ),
                        null,
                        SMB2ShareAccess.ALL,
                        SMB2CreateDisposition.FILE_OPEN,
                        null,
                    )
            }
        file = f
        size = smb { f.fileInformation.standardInformation.endOfFile }
        Log.i(TAG, "smb open at ${dataSpec.position} of $size")
        if (dataSpec.position > size) throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        position = dataSpec.position
        bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else size - position
        chunkStart = 0
        chunkEnd = 0
        opened = true
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        if (chunkStart >= chunkEnd) {
            val want = minOf(CHUNK.toLong(), bytesRemaining).toInt()
            val f = file ?: throw IOException("SMB file not open")
            val got = smb { f.read(chunk, position, 0, want) }
            if (got <= 0) {
                // Only the real end of the file is an end: an empty read in the middle would make the player
                // think the film is over (marked as watched, next episode). It is a read error instead.
                if (position >= size) return C.RESULT_END_OF_INPUT
                Log.w(TAG, "smb empty read ($got) at $position of $size")
                throw DataSourceException(IOException("SMB read returned $got at $position of $size"), PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
            }
            chunkStart = 0
            chunkEnd = got
        }
        val n = minOf(length, chunkEnd - chunkStart, bytesRemaining.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        System.arraycopy(chunk, chunkStart, buffer, offset, n)
        chunkStart += n
        position += n
        bytesRemaining -= n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        uri = null
        runCatching { file?.close() }
        file = null
        if (opened) {
            opened = false
            transferEnded()
        }
    }

    /** SMB errors become read errors: Platea's error handling (retries, then the server's stream) applies. */
    private fun <T> smb(block: () -> T): T =
        try {
            block()
        } catch (e: Exception) {
            if (e is DataSourceException) throw e
            // The player interrupts a read when it seeks or stops: the shared connection is still good
            val interrupted = Thread.currentThread().isInterrupted || generateSequence(e as Throwable) { it.cause }.any { it is InterruptedException }
            if (!interrupted) {
                // smbj reports most failures (access denied, file missing, timeouts) as unchecked exceptions
                Log.w(TAG, "smb failed at $position of $size: ${e.javaClass.simpleName}: ${e.message}")
                SmbConnections.forget()
            }
            throw DataSourceException(e as? IOException ?: IOException(e.message, e), PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
        }

    companion object {
        const val CHUNK = 1 shl 20
        private const val TAG = "PlateaNet"
    }

    class Factory(
        private val config: () -> NasConfig,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource = SmbDataSource(config)
    }
}

/** Sends `smb://` addresses to [smb] and everything else to [other]. */
@OptIn(UnstableApi::class)
class NasRoutingDataSource(
    private val other: DataSource,
    private val smbFactory: () -> DataSource,
) : DataSource {
    private val listeners = mutableListOf<TransferListener>()
    private var smb: DataSource? = null
    private var active: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        listeners += transferListener
        other.addTransferListener(transferListener)
        smb?.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val target =
            if (dataSpec.uri.scheme.equals("smb", ignoreCase = true)) {
                smb ?: smbFactory().also { s ->
                    listeners.forEach { s.addTransferListener(it) }
                    smb = s
                }
            } else {
                other
            }
        active = target
        return target.open(dataSpec)
    }

    override fun read(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int = active?.read(buffer, offset, length) ?: throw IOException("Not open")

    override fun getUri(): Uri? = active?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = active?.responseHeaders ?: emptyMap()

    override fun close() {
        active?.close()
        active = null
    }

    class Factory(
        private val other: DataSource.Factory,
        private val config: () -> NasConfig,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource =
            NasRoutingDataSource(other.createDataSource()) {
                SmbDataSource(config).also { it.addTransferListener(com.github.gionnio.platea.playback.StreamMeter) }
            }
    }
}
