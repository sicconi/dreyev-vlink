package com.dreyev.vlink.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dreyev.vlink.obd.Corner
import com.dreyev.vlink.obd.Reading
import com.dreyev.vlink.obd.Source
import com.dreyev.vlink.obd.TireState
import com.dreyev.vlink.obd.UnitPrefs
import com.dreyev.vlink.obd.VehicleState

/** Low-pressure warning threshold: 220 kPa ≈ 32 psi. */
const val LOW_TIRE_KPA = 220.0

@Composable
fun TiresScreen(s: VehicleState, u: UnitPrefs, now: Long) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                TireCard(Corner.FL, s.tires[Corner.FL], u, now)
                TireCard(Corner.RL, s.tires[Corner.RL], u, now)
            }
            CarTopView(s, Modifier.width(56.dp), 300.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                TireCard(Corner.FR, s.tires[Corner.FR], u, now)
                TireCard(Corner.RR, s.tires[Corner.RR], u, now)
            }
        }
        BrakeCard(s, u, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), 124.dp)
        AccelCard(s, Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
    }
}

@Composable
fun TireCard(c: Corner, t: TireState?, u: UnitPrefs, now: Long, compact: Boolean = false) {
    val kpa = t?.pressureKpa?.value
    val low = kpa != null && kpa < LOW_TIRE_KPA
    DashCard(borderColor = if (low) C.mfr else C.border, radius = 16.dp, padding = if (compact) 9.dp else 11.dp) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            SectionLabel(c.label, Modifier.weight(1f))
            Badge("TPMS·${t?.pressureKpa?.source?.name ?: "MFR"}", if (t?.pressureKpa == null) C.textFaint else C.mfr, false, small = true)
        }
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                u.fmtTire(kpa), color = when { kpa == null -> C.textFaint; low -> C.lowAmber; else -> C.text },
                fontFamily = NumberFont, fontSize = if (kpa == null) 22.sp else if (compact) 24.sp else 30.sp, fontWeight = FontWeight.SemiBold, lineHeight = 30.sp,
            )
            Text(if (compact) "${u.tirePressureUnit} · ${u.fmtTemp(t?.tempC?.value)}" else u.tirePressureUnit, color = C.textMuted, fontSize = 11.sp, modifier = Modifier.padding(bottom = 3.dp))
            if (low) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                    Text("LOW", Modifier.clip(RoundedCornerShape(4.dp)).background(C.mfr).padding(horizontal = 6.dp, vertical = 2.dp), color = C.onAmber, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        if (!compact) Text("${u.fmtTemp(t?.tempC?.value)} tire temp", color = if (t?.tempC == null) C.textFaint else C.textDim, fontSize = 12.sp)
        ageText(t?.pressureKpa, now)?.let { Text("Updated $it", color = C.textFaint, fontSize = 10.sp) }
        Box(Modifier.fillMaxWidth().height(1.dp).background(C.border))
        RowKV("Wheel", t?.wheelSpeedKmh?.value?.let { u.fmtSpeed(it, 1) + " " + u.speedUnit } ?: "N/A", t?.wheelSpeedKmh, Source.MFR)
        RowKV("Caliper", u.fmtForce(t?.caliperForceKn?.value), t?.caliperForceKn, Source.EST)
    }
}

@Composable
fun RowKV(k: String, v: String, r: Reading<*>?, fallback: Source) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(k, color = C.textMuted, fontSize = 11.sp)
            Text(v, color = if (v == "N/A") C.textFaint else C.text, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
        val src = r?.source ?: fallback
        if (src == Source.EST) Badge("EST", if (r == null) C.textFaint else C.est, true, small = true)
        else Text(src.name, color = if (r == null) C.textFaint else C.source(src), fontSize = 9.sp, fontWeight = FontWeight.Bold)
    }
}

/** Car outline seen from above; a tire with low pressure is highlighted. */
@Composable
fun CarTopView(s: VehicleState, modifier: Modifier, height: Dp) {
    val low = Corner.entries.associateWith { (s.tires[it]?.pressureKpa?.value ?: 999.0) < LOW_TIRE_KPA }
    Canvas(modifier.height(height)) {
        val k = size.height / 300f
        val ox = (size.width - 60 * k) / 2
        scale(k, k, pivot = Offset.Zero) {
            val dx = ox / k
            drawRoundRect(C.card, Offset(6f + dx, 10f), Size(48f, 280f), CornerRadius(22f))
            drawRoundRect(C.wheel, Offset(6f + dx, 10f), Size(48f, 280f), CornerRadius(22f), style = Stroke(1.5f))
            drawRoundRect(C.carBody, Offset(12f + dx, 70f), Size(36f, 46f), CornerRadius(8f))
            drawRoundRect(C.carBody, Offset(12f + dx, 200f), Size(36f, 34f), CornerRadius(8f))
            drawLine(C.tick, Offset(30f + dx, 22f), Offset(30f + dx, 40f), 2f, StrokeCap.Round)
            drawLine(C.tick, Offset(24f + dx, 30f), Offset(30f + dx, 22f), 2f, StrokeCap.Round)
            drawLine(C.tick, Offset(36f + dx, 30f), Offset(30f + dx, 22f), 2f, StrokeCap.Round)
            fun tire(x: Float, y: Float, c: Corner) = drawRoundRect(if (low[c] == true) C.mfr else C.tireIdle, Offset(x + dx, y), Size(7f, 40f), CornerRadius(3f))
            tire(0f, 44f, Corner.FL); tire(53f, 44f, Corner.FR); tire(0f, 216f, Corner.RL); tire(53f, 216f, Corner.RR)
        }
    }
}

@Composable
fun BrakeCard(s: VehicleState, u: UnitPrefs, modifier: Modifier = Modifier, gaugeSize: Dp) {
    DashCard(modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Gauge(
                brakePct(s, u), gaugeSize, "Brake pressure", u.brakePressureUnit, s.brakePressureBar?.source ?: Source.MFR,
                max = u.brakePressureMax, ticks = 8, color = C.red,
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Brake pedal", color = C.textDim, fontSize = 12.sp)
                    val on = s.brakePedalOn?.value
                    Pill(when (on) { true -> "ON"; false -> "OFF"; null -> "N/A" }, if (on == true) C.redSoft else C.textDim, if (on == true) C.redBg else C.track, fontSize = 12.sp)
                }
                CaliperBar("Front caliper force", s.caliperFrontKn?.value, u, 1f)
                CaliperBar("Rear caliper force", s.caliperRearKn?.value, u, 0.75f)
                Text("Caliper force is estimated: pressure × piston area per corner.", color = C.textFaint, fontSize = 10.sp, lineHeight = 13.sp)
            }
        }
    }
}

@Composable
fun CaliperBar(label: String, kn: Double?, u: UnitPrefs, alpha: Float) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = C.textMuted, fontSize = 11.sp)
            Text(u.fmtForce(kn), color = if (kn == null) C.textFaint else C.text, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
        FillBar(kn?.let { u.force(it) / u.forceMax }, C.red.copy(alpha = alpha), Modifier.fillMaxWidth())
    }
}

@Composable
fun AccelCard(s: VehicleState, modifier: Modifier = Modifier) {
    val lon = s.longitudinal(); val lat = s.lateral(); val v = s.vertical(); val fs = s.speedDerivedLongG
    DashCard(modifier.fillMaxWidth()) {
        CardHeader("Acceleration (±1 g)") {
            Badge(
                when (lon?.source) { Source.MFR -> "CAR ESC"; Source.PHONE -> "PHONE"; else -> "CAR ESC › PHONE" },
                lon?.source?.let { C.source(it) } ?: C.textFaint, false,
            )
        }
        AxisRow("Longitudinal", lon)
        AxisRow("Lateral", lat)
        AxisRow("Vertical Δ1g", v?.let { Reading(it.value - 1.0, it.source, it.tag, it.timeMs) })
        AxisRow("From speed", fs)
        Text(
            "Car sensors when the make profile supports them, else phone. Vertical is phone only. “From speed” = change in OBD speed (EST).",
            color = C.textFaint, fontSize = 10.sp, lineHeight = 13.sp,
        )
    }
}

@Composable
fun AxisRow(name: String, r: Reading<Double>?) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(name, Modifier.width(96.dp), color = C.textDim, fontSize = 12.sp, maxLines = 1)
        AxisBar(r?.value, Modifier.weight(1f), if (r?.source == Source.EST) C.est else C.phone)
        Text(UnitPrefs.signedG(r?.value), Modifier.width(62.dp), color = if (r == null) C.textFaint else C.text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, textAlign = androidx.compose.ui.text.style.TextAlign.End)
    }
}

