package com.ultradeep.music.scanner

import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MediaStoreContentObserver(
    private val scope: CoroutineScope,
    private val onStorageChanged: () -> Unit
) : ContentObserver(Handler(Looper.getMainLooper())) {

    private var debounceJob: Job? = null

    override fun onChange(selfChange: Boolean, uri: Uri?) {
        super.onChange(selfChange, uri)
        // Debounce storage change notifications by 1000ms
        debounceJob?.cancel()
        debounceJob = scope.launch(Dispatchers.Main) {
            delay(1000)
            onStorageChanged()
        }
    }
}
