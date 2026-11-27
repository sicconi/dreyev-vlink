package com.dreyev.vlink.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dreyev.vlink.obd.EngineState
import com.dreyev.vlink.obd.Source
import com.dreyev.vlink.obd.UnitPrefs
import com.dreyev.vlink.obd.VehicleState
import com.dreyev.vlink.obd.Vin
import java.util.Locale
import kotlin.math.abs

@Composable
fun RecordButton(recording: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(22.dp)).background(if (recording) C.red else C.redBg.copy(alpha = 0.7f))
            .border(1.dp, C.red, RoundedCornerShape(22.dp)).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(if (recording) 2.dp else 5.dp)).background(if (recording) C.text else C.red))
        Text(if (recording) "Stop" else "Record trip", color = if (recording) C.text else C.redSoft, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun VehicleScreen(s: VehicleState, u: UnitPrefs, trail: List<Pair<Double, Double>>) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        DashCard(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            CardHeader("VIN") { SourceBadge(s.vin, Source.OBD, "09 02") }
            Text(s.vin?.value ?: "N/A", color = if (s.vin == null) C.textFaint else C.text, fontFamily = MonoFont, fontSize = 21.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.5.sp)
            Text(
                s.vin?.value?.let { Vin.describe(it) + " · read once on connect" } ?: "Read once on connect (most 2005+ cars)",
                color = C.textFaint, fontSize = 11.sp,
            )
            s.profileName?.let { Text("Profile: $it", color = C.textFaint, fontSize = 11.sp) }
        }

        DashCard(Modifier.fillMaxWidth()) {
            CardHeader("Odometer") { SourceBadge(s.odometerKm, Source.OBD, "A6") }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                val v = s.odometerKm?.value?.let { u.distance(it) }
                val digits = v?.let { String.format(Locale.US, "%08.1f", it).replace(".", "") } ?: "-------"
                digits.forEachIndexed { i, ch ->
                    val last = v != null && i == digits.length - 1
                    Box(Modifier.size(30.dp, 40.dp).clip(RoundedCornerShape(6.dp)).background(if (last) C.text else C.bg), contentAlignment = Alignment.Center) {
                        Text(ch.toString(), color = if (last) C.bg else if (v == null) C.textFaint else C.text, fontFamily = MonoFont, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
                Text(u.distanceUnit, color = C.textMuted, fontSize = 13.sp, modifier = Modifier.padding(start = 6.dp))
            }
            if (s.odometerKm == null) Text("PID A6 is mostly on newer cars; a manufacturer PID can fill it in.", color = C.textFaint, fontSize = 10.sp)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            DashCard(Modifier.weight(1f)) {
                CardHeader("Outside") { SourceBadge(s.ambientC, Source.OBD, "46") }
                Text(u.fmtTemp(s.ambientC?.value), color = if (s.ambientC == null) C.textFaint else C.text, fontFamily = NumberFont, fontSize = 34.sp, fontWeight = FontWeight.SemiBold)
                Text("Ambient air temp", color = C.textFaint, fontSize = 11.sp)
            }
            if (s.isEv) DashCard(Modifier.weight(1f)) {
                CardHeader("Drive state") { SourceBadge(s.engine, Source.MFR, "READY") }
                val st = s.engine?.value
                Text(
                    when (st) { EngineState.ON -> "READY"; EngineState.OFF -> "OFF"; else -> "N/A" },
                    color = when (st) { EngineState.ON -> C.greenText; EngineState.OFF -> C.textDim; else -> C.textFaint },
                    fontFamily = NumberFont, fontSize = 30.sp, fontWeight = FontWeight.SemiBold,
                )
                Text(
                    listOfNotNull(
                        s.socPct?.value?.let { "SOC %.0f%%".format(it) },
                        s.hvVoltageV?.value?.let { "%.0f V".format(it) },
                        s.hvPowerKw?.value?.let { "%.1f kW".format(it) },
                    ).joinToString(" · ").ifEmpty { "Battery N/A" },
                    color = C.textFaint, fontSize = 11.sp,
                )
            } else DashCard(Modifier.weight(1f)) {
                CardHeader("Engine") { SourceBadge(s.engine, Source.OBD, "0C · 1F") }
                val st = s.engine?.value
                Text(
                    when (st) { EngineState.ON -> "ON"; EngineState.OFF -> "OFF"; else -> "N/A" },
                    color = when (st) { EngineState.ON -> C.greenText; EngineState.OFF -> C.textDim; else -> C.textFaint },
                    fontFamily = NumberFont, fontSize = 34.sp, fontWeight = FontWeight.SemiBold,
                )
                Text("Run time " + (s.runTimeS?.value?.let { UnitPrefs.duration(it) } ?: "N/A"), color = C.textFaint, fontSize = 11.sp)
            }
        }

        GpsCard(s, u, trail)
    }
}

@Composable
fun GpsCard(s: VehicleState, u: UnitPrefs, trail: List<Pair<Double, Double>>) {
    val g = s.gps?.value
    DashCard(Modifier.fillMaxWidth()) {
        CardHeader("GPS") {
            val detail = listOfNotNull("PHONE GNSS", g?.satellites?.let { "$it sats" }, g?.accuracyM?.let { "±${it.toInt()} m" }).joinToString(" · ")
            Badge(if (g == null) "PHONE · no fix" else detail, if (g == null) C.textFaint else C.phone, false)
        }
        Box(Modifier.fillMaxWidth().height(128.dp).clip(RoundedCornerShape(12.dp)).background(C.cardInner).border(1.dp, C.border, RoundedCornerShape(12.dp))) {
            TrailView(trail, Modifier.fillMaxWidth().height(128.dp))
            Text(if (g == null) "Waiting for GPS fix…" else "Your track (last ~2.5 min)", Modifier.align(Alignment.BottomStart).padding(10.dp), color = C.textFaint, fontSize = 10.sp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Compass(g?.headingDeg)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row { Kv("Lat", g?.lat?.let { latLon(it, 'N', 'S') }, Modifier.weight(1f)); Kv("Long", g?.lon?.let { latLon(it, 'E', 'W') }, Modifier.weight(1f)) }
                Row {
                    Kv("Heading", g?.headingDeg?.let { "${it.toInt()}° ${UnitPrefs.compass(it)}" }, Modifier.weight(1f))
                    Kv("Altitude", g?.altitudeM?.let { u.fmtAltitude(it) }, Modifier.weight(1f))
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            InnerStat("GPS speed", g?.speedKmh?.let { u.fmtSpeed(it, 1) }, u.speedUnit, Modifier.weight(1f))
            InnerStat("GPS accel.", s.gpsAccelMs2?.value?.let { UnitPrefs.fmt(u.accelMs2(it), 1) }, u.accelUnit, Modifier.weight(1f))
        }
    }
}

private fun latLon(v: Double, pos: Char, neg: Char) = String.format(Locale.US, "%.5f° %c", abs(v), if (v >= 0) pos else neg)

@Composable
private fun Kv(k: String, v: String?, modifier: Modifier) {
    Column(modifier) {
        Text(k, color = C.textMuted, fontSize = 11.sp)
        Text(v ?: "N/A", color = if (v == null) C.textFaint else C.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
private fun InnerStat(k: String, v: String?, unit: String, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(12.dp)).background(C.cardInner).padding(horizontal = 10.dp, vertical = 8.dp)) {
        Text(k.uppercase(), color = C.textMuted, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp)
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(v ?: "N/A", color = if (v == null) C.textFaint else C.text, fontFamily = NumberFont, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            Text(unit, color = C.textMuted, fontSize = 12.sp, modifier = Modifier.padding(bottom = 3.dp))
        }
    }
}
