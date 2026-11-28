package com.dreyev.vlink.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dreyev.vlink.obd.EngineState
import com.dreyev.vlink.obd.LinkState
import com.dreyev.vlink.obd.VehicleState

enum class Tab(val title: String) { DRIVE("Drive"), TIRES("Tires & chassis"), VEHICLE("Vehicle & GPS"), MAP("Map") }

fun linkTitle(s: VehicleState): Pair<String, Color> = when (s.link.state) {
    LinkState.CONNECTED -> (if (s.link.simulated) "Demo mode" else "VLink connected") to C.green
    LinkState.CONNECTING, LinkState.INITIALIZING -> "Connecting…" to C.mfr
    LinkState.ERROR -> "Reconnecting…" to C.red
    LinkState.DISCONNECTED -> "Not connected" to C.textFaint
}

fun linkSubtitle(s: VehicleState): String = when (s.link.state) {
    LinkState.CONNECTED -> listOfNotNull(
        s.link.transport, s.link.elmVersion?.substringBefore(' ')?.let { "ELM327" },
        "%.0f Hz".format(s.link.pollHz), if (s.link.simulated) "simulated" else null,
    ).joinToString(" · ")
    LinkState.DISCONNECTED -> "Tap the gear to pick your adapter"
    else -> s.link.message ?: s.link.deviceName ?: ""
}

@Composable
fun EnginePill(s: VehicleState) {
    if (s.isEv) when (s.engine?.value) {
        EngineState.ON -> Pill("READY", C.greenText, C.greenBg)
        EngineState.OFF -> Pill("OFF", C.textDim, C.track)
        else -> Pill("READY N/A", C.textFaint, C.track)
    } else when (s.engine?.value) {
        EngineState.ON -> Pill("ENGINE ON", C.greenText, C.greenBg)
        EngineState.OFF -> Pill("ENGINE OFF", C.textDim, C.track)
        else -> Pill("ENGINE N/A", C.textFaint, C.track)
    }
}

@Composable
fun ScreenHeader(
    title: String,
    subtitle: String,
    dot: Color,
    onSettings: (() -> Unit)?,
    trailing: @Composable () -> Unit = {},
) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            LogoChip()
            Column(Modifier.weight(1f, fill = false)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    StatusDot(dot)
                    Text(title, color = C.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
                Text(subtitle, color = C.textFaint, fontSize = 11.sp, maxLines = 1)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            trailing()
            if (onSettings != null) SettingsButton(onSettings)
        }
    }
}

@Composable
fun SettingsButton(onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).clip(RoundedCornerShape(22.dp)).background(C.card).border(1.dp, C.border, RoundedCornerShape(22.dp))
            .clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = "Settings and units" },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(20.dp)) {
            val k = size.width / 24f
            scale(k, k, pivot = Offset.Zero) {
                val st = Stroke(1.8f, cap = StrokeCap.Round)
                drawCircle(C.textDim, 3f, Offset(12f, 12f), style = st)
                val p = Path().apply {
                    moveTo(12f, 2f); lineTo(12f, 5f); moveTo(12f, 19f); lineTo(12f, 22f); moveTo(2f, 12f); lineTo(5f, 12f); moveTo(19f, 12f); lineTo(22f, 12f)
                    moveTo(4.9f, 4.9f); lineTo(7f, 7f); moveTo(17f, 17f); lineTo(19.1f, 19.1f); moveTo(4.9f, 19.1f); lineTo(7f, 17f); moveTo(17f, 7f); lineTo(19.1f, 4.9f)
                }
                drawPath(p, C.textDim, style = st)
            }
        }
    }
}

@Composable
fun TabIcon(tab: Tab, color: Color) {
    Canvas(Modifier.size(22.dp)) {
        val k = size.width / 24f
        scale(k, k, pivot = Offset.Zero) {
            val st = Stroke(1.8f, cap = StrokeCap.Round)
            when (tab) {
                Tab.DRIVE -> {
                    drawArc(color, 180f, 180f, false, Offset(4f, 9f), androidx.compose.ui.geometry.Size(16f, 16f), style = st)
                    drawLine(color, Offset(12f, 17f), Offset(16f, 12f), 1.8f, StrokeCap.Round)
                }
                Tab.TIRES -> { drawCircle(color, 8f, Offset(12f, 12f), style = st); drawCircle(color, 3.5f, Offset(12f, 12f), style = st) }
                Tab.VEHICLE -> {
                    val p = Path().apply {
                        moveTo(12f, 21f); cubicTo(12f, 21f, 6f, 15.5f, 6f, 10f); cubicTo(6f, 6.7f, 8.7f, 4f, 12f, 4f)
                        cubicTo(15.3f, 4f, 18f, 6.7f, 18f, 10f); cubicTo(18f, 15.5f, 12f, 21f, 12f, 21f)
                    }
                    drawPath(p, color, style = st); drawCircle(color, 2.2f, Offset(12f, 10f), style = st)
                }
                Tab.MAP -> {
                    // folded map
                    val p = Path().apply {
                        moveTo(3f, 6f); lineTo(9f, 4f); lineTo(15f, 6f); lineTo(21f, 4f); lineTo(21f, 18f)
                        lineTo(15f, 20f); lineTo(9f, 18f); lineTo(3f, 20f); close()
                    }
                    drawPath(p, color, style = st)
                    drawLine(color, Offset(9f, 4f), Offset(9f, 18f), 1.8f); drawLine(color, Offset(15f, 6f), Offset(15f, 20f), 1.8f)
                }
            }
        }
    }
}

@Composable
fun BottomTabs(tabs: List<Tab>, current: Tab, onSelect: (Tab) -> Unit) {
    Row(Modifier.fillMaxWidth().background(C.nav).padding(start = 8.dp, end = 8.dp, top = 6.dp, bottom = 10.dp)) {
        tabs.forEach { t ->
            val sel = t == current
            val color = if (sel) C.obd else C.textFaint
            Column(
                Modifier.weight(1f).heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp))
                    .clickable(role = Role.Tab) { onSelect(t) },
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically),
            ) {
                TabIcon(t, color)
                Text(t.title, color = color, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** Compact tabs for the landscape header. */
@Composable
fun TopTabs(tabs: List<Tab>, current: Tab, onSelect: (Tab) -> Unit) {
    Row(Modifier.clip(RoundedCornerShape(999.dp)).background(C.card).border(1.dp, C.border, RoundedCornerShape(999.dp)).padding(3.dp)) {
        tabs.forEach { t ->
            val sel = t == current
            Box(
                Modifier.clip(RoundedCornerShape(999.dp)).background(if (sel) C.selected else Color.Transparent)
                    .clickable(role = Role.Tab) { onSelect(t) }.heightIn(min = 40.dp).padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(t.title, color = if (sel) C.obd else C.textMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
