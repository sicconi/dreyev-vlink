package com.dreyev.vlink.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dreyev.vlink.VLinkApp
import com.dreyev.vlink.bt.AdapterDevice
import com.dreyev.vlink.bt.LinkKind
import com.dreyev.vlink.data.SavedDevice
import com.dreyev.vlink.obd.LinkState
import com.dreyev.vlink.obd.TempUnit
import com.dreyev.vlink.obd.UnitPrefs
import com.dreyev.vlink.obd.UnitSystem
import com.dreyev.vlink.sensors.CalStep
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(app: VLinkApp, onBack: () -> Unit) {
    val settings by app.settings.collectAsState()
    val state by app.store.state.collectAsState()
    val devices by app.scanner.devices.collectAsState()
    val scanning by app.scanner.scanning.collectAsState()
    val cal by app.motion.calStep.collectAsState()
    val scope = rememberCoroutineScope()
    val u = settings.units
    var importMsg by remember { mutableStateOf<String?>(null) }
    val profiles = remember(importMsg) { app.profiles.all() }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importMsg = app.profiles.import(uri).fold({ "Imported “${it.name}”" }, { "Could not import: ${it.message}" })
    }

    DisposableEffect(Unit) {
        app.scanner.refreshBonded()
        onDispose { app.scanner.stopBleScan() }
    }

    Column(Modifier.fillMaxSize().background(C.bg)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                Modifier.size(44.dp).clip(RoundedCornerShape(22.dp)).background(C.card).border(1.dp, C.border, RoundedCornerShape(22.dp)).clickable(role = Role.Button, onClick = onBack),
                contentAlignment = Alignment.Center,
            ) { Text("‹", color = C.text, fontSize = 26.sp) }
            Text("Settings", color = C.text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        }
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {

            // ---- Adapter ----
            DashCard(Modifier.fillMaxWidth()) {
                SectionLabel("OBD-II adapter")
                val (title, dot) = linkTitle(state)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    StatusDot(dot); Text(title, color = C.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
                Text(
                    listOfNotNull(state.link.deviceName, state.link.protocol, state.batteryV?.value?.let { "%.1f V".format(it) }, state.link.message).joinToString(" · ").ifEmpty { "Pair your VLink in Android Bluetooth settings first (PIN 1234 or 0000), then pick it below." },
                    color = C.textFaint, fontSize = 11.sp,
                )
                if (state.link.state != LinkState.DISCONNECTED) {
                    ActionButton("Disconnect", C.red) { app.disconnect() }
                }
                if (!app.scanner.isAvailable) Text("This phone has no Bluetooth.", color = C.red, fontSize = 12.sp)
                else if (!app.scanner.isEnabled) Text("Bluetooth is off. Turn it on to see your adapter.", color = C.mfr, fontSize = 12.sp)
                devices.forEach { d -> DeviceRow(d, settings.device?.address == d.address) { app.connect(SavedDevice(d.address, d.name, d.kind)) } }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionButton(if (scanning) "Stop BLE scan" else "Scan for BLE adapters", C.obd) {
                        if (scanning) app.scanner.stopBleScan() else app.scanner.startBleScan()
                    }
                    ActionButton("Demo mode", C.phone) { app.startDemo(); onBack() }
                }
            }

            // ---- Units ----
            DashCard(Modifier.fillMaxWidth()) {
                SectionLabel("Units")
                Text("Measurement system", color = C.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text("Speed, distance, pressure and acceleration", color = C.textFaint, fontSize = 11.sp)
                Segmented(listOf("Imperial (US)" to (u.system == UnitSystem.IMPERIAL), "Metric" to (u.system == UnitSystem.METRIC))) { i ->
                    scope.launch { app.settingsRepo.setUnitSystem(if (i == 0) UnitSystem.IMPERIAL else UnitSystem.METRIC) }
                }
                Text("Temperature", color = C.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
                Text("Outside air and tire temperatures", color = C.textFaint, fontSize = 11.sp)
                Segmented(listOf("Fahrenheit (°F)" to (u.temp == TempUnit.F), "Celsius (°C)" to (u.temp == TempUnit.C))) { i ->
                    scope.launch { app.settingsRepo.setTempUnit(if (i == 0) TempUnit.F else TempUnit.C) }
                }
                SectionLabel("Preview", Modifier.padding(top = 6.dp))
                previewRows(u).forEach { (k, v) ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(k, color = C.textMuted, fontSize = 12.sp); Text(v, color = C.text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
                Text("Saved on this phone and restored every time the app starts. First launch uses Imperial and °F.", color = C.textFaint, fontSize = 11.sp)
                ActionButton("Reset to US defaults", C.textDim) { scope.launch { app.settingsRepo.resetUnits() } }
            }

            // ---- Manufacturer profile ----
            DashCard(Modifier.fillMaxWidth()) {
                SectionLabel("Manufacturer profile (MFR signals)")
                Text(
                    "Brake, steering, wheel speeds, TPMS, turn signals, seatbelts and car acceleration need manufacturer PIDs. Pick a profile, or Auto to match by VIN.",
                    color = C.textFaint, fontSize = 11.sp,
                )
                ChoiceRow("Auto (match by VIN)", settings.profileId == "auto") { scope.launch { app.settingsRepo.setProfile("auto") } }
                profiles.forEach { p ->
                    ChoiceRow(p.name, settings.profileId == p.id, p.notes) { scope.launch { app.settingsRepo.setProfile(p.id) } }
                }
                ActionButton("Import profile (.json)", C.mfr) { importer.launch(arrayOf("application/json", "text/plain", "*/*")) }
                importMsg?.let { Text(it, color = C.textDim, fontSize = 11.sp) }
                Text("Active now: ${state.profileName ?: "none"}. Changes apply on the next connection.", color = C.textFaint, fontSize = 11.sp)
            }

            // ---- Phone mount ----
            DashCard(Modifier.fillMaxWidth()) {
                SectionLabel("Phone mount calibration")
                Text(
                    when (cal) {
                        CalStep.IDLE -> if (settings.mountCalibration != null) "Calibrated. Recalibrate if you move the mount." else "Not calibrated: assumes the screen faces you. Calibrate for best phone G-force readings."
                        CalStep.HOLD_STILL -> "Step 1 of 2: keep the car still…"
                        CalStep.BRAKE_GENTLY -> "Step 2 of 2: drive straight and brake gently to a stop."
                        CalStep.DONE -> "Done. Phone acceleration now uses vehicle axes."
                        CalStep.FAILED -> "Didn't detect a stop within 20 s. Try again."
                    },
                    color = C.textDim, fontSize = 12.sp,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionButton("Calibrate", C.phone) { app.motion.beginCalibration() }
                    if (settings.mountCalibration != null) ActionButton("Clear", C.textDim) {
                        app.motion.calibration = null; scope.launch { app.settingsRepo.setMountCalibration(null) }
                    }
                }
            }

            DashCard(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Keep screen on", color = C.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text("While the dashboard is open", color = C.textFaint, fontSize = 11.sp)
                    }
                    Switch(settings.keepScreenOn, { v -> scope.launch { app.settingsRepo.setKeepScreenOn(v) } },
                        colors = SwitchDefaults.colors(checkedTrackColor = C.obd, checkedThumbColor = C.bg))
                }
            }
            Text("Trip logs are saved to Android/data/com.dreyev.vlink/files/trips as CSV.", color = C.textFaint, fontSize = 11.sp, modifier = Modifier.padding(bottom = 24.dp))
        }
    }
}

private fun previewRows(u: UnitPrefs) = listOf(
    "Speed" to "${u.fmtSpeed(75.6)} ${u.speedUnit}",
    "Odometer" to "${u.fmtDistance(77591.9)} ${u.distanceUnit}",
    "Tire pressure" to "${u.fmtTire(242.7)} ${u.tirePressureUnit}",
    "Brake pressure" to "${UnitPrefs.fmt(u.brakePressure(40.0), 0)} ${u.brakePressureUnit}",
    "Acceleration" to "0.21 g · ${UnitPrefs.fmt(u.accelMs2(0.21 * 9.80665), 1)} ${u.accelUnit}",
    "Altitude" to u.fmtAltitude(4.0),
    "Outside temp" to u.fmtTemp(23.3),
    "Tire temp" to u.fmtTemp(31.1),
)

@Composable
private fun Segmented(options: List<Pair<String, Boolean>>, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).border(1.dp, C.border, RoundedCornerShape(12.dp)).padding(3.dp)) {
        options.forEachIndexed { i, (label, sel) ->
            Box(
                Modifier.weight(1f).heightIn(min = 44.dp).clip(RoundedCornerShape(10.dp)).background(if (sel) C.selected else Color.Transparent)
                    .clickable(role = Role.RadioButton) { onSelect(i) },
                contentAlignment = Alignment.Center,
            ) { Text(label, color = if (sel) C.obd else C.textMuted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
        }
    }
}

@Composable
private fun ChoiceRow(label: String, selected: Boolean, detail: String = "", onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(RoundedCornerShape(10.dp)).background(if (selected) C.selected else Color.Transparent)
            .clickable(role = Role.RadioButton, onClick = onClick).padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(16.dp).clip(RoundedCornerShape(8.dp)).border(2.dp, if (selected) C.obd else C.tick, RoundedCornerShape(8.dp)).padding(4.dp)) {
            if (selected) Box(Modifier.fillMaxSize().clip(RoundedCornerShape(4.dp)).background(C.obd))
        }
        Column {
            Text(label, color = if (selected) C.obd else C.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            if (detail.isNotBlank()) Text(detail, color = C.textFaint, fontSize = 10.sp, maxLines = 2)
        }
    }
}

@Composable
private fun DeviceRow(d: AdapterDevice, saved: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(10.dp)).background(C.cardInner)
            .border(1.dp, if (saved) C.obd else C.border, RoundedCornerShape(10.dp)).clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(d.name, color = C.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(
                listOfNotNull(if (d.kind == LinkKind.BLE) "Bluetooth LE" else "Bluetooth Classic", if (d.bonded) "paired" else null, if (saved) "last used" else null, d.address).joinToString(" · "),
                color = C.textFaint, fontSize = 11.sp,
            )
        }
        if (d.looksLikeObd) Badge("OBD", C.obd, false)
        Text("  Connect", color = C.obd, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun ActionButton(label: String, color: Color, onClick: () -> Unit) {
    Box(
        Modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(22.dp)).border(1.dp, color, RoundedCornerShape(22.dp))
            .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = color, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
}
