package com.dreyev.vlink.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import com.dreyev.vlink.obd.Source

/** One set of dashboard colors. The dark set is the approved mockup. */
class Palette(
    val bg: Color,
    val nav: Color,
    val card: Color,
    val cardInner: Color,
    val border: Color,
    val track: Color,
    val grid: Color,
    val tick: Color,
    val minorTick: Color,
    val wheel: Color,
    val text: Color,
    val textDim: Color,
    val textMuted: Color,
    val textFaint: Color,
    val needle: Color,
    val obd: Color,
    val mfr: Color,
    val phone: Color,
    val est: Color,
    val red: Color,
    val redSoft: Color,
    val redBg: Color,
    val green: Color,
    val greenText: Color,
    val greenBg: Color,
    val logoChip: Color,
    val selected: Color,
    val lowAmber: Color,
    val scaleText: Color,
    val hub: Color,
    val road: Color,
    val carBody: Color,
    val tireIdle: Color,
    val onAmber: Color,
)

val DarkPalette = Palette(
    bg = Color(0xFF0B0E12),
    nav = Color(0xFF0E1217),
    card = Color(0xFF141A21),
    cardInner = Color(0xFF10151B),
    border = Color(0xFF222B35),
    track = Color(0xFF232C37),
    grid = Color(0xFF2A3440),
    tick = Color(0xFF5B6876),
    minorTick = Color(0xFF39434F),
    wheel = Color(0xFF3A4552),
    text = Color(0xFFE8EDF2),
    textDim = Color(0xFFB4BEC8),
    textMuted = Color(0xFF9AA6B2),
    textFaint = Color(0xFF8C99A6),
    needle = Color(0xFFF2F5F8),
    obd = Color(0xFF4CC9F0),
    mfr = Color(0xFFF5A524),
    phone = Color(0xFFB7A6FF),
    est = Color(0xFFC9D1D9),
    red = Color(0xFFFF6B4A),
    redSoft = Color(0xFFFF8A6E),
    redBg = Color(0xFF3A1A14),
    green = Color(0xFF3DDC97),
    greenText = Color(0xFF5BE3A8),
    greenBg = Color(0xFF123A2B),
    logoChip = Color(0xFFEEF3F7),
    selected = Color(0xFF1E3A47),
    lowAmber = Color(0xFFF5B94A),
    scaleText = Color(0xFF7D8A97),
    hub = Color(0xFF1C242D),
    road = Color(0xFF1E2731),
    carBody = Color(0xFF1F2A35),
    tireIdle = Color(0xFF4A5663),
    onAmber = Color(0xFF1A1206),
)

/** Light set: same hues, darker accents so text and needles keep their contrast on white. */
val LightPalette = Palette(
    bg = Color(0xFFF2F4F7),
    nav = Color(0xFFFFFFFF),
    card = Color(0xFFFFFFFF),
    cardInner = Color(0xFFF1F4F7),
    border = Color(0xFFD8DEE5),
    track = Color(0xFFE1E6EC),
    grid = Color(0xFFD0D7DF),
    tick = Color(0xFF7F8B98),
    minorTick = Color(0xFFC2CAD3),
    wheel = Color(0xFF9AA6B2),
    text = Color(0xFF10161D),
    textDim = Color(0xFF36414D),
    textMuted = Color(0xFF505B67),
    textFaint = Color(0xFF65707C),
    needle = Color(0xFF10161D),
    obd = Color(0xFF0A84AD),
    mfr = Color(0xFFB86E00),
    phone = Color(0xFF6A50D8),
    est = Color(0xFF56616D),
    red = Color(0xFFD63E22),
    redSoft = Color(0xFFC9492F),
    redBg = Color(0xFFFCE4DE),
    green = Color(0xFF14965F),
    greenText = Color(0xFF0B7E4E),
    greenBg = Color(0xFFDAF3E7),
    logoChip = Color(0xFFEEF3F7),
    selected = Color(0xFFD3ECF6),
    lowAmber = Color(0xFFB97C0A),
    scaleText = Color(0xFF65707C),
    hub = Color(0xFFDCE2E8),
    road = Color(0xFFE2E7ED),
    carBody = Color(0xFFD3DBE3),
    tireIdle = Color(0xFF8A96A3),
    onAmber = Color(0xFF1A1206),
)

/** Theme choice in Settings. SYSTEM follows the phone's dark/light setting. */
enum class ThemeMode { SYSTEM, DARK, LIGHT }

/**
 * Current dashboard colors. Backed by Compose state, so switching the palette redraws
 * every screen that reads C.* without passing colors around.
 */
object C {
    var palette: Palette by mutableStateOf(DarkPalette)
    val isDark get() = palette === DarkPalette
    val bg get() = palette.bg
    val nav get() = palette.nav
    val card get() = palette.card
    val cardInner get() = palette.cardInner
    val border get() = palette.border
    val track get() = palette.track
    val grid get() = palette.grid
    val tick get() = palette.tick
    val minorTick get() = palette.minorTick
    val wheel get() = palette.wheel
    val text get() = palette.text
    val textDim get() = palette.textDim
    val textMuted get() = palette.textMuted
    val textFaint get() = palette.textFaint
    val needle get() = palette.needle
    val obd get() = palette.obd
    val mfr get() = palette.mfr
    val phone get() = palette.phone
    val est get() = palette.est
    val red get() = palette.red
    val redSoft get() = palette.redSoft
    val redBg get() = palette.redBg
    val green get() = palette.green
    val greenText get() = palette.greenText
    val greenBg get() = palette.greenBg
    val logoChip get() = palette.logoChip
    val selected get() = palette.selected
    val lowAmber get() = palette.lowAmber
    val scaleText get() = palette.scaleText
    val hub get() = palette.hub
    val road get() = palette.road
    val carBody get() = palette.carBody
    val tireIdle get() = palette.tireIdle
    val onAmber get() = palette.onAmber

    fun source(s: Source) = when (s) {
        Source.OBD -> obd; Source.MFR -> mfr; Source.PHONE -> phone; Source.EST -> est
    }
}

/** Number font: the mockup uses Space Grotesk; system sans keeps the APK self-contained. */
val NumberFont: FontFamily = FontFamily.SansSerif
val MonoFont: FontFamily = FontFamily.Monospace

@Composable
fun VLinkTheme(mode: ThemeMode = ThemeMode.DARK, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
    }
    C.palette = if (dark) DarkPalette else LightPalette
    val scheme = if (dark) darkColorScheme(
        background = C.bg, surface = C.card, primary = C.obd, onPrimary = C.bg,
        onBackground = C.text, onSurface = C.text, secondary = C.mfr, error = C.red,
    ) else lightColorScheme(
        background = C.bg, surface = C.card, primary = C.obd, onPrimary = C.card,
        onBackground = C.text, onSurface = C.text, secondary = C.mfr, error = C.red,
    )
    MaterialTheme(colorScheme = scheme, content = content)
}
