package com.ultradeep.music

import android.app.Application
import android.provider.MediaStore
import com.ultradeep.music.data.db.AppDatabase
import com.ultradeep.music.data.repository.LyricsRepository
import com.ultradeep.music.scanner.AudioScanner
import com.ultradeep.music.scanner.MediaStoreContentObserver
import com.ultradeep.music.service.MusicController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class UltraDeepApplication : Application() {

    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    val database by lazy { AppDatabase.getInstance(this) }
    val lyricsRepository by lazy { LyricsRepository(database.lyricsDao()) }

    private var contentObserver: MediaStoreContentObserver? = null

    override fun onCreate() {
        super.onCreate()
        instance = this

        // Apply saved theme
        val prefs = getSharedPreferences("ultradeep_prefs", android.content.Context.MODE_PRIVATE)
        val themeMode = prefs.getInt("theme_mode", androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES)
        androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(themeMode)

        // Initialize MusicController
        MusicController.init(this)

        // Setup MediaStore ContentObserver for auto-syncing newly added songs
        setupMediaStoreObserver()
    }

    private fun setupMediaStoreObserver() {
        try {
            contentObserver = MediaStoreContentObserver(applicationScope) {
                // Trigger auto-sync across the app
                applicationScope.launch {
                    val songs = AudioScanner.scanAudioFiles(this@UltraDeepApplication)
                    MusicController.updateLibrary(songs)
                }
            }
            contentResolver.registerContentObserver(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                true,
                contentObserver!!
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onTerminate() {
        super.onTerminate()
        contentObserver?.let {
            try {
                contentResolver.unregisterContentObserver(it)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    companion object {
        lateinit var instance: UltraDeepApplication
            private set
    }
}
