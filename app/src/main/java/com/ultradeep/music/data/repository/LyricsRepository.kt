package com.ultradeep.music.data.repository

import com.ultradeep.music.data.db.dao.LyricsDao
import com.ultradeep.music.data.db.entity.LyricsEntity
import com.ultradeep.music.data.model.LrcResult
import com.ultradeep.music.data.model.Song
import com.ultradeep.music.data.remote.ApiClient
import com.ultradeep.music.util.LrcParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
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

        // Multi-tier lyric fetching pipeline: LRCLIB -> JioSaavn (Hindi/Bhojpuri/Gujarati/etc.) -> Lyrics.ovh -> ChartLyrics
        try {
            val durationSec = song.duration / 1000
            val sanitized = com.ultradeep.music.util.TrackNameSanitizer.sanitize(song.title, song.artist)
            val candidateQueries = com.ultradeep.music.util.TrackNameSanitizer.generateSearchQueries(song.title, song.artist)

            // Tier 1: Try LRCLIB exact match
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

            // Tier 2: Try LRCLIB search with candidate queries
            for (query in candidateQueries) {
                try {
                    val searchResponse = ApiClient.lrcLibService.searchLyrics(query)
                    if (searchResponse.isSuccessful && !searchResponse.body().isNullOrEmpty()) {
                        val list = searchResponse.body()!!
                        val best = list.maxByOrNull { candidate ->
                            scoreCandidate(candidate, sanitized.title, sanitized.artist, durationSec)
                        } ?: list.first()

                        val isInstrumental = best.instrumental == true
                        val rawLyrics = best.syncedLyrics ?: best.plainLyrics ?: ""
                        val isSynced = !best.syncedLyrics.isNullOrEmpty()

                        if (rawLyrics.isNotBlank() || isInstrumental) {
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
                } catch (_: Exception) {
                    // Try next query
                }
            }

            // Tier 3: JioSaavn API (Dominant database for Indian languages: Hindi, Bhojpuri, Gujarati, Punjabi, etc.)
            val saavnLyrics = fetchLyricsFromJioSaavn(candidateQueries)
            if (!saavnLyrics.isNullOrBlank()) {
                lyricsDao.insertLyrics(
                    LyricsEntity(
                        songId = song.id,
                        title = song.title,
                        artist = song.artist,
                        durationSec = durationSec,
                        lrcContent = saavnLyrics,
                        isSynced = false,
                        isCustom = false,
                        isInstrumental = false
                    )
                )
                return@withContext LrcParser.parse(saavnLyrics, false)
            }

            // Tier 4: Fallback to Lyrics.ovh for International / English / Pop tracks
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

            // Tier 5: Fallback to ChartLyrics API
            val chartLyrics = fetchLyricsFromChartLyrics(
                artist = if (sanitized.artist != "Unknown Artist") sanitized.artist else "",
                title = sanitized.title
            )

            if (!chartLyrics.isNullOrBlank()) {
                lyricsDao.insertLyrics(
                    LyricsEntity(
                        songId = song.id,
                        title = song.title,
                        artist = song.artist,
                        durationSec = durationSec,
                        lrcContent = chartLyrics,
                        isSynced = false,
                        isCustom = false,
                        isInstrumental = false
                    )
                )
                return@withContext LrcParser.parse(chartLyrics, false)
            }
        } catch (e: CancellationException) {
            throw e
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

    private fun fetchLyricsFromJioSaavn(queries: List<String>): String? {
        for (query in queries) {
            if (query.isBlank()) continue
            try {
                val encoded = java.net.URLEncoder.encode(query, "UTF-8")
                val searchUrl = "https://www.jiosaavn.com/api.php?__call=search.getResults&_format=json&p=1&n=8&q=$encoded"
                val request = Request.Builder()
                    .url(searchUrl)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36")
                    .build()

                val raw = ApiClient.okHttpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) response.body?.string() else null
                } ?: continue

                val json = try { org.json.JSONObject(raw) } catch (_: Exception) { null } ?: continue
                val results = json.optJSONArray("results") ?: continue

                for (i in 0 until results.length()) {
                    val item = results.optJSONObject(i) ?: continue
                    val songId = item.optString("id")
                    val hasLyrics = item.optString("has_lyrics") == "true" || item.optBoolean("has_lyrics", false)
                    if (songId.isNotBlank() && hasLyrics) {
                        val lyricUrl = "https://www.jiosaavn.com/api.php?__call=lyrics.getLyrics&_format=json&ctx=web6dot0&_marker=0&lyrics_id=$songId"
                        val lyricReq = Request.Builder()
                            .url(lyricUrl)
                            .header("User-Agent", "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36")
                            .build()

                        val lyricRaw = ApiClient.okHttpClient.newCall(lyricReq).execute().use { lyricResp ->
                            if (lyricResp.isSuccessful) lyricResp.body?.string() else null
                        }

                        if (!lyricRaw.isNullOrBlank()) {
                            val lyricJson = try { org.json.JSONObject(lyricRaw) } catch (_: Exception) { null }
                            val lyricsHtml = lyricJson?.optString("lyrics")
                            if (!lyricsHtml.isNullOrBlank()) {
                                val cleanLyrics = android.text.Html.fromHtml(lyricsHtml, android.text.Html.FROM_HTML_MODE_LEGACY)
                                    .toString()
                                    .trim()
                                if (cleanLyrics.isNotBlank()) {
                                    return cleanLyrics
                                }
                            }
                        }
                    }
                }
            } catch (_: Exception) {
                // Try next query candidate
            }
        }
        return null
    }

    private fun fetchLyricsFromChartLyrics(artist: String, title: String): String? {
        if (title.isBlank()) return null
        return try {
            val encTitle = java.net.URLEncoder.encode(title.trim(), "UTF-8")
            val encArtist = if (artist.isNotBlank() && artist != "Unknown Artist") {
                java.net.URLEncoder.encode(artist.trim(), "UTF-8")
            } else ""
            val url = "http://api.chartlyrics.com/apiv1.asmx/SearchLyricDirect?artist=$encArtist&song=$encTitle"
            val request = Request.Builder().url(url).build()
            ApiClient.okHttpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: return@use null
                    val lyricMatch = Regex("<Lyric>(.*?)</Lyric>", RegexOption.DOT_MATCHES_ALL).find(body)
                    val lyrics = lyricMatch?.groupValues?.getOrNull(1)?.trim()
                    if (!lyrics.isNullOrBlank() && !lyrics.contains("not found", ignoreCase = true)) {
                        return@use lyrics
                    }
                }
                null
            }
        } catch (_: Exception) {
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
