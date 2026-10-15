package com.ultradeep.music.util

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileOutputStream

object FileUtil {
    fun copyUriToFile(context: Context, uri: Uri, destinationFile: File): Boolean {
        return try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                FileOutputStream(destinationFile).use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
            destinationFile.exists() && destinationFile.length() > 0
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}
