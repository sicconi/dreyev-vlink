package com.dreyev.vlink.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dreyev.vlink.obd.Reading
import com.dreyev.vlink.obd.Source
import com.dreyev.vlink.obd.UnitPrefs
import com.dreyev.vlink.obd.VehicleState
import java.util.Locale

/** Hero speed/RPM gauge values shared by portrait and landscape. */
data class Hero(val rpmK: Double?, val speed: String, val sub: String)

fun hero(s: VehicleState, u: UnitPrefs): Hero {
    val rpm = s.rpm?.value
    val gps = s.gps?.value?.speedKmh
    val sub = listOfNotNull(
        rpm?.let { String.format(Locale.US, "%,d rpm", Math.round(it)) },
        gps?.let { "GPS " + u.fmtSpeed(it, 1) },
    ).joinToString(" · ").ifEmpty { "RPM N/A" }
    // OBD speed first; GPS speed as a fallback if the car doesn't answer 0D.
    val speed = s.speedKmh?.value ?: gps
    return Hero(rpm?.div(1000), speed?.let { u.fmtSpeed(it) } ?: "N/A", sub)
}

fun brakePct(s: VehicleState, u: UnitPrefs): Double? = s.brakePressureBar?.value?.let { u.brakePressure(it) }

/**
 * Big speed gauge. Petrol/diesel: arc = engine RPM. Electric (EV profile): arc = battery
 * power in kW, negative while regenerating, with SOC and motor RPM underneath.
 */
@Composable
fun HeroGauge(s: VehicleState, u: UnitPrefs, size: androidx.compose.ui.unit.Dp) {
    val h = hero(s, u)
    val sp = s.speedKmh
    val src = sp?.source ?: s.gps?.let { Source.PHONE }
    val srcTag = if (sp != null) (if (sp.source == Source.OBD) "0D" else sp.tag) else "GPS"
    if (s.isEv) {
        val kw = s.hvPowerKw?.value
        val sub = listOfNotNull(
            kw?.let { String.format(Locale.US, "%.0f kW", it) },
            s.socPct?.value?.let { String.format(Locale.US, "SOC %.0f%%", it) },
            s.gps?.value?.speedKmh?.let { "GPS " + u.fmtSpeed(it, 1) },
        ).joinToString(" · ").ifEmpty { "Power N/A" }
        Gauge(
            value = kw, size = size, label = "Speed · Power kW", unit = u.speedUnit, source = src, sourceTag = srcTag,
            min = -100.0, max = 250.0, ticks = 7, color = if ((kw ?: 0.0) < 0) C.green else C.obd, display = h.speed, sub = sub, origin = 0.0,
        )
    } else {
        Gauge(
            value = h.rpmK, size = size, label = "Speed · RPM ×1000", unit = u.speedUnit, source = src,
            sourceTag = if (s.speedKmh?.source == Source.OBD) "0D · 0C" else srcTag,
            min = 0.0, max = 8.0, ticks = 8, redFrom = 6.5, display = h.speed, sub = h.sub,
        )
    }
}

/** Second small gauge: engine load, or battery state of charge on an EV. */
@Composable
fun LoadOrSocGauge(s: VehicleState, size: androidx.compose.ui.unit.Dp) {
    if (s.isEv) Gauge(s.socPct?.value, size, "Battery SOC", "%", s.socPct?.source ?: Source.MFR, s.socPct?.tag ?: "", color = C.green)
    else Gauge(s.engineLoadPct?.value, size, "Engine load", "%", s.engineLoadPct?.source, "04", redFrom = 90.0)
}

@Composable
fun DriveScreen(s: VehicleState, u: UnitPrefs, now: Long) {
    Column(Modifier.fillMaxWidth()) {
        // Hero gauge with turn signals and seatbelts around it
        Box(Modifier.fillMaxWidth().padding(top = 2.dp), contentAlignment = Alignment.TopCenter) {
            HeroGauge(s, u, 240.dp)
            Column(Modifier.align(Alignment.TopStart).padding(start = 16.dp, top = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                TurnArrow(true, s.turnLeft?.value)
                SrcText(s.turnLeft)
            }
            Column(Modifier.align(Alignment.TopEnd).padding(end = 16.dp, top = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                TurnArrow(false, s.turnRight?.value)
                SrcText(s.turnRight)
            }
            Box(Modifier.align(Alignment.BottomStart).padding(start = 10.dp, bottom = 34.dp)) { BeltStatus(s.beltDriver?.value, false, s.beltDriver) }
            Box(Modifier.align(Alignment.BottomEnd).padding(end = 10.dp, bottom = 34.dp)) { BeltStatus(s.beltPassenger?.value, true, s.beltPassenger) }
        }

        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            Gauge(s.pedalPct?.value, 108.dp, "Gas pedal", "%", s.pedalPct?.source, s.pedalPct?.tag ?: "49")
            LoadOrSocGauge(s, 108.dp)
            Gauge(
                brakePct(s, u), 108.dp, "Brake press.", u.brakePressureUnit, s.brakePressureBar?.source ?: Source.MFR,
                max = u.brakePressureMax, ticks = 8, color = C.red,
            )
        }

        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GForceCard(s, Modifier.weight(1f))
            SteeringCard(s, Modifier.weight(1f))
        }

        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val on = s.brakePedalOn?.value
            MiniTile(
                "Brake pedal", when (on) { true -> "ON"; false -> "OFF"; null -> "N/A" }, s.brakePedalOn, Source.MFR, Modifier.weight(1f),
                valueColor = if (on == true) C.redSoft else if (on == null) C.textFaint else C.text,
                bg = if (on == true) C.redBg else C.card, border = if (on == true) C.red else C.border,
            )
            MiniTile("Outside", u.fmtTemp(s.ambientC?.value), s.ambientC, Source.OBD, Modifier.weight(1f), fallbackTag = "46")
            MiniTile("Vertical", s.vertical()?.value?.let { UnitPrefs.fmt(it, 2) + " g" } ?: "N/A", s.vertical(), Source.PHONE, Modifier.weight(1f))
        }
    }
}

@Composable
fun SrcText(r: Reading<*>?, fallback: Source = Source.MFR) {
    Text(r?.source?.name ?: "$fallback", color = if (r == null) C.textFaint else C.source(r.source), fontSize = 9.sp, fontWeight = FontWeight.Bold)
}

@Composable
fun MiniTile(
    title: String, value: String, r: Reading<*>?, fallback: Source, modifier: Modifier = Modifier,
    valueColor: Color = if (value == "N/A") C.textFaint else C.text, bg: Color = C.card, border: Color = C.border, fallbackTag: String = "",
) {
    DashCard(modifier, borderColor = border, background = bg, radius = 14.dp, padding = 10.dp) {
        Text(title.uppercase(), color = C.textDim, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp, maxLines = 1)
        Text(value, color = valueColor, fontSize = 17.sp, fontWeight = FontWeight.Bold, fontFamily = NumberFont, maxLines = 1)
        val label = r?.let { if (it.tag.isBlank()) it.source.name else "${it.source.name} ${it.tag}" } ?: "$fallback ${fallbackTag}".trim() + " · N/A"
        Text(label, color = r?.let { C.source(it.source) } ?: C.textFaint, fontSize = 9.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

@Composable
fun GForceCard(s: VehicleState, modifier: Modifier = Modifier, size: androidx.compose.ui.unit.Dp = 120.dp) {
    val lon = s.longitudinal(); val lat = s.lateral()
    DashCard(modifier) {
        CardHeader("G-force") {
            val src = lon?.source
            Badge(if (src == Source.MFR) "CAR ESC" else if (src == Source.PHONE) "PHONE" else "ESC › PHONE", src?.let { C.source(it) } ?: C.textFaint, false)
        }
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { FrictionCircle(lat?.value, lon?.value, size) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            LabeledValue("Lat", UnitPrefs.signedG(lat?.value))
            LabeledValue("Long", UnitPrefs.signedG(lon?.value))
        }
    }
}

@Composable
fun LabeledValue(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, color = C.textMuted, fontSize = 11.sp)
        Text(value, color = C.text, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun SteeringCard(s: VehicleState, modifier: Modifier = Modifier, size: androidx.compose.ui.unit.Dp = 120.dp) {
    DashCard(modifier) {
        CardHeader("Steering") { SourceBadge(s.steeringDeg, Source.MFR) }
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { SteeringWheel(s.steeringDeg?.value, size) }
        Text(
            UnitPrefs.steering(s.steeringDeg?.value), Modifier.fillMaxWidth(), color = if (s.steeringDeg == null) C.textFaint else C.text,
            fontSize = 13.sp, fontWeight = FontWeight.SemiBold, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}
