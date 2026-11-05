package com.github.gionnio.platea.services.hilt

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.dataStoreFile
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import com.github.gionnio.platea.data.AppDatabase
import com.github.gionnio.platea.data.ItemPlaybackDao
import com.github.gionnio.platea.data.JellyfinServerDao
import com.github.gionnio.platea.data.Migrations
import com.github.gionnio.platea.data.PlaybackLanguageChoiceDao
import com.github.gionnio.platea.preferences.AppPreferences
import com.github.gionnio.platea.preferences.AppPreferencesSerializer
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun database(
        @ApplicationContext context: Context,
    ): AppDatabase {
        // The database file kept the name of the app Platea started from: rename it once, journal files first,
        // so a stop halfway leaves nothing behind that the next start cannot finish
        listOf("-wal", "-shm", "").forEach { suffix ->
            val old = context.getDatabasePath("wholphin$suffix")
            val new = context.getDatabasePath("$DATABASE_NAME$suffix")
            if (old.exists() && !new.exists()) old.renameTo(new)
        }
        return Room
            .databaseBuilder(
                context,
                AppDatabase::class.java,
                DATABASE_NAME,
            ).addMigrations(Migrations.Migrate35to36)
            // A database older than the last Wholphin schema, or one that cannot be migrated, starts empty
            // (sign in again) instead of stopping the app at launch
            .fallbackToDestructiveMigration(dropAllTables = true)
//            .setQueryCallback({ sqlQuery, args ->
//                Timber.v("sqlQuery=$sqlQuery, args=$args")
//            }, PlateaDispatchers.IO.asExecutor())
            .build()
    }

    private const val DATABASE_NAME = "platea"

    @Provides
    @Singleton
    fun serverDao(db: AppDatabase): JellyfinServerDao = db.serverDao()

    @Provides
    @Singleton
    fun itemPlaybackDao(db: AppDatabase): ItemPlaybackDao = db.itemPlaybackDao()

    @Provides
    @Singleton
    fun playbackLanguageChoiceDao(db: AppDatabase): PlaybackLanguageChoiceDao = db.playbackLanguageChoiceDao()

    @Provides
    @Singleton
    fun userPreferencesDataStore(
        @ApplicationContext context: Context,
        userPreferencesSerializer: AppPreferencesSerializer,
    ): DataStore<AppPreferences> =
        DataStoreFactory.create(
            serializer = userPreferencesSerializer,
            produceFile = { context.dataStoreFile("app_preferences.pb") },
            corruptionHandler =
                ReplaceFileCorruptionHandler(
                    produceNewData = { AppPreferences.getDefaultInstance() },
                ),
        )

    @Provides
    @Singleton
    fun keyValueDataStore(
        @ApplicationContext context: Context,
    ): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(
            produceFile = { context.preferencesDataStoreFile("key_value") },
        )
}
