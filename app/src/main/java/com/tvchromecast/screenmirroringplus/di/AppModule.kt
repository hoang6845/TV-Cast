package com.tvchromecast.screenmirroringplus.di

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import com.tvchromecast.screenmirroringplus.model.dao.AppDatabase
import com.tvchromecast.screenmirroringplus.model.dao.ChannelDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context
    ): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "TV Cast"
        )
            .addMigrations(AppDatabase.MIGRATION_1_2)
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .build()
    }

    @Provides
    fun channelDao(
        database: AppDatabase
    ): ChannelDao = database.channelDao()
}
