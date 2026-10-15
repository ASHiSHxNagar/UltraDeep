package com.ultradeep.music.util

data class CleanTrackInfo(
    val title: String,
    val artist: String,
    val searchQuery: String
)

object TrackNameSanitizer {

    private val BITRATE_REGEX = Regex("(?i)[\\(\\[]?(128k|192k|256k|320k|44\\.?1k|48k|kbps)[\\)\\]]?", RegexOption.IGNORE_CASE)
    private val EXT_REGEX = Regex("(?i)\\.(mp3|m4a|flac|aac|wav|ogg)$")
    private val BRACKET_TAG_REGEX = Regex("(?i)[\\(\\[][^\\)\\]]*(official|audio|video|clip officiel|lyric video|music video|hd|4k|remix|slowed|reverb|viral|instrumental|bhojpuri|gujarati|garba|dakla|bhajan|lokgeet|nonstop|dhol mix|dj remix|status|ringtone|audio song|video song|full song|original)[^\\)\\]]*[\\)\\]]", RegexOption.IGNORE_CASE)
    private val STANDALONE_TAG_REGEX = Regex("(?i)\\b(official (audio|video|music video)|lyric video|music video|dhol mix|dj remix|audio song|video song|full song|full audio)\\b", RegexOption.IGNORE_CASE)
    private val FEAT_REGEX = Regex("(?i)[\\(\\[]?\\b(ft|feat|featuring)\\.?\\s+[^\\)\\]]+[\\)\\]]?", RegexOption.IGNORE_CASE)

    fun sanitize(rawTitle: String, rawArtist: String): CleanTrackInfo {
        var title = rawTitle.replace(EXT_REGEX, "").trim()
        var artist = rawArtist.trim()

        val isUnknownArtist = artist.isEmpty() ||
                artist.equals("Unknown Artist", ignoreCase = true) ||
                artist.equals("<unknown>", ignoreCase = true)

        // If artist is unknown, check if filename has "Artist - Title" or "Artist_-_Title" pattern
        if (isUnknownArtist) {
            when {
                title.contains("_-_") -> {
                    val parts = title.split("_-_", limit = 2)
                    artist = parts[0].replace("_", " ").trim()
                    title = parts[1]
                }
                title.contains(" - ") -> {
                    val parts = title.split(" - ", limit = 2)
                    artist = parts[0].replace("_", " ").trim()
                    title = parts[1]
                }
                title.contains(" — ") -> {
                    val parts = title.split(" — ", limit = 2)
                    artist = parts[0].replace("_", " ").trim()
                    title = parts[1]
                }
            }
        }

        // If title contains pipe '|' or slash '/' (common in Indian YouTube song titles), extract main title
        if (title.contains("|")) {
            val parts = title.split("|")
            title = parts[0].trim()
        }

        // Clean title
        var cleanTitle = title.replace("_", " ")
        cleanTitle = BITRATE_REGEX.replace(cleanTitle, "")
        cleanTitle = BRACKET_TAG_REGEX.replace(cleanTitle, "")
        cleanTitle = STANDALONE_TAG_REGEX.replace(cleanTitle, "")
        cleanTitle = FEAT_REGEX.replace(cleanTitle, "")
        cleanTitle = cleanTitle.replace(Regex("\\s+"), " ").trim()

        // Clean artist
        var cleanArtist = artist.replace("_", " ")
        cleanArtist = BITRATE_REGEX.replace(cleanArtist, "")
        cleanArtist = BRACKET_TAG_REGEX.replace(cleanArtist, "")
        cleanArtist = STANDALONE_TAG_REGEX.replace(cleanArtist, "")
        cleanArtist = cleanArtist.replace(Regex("\\s+"), " ").trim()

        if (cleanArtist.equals("Unknown Artist", ignoreCase = true) || cleanArtist.isEmpty()) {
            cleanArtist = "Unknown Artist"
        }

        // Construct search query
        val searchQuery = if (cleanArtist != "Unknown Artist") {
            "$cleanArtist $cleanTitle".trim()
        } else {
            cleanTitle
        }

        return CleanTrackInfo(
            title = if (cleanTitle.isNotBlank()) cleanTitle else rawTitle,
            artist = cleanArtist,
            searchQuery = searchQuery
        )
    }
}
