package com.dreyev.vlink.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dreyev.vlink.obd.Corner
import com.dreyev.vlink.obd.Reading
import com.dreyev.vlink.obd.Source
import com.dreyev.vlink.obd.UnitPrefs
import com.dreyev.vlink.obd.VehicleState
import java.util.Locale

/** Status chips shown in the landscape header: brake, turn signals, belts. */
@Composable
fun LandscapeStatus(s: VehicleState) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        EnginePill(s)
        val on = s.brakePedalOn?.value
        Pill("BRAKE " + when (on) { true -> "ON"; false -> "OFF"; null -> "N/A" }, if (on == true) C.redSoft else C.textMuted, if (on == true) C.redBg else C.track)
        TurnArrow(true, s.turnLeft?.value)
        TurnArrow(false, s.turnRight?.value)
        SeatbeltIcon(s.beltDriver?.value, false, 22.dp)
        SeatbeltIcon(s.beltPassenger?.value, true, 22.dp)
    }
}

@Composable
fun DriveLandscape(s: VehicleState, u: UnitPrefs) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            // Pedals
            Row(Modifier.height(240.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PedalBar("GAS", s.pedalPct?.value?.div(100), s.pedalPct?.value?.let { "${it.toInt()}%" }, C.obd, s.pedalPct, Source.OBD)
                val b = brakePct(s, u)
                PedalBar("BRAKE", b?.div(u.brakePressureMax), b?.let { "${it.toInt()} ${u.brakePressureUnit}" }, C.red, s.brakePressureBar, Source.MFR)
            }
            if (s.isEv) {
                EvSpeedGauge(s, u, 160.dp)
                PowerGauge(s, 160.dp)
            } else HeroGauge(s, u, 236.dp)
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    DashCard(Modifier.fillMaxWidth(), padding = 10.dp) {
                        CardHeader("G-force") { SourceBadge(s.longitudinal()?.source, "") }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FrictionCircle(s.lateral()?.value, s.longitudinal()?.value, 92.dp)
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                SmallKv("Lateral", UnitPrefs.signedG(s.lateral()?.value))
                                SmallKv("Longitudinal", UnitPrefs.signedG(s.longitudinal()?.value))
                                SmallKv("Vertical", s.vertical()?.value?.let { UnitPrefs.fmt(it, 2) + " g" } ?: "N/A")
                            }
                        }
                    }
                }
                Column(Modifier.width(132.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    DashCard(Modifier.fillMaxWidth(), padding = 10.dp) {
                        CardHeader("Steering") { SourceBadge(s.steeringDeg, Source.MFR) }
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { SteeringWheel(s.steeringDeg?.value, 72.dp) }
                        Text(UnitPrefs.steering(s.steeringDeg?.value), color = C.text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                    LoadOrSocGauge(s, 88.dp)
                }
            }
        }
        // Bottom strip
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StripCell("TPMS", s.tires[Corner.FL]?.pressureKpa, Source.MFR, Modifier.weight(1.6f)) {
                Text(
                    Corner.entries.joinToString("  ") { c -> u.fmtTire(s.tires[c]?.pressureKpa?.value) },
                    color = C.text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                )
                Text(Corner.entries.joinToString("  ") { c -> u.fmtTemp(s.tires[c]?.tempC?.value) }, color = C.textMuted, fontSize = 10.sp, maxLines = 1)
            }
            StripCell("Outside", s.ambientC, Source.OBD, Modifier.weight(0.8f)) { StripValue(u.fmtTemp(s.ambientC?.value)) }
            StripCell("Odometer", s.odometerKm, Source.OBD, Modifier.weight(1f)) { StripValue(s.odometerKm?.value?.let { u.fmtDistance(it) + " " + u.distanceUnit } ?: "N/A") }
            StripCell("GPS", s.gps, Source.PHONE, Modifier.weight(1.3f)) {
                StripValue(s.gps?.value?.let { String.format(Locale.US, "%.5f, %.5f", it.lat, it.lon) } ?: "No fix")
            }
            StripCell("VIN", s.vin, Source.OBD, Modifier.weight(1.4f)) { StripValue(s.vin?.value ?: "N/A", mono = true) }
        }
    }
}

@Composable
private fun SmallKv(k: String, v: String) {
    Column {
        Text(k, color = C.textMuted, fontSize = 10.sp)
        Text(v, color = C.text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun PedalBar(label: String, fraction: Double?, value: String?, color: androidx.compose.ui.graphics.Color, r: Reading<*>?, fb: Source) {
    Column(Modifier.width(44.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(value ?: "N/A", color = if (value == null) C.textFaint else C.text, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        VerticalBar(fraction, color, Modifier.width(26.dp).weight(1f))
        Text(label, color = C.textDim, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
        SrcText(r, fb)
    }
}

@Composable
private fun StripCell(title: String, r: Reading<*>?, fb: Source, modifier: Modifier, content: @Composable () -> Unit) {
    DashCard(modifier, radius = 12.dp, padding = 9.dp) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            SectionLabel(title)
            SrcText(r, fb)
        }
        content()
    }
}

@Composable
private fun StripValue(v: String, mono: Boolean = false) {
    Text(v, color = if (v == "N/A" || v == "No fix") C.textFaint else C.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, fontFamily = if (mono) MonoFont else NumberFont)
}

@Composable
fun TiresLandscape(s: VehicleState, u: UnitPrefs, now: Long) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(Modifier.width(410.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TireCard(Corner.FL, s.tires[Corner.FL], u, now, compact = true)
                TireCard(Corner.RL, s.tires[Corner.RL], u, now, compact = true)
            }
            CarTopView(s, Modifier.width(44.dp), 250.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TireCard(Corner.FR, s.tires[Corner.FR], u, now, compact = true)
                TireCard(Corner.RR, s.tires[Corner.RR], u, now, compact = true)
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            BrakeCard(s, u, gaugeSize = 104.dp)
            AccelCard(s)
        }
    }
}
