package com.example.youtubetomp3.util

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.toArgb

object AppearanceBridge {
    @Volatile
    var primaryArgb: Int = 0xFF1D4ED8.toInt()

    @Volatile
    var secondaryArgb: Int = 0xFF7C3AED.toInt()

    @Volatile
    var surfaceArgb: Int = 0xFFFFFFFF.toInt()

    @Volatile
    var onSurfaceArgb: Int = 0xFF0F172A.toInt()

    fun updateFromScheme(scheme: ColorScheme) {
        primaryArgb = scheme.primary.toArgb()
        secondaryArgb = scheme.secondary.toArgb()
        surfaceArgb = scheme.surface.toArgb()
        onSurfaceArgb = scheme.onSurface.toArgb()
    }
}
