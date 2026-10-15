package com.ultradeep.music.service

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.ultradeep.music.data.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

object MusicController {

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var mediaController: MediaController? = null
    private val scope = CoroutineScope(Dispatchers.Main)
    private var progressJob: Job? = null

    // Library & Queue State
    private val _allSongs = MutableStateFlow<List<Song>>(emptyList())
    val allSongs: StateFlow<List<Song>> = _allSongs.asStateFlow()

    private val _queue = MutableStateFlow<List<Song>>(emptyList())
    val queue: StateFlow<List<Song>> = _queue.asStateFlow()

    private val _currentIndex = MutableStateFlow(-1)
    val currentIndex: StateFlow<Int> = _currentIndex.asStateFlow()

    private val _currentSong = MutableStateFlow<Song?>(null)
    val currentSong: StateFlow<Song?> = _currentSong.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _currentPosition = MutableStateFlow(0L)
    val currentPosition: StateFlow<Long> = _currentPosition.asStateFlow()

    private val _duration = MutableStateFlow(0L)
    val duration: StateFlow<Long> = _duration.asStateFlow()

    private val _isShuffle = MutableStateFlow(false)
    val isShuffle: StateFlow<Boolean> = _isShuffle.asStateFlow()

    private val _repeatMode = MutableStateFlow(Player.REPEAT_MODE_OFF)
    val repeatMode: StateFlow<Int> = _repeatMode.asStateFlow()

    fun init(context: Context) {
        if (controllerFuture != null) return

        val sessionToken = SessionToken(
            context,
            ComponentName(context, PlaybackService::class.java)
        )

        controllerFuture = MediaController.Builder(context, sessionToken).buildAsync()
        controllerFuture?.addListener({
            try {
                mediaController = controllerFuture?.get()
                setupPlayerListener()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }, MoreExecutors.directExecutor())
    }

    private fun setupPlayerListener() {
        val player = mediaController ?: return

        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _isPlaying.value = isPlaying
                if (isPlaying) {
                    startProgressTracker()
                } else {
                    stopProgressTracker()
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_READY -> {
                        _duration.value = player.duration.coerceAtLeast(0L)
                        _currentPosition.value = player.currentPosition.coerceAtLeast(0L)
                    }
                    Player.STATE_ENDED -> {
                        skipNext()
                    }
                }
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int
            ) {
                _currentPosition.value = newPosition.positionMs.coerceAtLeast(0L)
            }
        })
    }

    private fun startProgressTracker() {
        stopProgressTracker()
        progressJob = scope.launch {
            while (isActive) {
                mediaController?.let { player ->
                    _currentPosition.value = player.currentPosition.coerceAtLeast(0L)
                    _duration.value = player.duration.coerceAtLeast(0L)
                }
                delay(100) // 100ms for ultra-smooth real-time lyric scrolling
            }
        }
    }

    private fun stopProgressTracker() {
        progressJob?.cancel()
        progressJob = null
    }

    fun updateLibrary(songs: List<Song>) {
        _allSongs.value = songs
        if (_queue.value.isEmpty()) {
            _queue.value = songs
        }
    }

    fun playSong(song: Song, newQueue: List<Song> = _queue.value) {
        val player = mediaController ?: return
        _queue.value = if (newQueue.isNotEmpty()) newQueue else listOf(song)
        val index = _queue.value.indexOfFirst { it.id == song.id }
        _currentIndex.value = if (index >= 0) index else 0
        _currentSong.value = song

        val mediaMetadata = MediaMetadata.Builder()
            .setTitle(song.title)
            .setArtist(song.artist)
            .setAlbumTitle(song.album)
            .setArtworkUri(song.albumArtUri?.let { Uri.parse(it) })
            .build()

        val mediaItem = MediaItem.Builder()
            .setMediaId(song.id.toString())
            .setUri(song.contentUri)
            .setMediaMetadata(mediaMetadata)
            .build()

        player.setMediaItem(mediaItem)
        player.prepare()
        player.play()
        _isPlaying.value = true
        _duration.value = song.duration
        _currentPosition.value = 0L
        startProgressTracker()
    }

    fun togglePlayPause() {
        val player = mediaController ?: return
        if (player.isPlaying) {
            player.pause()
            _isPlaying.value = false
        } else {
            if (player.playbackState == Player.STATE_IDLE && _currentSong.value != null) {
                playSong(_currentSong.value!!)
            } else {
                player.play()
                _isPlaying.value = true
            }
        }
    }

    fun seekTo(positionMs: Long) {
        val player = mediaController ?: return
        val safePosition = positionMs.coerceIn(0L, _duration.value.coerceAtLeast(0L))
        player.seekTo(safePosition)
        _currentPosition.value = safePosition
    }

    fun skipNext() {
        val currentQueue = _queue.value
        if (currentQueue.isEmpty()) return

        val nextIndex = if (_isShuffle.value) {
            currentQueue.indices.random()
        } else {
            val idx = _currentIndex.value + 1
            if (idx >= currentQueue.size) {
                if (_repeatMode.value == Player.REPEAT_MODE_ALL) 0 else return
            } else idx
        }

        val nextSong = currentQueue.getOrNull(nextIndex) ?: return
        playSong(nextSong, currentQueue)
    }

    fun skipPrevious() {
        val player = mediaController
        if (player != null && player.currentPosition > 3000L) {
            seekTo(0L)
            return
        }

        val currentQueue = _queue.value
        if (currentQueue.isEmpty()) return

        val prevIndex = (_currentIndex.value - 1).coerceAtLeast(0)
        val prevSong = currentQueue.getOrNull(prevIndex) ?: return
        playSong(prevSong, currentQueue)
    }

    fun toggleShuffle() {
        _isShuffle.value = !_isShuffle.value
    }

    fun toggleRepeat() {
        val player = mediaController ?: return
        val nextMode = when (_repeatMode.value) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
        player.repeatMode = nextMode
        _repeatMode.value = nextMode
    }
}
