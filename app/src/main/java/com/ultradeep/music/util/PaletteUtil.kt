package com.ultradeep.music.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import androidx.palette.graphics.Palette
import coil.ImageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class AmbientGradientColors(
    val topColor: Int,
    val centerColor: Int,
    val bottomColor: Int = Color.parseColor("#08080C")
)

object PaletteUtil {

    private val DEFAULT_TOP = Color.parseColor("#3D1263")
    private val DEFAULT_CENTER = Color.parseColor("#140826")
    private val DEFAULT_BOTTOM = Color.parseColor("#08080C")

    suspend fun extractColorsFromUri(context: Context, uriString: String?): AmbientGradientColors = withContext(Dispatchers.IO) {
        if (uriString.isNullOrEmpty()) {
            return@withContext AmbientGradientColors(DEFAULT_TOP, DEFAULT_CENTER, DEFAULT_BOTTOM)
        }

        try {
            val loader = ImageLoader(context)
            val request = ImageRequest.Builder(context)
                .data(Uri.parse(uriString))
                .allowHardware(false) // Required for Palette software bitmap access
                .build()

            val result = loader.execute(request)
            if (result is SuccessResult) {
                val drawable = result.drawable
                if (drawable is BitmapDrawable) {
                    return@withContext extractColors(drawable.bitmap)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        AmbientGradientColors(DEFAULT_TOP, DEFAULT_CENTER, DEFAULT_BOTTOM)
    }

    fun extractColors(bitmap: Bitmap): AmbientGradientColors {
        return try {
            val palette = Palette.from(bitmap)
                .maximumColorCount(16)
                .generate()

            val dominant = palette.getDominantColor(DEFAULT_TOP)
            val vibrant = palette.getVibrantColor(dominant)
            val darkVibrant = palette.getDarkVibrantColor(palette.getDarkMutedColor(DEFAULT_CENTER))

            // Ensure rich contrast for AMOLED background
            val topColor = adjustAlpha(vibrant, 0.85f)
            val centerColor = adjustAlpha(darkVibrant, 0.95f)

            AmbientGradientColors(topColor, centerColor, DEFAULT_BOTTOM)
        } catch (e: Exception) {
            AmbientGradientColors(DEFAULT_TOP, DEFAULT_CENTER, DEFAULT_BOTTOM)
        }
    }

    private fun adjustAlpha(color: Int, factor: Float): Int {
        val alpha = (Color.alpha(color) * factor).toInt().coerceIn(0, 255)
        val red = Color.red(color)
        val green = Color.green(color)
        val blue = Color.blue(color)
        return Color.argb(alpha, red, green, blue)
    }
}
