package com.ultradeep.music.util

import android.content.Context
import android.net.Uri
import com.ultradeep.music.R
import com.ultradeep.music.data.model.Song
import java.io.File
import kotlin.math.abs

object ArtworkManager {

    val DEFAULT_ARTWORKS = intArrayOf(
        R.drawable.art_neon_synthwave,
        R.drawable.art_cyber_violet,
        R.drawable.art_electric_cyan,
        R.drawable.art_cosmic_deep,
        R.drawable.art_aurora_borealis,
        R.drawable.art_midnight_lofi,
        R.drawable.art_sunset_vibes,
        R.drawable.art_emerald_glow,
        R.drawable.art_acoustic_sound,
        R.drawable.art_liquid_gradient
    )

    fun getArtworkForSong(context: Context, song: Song): Any {
        // 1. Check if user set a custom artwork for this specific song
        val customSongFile = getCustomArtworkFile(context, song.id)
        if (customSongFile.exists() && customSongFile.length() > 0) {
            return Uri.fromFile(customSongFile)
        }

        // 2. Check if embedded album artwork exists from MediaStore
        if (!song.albumArtUri.isNullOrEmpty()) {
            return Uri.parse(song.albumArtUri)
        }

        // 3. Fallback to one of the 10 curated generative artworks
        return getDefaultArtworkRes(song.id)
    }

    fun getDefaultArtworkRes(songId: Long): Int {
        val index = abs(songId.hashCode()) % DEFAULT_ARTWORKS.size
        return DEFAULT_ARTWORKS[index]
    }

    fun getCustomArtworkFile(context: Context, songId: Long): File {
        val dir = File(context.filesDir, "custom_artworks")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "song_${songId}.jpg")
    }

    fun getGlobalCustomBgFile(context: Context): File {
        val dir = File(context.filesDir, "custom_ambient")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "default_custom_bg.jpg")
    }
}
