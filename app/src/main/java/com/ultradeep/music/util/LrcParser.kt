package com.ultradeep.music.util

import com.ultradeep.music.data.model.LyricLine
import com.ultradeep.music.data.model.LrcResult
import java.util.regex.Pattern

object LrcParser {
    private val TIME_TAG_REGEX = Pattern.compile("\\[(\\d{1,2}):(\\d{2})(?:\\.(\\d{1,3}))?\\]")

    fun parse(content: String?, isCustom: Boolean = false): LrcResult {
        if (content.isNullOrBlank()) {
            return LrcResult(
                lines = emptyList(),
                isSynced = false,
                plainText = "",
                isCustom = isCustom
            )
        }

        val lines = mutableListOf<LyricLine>()
        var foundAnyTimestamp = false

        content.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty()) return@forEach

            // Check if this line is metadata like [ti:Title] or [ar:Artist]
            if (line.startsWith("[") && line.contains(":") && !line.substring(1, 2).all { it.isDigit() }) {
                return@forEach
            }

            val matcher = TIME_TAG_REGEX.matcher(line)
            val timestamps = mutableListOf<Long>()
            var lastMatchEnd = 0

            while (matcher.find()) {
                val minStr = matcher.group(1) ?: "0"
                val secStr = matcher.group(2) ?: "0"
                val msStr = matcher.group(3) ?: "0"

                val minutes = minStr.toLongOrNull() ?: 0L
                val seconds = secStr.toLongOrNull() ?: 0L
                val millis = when (msStr.length) {
                    1 -> (msStr.toLongOrNull() ?: 0L) * 100
                    2 -> (msStr.toLongOrNull() ?: 0L) * 10
                    3 -> msStr.toLongOrNull() ?: 0L
                    else -> 0L
                }

                val totalMs = (minutes * 60 * 1000) + (seconds * 1000) + millis
                timestamps.add(totalMs)
                lastMatchEnd = matcher.end()
            }

            if (timestamps.isNotEmpty()) {
                foundAnyTimestamp = true
                val text = line.substring(lastMatchEnd).trim()
                for (timestamp in timestamps) {
                    lines.add(LyricLine(timestamp, text))
                }
            }
        }

        return if (foundAnyTimestamp && lines.isNotEmpty()) {
            lines.sortBy { it.timestampMs }
            LrcResult(
                lines = lines,
                isSynced = true,
                plainText = lines.joinToString("\n") { it.text },
                isCustom = isCustom
            )
        } else {
            // Treat as plain unsynced lyrics: use -1L timestamp so it does not falsely auto-scroll
            val plainLines = content.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .map { text -> LyricLine(-1L, text) }
                .toList()

            LrcResult(
                lines = plainLines,
                isSynced = false,
                plainText = content,
                isCustom = isCustom
            )
        }
    }
}
