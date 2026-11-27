package com.dreyev.vlink.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import com.dreyev.vlink.obd.Source

/** Colors from the approved mockup. */
object C {
    val bg = Color(0xFF0B0E12)
    val nav = Color(0xFF0E1217)
    val card = Color(0xFF141A21)
    val cardInner = Color(0xFF10151B)
    val border = Color(0xFF222B35)
    val track = Color(0xFF232C37)
    val grid = Color(0xFF2A3440)
    val tick = Color(0xFF5B6876)
    val minorTick = Color(0xFF39434F)
    val wheel = Color(0xFF3A4552)
    val text = Color(0xFFE8EDF2)
    val textDim = Color(0xFFB4BEC8)
    val textMuted = Color(0xFF9AA6B2)
    val textFaint = Color(0xFF8C99A6)
    val needle = Color(0xFFF2F5F8)
    val obd = Color(0xFF4CC9F0)
    val mfr = Color(0xFFF5A524)
    val phone = Color(0xFFB7A6FF)
    val est = Color(0xFFC9D1D9)
    val red = Color(0xFFFF6B4A)
    val redSoft = Color(0xFFFF8A6E)
    val redBg = Color(0xFF3A1A14)
    val green = Color(0xFF3DDC97)
    val greenText = Color(0xFF5BE3A8)
    val greenBg = Color(0xFF123A2B)
    val logoChip = Color(0xFFEEF3F7)
    val selected = Color(0xFF1E3A47)
    val lowAmber = Color(0xFFF5B94A)

    fun source(s: Source) = when (s) {
        Source.OBD -> obd; Source.MFR -> mfr; Source.PHONE -> phone; Source.EST -> est
    }
}

/** Number font: the mockup uses Space Grotesk; system sans keeps the APK self-contained. */
val NumberFont: FontFamily = FontFamily.SansSerif
val MonoFont: FontFamily = FontFamily.Monospace

@Composable
fun VLinkTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = C.bg, surface = C.card, primary = C.obd, onPrimary = C.bg,
            onBackground = C.text, onSurface = C.text, secondary = C.mfr, error = C.red,
        ),
        content = content,
    )
}
