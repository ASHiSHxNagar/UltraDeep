package com.ultradeep.music.data.repository

import com.ultradeep.music.data.db.dao.LyricsDao
import com.ultradeep.music.data.db.entity.LyricsEntity
import com.ultradeep.music.data.model.LrcResult
import com.ultradeep.music.data.model.Song
import com.ultradeep.music.data.remote.ApiClient
import com.ultradeep.music.util.LrcParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

class LyricsRepository(private val lyricsDao: LyricsDao) {

    suspend fun getLyrics(song: Song, forceRefresh: Boolean = false): LrcResult = withContext(Dispatchers.IO) {
        val cached = lyricsDao.getLyricsForSong(song.id)
        if (cached != null && cached.isCustom) {
            return@withContext LrcParser.parse(cached.lrcContent, true)
        }

        if (!forceRefresh && cached != null) {
            if (cached.isInstrumental) {
                return@withContext LrcResult(
                    lines = emptyList(),
                    isSynced = false,
                    plainText = "",
                    isCustom = false,
                    isInstrumental = true
                )
            }
            // A saved plain lyric is still a deliberate cached result.  Reusing it avoids
            // repeatedly hitting the network every time this track is played.
            return@withContext LrcParser.parse(cached.lrcContent, cached.isCustom)
        }

        // Fetch from LRCLIB API
        try {
            val durationSec = song.duration / 1000
            val sanitized = com.ultradeep.music.util.TrackNameSanitizer.sanitize(song.title, song.artist)

            var response = ApiClient.lrcLibService.getLyrics(
                trackName = sanitized.title,
                artistName = if (sanitized.artist != "Unknown Artist") sanitized.artist else "",
                durationSec = durationSec
            )

            if (!response.isSuccessful && (sanitized.title != song.title || sanitized.artist != song.artist)) {
                response = ApiClient.lrcLibService.getLyrics(
                    trackName = song.title,
                    artistName = song.artist,
                    durationSec = durationSec
                )
            }

            if (response.isSuccessful && response.body() != null) {
                val body = response.body()!!
                val isInstrumental = body.instrumental == true
                val rawLyrics = body.syncedLyrics ?: body.plainLyrics ?: ""
                val isSynced = !body.syncedLyrics.isNullOrEmpty()

                // Cache in Room DB
                lyricsDao.insertLyrics(
                    LyricsEntity(
                        songId = song.id,
                        title = song.title,
                        artist = song.artist,
                        durationSec = durationSec,
                        lrcContent = rawLyrics,
                        isSynced = isSynced,
                        isCustom = false,
                        isInstrumental = isInstrumental
                    )
                )

                if (isInstrumental) {
                    return@withContext LrcResult(emptyList(), false, "", isCustom = false, isInstrumental = true)
                }

                return@withContext LrcParser.parse(rawLyrics, false)
            } else {
                // Try smart search with sanitized query (e.g. "Jessie J Price Tag")
                var searchResponse = ApiClient.lrcLibService.searchLyrics(sanitized.searchQuery)
                if (!searchResponse.isSuccessful || searchResponse.body().isNullOrEmpty()) {
                    if (sanitized.title.isNotBlank() && sanitized.title != sanitized.searchQuery) {
                        searchResponse = ApiClient.lrcLibService.searchLyrics(sanitized.title)
                    }
                }
                if (!searchResponse.isSuccessful || searchResponse.body().isNullOrEmpty()) {
                    searchResponse = ApiClient.lrcLibService.searchLyrics("${song.title} ${song.artist}".trim())
                }

                if (searchResponse.isSuccessful && !searchResponse.body().isNullOrEmpty()) {
                    val list = searchResponse.body()!!
                    // Search can return covers/live edits. Score title, artist and duration,
                    // while still strongly preferring synchronized LRC.
                    val best = list.maxByOrNull { candidate ->
                        scoreCandidate(candidate, sanitized.title, sanitized.artist, durationSec)
                    } ?: list.first()
                    val isInstrumental = best.instrumental == true
                    val rawLyrics = best.syncedLyrics ?: best.plainLyrics ?: ""
                    val isSynced = !best.syncedLyrics.isNullOrEmpty()

                    lyricsDao.insertLyrics(
                        LyricsEntity(
                            songId = song.id,
                            title = song.title,
                            artist = song.artist,
                            durationSec = durationSec,
                            lrcContent = rawLyrics,
                            isSynced = isSynced,
                            isCustom = false,
                            isInstrumental = isInstrumental
                        )
                    )

                    if (isInstrumental) {
                        return@withContext LrcResult(emptyList(), false, "", isCustom = false, isInstrumental = true)
                    }

                    return@withContext LrcParser.parse(rawLyrics, false)
                }
            }

            // Fallback to secondary source (Lyrics.ovh) for Hindi, Gujarati, and regional tracks
            val ovhLyrics = fetchLyricsFromLyricsOvh(
                artist = if (sanitized.artist != "Unknown Artist") sanitized.artist else "",
                title = sanitized.title
            )

            if (!ovhLyrics.isNullOrBlank()) {
                lyricsDao.insertLyrics(
                    LyricsEntity(
                        songId = song.id,
                        title = song.title,
                        artist = song.artist,
                        durationSec = durationSec,
                        lrcContent = ovhLyrics,
                        isSynced = false,
                        isCustom = false,
                        isInstrumental = false
                    )
                )
                return@withContext LrcParser.parse(ovhLyrics, false)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Fallback to cached lyrics if network search fails
        if (cached != null) {
            return@withContext LrcParser.parse(cached.lrcContent, false)
        }

        // Return empty result if not found
        LrcResult(emptyList(), false, "")
    }

    /** True when this track already has either downloaded or manually saved lyrics. */
    suspend fun hasSavedLyrics(songId: Long): Boolean = withContext(Dispatchers.IO) {
        lyricsDao.getLyricsForSong(songId) != null
    }

    private fun scoreCandidate(
        candidate: com.ultradeep.music.data.remote.LrcLibResponse,
        title: String,
        artist: String,
        durationSec: Long
    ): Int {
        fun normalize(value: String?) = value.orEmpty().lowercase()
            .replace(Regex("[^\\p{L}\\p{N}]"), "")

        val wantedTitle = normalize(title)
        val wantedArtist = normalize(artist)
        val resultTitle = normalize(candidate.trackName ?: candidate.name)
        val resultArtist = normalize(candidate.artistName)
        var score = 0
        if (wantedTitle.isNotBlank() && resultTitle == wantedTitle) score += 80
        else if (wantedTitle.isNotBlank() && resultTitle.isNotBlank() &&
            (resultTitle.contains(wantedTitle) || wantedTitle.contains(resultTitle))) score += 45
        if (wantedArtist.isNotBlank() && resultArtist == wantedArtist) score += 55
        else if (wantedArtist.isNotBlank() && (resultArtist.contains(wantedArtist) || wantedArtist.contains(resultArtist))) score += 25
        if (!candidate.syncedLyrics.isNullOrBlank()) score += 35
        candidate.duration?.toLong()?.let { duration ->
            score += when (kotlin.math.abs(duration - durationSec)) {
                in 0..2 -> 25
                in 3..6 -> 12
                in 7..12 -> 4
                else -> -20
            }
        }
        return score
    }

    private fun fetchLyricsFromLyricsOvh(artist: String, title: String): String? {
        if (title.isBlank()) return null
        return try {
            val cleanTitle = java.net.URLEncoder.encode(title.trim(), "UTF-8").replace("+", "%20")
            val cleanArtist = if (artist.isNotBlank() && artist != "Unknown Artist") {
                java.net.URLEncoder.encode(artist.trim(), "UTF-8").replace("+", "%20")
            } else {
                cleanTitle
            }
            val url = "https://api.lyrics.ovh/v1/$cleanArtist/$cleanTitle"
            val request = Request.Builder().url(url).build()
            ApiClient.okHttpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val raw = response.body?.string() ?: return@use null
                    val json = org.json.JSONObject(raw)
                    if (json.has("lyrics")) {
                        val lyrics = json.getString("lyrics").trim()
                        if (lyrics.isNotBlank()) return@use lyrics
                    }
                }
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    suspend fun saveCustomLyrics(song: Song, customText: String): LrcResult = withContext(Dispatchers.IO) {
        val parsed = LrcParser.parse(customText, isCustom = true)
        lyricsDao.insertLyrics(
            LyricsEntity(
                songId = song.id,
                title = song.title,
                artist = song.artist,
                durationSec = song.duration / 1000,
                lrcContent = customText,
                isSynced = parsed.isSynced,
                isCustom = true,
                isInstrumental = false
            )
        )
        parsed
    }

    suspend fun resetLyrics(songId: Long) = withContext(Dispatchers.IO) {
        lyricsDao.deleteLyricsForSong(songId)
    }

    suspend fun fetchLrcFromUrl(url: String): String? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(url).build()
            ApiClient.okHttpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val raw = response.body?.string() ?: return@use null
                    if (raw.trim().startsWith("{")) {
                        try {
                            val json = org.json.JSONObject(raw)
                            if (json.has("syncedLyrics") && !json.isNull("syncedLyrics")) {
                                val synced = json.getString("syncedLyrics")
                                if (synced.isNotBlank()) return@use synced
                            }
                            if (json.has("plainLyrics") && !json.isNull("plainLyrics")) {
                                val plain = json.getString("plainLyrics")
                                if (plain.isNotBlank()) return@use plain
                            }
                        } catch (e: Exception) {
                            // Fallback to raw text if json parsing fails
                        }
                    }
                    raw
                } else null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
