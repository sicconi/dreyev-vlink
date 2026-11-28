package com.dreyev.vlink.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dreyev.vlink.VLinkApp
import com.dreyev.vlink.diag.FinderState
import com.dreyev.vlink.diag.SignalFinder

/**
 * Signal finder: checks the active profile request by request, scans the car's ECUs for
 * readable data, and watches that data while the driver tags actions, so turn signals,
 * belts and brake pressure can be located byte by byte.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SignalFinderScreen(app: VLinkApp, onBack: () -> Unit) {
    val st by app.finder.state.collectAsState()
    val marker by app.finder.marker.collectAsState()
    val context = LocalContext.current
    BackHandler { onBack() }

    Column(Modifier.fillMaxSize().background(C.bg)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                Modifier.size(44.dp).clip(RoundedCornerShape(22.dp)).background(C.card).border(1.dp, C.border, RoundedCornerShape(22.dp)).clickable(role = Role.Button, onClick = onBack),
                contentAlignment = Alignment.Center,
            ) { Text("‹", color = C.text, fontSize = 26.sp) }
            Column {
                Text("Signal finder", color = C.text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Text("Experimental · pauses the dashboard while it runs", color = C.textFaint, fontSize = 11.sp)
            }
        }
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            StatusCard(st, onStop = { app.finder.stop() }, onShare = {
                // Latest check + scan + watch logs and the latest trip, in one share.
                val logs = app.finder.allLogs().groupBy { it.name.substringBefore("-2") }.values.map { it.first() }
                app.finder.share(context, logs + listOfNotNull(app.finder.latestTrip()))
            })

            DashCard(Modifier.fillMaxWidth()) {
                SectionLabel("1 · Check the profile")
                Text("Sends every request in the active profile once and shows what the car answered. Use this when a gauge shows N/A.", color = C.textFaint, fontSize = 11.sp)
                ActionButton("Check profile", C.obd) { app.finder.start(SignalFinder.Mode.CHECK_PROFILE) }
                st.profileRows.forEach { r ->
                    Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("${r.header} ${r.request}", color = C.text, fontFamily = MonoFont, fontSize = 12.sp)
                            Text(r.status, color = if (r.ok) C.green else C.red, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                        Text(r.note, color = C.textDim, fontSize = 11.sp)
                        if (!r.ok) Text("Reply: ${r.raw.ifEmpty { "(none)" }}", color = C.textFaint, fontFamily = MonoFont, fontSize = 10.sp)
                    }
                }
            }

            DashCard(Modifier.fillMaxWidth()) {
                SectionLabel("2 · Scan the car")
                Text("Car on (READY) and parked. Looks for ECUs behind the OBD port and tries about 100 data IDs on each. Takes 2 to 5 minutes.", color = C.textFaint, fontSize = 11.sp)
                ActionButton("Scan ECUs", C.mfr) { app.finder.start(SignalFinder.Mode.SCAN) }
                if (st.found.isNotEmpty()) {
                    val byEcu = st.found.groupBy { it.header }
                    Text("${st.found.size} readable IDs on ${byEcu.size} ECUs", color = C.text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    byEcu.forEach { (ecu, rows) ->
                        Text("$ecu: " + rows.joinToString(" ") { it.request.removePrefix("22") }, color = C.textDim, fontFamily = MonoFont, fontSize = 10.sp)
                    }
                }
            }

            DashCard(Modifier.fillMaxWidth()) {
                SectionLabel("3 · Watch while you act")
                Text(
                    "Start watching, then tap what you are doing and hold it for about 10 seconds: signal on, belt buckled, brake pressed. " +
                        "Go back to “baseline” between actions. Bytes that change show up below, tagged with your action.",
                    color = C.textFaint, fontSize = 11.sp,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionButton(if (st.running) "Watching…" else "Start watching", C.phone) { app.finder.start(SignalFinder.Mode.WATCH) }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SignalFinder.MARKERS.forEach { m ->
                        val sel = m == marker
                        Box(
                            Modifier.heightIn(min = 36.dp).clip(RoundedCornerShape(18.dp)).background(if (sel) C.selected else C.cardInner)
                                .border(1.dp, if (sel) C.obd else C.border, RoundedCornerShape(18.dp))
                                .clickable(role = Role.Button) { app.finder.marker.value = m }.padding(horizontal = 12.dp),
                            contentAlignment = Alignment.Center,
                        ) { Text(m, color = if (sel) C.text else C.textDim, fontSize = 12.sp) }
                    }
                }
                if (st.changes.isNotEmpty()) {
                    SectionLabel("Changes (newest first)", Modifier.padding(top = 6.dp))
                    st.changes.take(40).forEach { c ->
                        Text(
                            "${c.target} byte ${c.letter} (${c.index}): " + String.format("%02X → %02X", c.from, c.to) + " · ${c.marker}",
                            color = C.textDim, fontFamily = MonoFont, fontSize = 10.sp,
                        )
                    }
                }
            }
            Text(
                "Every log is also copied to Downloads/dreyev-vlink on your phone when a run ends (tap Stop to end a watch). Trip recordings are copied there too.",
                color = C.textFaint, fontSize = 11.sp, modifier = Modifier.padding(bottom = 24.dp),
            )
        }
    }
}

@Composable
private fun StatusCard(st: FinderState, onStop: () -> Unit, onShare: () -> Unit) {
    DashCard(Modifier.fillMaxWidth()) {
        Text(if (st.running) st.step else if (st.step.isEmpty()) "Ready" else st.step, color = C.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        st.error?.let { Text(it, color = C.red, fontSize = 12.sp) }
        if (st.running) {
            Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(C.track)) {
                Box(Modifier.fillMaxWidth(st.progress.coerceIn(0.02f, 1f)).height(6.dp).clip(RoundedCornerShape(3.dp)).background(C.obd))
            }
            ActionButton("Stop and save log", C.red, onStop)
        } else if (st.file != null) {
            Text(
                st.savedPath?.let { "Log saved to $it" } ?: "Log saved in the app (${st.file.name}). Use Send logs below.",
                color = C.green, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
            )
        }
        SendLogsButton(enabled = !st.running, onShare)
        Text(
            "Send logs opens your phone's share menu with the latest check, scan and watch logs plus your latest trip. " +
                "Pick Save to Drive, email or Files, or find them later in Downloads/dreyev-vlink.",
            color = C.textFaint, fontSize = 11.sp,
        )
    }
}

@Composable
private fun SendLogsButton(enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(24.dp))
            .background(if (enabled) C.obd else C.track)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text("Send logs", color = if (enabled) C.card else C.textFaint, fontSize = 15.sp, fontWeight = FontWeight.Bold) }
}
