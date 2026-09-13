package com.qixuan.channelvideoflow.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "videos",
    primaryKeys = ["chat_id", "message_id"],
    foreignKeys = [
        ForeignKey(
            entity = ChannelEntity::class,
            parentColumns = ["chat_id"],
            childColumns = ["chat_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["chat_id"]),
        Index(value = ["publish_time", "chat_id", "message_id"]),
        Index(value = ["is_deleted", "invalidated_at"]),
    ],
)
data class VideoEntity(
    @ColumnInfo(name = "chat_id")
    val chatId: Long,
    @ColumnInfo(name = "message_id")
    val messageId: Long,
    @ColumnInfo(name = "file_id")
    val fileId: Int,
    @ColumnInfo(name = "remote_unique_id")
    val remoteUniqueId: String,
    val caption: String,
    @ColumnInfo(name = "duration_seconds")
    val durationSeconds: Int,
    val width: Int,
    val height: Int,
    @ColumnInfo(name = "file_size")
    val fileSize: Long?,
    @ColumnInfo(name = "supports_streaming")
    val supportsStreaming: Boolean,
    @ColumnInfo(name = "publish_time")
    val publishTime: Long,
    @ColumnInfo(name = "edit_time")
    val editTime: Long?,
    @ColumnInfo(name = "can_be_saved")
    val canBeSaved: Boolean,
    @ColumnInfo(name = "is_deleted")
    val isDeleted: Boolean = false,
    @ColumnInfo(name = "indexed_at")
    val indexedAt: Long,
    @ColumnInfo(name = "invalidated_at")
    val invalidatedAt: Long? = null,
)
