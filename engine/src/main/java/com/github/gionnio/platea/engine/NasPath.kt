package com.github.gionnio.platea.engine

/**
 * Turns the path of a file on the Jellyfin server into its address on the NAS, for direct reading (like Infuse
 * or VLC) instead of the server's stream. The server sees the share mounted at [serverPrefix] (for example
 * `/mnt/nas`), the NAS publishes it as [smbRoot] (for example `smb://192.168.1.20/media`).
 */
data class NasPath(
    val serverPrefix: String,
    val smbRoot: String,
) {
    /** The `smb://host/share/...` address of [serverPath], or null when the file is not under [serverPrefix]. */
    fun toSmb(serverPath: String?): String? {
        if (serverPath.isNullOrBlank()) return null
        val prefix = serverPrefix.trim().trimEnd('/', '\\').replace('\\', '/')
        val root = smbRoot.trim().trimEnd('/')
        if (prefix.isEmpty() || !root.startsWith("smb://", ignoreCase = true) || root.length <= "smb://".length) return null
        val path = serverPath.replace('\\', '/')
        if (!path.startsWith("$prefix/")) return null
        val rest = path.removePrefix(prefix).trimStart('/')
        return "$root/" + rest.split('/').joinToString("/") { encode(it) }
    }

    companion object {
        /** Percent-encoding of one path segment, so that spaces, brackets and accents survive the URI. */
        fun encode(segment: String): String =
            buildString {
                segment.toByteArray(Charsets.UTF_8).forEach { b ->
                    val c = b.toInt() and 0xFF
                    if (c.toChar().isLetterOrDigit() && c < 0x80 || c.toChar() in "-._~") {
                        append(c.toChar())
                    } else {
                        append('%').append("0123456789ABCDEF"[c shr 4]).append("0123456789ABCDEF"[c and 0xF])
                    }
                }
            }

        /** Host, share and path inside the share of an `smb://` address (decoded), or null if it is not one. */
        fun parse(uri: String): Parts? {
            if (!uri.startsWith("smb://", ignoreCase = true)) return null
            val body = uri.substring("smb://".length)
            val host = body.substringBefore('/').takeIf { it.isNotBlank() } ?: return null
            val afterHost = body.substringAfter('/', "")
            val share = decode(afterHost.substringBefore('/')).takeIf { it.isNotBlank() } ?: return null
            val path = afterHost.substringAfter('/', "").split('/').filter { it.isNotEmpty() }.joinToString("\\") { decode(it) }
            return Parts(host, share, path)
        }

        private fun decode(s: String): String {
            val out = java.io.ByteArrayOutputStream()
            var i = 0
            while (i < s.length) {
                val v = if (s[i] == '%' && i + 3 <= s.length) s.substring(i + 1, i + 3).toIntOrNull(16) else null
                if (v != null) {
                    out.write(v)
                    i += 3
                } else {
                    out.write(s[i].toString().toByteArray(Charsets.UTF_8))
                    i++
                }
            }
            return String(out.toByteArray(), Charsets.UTF_8)
        }
    }

    /** An `smb://` address split for the SMB client: [path] uses backslashes, as SMB expects. */
    data class Parts(
        val host: String,
        val share: String,
        val path: String,
    )
}
