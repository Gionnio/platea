package com.github.gionnio.platea.home

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Last home of each user, saved on the device (files/platea/home-<user>.json) and shown at the next start
 * while the server is queried. Only titles, texts and image URLs: images use Coil's cache.
 */
@Singleton
class HomeCache
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) {
        // Wholphin destinations have a "type" property: use another discriminator for sealed classes
        private val json =
            Json {
                ignoreUnknownKeys = true
                classDiscriminator = "#class"
            }

        private fun file(userId: UUID) = File(context.filesDir, "platea/home-$userId.json")

        fun read(userId: UUID): HomeSnapshot? =
            runCatching {
                file(userId).takeIf { it.exists() }?.readText()?.let { json.decodeFromString<HomeSnapshot>(it) }
            }.onFailure { Timber.w(it, "Platea home cache unreadable") }.getOrNull()

        fun write(snapshot: HomeSnapshot) {
            runCatching {
                val target = file(snapshot.userId)
                target.parentFile?.mkdirs()
                val tmp = File(target.parentFile, target.name + ".tmp")
                tmp.writeText(json.encodeToString(snapshot))
                tmp.renameTo(target)
            }.onFailure { Timber.w(it, "Platea home cache not saved") }
        }

        fun clear(userId: UUID) {
            file(userId).delete()
        }
    }
