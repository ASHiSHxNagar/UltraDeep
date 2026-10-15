package com.ultradeep.music.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.ultradeep.music.data.db.entity.LyricsEntity

@Dao
interface LyricsDao {
    @Query("SELECT * FROM lyrics WHERE songId = :songId LIMIT 1")
    suspend fun getLyricsForSong(songId: Long): LyricsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLyrics(lyrics: LyricsEntity)

    @Query("DELETE FROM lyrics WHERE songId = :songId")
    suspend fun deleteLyricsForSong(songId: Long)
}
