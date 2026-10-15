package com.ultradeep.music.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "lyrics")
data class LyricsEntity(
    @PrimaryKey
    val songId: Long,
    val title: String,
    val artist: String,
    val durationSec: Long,
    val lrcContent: String,
    val isSynced: Boolean,
    val isCustom: Boolean,
    val isInstrumental: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis()
)
