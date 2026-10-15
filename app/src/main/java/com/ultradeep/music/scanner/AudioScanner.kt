package com.ultradeep.music.scanner

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.ultradeep.music.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object AudioScanner {

    private val ALBUM_ART_BASE_URI = Uri.parse("content://media/external/audio/albumart")

    suspend fun scanAudioFiles(context: Context): List<Song> = withContext(Dispatchers.IO) {
        val songList = mutableListOf<Song>()
        val contentResolver = context.contentResolver

        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.ALBUM_ID
        )

        // Filter: only music tracks, duration >= 10 seconds (10000 ms)
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DURATION} >= 10000"
        val sortOrder = "${MediaStore.Audio.Media.DATE_ADDED} DESC"

        try {
            contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                null,
                sortOrder
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val dateAddedCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
                val albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val rawTitle = cursor.getString(titleCol) ?: "Unknown Track"
                    val rawArtist = cursor.getString(artistCol) ?: "Unknown Artist"
                    val rawAlbum = cursor.getString(albumCol) ?: "Unknown Album"
                    val duration = cursor.getLong(durationCol)
                    val dateAdded = cursor.getLong(dateAddedCol)
                    val size = cursor.getLong(sizeCol)
                    val albumId = cursor.getLong(albumIdCol)

                    val cleanArtist = if (rawArtist.equals("<unknown>", ignoreCase = true)) "Unknown Artist" else rawArtist
                    val cleanAlbum = if (rawAlbum.equals("<unknown>", ignoreCase = true)) "Unknown Album" else rawAlbum

                    val contentUri = ContentUris.withAppendedId(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                        id
                    ).toString()

                    val albumArtUri = try {
                        ContentUris.withAppendedId(ALBUM_ART_BASE_URI, albumId).toString()
                    } catch (e: Exception) {
                        null
                    }

                    songList.add(
                        Song(
                            id = id,
                            title = rawTitle,
                            artist = cleanArtist,
                            album = cleanAlbum,
                            duration = duration,
                            contentUri = contentUri,
                            albumArtUri = albumArtUri,
                            dateAdded = dateAdded,
                            size = size
                        )
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        songList
    }
}
