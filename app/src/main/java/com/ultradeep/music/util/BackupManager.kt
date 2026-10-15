package com.ultradeep.music.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Base64
import androidx.core.content.FileProvider
import com.ultradeep.music.UltraDeepApplication
import com.ultradeep.music.data.db.entity.FavoriteSongEntity
import com.ultradeep.music.data.db.entity.LyricsEntity
import com.ultradeep.music.data.db.entity.PlaylistEntity
import com.ultradeep.music.data.db.entity.PlaylistSongCrossRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class RestoreSummary(
    val lyricsCount: Int,
    val favoritesCount: Int,
    val playlistsCount: Int,
    val artworksCount: Int
)

object BackupManager {

    private const val AUTO_BACKUP_PREFS = "automatic_backup_prefs"
    private const val AUTO_BACKUP_TREE_URI = "backup_tree_uri"
    private const val AUTO_BACKUP_FILE_NAME = "UltraDeep_AutoBackup.json"
    private val autoBackupMutex = Mutex()

    data class AutoBackupSetupResult(val restored: RestoreSummary?, val written: Boolean)

    fun hasAutoBackupFolder(context: Context): Boolean {
        val uriText = context.getSharedPreferences(AUTO_BACKUP_PREFS, Context.MODE_PRIVATE)
            .getString(AUTO_BACKUP_TREE_URI, null) ?: return false
        val uri = Uri.parse(uriText)
        return context.contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isReadPermission && it.isWritePermission
        }
    }

    /** Stores a durable SAF folder grant and recovers a backup if this is a clean install. */
    suspend fun selectAutoBackupFolder(context: Context, treeUri: Uri): AutoBackupSetupResult = withContext(Dispatchers.IO) {
        context.contentResolver.takePersistableUriPermission(
            treeUri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        context.getSharedPreferences(AUTO_BACKUP_PREFS, Context.MODE_PRIVATE)
            .edit().putString(AUTO_BACKUP_TREE_URI, treeUri.toString()).commit()

        val existing = readAutoBackup(context, treeUri)
        val restored = if (!existing.isNullOrBlank() && !hasLocalUserData(context)) {
            restoreBackup(context, existing)
        } else null
        AutoBackupSetupResult(restored, updateAutoBackup(context))
    }

    /** Rewrites one stable JSON file; safe to invoke both at app launch and when leaving it. */
    suspend fun updateAutoBackup(context: Context): Boolean = autoBackupMutex.withLock {
        withContext(Dispatchers.IO) {
            val uriText = context.getSharedPreferences(AUTO_BACKUP_PREFS, Context.MODE_PRIVATE)
                .getString(AUTO_BACKUP_TREE_URI, null) ?: return@withContext false
            val treeUri = Uri.parse(uriText)
            val hasPermission = context.contentResolver.persistedUriPermissions.any {
                it.uri == treeUri && it.isReadPermission && it.isWritePermission
            }
            if (!hasPermission) return@withContext false
            writeAutoBackup(context, treeUri)
        }
    }

    suspend fun restoreSelectedAutoBackup(context: Context): RestoreSummary? = withContext(Dispatchers.IO) {
        val uriText = context.getSharedPreferences(AUTO_BACKUP_PREFS, Context.MODE_PRIVATE)
            .getString(AUTO_BACKUP_TREE_URI, null) ?: return@withContext null
        val contents = readAutoBackup(context, Uri.parse(uriText)) ?: return@withContext null
        restoreBackup(context, contents)
    }

    private suspend fun writeAutoBackup(context: Context, treeUri: Uri): Boolean {
        return try {
            val json = createBackupJson(context)
            val documentUri = findAutoBackupDocument(context, treeUri, create = true) ?: return false
            context.contentResolver.openOutputStream(documentUri, "wt")?.bufferedWriter()?.use { writer ->
                writer.write(json)
            } ?: return false
            true
        } catch (error: Exception) {
            error.printStackTrace()
            false
        }
    }

    private fun readAutoBackup(context: Context, treeUri: Uri): String? {
        return try {
            val documentUri = findAutoBackupDocument(context, treeUri, create = false) ?: return null
            context.contentResolver.openInputStream(documentUri)?.bufferedReader()?.use { it.readText() }
        } catch (error: Exception) {
            error.printStackTrace()
            null
        }
    }

    private fun findAutoBackupDocument(context: Context, treeUri: Uri, create: Boolean): Uri? {
        val resolver = context.contentResolver
        val treeDocumentId = DocumentsContract.getTreeDocumentId(treeUri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeDocumentId)
        resolver.query(
            childrenUri,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            while (cursor.moveToNext()) {
                if (cursor.getString(nameColumn) == AUTO_BACKUP_FILE_NAME) {
                    return DocumentsContract.buildDocumentUriUsingTree(treeUri, cursor.getString(idColumn))
                }
            }
        }
        if (!create) return null
        val parentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, treeDocumentId)
        return DocumentsContract.createDocument(resolver, parentUri, "application/json", AUTO_BACKUP_FILE_NAME)
    }

    private suspend fun hasLocalUserData(context: Context): Boolean {
        val db = UltraDeepApplication.instance.database
        if (db.lyricsDao().getAllLyrics().isNotEmpty() ||
            db.favoriteDao().getAllFavorites().isNotEmpty() ||
            db.playlistDao().getAllPlaylistsSync().isNotEmpty() ||
            db.playlistDao().getAllPlaylistSongsSync().isNotEmpty() ||
            ArtworkManager.getAllCustomArtworkFiles(context).isNotEmpty() ||
            ArtworkManager.hasCustomGlobalDefault(context)) return true

        return context.getSharedPreferences("lyrics_timer_prefs", Context.MODE_PRIVATE)
            .all.keys.any { it.startsWith("lyrics_offset_") }
    }

    suspend fun createBackupJson(context: Context): String = withContext(Dispatchers.IO) {
        val root = JSONObject()
        root.put("app", "UltraDeep")
        root.put("version", 1)
        root.put("timestamp", System.currentTimeMillis())

        val db = UltraDeepApplication.instance.database

        // 1. Lyrics
        val lyricsArray = JSONArray()
        val allLyrics = db.lyricsDao().getAllLyrics()
        for (item in allLyrics) {
            val obj = JSONObject()
            obj.put("songId", item.songId)
            obj.put("title", item.title)
            obj.put("artist", item.artist)
            obj.put("durationSec", item.durationSec)
            obj.put("lrcContent", item.lrcContent)
            obj.put("isSynced", item.isSynced)
            obj.put("isCustom", item.isCustom)
            obj.put("isInstrumental", item.isInstrumental)
            lyricsArray.put(obj)
        }
        root.put("lyrics", lyricsArray)

        // 2. Lyrics sync offsets (SharedPreferences)
        val offsetPrefs = context.getSharedPreferences("lyrics_timer_prefs", Context.MODE_PRIVATE)
        val offsetObj = JSONObject()
        for ((key, value) in offsetPrefs.all) {
            offsetObj.put(key, value)
        }
        root.put("lyricsOffsets", offsetObj)

        // 3. Favorites
        val favoritesArray = JSONArray()
        val allFavorites = db.favoriteDao().getAllFavorites()
        for (item in allFavorites) {
            val obj = JSONObject()
            obj.put("songId", item.songId)
            obj.put("addedAt", item.addedAt)
            favoritesArray.put(obj)
        }
        root.put("favorites", favoritesArray)

        // 4. Playlists
        val playlistsArray = JSONArray()
        for (p in db.playlistDao().getAllPlaylistsSync()) {
            val obj = JSONObject()
            obj.put("id", p.id)
            obj.put("name", p.name)
            obj.put("createdAt", p.createdAt)
            playlistsArray.put(obj)
        }
        root.put("playlists", playlistsArray)

        // 5. Playlist songs
        val playlistSongsArray = JSONArray()
        for (ps in db.playlistDao().getAllPlaylistSongsSync()) {
            val obj = JSONObject()
            obj.put("playlistId", ps.playlistId)
            obj.put("songId", ps.songId)
            obj.put("orderIndex", ps.orderIndex)
            playlistSongsArray.put(obj)
        }
        root.put("playlistSongs", playlistSongsArray)

        // 6. Custom song artworks (Base64)
        val artworksObj = JSONObject()
        val customFiles = ArtworkManager.getAllCustomArtworkFiles(context)
        for (file in customFiles) {
            try {
                val bytes = file.readBytes()
                val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                artworksObj.put(file.name, b64)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        root.put("customArtworks", artworksObj)

        // 7. Global custom default artwork
        val globalDefault = ArtworkManager.getGlobalDefaultArtworkFile(context)
        if (globalDefault.exists() && globalDefault.length() > 0) {
            try {
                val bytes = globalDefault.readBytes()
                root.put("globalDefaultArtBase64", Base64.encodeToString(bytes, Base64.NO_WRAP))
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        root.toString(2)
    }

    suspend fun exportBackupAndShare(context: Context): Intent = withContext(Dispatchers.IO) {
        val json = createBackupJson(context)
        val backupDir = File(context.cacheDir, "backups").apply { if (!exists()) mkdirs() }
        val backupFile = File(backupDir, "UltraDeep_Backup_${System.currentTimeMillis()}.json")
        backupFile.writeText(json)

        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            backupFile
        )

        Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "UltraDeep Music Backup")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    suspend fun restoreBackup(context: Context, jsonText: String): RestoreSummary = withContext(Dispatchers.IO) {
        val root = JSONObject(jsonText)
        val db = UltraDeepApplication.instance.database

        // 1. Restore Lyrics
        var restoredLyrics = 0
        if (root.has("lyrics")) {
            val lyricsArray = root.getJSONArray("lyrics")
            val list = mutableListOf<LyricsEntity>()
            for (i in 0 until lyricsArray.length()) {
                val obj = lyricsArray.getJSONObject(i)
                list.add(
                    LyricsEntity(
                        songId = obj.getLong("songId"),
                        title = obj.optString("title", ""),
                        artist = obj.optString("artist", ""),
                        durationSec = obj.optLong("durationSec", 0),
                        lrcContent = obj.optString("lrcContent", ""),
                        isSynced = obj.optBoolean("isSynced", false),
                        isCustom = obj.optBoolean("isCustom", false),
                        isInstrumental = obj.optBoolean("isInstrumental", false)
                    )
                )
            }
            if (list.isNotEmpty()) {
                db.lyricsDao().insertAll(list)
                restoredLyrics = list.size
            }
        }

        // 2. Restore Lyrics offsets
        if (root.has("lyricsOffsets")) {
            val offsetObj = root.getJSONObject("lyricsOffsets")
            val prefs = context.getSharedPreferences("lyrics_timer_prefs", Context.MODE_PRIVATE)
            val editor = prefs.edit()
            val keys = offsetObj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val value = offsetObj.get(key)
                when (value) {
                    is Number -> editor.putLong(key, value.toLong())
                    is String -> value.toLongOrNull()?.let { editor.putLong(key, it) }
                }
            }
            editor.apply()
        }

        // 3. Restore Favorites
        var restoredFavs = 0
        if (root.has("favorites")) {
            val favsArray = root.getJSONArray("favorites")
            val list = mutableListOf<FavoriteSongEntity>()
            for (i in 0 until favsArray.length()) {
                val obj = favsArray.getJSONObject(i)
                list.add(
                    FavoriteSongEntity(
                        songId = obj.getLong("songId"),
                        addedAt = obj.optLong("addedAt", System.currentTimeMillis())
                    )
                )
            }
            if (list.isNotEmpty()) {
                db.favoriteDao().insertAll(list)
                restoredFavs = list.size
            }
        }

        // 4. Restore Playlists & songs
        var restoredPlaylists = 0
        if (root.has("playlists")) {
            val playlistsArray = root.getJSONArray("playlists")
            val list = mutableListOf<PlaylistEntity>()
            for (i in 0 until playlistsArray.length()) {
                val obj = playlistsArray.getJSONObject(i)
                list.add(
                    PlaylistEntity(
                        id = obj.getLong("id"),
                        name = obj.getString("name"),
                        createdAt = obj.optLong("createdAt", System.currentTimeMillis())
                    )
                )
            }
            if (list.isNotEmpty()) {
                db.playlistDao().insertAllPlaylists(list)
                restoredPlaylists = list.size
            }
        }

        if (root.has("playlistSongs")) {
            val songsArray = root.getJSONArray("playlistSongs")
            val list = mutableListOf<PlaylistSongCrossRef>()
            for (i in 0 until songsArray.length()) {
                val obj = songsArray.getJSONObject(i)
                list.add(
                    PlaylistSongCrossRef(
                        playlistId = obj.getLong("playlistId"),
                        songId = obj.getLong("songId"),
                        orderIndex = obj.optInt("orderIndex", i)
                    )
                )
            }
            if (list.isNotEmpty()) {
                db.playlistDao().insertAllPlaylistSongs(list)
            }
        }

        // 5. Restore Custom Artworks
        var restoredArtworks = 0
        if (root.has("customArtworks")) {
            val artsObj = root.getJSONObject("customArtworks")
            val dir = File(context.filesDir, "custom_artworks").apply { if (!exists()) mkdirs() }
            val keys = artsObj.keys()
            while (keys.hasNext()) {
                val filename = keys.next()
                val b64 = artsObj.getString(filename)
                try {
                    val bytes = Base64.decode(b64, Base64.DEFAULT)
                    val target = File(dir, filename)
                    target.writeBytes(bytes)
                    restoredArtworks++
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        // 6. Restore Global Default Artwork
        if (root.has("globalDefaultArtBase64")) {
            val b64 = root.getString("globalDefaultArtBase64")
            try {
                val bytes = Base64.decode(b64, Base64.DEFAULT)
                val target = ArtworkManager.getGlobalDefaultArtworkFile(context)
                target.writeBytes(bytes)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        RestoreSummary(
            lyricsCount = restoredLyrics,
            favoritesCount = restoredFavs,
            playlistsCount = restoredPlaylists,
            artworksCount = restoredArtworks
        )
    }
}
