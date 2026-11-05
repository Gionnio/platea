package com.github.gionnio.platea.nas

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.github.gionnio.platea.engine.NasPath
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import timber.log.Timber
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/** Direct reading from the NAS, as set on this device. */
data class NasConfig(
    val enabled: Boolean = false,
    /** Where the Jellyfin server sees the NAS share, e.g. `/mnt/nas`. */
    val serverPrefix: String = "",
    /** The same share on the NAS, e.g. `smb://192.168.1.20/media`. */
    val smbRoot: String = "",
    /** Empty: guest access. */
    val username: String = "",
    val password: String = "",
) {
    val paths get() = NasPath(serverPrefix, smbRoot)

    val usable get() = enabled && paths.toSmb("$serverPrefix/x") != null
}

/**
 * Stores [NasConfig] on the device only (it never goes to the server). The password is encrypted with a key of
 * the Android keystore that cannot leave the device.
 */
@Singleton
class NasSettings
    @Inject
    constructor(
        @param:ApplicationContext context: Context,
    ) {
        private val prefs = context.getSharedPreferences("platea_nas", Context.MODE_PRIVATE)
        private val _config = MutableStateFlow(read())
        val config: StateFlow<NasConfig> = _config

        val current: NasConfig get() = _config.value

        fun update(block: (NasConfig) -> NasConfig) {
            val next = block(current)
            prefs
                .edit()
                .putBoolean(KEY_ENABLED, next.enabled)
                .putString(KEY_PREFIX, next.serverPrefix)
                .putString(KEY_ROOT, next.smbRoot)
                .putString(KEY_USER, next.username)
                .putString(KEY_PASSWORD, encrypt(next.password))
                .apply()
            _config.value = next
            SmbConnections.forget()
        }

        private fun read() =
            NasConfig(
                enabled = prefs.getBoolean(KEY_ENABLED, false),
                serverPrefix = prefs.getString(KEY_PREFIX, null).orEmpty(),
                smbRoot = prefs.getString(KEY_ROOT, null).orEmpty(),
                username = prefs.getString(KEY_USER, null).orEmpty(),
                password = decrypt(prefs.getString(KEY_PASSWORD, null)),
            )

        private fun key(): SecretKey {
            val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
            (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
            return KeyGenerator
                .getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
                .apply {
                    init(
                        KeyGenParameterSpec
                            .Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                            .build(),
                    )
                }.generateKey()
        }

        private fun encrypt(plain: String): String? {
            if (plain.isEmpty()) return null
            return runCatching {
                val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
                val data = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
                Base64.encodeToString(cipher.iv + data, Base64.NO_WRAP)
            }.onFailure { Timber.w(it, "NAS password not saved") }.getOrNull()
        }

        private fun decrypt(stored: String?): String {
            if (stored.isNullOrEmpty()) return ""
            return runCatching {
                val bytes = Base64.decode(stored, Base64.NO_WRAP)
                val cipher =
                    Cipher.getInstance(TRANSFORMATION).apply {
                        init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, IV_SIZE))
                    }
                String(cipher.doFinal(bytes, IV_SIZE, bytes.size - IV_SIZE), Charsets.UTF_8)
            }.onFailure { Timber.w(it, "NAS password unreadable") }.getOrDefault("")
        }

        private companion object {
            const val KEYSTORE = "AndroidKeyStore"
            const val KEY_ALIAS = "platea_nas"
            const val TRANSFORMATION = "AES/GCM/NoPadding"
            const val IV_SIZE = 12
            const val KEY_ENABLED = "enabled"
            const val KEY_PREFIX = "server_prefix"
            const val KEY_ROOT = "smb_root"
            const val KEY_USER = "username"
            const val KEY_PASSWORD = "password"
        }
    }
