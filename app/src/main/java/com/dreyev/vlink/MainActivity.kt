package com.dreyev.vlink

import android.Manifest
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.dreyev.vlink.obd.LinkState
import com.dreyev.vlink.ui.BottomTabs
import com.dreyev.vlink.ui.C
import com.dreyev.vlink.ui.DriveLandscape
import com.dreyev.vlink.ui.DriveScreen
import com.dreyev.vlink.ui.EnginePill
import com.dreyev.vlink.ui.LandscapeStatus
import com.dreyev.vlink.ui.LogoChip
import com.dreyev.vlink.ui.RecordButton
import com.dreyev.vlink.ui.ScreenHeader
import com.dreyev.vlink.ui.SettingsButton
import com.dreyev.vlink.ui.SettingsScreen
import com.dreyev.vlink.ui.Tab
import com.dreyev.vlink.ui.TiresLandscape
import com.dreyev.vlink.ui.TiresScreen
import com.dreyev.vlink.ui.TopTabs
import com.dreyev.vlink.ui.VLinkTheme
import com.dreyev.vlink.ui.VehicleScreen
import com.dreyev.vlink.ui.linkSubtitle
import com.dreyev.vlink.ui.linkTitle
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    private val app get() = application as VLinkApp

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        app.startPhoneSensors()
        app.scanner.refreshBonded()
        app.autoConnect()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val needed = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= 31) { add(Manifest.permission.BLUETOOTH_CONNECT); add(Manifest.permission.BLUETOOTH_SCAN) }
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissions.launch(needed.toTypedArray())

        setContent {
            val settings by app.settings.collectAsState()
            VLinkTheme(settings.theme) {
                val dark = C.isDark
                LaunchedEffect(dark) {
                    // Dark status/nav bar icons on the light theme, light icons on the dark one.
                    WindowCompat.getInsetsController(window, window.decorView).apply {
                        isAppearanceLightStatusBars = !dark
                        isAppearanceLightNavigationBars = !dark
                    }
                }
                LaunchedEffect(settings.keepScreenOn) {
                    if (settings.keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
                Box(Modifier.fillMaxSize().background(C.bg).safeDrawingPadding()) { Dashboard(app) }
            }
        }
    }

}

@Composable
private fun Dashboard(app: VLinkApp) {
    val s by app.store.state.collectAsState()
    val settings by app.settings.collectAsState()
    val recording by app.trips.recordingFile.collectAsState()
    var tab by rememberSaveable { mutableStateOf(Tab.DRIVE) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(1_000); now = System.currentTimeMillis() } }
    val u = settings.units
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    if (showSettings) {
        BackHandler { showSettings = false }
        SettingsScreen(app) { showSettings = false }
        return
    }
    val (title, dot) = linkTitle(s)
    val trail = app.location.trail.toList()

    if (landscape) {
        // Landscape: Drive and Tires, Vehicle info lives in the Drive bottom strip.
        val tabs = listOf(Tab.DRIVE, Tab.TIRES)
        if (tab == Tab.VEHICLE) tab = Tab.DRIVE
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LogoChip()
                Column(Modifier.weight(1f)) {
                    androidx.compose.material3.Text(title, color = C.text, fontSize = androidx.compose.ui.unit.TextUnit(14f, androidx.compose.ui.unit.TextUnitType.Sp))
                    androidx.compose.material3.Text(linkSubtitle(s), color = C.textFaint, fontSize = androidx.compose.ui.unit.TextUnit(11f, androidx.compose.ui.unit.TextUnitType.Sp), maxLines = 1)
                }
                LandscapeStatus(s)
                TopTabs(tabs, tab) { tab = it }
                SettingsButton { showSettings = true }
            }
            Box(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())) {
                if (tab == Tab.DRIVE) DriveLandscape(s, u) else TiresLandscape(s, u, now)
            }
        }
    } else {
        Column(Modifier.fillMaxSize()) {
            when (tab) {
                Tab.DRIVE -> ScreenHeader(title, linkSubtitle(s), dot, { showSettings = true }) { EnginePill(s) }
                Tab.TIRES -> ScreenHeader("Tires & chassis", if (s.link.state == LinkState.CONNECTED) "TPMS via ${s.profileName ?: "manufacturer PIDs"}" else linkSubtitle(s), dot, { showSettings = true }) { EnginePill(s) }
                Tab.VEHICLE -> ScreenHeader(
                    "Vehicle & GPS",
                    listOfNotNull(s.link.protocol, s.batteryV?.value?.let { "%.1f V".format(it) }, if (s.link.simulated) "simulated" else null).joinToString(" · ").ifEmpty { linkSubtitle(s) },
                    dot, { showSettings = true },
                ) { RecordButton(recording != null) { app.trips.toggle() } }
            }
            Box(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())) {
                when (tab) {
                    Tab.DRIVE -> DriveScreen(s, u, now)
                    Tab.TIRES -> TiresScreen(s, u, now)
                    Tab.VEHICLE -> VehicleScreen(s, u, trail)
                }
            }
            BottomTabs(Tab.entries, tab) { tab = it }
        }
    }
}
