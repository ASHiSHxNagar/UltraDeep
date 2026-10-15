package com.ultradeep.music.util

import android.content.Context
import android.net.Uri
import com.ultradeep.music.R
import com.ultradeep.music.data.model.Song
import java.io.File

object ArtworkManager {

    /**
     * Resolves artwork for a song following the user's explicit hierarchy:
     * 1. Custom artwork set by user for this specific song (via 3-dots -> Change Artwork)
     * 2. Global default artwork picked by user in Settings
     * 3. Default bundled car photo: R.drawable.car_default_photo
     */
    fun getArtworkForSong(context: Context, song: Song): Any {
        // 1. Check if user set a custom artwork for this specific song
        val customSongFile = getCustomArtworkFile(context, song.id)
        if (customSongFile.exists() && customSongFile.length() > 0) {
            return Uri.fromFile(customSongFile)
        }

        // 2. Check if user set a global default artwork in Settings
        val globalDefaultFile = getGlobalDefaultArtworkFile(context)
        if (globalDefaultFile.exists() && globalDefaultFile.length() > 0) {
            return Uri.fromFile(globalDefaultFile)
        }

        // 3. Fallback: bundled sleek car photo default
        return R.drawable.car_default_photo
    }

    /**
     * Returns the current global default artwork (chosen custom image or bundled car photo).
     */
    fun getGlobalDefaultArtwork(context: Context): Any {
        val file = getGlobalDefaultArtworkFile(context)
        if (file.exists() && file.length() > 0) {
            return Uri.fromFile(file)
        }
        return R.drawable.car_default_photo
    }

    fun hasCustomGlobalDefault(context: Context): Boolean {
        val file = getGlobalDefaultArtworkFile(context)
        return file.exists() && file.length() > 0
    }

    fun getCustomArtworkFile(context: Context, songId: Long): File {
        val dir = File(context.filesDir, "custom_artworks")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "song_${songId}.jpg")
    }

    fun getGlobalDefaultArtworkFile(context: Context): File {
        val dir = File(context.filesDir, "custom_artwork_default")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "default_artwork.jpg")
    }

    fun getAllCustomArtworkFiles(context: Context): List<File> {
        val dir = File(context.filesDir, "custom_artworks")
        if (!dir.exists()) return emptyList()
        return dir.listFiles()?.filter { it.isFile && it.name.startsWith("song_") } ?: emptyList()
    }
}
