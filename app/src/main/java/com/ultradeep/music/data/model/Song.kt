package com.ultradeep.music.data.model

import android.net.Uri
import java.util.Locale

data class Song(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val duration: Long,
    val contentUri: String,
    val albumArtUri: String?,
    val dateAdded: Long,
    val size: Long
) {
    val formattedDuration: String
        get() {
            val totalSeconds = duration / 1000
            val minutes = totalSeconds / 60
            val seconds = totalSeconds % 60
            return String.format(Locale.getDefault(), "%d:%02d", minutes, seconds)
        }

    val uri: Uri
        get() = Uri.parse(contentUri)
}
