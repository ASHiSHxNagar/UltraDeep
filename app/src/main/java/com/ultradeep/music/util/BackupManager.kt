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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class RestoreSummary(
    val lyricsCount: Int,
    val favoritesCount: Int,
    val playlistsCount: Int,
    val artworksCount: Int
)

/**
 * Robust non-overwriting backup manager:
 * - Every time the app opens (or user triggers backup), a dedicated folder is created:
 *   Format: `UltraDeep - DD-MM-YY - HH.mm.ss`
 *   Inside: `UltraDeep - DD-MM-YY - HH.mm.ss.json`
 * - Previous backups are NEVER overwritten or replaced.
 * - When reinstalled or selecting a folder on a clean install, the latest backup
 *   is automatically detected and restored with zero data loss.
 */
object BackupManager {

    private const val AUTO_BACKUP_PREFS = "automatic_backup_prefs"
    private const val AUTO_BACKUP_TREE_URI = "backup_tree_uri"
    private val autoBackupMutex = Mutex()
    private var lastBackupTimeMs = 0L

    data class AutoBackupSetupResult(
        val restored: RestoreSummary?,
        val written: Boolean,
        val backupFolderName: String? = null
    )

    data class DiscoveredBackup(
        val fileUri: Uri,
        val displayName: String,
        val timestamp: Long,
        val folderName: String
    )

    fun hasAutoBackupFolder(context: Context): Boolean {
        val uriText = context.getSharedPreferences(AUTO_BACKUP_PREFS, Context.MODE_PRIVATE)
            .getString(AUTO_BACKUP_TREE_URI, null) ?: return false
        val uri = Uri.parse(uriText)
        return context.contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isReadPermission && it.isWritePermission
        }
    }

    fun getAutoBackupFolderUri(context: Context): Uri? {
        val uriText = context.getSharedPreferences(AUTO_BACKUP_PREFS, Context.MODE_PRIVATE)
            .getString(AUTO_BACKUP_TREE_URI, null) ?: return null
        return Uri.parse(uriText)
    }

    /**
     * Stores the selected backup folder. If this is a fresh install (no local data),
     * it immediately restores from the newest backup found in the folder.
     */
    suspend fun selectAutoBackupFolder(context: Context, treeUri: Uri): AutoBackupSetupResult = withContext(Dispatchers.IO) {
        context.contentResolver.takePersistableUriPermission(
            treeUri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        context.getSharedPreferences(AUTO_BACKUP_PREFS, Context.MODE_PRIVATE)
            .edit().putString(AUTO_BACKUP_TREE_URI, treeUri.toString()).commit()

        val latest = findLatestBackup(context, treeUri)
        val localHadData = hasLocalUserData(context)

        // If local had no data (clean install after uninstall) and a backup exists in folder, auto-restore:
        val restored = if (!localHadData && latest != null) {
            val json = readBackupFromUri(context, latest.fileUri)
            if (!json.isNullOrBlank()) restoreBackup(context, json) else null
        } else null

        // Now create a new backup folder snapshot if local data is present
        val writtenFolder = if (hasLocalUserData(context)) {
            writeNewAutoBackupFolder(context, treeUri)
        } else null

        AutoBackupSetupResult(
            restored = restored,
            written = writtenFolder != null,
            backupFolderName = writtenFolder ?: latest?.folderName
        )
    }

    /**
     * Triggered every time the user opens the app:
     * - If local data is missing (clean install after reinstalling), restores from the newest backup.
     * - If local data exists, creates a new unique snapshot folder `UltraDeep - DD-MM-YY - HH.mm.ss/`.
     */
    suspend fun performAppOpenBackup(context: Context): RestoreSummary? = autoBackupMutex.withLock {
        withContext(Dispatchers.IO) {
            if (!hasAutoBackupFolder(context)) return@withContext null
            val treeUri = getAutoBackupFolderUri(context) ?: return@withContext null

            // Clean install check: if local data is missing, restore from the latest backup!
            if (!hasLocalUserData(context)) {
                val latest = findLatestBackup(context, treeUri)
                if (latest != null) {
                    val content = readBackupFromUri(context, latest.fileUri)
                    if (!content.isNullOrBlank()) {
                        val summary = restoreBackup(context, content)
                        return@withContext summary
                    }
                }
                return@withContext null
            }

            // Normal app open with data: check debounce (avoid duplicates on rotation)
            val now = System.currentTimeMillis()
            if (now - lastBackupTimeMs < 15_000L) {
                return@withContext null
            }
            lastBackupTimeMs = now

            writeNewAutoBackupFolder(context, treeUri)
            null
        }
    }

    /**
     * Explicitly creates a new timestamped backup folder and file.
     */
    suspend fun updateAutoBackup(context: Context): Boolean = autoBackupMutex.withLock {
        withContext(Dispatchers.IO) {
            val treeUri = getAutoBackupFolderUri(context) ?: return@withContext false
            val hasPermission = context.contentResolver.persistedUriPermissions.any {
                it.uri == treeUri && it.isReadPermission && it.isWritePermission
            }
            if (!hasPermission) return@withContext false
            val folderName = writeNewAutoBackupFolder(context, treeUri)
            folderName != null
        }
    }

    suspend fun restoreSelectedAutoBackup(context: Context): RestoreSummary? = withContext(Dispatchers.IO) {
        val treeUri = getAutoBackupFolderUri(context) ?: return@withContext null
        val latest = findLatestBackup(context, treeUri) ?: return@withContext null
        val contents = readBackupFromUri(context, latest.fileUri) ?: return@withContext null
        restoreBackup(context, contents)
    }

    /**
     * Creates a new subfolder in the tree with format `UltraDeep - DD-MM-YY - HH.mm.ss`
     * and a matching JSON file `UltraDeep - DD-MM-YY - HH.mm.ss.json` inside it.
     */
    private suspend fun writeNewAutoBackupFolder(context: Context, treeUri: Uri): String? {
        return try {
            if (!hasLocalUserData(context)) return null
            val json = createBackupJson(context)

            val now = Date()
            val folderDate = SimpleDateFormat("dd-MM-yy - HH.mm.ss", Locale.US).format(now)
            val folderName = "UltraDeep - $folderDate"
            val fileName = "$folderName.json"

            val resolver = context.contentResolver
            val treeDocumentId = DocumentsContract.getTreeDocumentId(treeUri)
            val parentDocumentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, treeDocumentId)

            // 1. Create dedicated subfolder
            val folderUri = DocumentsContract.createDocument(
                resolver,
                parentDocumentUri,
                DocumentsContract.Document.MIME_TYPE_DIR,
                folderName
            ) ?: return null

            val folderDocId = DocumentsContract.getDocumentId(folderUri)
            val subFolderUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, folderDocId)

            // 2. Create JSON file inside the subfolder
            val fileUri = DocumentsContract.createDocument(
                resolver,
                subFolderUri,
                "application/json",
                fileName
            ) ?: DocumentsContract.createDocument(
                resolver,
                folderUri,
                "application/json",
                fileName
            ) ?: return null

            // 3. Write backup JSON payload
            resolver.openOutputStream(fileUri, "wt")?.bufferedWriter()?.use { writer ->
                writer.write(json)
            } ?: return null

            folderName
        } catch (error: Exception) {
            error.printStackTrace()
            null
        }
    }

    /**
     * Scans the selected backup directory for all backup folders and files,
     * identifying the newest / most recent backup.
     */
    fun findLatestBackup(context: Context, treeUri: Uri): DiscoveredBackup? {
        val resolver = context.contentResolver
        val backups = mutableListOf<DiscoveredBackup>()

        try {
            val treeDocumentId = DocumentsContract.getTreeDocumentId(treeUri)
            val parentChildrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeDocumentId)

            resolver.query(
                parentChildrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED
                ),
                null,
                null,
                null
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val modCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED)

                while (cursor.moveToNext()) {
                    val docId = cursor.getString(idCol)
                    val name = cursor.getString(nameCol) ?: ""
                    val mime = cursor.getString(mimeCol) ?: ""
                    val lastMod = cursor.getLong(modCol)

                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        // Check if this is an UltraDeep backup folder
                        if (name.startsWith("UltraDeep", ignoreCase = true)) {
                            val subChildrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
                            resolver.query(
                                subChildrenUri,
                                arrayOf(
                                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                                    DocumentsContract.Document.COLUMN_LAST_MODIFIED
                                ),
                                null,
                                null,
                                null
                            )?.use { subCursor ->
                                val subIdCol = subCursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                                val subNameCol = subCursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                                val subModCol = subCursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED)

                                while (subCursor.moveToNext()) {
                                    val subDocId = subCursor.getString(subIdCol)
                                    val subName = subCursor.getString(subNameCol) ?: ""
                                    val subMod = subCursor.getLong(subModCol)

                                    if (subName.endsWith(".json", ignoreCase = true)) {
                                        val fileUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, subDocId)
                                        val ts = parseTimestampFromName(name)
                                            ?: parseTimestampFromName(subName)
                                            ?: subMod.takeIf { it > 0 }
                                            ?: lastMod
                                        backups.add(DiscoveredBackup(fileUri, subName, ts, name))
                                    }
                                }
                            }
                        }
                    } else if (name.endsWith(".json", ignoreCase = true) && name.startsWith("UltraDeep", ignoreCase = true)) {
                        // Legacy single root backup file
                        val fileUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                        val ts = parseTimestampFromName(name) ?: lastMod
                        backups.add(DiscoveredBackup(fileUri, name, ts, "Root"))
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return backups.maxByOrNull { it.timestamp }
    }

    private fun parseTimestampFromName(name: String): Long? {
        val formats = listOf(
            SimpleDateFormat("dd-MM-yy - HH.mm.ss", Locale.US),
            SimpleDateFormat("dd-MM-yy_HH-mm-ss", Locale.US),
            SimpleDateFormat("dd-MM-yyyy - HH.mm.ss", Locale.US),
            SimpleDateFormat("dd-MM-yyyy_HH-mm-ss", Locale.US),
            SimpleDateFormat("dd-MM-yy", Locale.US),
            SimpleDateFormat("dd-MM-yyyy", Locale.US)
        )
        val clean = name.removePrefix("UltraDeep - ").removePrefix("UltraDeep_").removeSuffix(".json").trim()
        for (format in formats) {
            try {
                format.isLenient = false
                val date = format.parse(clean)
                if (date != null) return date.time
            } catch (_: Exception) {}
        }
        val match = Regex("\\d{12,13}").find(name)
        if (match != null) {
            return match.value.toLongOrNull()
        }
        return null
    }

    fun readBackupFromUri(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        } catch (error: Exception) {
            error.printStackTrace()
            null
        }
    }

    suspend fun hasLocalUserData(context: Context): Boolean = withContext(Dispatchers.IO) {
        val db = UltraDeepApplication.instance.database
        if (db.lyricsDao().getAllLyrics().isNotEmpty() ||
            db.favoriteDao().getAllFavorites().isNotEmpty() ||
            db.playlistDao().getAllPlaylistsSync().isNotEmpty() ||
            db.playlistDao().getAllPlaylistSongsSync().isNotEmpty() ||
            ArtworkManager.getAllCustomArtworkFiles(context).isNotEmpty() ||
            ArtworkManager.hasCustomGlobalDefault(context)) return@withContext true

        context.getSharedPreferences("lyrics_timer_prefs", Context.MODE_PRIVATE)
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
