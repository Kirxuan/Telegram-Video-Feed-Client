package com.qixuan.channelvideoflow.di

import android.content.Context
import androidx.room.Room
import com.qixuan.channelvideoflow.database.ChannelDao
import com.qixuan.channelvideoflow.database.ChannelVideoFlowDatabase
import com.qixuan.channelvideoflow.database.DatabaseMigrations
import com.qixuan.channelvideoflow.database.MediaCacheEntryDao
import com.qixuan.channelvideoflow.database.VideoIndexDao
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
    fun provideDatabase(
        @ApplicationContext context: Context,
    ): ChannelVideoFlowDatabase = Room.databaseBuilder(
        context,
        ChannelVideoFlowDatabase::class.java,
        "channel-video-flow.db",
    ).addMigrations(
        DatabaseMigrations.MIGRATION_1_2,
        DatabaseMigrations.MIGRATION_2_3,
        DatabaseMigrations.MIGRATION_3_4,
        DatabaseMigrations.MIGRATION_4_5,
        DatabaseMigrations.MIGRATION_5_6,
    ).build()

    @Provides
    fun provideChannelDao(database: ChannelVideoFlowDatabase): ChannelDao = database.channelDao()

    @Provides
    fun provideVideoIndexDao(database: ChannelVideoFlowDatabase): VideoIndexDao =
        database.videoIndexDao()

    @Provides
    fun provideMediaCacheEntryDao(database: ChannelVideoFlowDatabase): MediaCacheEntryDao =
        database.mediaCacheEntryDao()
}
