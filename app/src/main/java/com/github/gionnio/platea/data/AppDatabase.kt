package com.github.gionnio.platea.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.github.gionnio.platea.data.model.ItemPlayback
import com.github.gionnio.platea.data.model.JellyfinServer
import com.github.gionnio.platea.data.model.JellyfinUser
import com.github.gionnio.platea.data.model.PlaybackLanguageChoice
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import org.jellyfin.sdk.model.serializer.toUUID
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

@Database(
    entities = [
        JellyfinServer::class,
        JellyfinUser::class,
        ItemPlayback::class,
        PlaybackLanguageChoice::class,
    ],
    version = 36,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun serverDao(): JellyfinServerDao

    abstract fun itemPlaybackDao(): ItemPlaybackDao

    abstract fun playbackLanguageChoiceDao(): PlaybackLanguageChoiceDao

}

class Converters {
    @TypeConverter
    fun convertToString(id: UUID): String = id.toString().replace("-", "")

    @TypeConverter
    fun convertToUUID(str: String): UUID = str.toUUID()

    @TypeConverter
    fun convertToString(dateTime: ZonedDateTime): String = DateTimeFormatter.ISO_ZONED_DATE_TIME.format(dateTime)

    @TypeConverter
    fun convertToLocalDateTime(dateTime: String): ZonedDateTime = ZonedDateTime.parse(dateTime, DateTimeFormatter.ISO_ZONED_DATE_TIME)
}

class ZonedDateTimeSerializer : KSerializer<ZonedDateTime> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor(ZonedDateTime::class.qualifiedName!!, PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): ZonedDateTime =
        ZonedDateTime.parse(decoder.decodeString(), DateTimeFormatter.ISO_ZONED_DATE_TIME)

    override fun serialize(
        encoder: Encoder,
        value: ZonedDateTime,
    ) {
        encoder.encodeString(DateTimeFormatter.ISO_ZONED_DATE_TIME.format(value))
    }
}

object Migrations {
    /**
     * Platea keeps servers, users and the track choices. The tables of the Wholphin features that are gone
     * (Seerr, navigation drawer, library view options, video effects, remembered tabs, subtitle delay) are dropped.
     */
    val Migrate35to36 =
        object : Migration(35, 36) {
            override fun migrate(db: SupportSQLiteDatabase) {
                DROPPED_TABLES.forEach { db.execSQL("DROP TABLE IF EXISTS `$it`") }
            }
        }

    // Users before servers: seerr_users refers to seerr_servers
    val DROPPED_TABLES =
        listOf(
            "seerr_users",
            "seerr_servers",
            "NavDrawerPinnedItem",
            "LibraryDisplayInfo",
            "playback_effects",
            "RememberedTab",
            "ItemTrackModification",
        )
}
