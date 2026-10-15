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
            return@withContext LrcParser.parse(cached.lrcContent, false)
        }

        // Fetch from LRCLIB API
        try {
            val durationSec = song.duration / 1000
            val response = ApiClient.lrcLibService.getLyrics(
                trackName = song.title,
                artistName = song.artist,
                durationSec = durationSec
            )

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
                // Try search fallback
                val searchResponse = ApiClient.lrcLibService.searchLyrics("${song.title} ${song.artist}".trim())
                if (searchResponse.isSuccessful && !searchResponse.body().isNullOrEmpty()) {
                    val first = searchResponse.body()!!.first()
                    val isInstrumental = first.instrumental == true
                    val rawLyrics = first.syncedLyrics ?: first.plainLyrics ?: ""
                    val isSynced = !first.syncedLyrics.isNullOrEmpty()

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
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Return empty result if not found
        LrcResult(emptyList(), false, "")
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
