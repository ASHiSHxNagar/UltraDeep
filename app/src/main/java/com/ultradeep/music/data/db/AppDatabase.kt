package com.ultradeep.music.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.ultradeep.music.data.db.dao.FavoriteDao
import com.ultradeep.music.data.db.dao.LyricsDao
import com.ultradeep.music.data.db.dao.PlaylistDao
import com.ultradeep.music.data.db.entity.FavoriteSongEntity
import com.ultradeep.music.data.db.entity.LyricsEntity
import com.ultradeep.music.data.db.entity.PlaylistEntity
import com.ultradeep.music.data.db.entity.PlaylistSongCrossRef

@Database(
    entities = [
        LyricsEntity::class,
        PlaylistEntity::class,
        PlaylistSongCrossRef::class,
        FavoriteSongEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun lyricsDao(): LyricsDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun favoriteDao(): FavoriteDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "ultradeep_database"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
