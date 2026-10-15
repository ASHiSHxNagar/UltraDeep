package com.ultradeep.music.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "favorites")
data class FavoriteSongEntity(
    @PrimaryKey
    val songId: Long,
    val addedAt: Long = System.currentTimeMillis()
)
