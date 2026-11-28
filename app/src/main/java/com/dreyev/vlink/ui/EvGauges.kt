package com.dreyev.vlink.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dreyev.vlink.obd.Reading
import com.dreyev.vlink.obd.Source
import com.dreyev.vlink.obd.UnitPrefs
import com.dreyev.vlink.obd.VehicleState
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Scale of the EV power gauge: regen down to −100 kW, traction up to +250 kW. */
const val POWER_MIN = -100.0
const val POWER_MAX = 250.0

/** Traction vs regen color for a power value. */
fun powerColor(kw: Double?): Color = when {
    kw == null -> C.textFaint
    kw < -0.5 -> C.green
    kw > 0.5 -> C.obd
    else -> C.textMuted
}

fun powerState(kw: Double?): String = when {
    kw == null -> "N/A"
    kw < -0.5 -> "REGEN"
    kw > 0.5 -> "TRACTION"
    else -> "COASTING"
}

/** EV speed gauge: same look as the other gauges, arc = speed. */
@Composable
fun EvSpeedGauge(s: VehicleState, u: UnitPrefs, size: Dp) {
    val h = hero(s, u)
    val kmh = s.speedKmh?.value ?: s.gps?.value?.speedKmh
    val src = s.speedKmh?.source ?: s.gps?.let { Source.PHONE }
    val tag = s.speedKmh?.let { if (it.source == Source.OBD) "0D" else it.tag } ?: "GPS"
    Gauge(
        value = kmh?.let { u.speed(it) }, size = size, label = "Speed", unit = u.speedUnit, source = src, sourceTag = tag,
        min = 0.0, max = if (u.speedUnit == "mph") 120.0 else 200.0, ticks = 6, display = h.speed,
        sub = s.gps?.value?.speedKmh?.let { "GPS " + u.fmtSpeed(it, 1) },
    )
}

/**
 * EV power gauge. Outer arc: battery power (cyan = traction, green = regen) growing from 0.
 * Two inner bands: rear motor (from the motor controller) and front motor (estimated), on the
 * same scale, so you can see which motor is working and how hard.
 */
@Composable
fun PowerGauge(s: VehicleState, size: Dp, now: Long = System.currentTimeMillis()) {
    val total = s.hvPowerKw?.value
    val rear = s.rearMotorKw?.takeIf { it.isFresh(now, 3_000) }
    val front = s.frontMotorKwEst(now)
    val color = powerColor(total)
    val h = size * 0.88f
    Column(Modifier.width(size).semantics { contentDescription = "Power ${total?.let { "%.0f kW".format(it) } ?: "not available"}, ${powerState(total)}" }, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(size, h)) {
            Canvas(Modifier.size(size, h)) {
                val k = this.size.width / 100f
                scale(k, k, pivot = Offset.Zero) { drawPowerArcs(total, rear?.value, front?.value) }
            }
            Column(Modifier.fillMaxWidth().padding(top = size * 0.33f), horizontalAlignment = Alignment.CenterHorizontally) {
                val main = (size.value * 0.19f).sp
                Text(
                    total?.let { String.format(Locale.US, "%.0f", it) } ?: "N/A",
                    color = if (total == null) C.textFaint else C.text, fontFamily = NumberFont, fontWeight = FontWeight.SemiBold,
                    fontSize = if (total == null) main * 0.7f else main, lineHeight = main, maxLines = 1,
                )
                Text("kW", color = C.textMuted, fontSize = maxOf(9f, size.value * 0.068f).sp, fontWeight = FontWeight.Medium)
                Text(powerState(total), color = color, fontSize = maxOf(9f, size.value * 0.055f).sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp, modifier = Modifier.padding(top = 2.dp))
            }
            Text("${POWER_MIN.toInt()}", Modifier.align(Alignment.BottomStart).padding(start = size * 0.17f), color = C.scaleText, fontSize = 9.sp)
            Text("+${POWER_MAX.toInt()}", Modifier.align(Alignment.BottomEnd).padding(end = size * 0.15f), color = C.scaleText, fontSize = 9.sp)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("POWER", color = C.textDim, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp, textAlign = TextAlign.Center)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MotorLegend("Rear", rear, size)
                MotorLegend("Front", front, size)
            }
            SourceBadge(s.hvPowerKw?.source ?: Source.MFR, s.hvPowerKw?.tag ?: "7E4")
        }
    }
}

@Composable
private fun MotorLegend(name: String, r: Reading<Double>?, size: Dp) {
    val v = r?.value
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(name, color = C.textMuted, fontSize = maxOf(9f, size.value * 0.055f).sp)
        Text(
            v?.let { String.format(Locale.US, "%.0f kW", it) } ?: "N/A",
            color = powerColor(v), fontSize = maxOf(9f, size.value * 0.058f).sp, fontWeight = FontWeight.Bold, maxLines = 1,
        )
        if (r?.source == Source.EST) Text("est", color = C.est, fontSize = 8.sp)
    }
}

private fun frac(kw: Double) = ((kw - POWER_MIN) / (POWER_MAX - POWER_MIN)).coerceIn(0.0, 1.0).toFloat()

private fun DrawScope.band(a0: Float, sw: Float, r: Float, w: Float, kw: Double?) {
    // Track
    drawArc(C.track, a0 - 90f, sw, false, Offset(50 - r, 50 - r), Size(2 * r, 2 * r), style = Stroke(w, cap = StrokeCap.Round))
    if (kw == null) return
    val f0 = frac(0.0); val f = frac(kw)
    if (abs(f - f0) < 0.004f) return
    drawArc(powerColor(kw), a0 - 90f + sw * minOf(f0, f), sw * abs(f - f0), false, Offset(50 - r, 50 - r), Size(2 * r, 2 * r), style = Stroke(w, cap = StrokeCap.Butt))
}

private fun DrawScope.drawPowerArcs(total: Double?, rear: Double?, front: Double?) {
    val a0 = -135f; val sw = 270f
    band(a0, sw, 41f, 7f, total)          // battery (total)
    band(a0, sw, 32.5f, 4f, rear)         // rear motor
    band(a0, sw, 26.5f, 4f, front)        // front motor
    // "R" and "F" just before the start of the inner bands, so each band is named.
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 5.6f; isFakeBoldText = true; textAlign = android.graphics.Paint.Align.CENTER
    }
    for ((r, label) in listOf(32.5f to "R", 26.5f to "F")) {
        val a = Math.toRadians((a0 - 10f).toDouble())
        paint.color = android.graphics.Color.argb(255, (C.textMuted.red * 255).toInt(), (C.textMuted.green * 255).toInt(), (C.textMuted.blue * 255).toInt())
        drawContext.canvas.nativeCanvas.drawText(label, (50 + r * sin(a)).toFloat(), (50 - r * cos(a)).toFloat() + 1.6f, paint)
    }
    // Ticks outside the main arc every 50 kW; the zero tick is longer.
    var kw = POWER_MIN
    while (kw <= POWER_MAX + 0.1) {
        val a = Math.toRadians((a0 + sw * frac(kw)).toDouble())
        val zero = abs(kw) < 0.1
        val r1 = 45.5f; val r2 = if (zero) 50f else 48.5f
        drawLine(
            if (zero) C.text else C.tick,
            Offset((50 + r1 * sin(a)).toFloat(), (50 - r1 * cos(a)).toFloat()),
            Offset((50 + r2 * sin(a)).toFloat(), (50 - r2 * cos(a)).toFloat()),
            if (zero) 1.8f else 1.2f, StrokeCap.Round,
        )
        kw += 50.0
    }
    // Needle on the battery arc, outside the digits.
    if (total != null) {
        val a = Math.toRadians((a0 + sw * frac(total)).toDouble())
        drawLine(C.needle, Offset((50 + 37f * sin(a)).toFloat(), (50 - 37f * cos(a)).toFloat()), Offset((50 + 45f * sin(a)).toFloat(), (50 - 45f * cos(a)).toFloat()), 2.2f, StrokeCap.Round)
    }
}
