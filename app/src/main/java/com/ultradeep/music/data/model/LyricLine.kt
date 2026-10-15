package com.ultradeep.music.data.model

data class LyricLine(
    val timestampMs: Long,
    val text: String
)

data class LrcResult(
    val lines: List<LyricLine>,
    val isSynced: Boolean,
    val plainText: String,
    val isCustom: Boolean = false,
    val isInstrumental: Boolean = false
)
