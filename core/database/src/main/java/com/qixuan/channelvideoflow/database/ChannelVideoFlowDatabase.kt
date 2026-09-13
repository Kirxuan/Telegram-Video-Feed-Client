package com.qixuan.channelvideoflow.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [
        ChannelEntity::class,
        VideoEntity::class,
        TagEntity::class,
        VideoTagCrossRef::class,
        MediaCacheEntryEntity::class,
    ],
    version = 6,
    exportSchema = true,
)
@TypeConverters(ChannelConverters::class)
abstract class ChannelVideoFlowDatabase : RoomDatabase() {
    abstract fun channelDao(): ChannelDao
    abstract fun videoIndexDao(): VideoIndexDao
    abstract fun mediaCacheEntryDao(): MediaCacheEntryDao
}
