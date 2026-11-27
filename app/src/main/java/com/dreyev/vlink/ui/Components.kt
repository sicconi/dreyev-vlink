package com.dreyev.vlink.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dreyev.vlink.R
import com.dreyev.vlink.obd.Reading
import com.dreyev.vlink.obd.Source
import kotlin.math.cos
import kotlin.math.sin

// ---------- small building blocks ----------

@Composable
fun SourceBadge(source: Source?, tag: String = "", modifier: Modifier = Modifier, small: Boolean = false) {
    val s = source ?: return Badge("N/A", C.textFaint, false, modifier)
    val label = if (tag.isBlank()) s.name else "${s.name} $tag"
    Badge(label, C.source(s), s == Source.EST, modifier, small)
}

@Composable
fun SourceBadge(r: Reading<*>?, fallback: Source, fallbackTag: String = "", modifier: Modifier = Modifier) {
    if (r == null) Badge("$fallback · N/A", C.textFaint, fallback == Source.EST, modifier)
    else SourceBadge(r.source, r.tag, modifier)
}

@Composable
fun Badge(text: String, color: Color, dashed: Boolean, modifier: Modifier = Modifier, small: Boolean = false) {
    Box(
        modifier
            .then(
                if (dashed) Modifier.dashedBorder(color) else Modifier.border(1.dp, color, RoundedCornerShape(4.dp))
            )
            .padding(horizontal = 5.dp, vertical = 1.dp),
    ) {
        Text(text, color = color, fontSize = if (small) 8.sp else 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.4.sp, maxLines = 1)
    }
}

private fun Modifier.dashedBorder(color: Color) = this.drawBehind {
    drawRoundRect(
        color, cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()),
        style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 3f))),
    )
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), modifier = modifier, color = C.textDim, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp, maxLines = 1)
}

@Composable
fun DashCard(
    modifier: Modifier = Modifier,
    borderColor: Color = C.border,
    background: Color = C.card,
    radius: Dp = 18.dp,
    padding: Dp = 12.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .clip(RoundedCornerShape(radius))
            .background(background)
            .border(1.dp, borderColor, RoundedCornerShape(radius))
            .padding(horizontal = padding, vertical = padding * 0.85f),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        content = content,
    )
}

@Composable
fun CardHeader(title: String, badge: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        SectionLabel(title, Modifier.weight(1f, fill = false))
        Spacer(Modifier.width(6.dp))
        badge()
    }
}

@Composable
fun Pill(text: String, fg: Color, bg: Color, modifier: Modifier = Modifier, fontSize: TextUnit = 11.sp) {
    Box(modifier.clip(RoundedCornerShape(999.dp)).background(bg).padding(horizontal = 10.dp, vertical = 6.dp)) {
        Text(text, color = fg, fontSize = fontSize, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp, maxLines = 1)
    }
}

@Composable
fun LogoChip(height: Dp = 20.dp) {
    Box(Modifier.clip(RoundedCornerShape(8.dp)).background(C.logoChip).padding(horizontal = 7.dp, vertical = 4.dp)) {
        Image(painterResource(R.drawable.dreyev_logo), contentDescription = "dreyev", modifier = Modifier.height(height))
    }
}

@Composable
fun StatusDot(color: Color) {
    Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(color))
}

// ---------- analog gauge (port of the mockup's Gauge component) ----------

/**
 * 270° analog gauge. [value] maps [min]..[max] onto the arc; [display] is the big number
 * in the middle (defaults to the value). Matches the mockup geometry (viewBox 100×88).
 */
@Composable
fun Gauge(
    value: Double?,
    size: Dp,
    label: String,
    unit: String,
    source: Source?,
    sourceTag: String = "",
    min: Double = 0.0,
    max: Double = 100.0,
    ticks: Int = 10,
    redFrom: Double? = null,
    color: Color = C.obd,
    display: String? = null,
    sub: String? = null,
    decimals: Int = 0,
    /** Value the colored arc grows from (e.g. 0 kW on a power gauge with a regen range). */
    origin: Double = min,
    modifier: Modifier = Modifier,
) {
    val f0 = ((origin - min) / (max - min)).coerceIn(0.0, 1.0).toFloat()
    val f = if (value == null) 0f else ((value - min) / (max - min)).coerceIn(0.0, 1.0).toFloat()
    val shown = display ?: value?.let { com.dreyev.vlink.obd.UnitPrefs.fmt(it, decimals) } ?: "N/A"
    val h = size * 0.88f
    Column(modifier.width(size).semantics { contentDescription = "$label $shown $unit" }, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(size, h)) {
            Canvas(Modifier.size(size, h)) {
                val k = this.size.width / 100f
                scale(k, k, pivot = Offset.Zero) { drawGaugeArc(f, ticks, redFrom?.let { ((it - min) / (max - min)).toFloat() }, color, value != null, f0) }
            }
            Column(
                Modifier.fillMaxWidth().padding(top = size * 0.36f),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val main = (size.value * (if (sub != null) 0.2f else 0.19f)).sp
                Text(shown, color = if (value == null) C.textFaint else C.text, fontFamily = NumberFont, fontWeight = FontWeight.SemiBold, fontSize = if (value == null) main * 0.7f else main, lineHeight = main, maxLines = 1)
                Text(unit, color = C.textMuted, fontSize = maxOf(9f, size.value * 0.068f).sp, fontWeight = FontWeight.Medium, maxLines = 1)
                if (sub != null) Text(sub, color = C.textMuted, fontSize = maxOf(9f, size.value * 0.05f).sp, maxLines = 1, modifier = Modifier.padding(top = 4.dp))
            }
            Text(fmtScale(min), Modifier.align(Alignment.BottomStart).padding(start = size * 0.2f), color = Color(0xFF7D8A97), fontSize = 9.sp)
            Text(fmtScale(max), Modifier.align(Alignment.BottomEnd).padding(end = size * 0.2f), color = Color(0xFF7D8A97), fontSize = 9.sp)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(label.uppercase(), color = C.textDim, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp, textAlign = TextAlign.Center, maxLines = 1)
            SourceBadge(source, sourceTag)
        }
    }
}

private fun fmtScale(x: Double): String =
    if (kotlin.math.abs(x) >= 1000) String.format(java.util.Locale.US, "%,d", Math.round(x)) else
        (Math.round(x * 10) / 10.0).let { if (it == Math.floor(it)) it.toLong().toString() else it.toString() }

private fun pt(aDeg: Float, r: Float): Offset {
    val t = Math.toRadians(aDeg.toDouble())
    return Offset((50 + r * sin(t)).toFloat(), (50 - r * cos(t)).toFloat())
}

private fun DrawScope.arc(a0: Float, sweep: Float, r: Float, color: Color, w: Float, cap: StrokeCap = StrokeCap.Round) {
    // Compose angles start at 3 o'clock; mockup angles start at 12 o'clock.
    drawArc(color, startAngle = a0 - 90f, sweepAngle = sweep, useCenter = false,
        topLeft = Offset(50 - r, 50 - r), size = Size(2 * r, 2 * r), style = Stroke(w, cap = cap))
}

private fun DrawScope.drawGaugeArc(f: Float, n: Int, redFrom: Float?, color: Color, live: Boolean, f0: Float = 0f) {
    val a0 = -135f; val sw = 270f
    arc(a0, sw, 40f, C.track, 7f)
    redFrom?.let { arc(a0 + sw * it, sw * (1 - it), 46f, C.red.copy(alpha = 0.9f), 2.5f, StrokeCap.Butt) }
    if (live && kotlin.math.abs(f - f0) > 0.003f) arc(a0 + sw * minOf(f0, f), sw * kotlin.math.abs(f - f0), 40f, color, 7f)
    if (f0 > 0f) drawLine(C.tick, pt(a0 + sw * f0, 44f), pt(a0 + sw * f0, 36f), 1.5f)
    for (i in 0..n) {
        val a = a0 + sw * i / n
        drawLine(C.tick, pt(a, 33f), pt(a, 27.5f), 1.2f, StrokeCap.Round)
        if (i < n) { val b = a0 + sw * (i + 0.5f) / n; drawLine(C.minorTick, pt(b, 33f), pt(b, 30f), 0.7f) }
    }
    // Needle drawn as an outer pointer only, so it never covers the digital readout.
    if (live) {
        rotate(a0 + sw * f, pivot = Offset(50f, 50f)) {
            drawLine(C.needle, Offset(50f, 26f), Offset(50f, 14f), 2.2f, StrokeCap.Round)
        }
    }
}

// ---------- icons and small visuals ----------

@Composable
fun TurnArrow(left: Boolean, on: Boolean?, modifier: Modifier = Modifier) {
    val desc = (if (left) "Left" else "Right") + " turn signal " + when (on) { true -> "on"; false -> "off"; null -> "not available" }
    Canvas(modifier.size(34.dp, 28.dp).semantics { contentDescription = desc }) {
        val k = size.width / 34f
        scale(k, k, pivot = Offset.Zero) {
            val p = Path().apply {
                if (left) { moveTo(2f, 14f); lineTo(15f, 2f); lineTo(15f, 9f); lineTo(32f, 9f); lineTo(32f, 19f); lineTo(15f, 19f); lineTo(15f, 26f) }
                else { moveTo(32f, 14f); lineTo(19f, 2f); lineTo(19f, 9f); lineTo(2f, 9f); lineTo(2f, 19f); lineTo(19f, 19f); lineTo(19f, 26f) }
                close()
            }
            if (on == true) drawPath(p, C.green)
            drawPath(p, if (on == true) C.green else C.wheel, style = Stroke(1.5f, join = StrokeJoin.Round))
        }
    }
}

@Composable
fun SeatbeltIcon(buckled: Boolean?, passenger: Boolean, size: Dp = 26.dp) {
    val color = when (buckled) { true -> C.greenText; false -> C.red; null -> C.textFaint }
    Canvas(Modifier.size(size)) {
        val k = this.size.width / 24f
        scale(k, k, pivot = Offset.Zero) {
            val st = Stroke(1.8f, cap = StrokeCap.Round)
            drawCircle(color, 2.5f, Offset(12f, 4.5f), style = st)
            val body = Path().apply { moveTo(7f, 21f); lineTo(7f, 12f); quadraticTo(7f, 8.5f, 12f, 8.5f); quadraticTo(17f, 8.5f, 17f, 12f); lineTo(17f, 21f) }
            drawPath(body, color, style = st)
            if (passenger) drawLine(color, Offset(16f, 9.5f), Offset(7.5f, 19f), 1.8f, StrokeCap.Round)
            else drawLine(color, Offset(8f, 9.5f), Offset(16.5f, 19f), 1.8f, StrokeCap.Round)
        }
    }
}

@Composable
fun BeltStatus(buckled: Boolean?, passenger: Boolean, source: Reading<*>?) {
    val color = when (buckled) { true -> C.greenText; false -> C.red; null -> C.textFaint }
    val txt = when (buckled) { true -> "Buckled"; false -> "UNBUCKLED"; null -> "N/A" }
    Column(Modifier.width(56.dp).semantics { contentDescription = (if (passenger) "Passenger" else "Driver") + " seatbelt $txt" }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        SeatbeltIcon(buckled, passenger)
        Text(txt, color = color, fontSize = 9.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        Text(source?.source?.name ?: "MFR", color = if (source == null) C.textFaint else C.source(source.source), fontSize = 9.sp, fontWeight = FontWeight.Bold)
    }
}

/** Friction circle: lateral (x) vs longitudinal (y) g, ring = 1 g, dashed = 0.5 g. */
@Composable
fun FrictionCircle(latG: Double?, longG: Double?, size: Dp = 120.dp) {
    Canvas(Modifier.size(size).semantics { contentDescription = "Friction circle" }) {
        val k = this.size.width / 100f
        scale(k, k, pivot = Offset.Zero) {
            drawCircle(C.cardInner, 44f, Offset(50f, 50f))
            drawCircle(C.grid, 44f, Offset(50f, 50f), style = Stroke(1f))
            drawCircle(C.grid, 22f, Offset(50f, 50f), style = Stroke(1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(2f, 2f))))
            drawLine(C.grid, Offset(6f, 50f), Offset(94f, 50f), 0.8f)
            drawLine(C.grid, Offset(50f, 6f), Offset(50f, 94f), 0.8f)
            if (latG != null && longG != null) {
                val x = 50f + (latG.coerceIn(-1.0, 1.0) * 44).toFloat()
                val y = 50f - (longG.coerceIn(-1.0, 1.0) * 44).toFloat()
                drawLine(C.phone.copy(alpha = 0.6f), Offset(50f, 50f), Offset(x, y), 1.5f)
                drawCircle(C.phone, 5f, Offset(x, y))
            }
        }
    }
}

@Composable
fun SteeringWheel(deg: Double?, size: Dp = 120.dp) {
    Canvas(Modifier.size(size).rotate((deg ?: 0.0).toFloat()).semantics { contentDescription = "Steering wheel angle" }) {
        val k = this.size.width / 100f
        scale(k, k, pivot = Offset.Zero) {
            val c = if (deg == null) C.track else C.wheel
            drawCircle(c, 38f, Offset(50f, 50f), style = Stroke(8f))
            drawLine(c, Offset(14f, 54f), Offset(40f, 54f), 7f, StrokeCap.Round)
            drawLine(c, Offset(60f, 54f), Offset(86f, 54f), 7f, StrokeCap.Round)
            drawLine(c, Offset(50f, 62f), Offset(50f, 86f), 7f, StrokeCap.Round)
            drawCircle(Color(0xFF1C242D), 11f, Offset(50f, 54f))
            drawCircle(c, 11f, Offset(50f, 54f), style = Stroke(3f))
            if (deg != null) drawLine(C.mfr, Offset(50f, 8f), Offset(50f, 20f), 5f, StrokeCap.Round)
        }
    }
}

/** Centered bar for ±1 g. */
@Composable
fun AxisBar(value: Double?, modifier: Modifier = Modifier, color: Color = C.phone) {
    Canvas(modifier.height(10.dp)) {
        val r = androidx.compose.ui.geometry.CornerRadius(5.dp.toPx())
        drawRoundRect(C.track, cornerRadius = r)
        if (value != null) {
            val c = value.coerceIn(-1.0, 1.0).toFloat()
            val mid = size.width / 2
            val w = kotlin.math.abs(c) * mid
            drawRoundRect(color, topLeft = Offset(if (c >= 0) mid else mid - w, 0f), size = Size(w, size.height), cornerRadius = r)
        }
        drawLine(C.tick, Offset(size.width / 2, -3.dp.toPx()), Offset(size.width / 2, size.height + 3.dp.toPx()), 1.dp.toPx())
    }
}

/** Horizontal fill bar 0..1. */
@Composable
fun FillBar(fraction: Double?, color: Color, modifier: Modifier = Modifier, height: Dp = 8.dp) {
    Canvas(modifier.height(height)) {
        val r = androidx.compose.ui.geometry.CornerRadius(size.height / 2)
        drawRoundRect(C.track, cornerRadius = r)
        if (fraction != null) drawRoundRect(color, size = Size(size.width * fraction.coerceIn(0.0, 1.0).toFloat(), size.height), cornerRadius = r)
    }
}

/** Vertical pedal bar (landscape Drive). */
@Composable
fun VerticalBar(fraction: Double?, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val r = androidx.compose.ui.geometry.CornerRadius(10.dp.toPx())
        drawRoundRect(C.track, cornerRadius = r)
        if (fraction != null) {
            val h = size.height * fraction.coerceIn(0.0, 1.0).toFloat()
            drawRoundRect(color, topLeft = Offset(0f, size.height - h), size = Size(size.width, h), cornerRadius = r)
        }
    }
}

@Composable
fun Compass(headingDeg: Double?, size: Dp = 60.dp) {
    Canvas(Modifier.size(size).semantics { contentDescription = "Heading compass" }) {
        val k = this.size.width / 60f
        scale(k, k, pivot = Offset.Zero) {
            drawCircle(C.cardInner, 27f, Offset(30f, 30f))
            drawCircle(C.grid, 27f, Offset(30f, 30f), style = Stroke(1.5f))
            drawLine(C.tick, Offset(30f, 5f), Offset(30f, 10f), 1.5f)
            drawLine(C.tick, Offset(55f, 30f), Offset(50f, 30f), 1.5f)
            drawLine(C.tick, Offset(30f, 55f), Offset(30f, 50f), 1.5f)
            drawLine(C.tick, Offset(5f, 30f), Offset(10f, 30f), 1.5f)
            if (headingDeg != null) rotate(headingDeg.toFloat(), pivot = Offset(30f, 30f)) {
                drawPath(Path().apply { moveTo(30f, 12f); lineTo(35f, 32f); lineTo(30f, 29f); lineTo(25f, 32f); close() }, C.phone)
            }
        }
    }
}

/** Breadcrumb trail of recent GPS fixes (no map tiles, so no API key or data plan needed). */
@Composable
fun TrailView(points: List<Pair<Double, Double>>, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        // background "streets" grid for context
        val step = 40.dp.toPx()
        var x = step / 2
        while (x < size.width) { drawLine(Color(0xFF1E2731), Offset(x, 0f), Offset(x + 12f, size.height), 6.dp.toPx()); x += step * 2 }
        drawLine(Color(0xFF1E2731), Offset(0f, size.height * 0.33f), Offset(size.width, size.height * 0.4f), 6.dp.toPx())
        drawLine(Color(0xFF1E2731), Offset(0f, size.height * 0.75f), Offset(size.width, size.height * 0.7f), 6.dp.toPx())
        if (points.isEmpty()) return@Canvas
        val last = points.last()
        // ~ 400 m across the box
        val mPerDegLat = 111_320.0
        val mPerDegLon = 111_320.0 * cos(Math.toRadians(last.first))
        val scale = size.width / 400.0
        fun p(lat: Double, lon: Double) = Offset(
            (size.width / 2 + (lon - last.second) * mPerDegLon * scale).toFloat(),
            (size.height / 2 - (lat - last.first) * mPerDegLat * scale).toFloat(),
        )
        val path = Path()
        points.forEachIndexed { i, (la, lo) -> val o = p(la, lo); if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y) }
        drawPath(path, C.obd.copy(alpha = 0.7f), style = Stroke(2.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 9f))))
        val c = Offset(size.width / 2, size.height / 2)
        drawCircle(C.obd.copy(alpha = 0.2f), 11.dp.toPx(), c)
        drawCircle(C.obd, 5.dp.toPx(), c)
    }
}

/** "updated 12 s ago" label so stale TPMS (or any slow) values are obvious. */
fun ageText(r: Reading<*>?, now: Long): String? {
    r ?: return null
    val s = (now - r.timeMs) / 1000
    return when {
        s < 3 -> "live"
        s < 60 -> "${s}s ago"
        s < 3600 -> "${s / 60} min ago"
        else -> "${s / 3600} h ago"
    }
}
