package com.dreyev.vlink

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.ScreenOrientation
import com.dreyev.vlink.obd.Elm327
import com.dreyev.vlink.obd.GpsFix
import com.dreyev.vlink.obd.ManufacturerProfile
import com.dreyev.vlink.obd.ObdPoller
import com.dreyev.vlink.obd.Accel3
import com.dreyev.vlink.obd.Reading
import com.dreyev.vlink.obd.SimulatedElm327
import com.dreyev.vlink.obd.Source
import com.dreyev.vlink.obd.UnitPrefs
import com.dreyev.vlink.obd.UnitSystem
import com.dreyev.vlink.obd.TempUnit
import com.dreyev.vlink.obd.VehicleState
import com.dreyev.vlink.obd.VehicleStore
import com.dreyev.vlink.ui.BottomTabs
import com.dreyev.vlink.ui.C
import com.dreyev.vlink.ui.DriveLandscape
import com.dreyev.vlink.ui.DriveScreen
import com.dreyev.vlink.ui.EnginePill
import com.dreyev.vlink.ui.ScreenHeader
import com.dreyev.vlink.ui.Tab
import com.dreyev.vlink.ui.TiresLandscape
import com.dreyev.vlink.ui.TiresScreen
import com.dreyev.vlink.ui.VLinkTheme
import com.dreyev.vlink.ui.VehicleScreen
import com.dreyev.vlink.ui.linkSubtitle
import com.dreyev.vlink.ui.linkTitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test

/** Renders each dashboard screen with data that went through the real simulator → parser → poller path. */
class ScreenshotTest {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5, maxPercentDifference = 0.1)

    private fun simulatedState(): VehicleState = runBlocking {
        val store = VehicleStore()
        val sim = SimulatedElm327(latencyMs = 1)
        val demo = ManufacturerProfile.fromJson(SimulatedElm327.DEMO_PROFILE_JSON)
        val job = launch(Dispatchers.Default) { ObdPoller(Elm327(sim), store, sim.name, sim.kind, true, { demo }).run() }
        delay(3_500)
        job.cancel()
        val t = System.currentTimeMillis()
        store.update {
            it.copy(
                phoneAccel = Reading(Accel3(0.12, -0.2, 1.03), Source.PHONE, "IMU", t),
                gps = Reading(GpsFix(25.76171, -80.19180, 3.7, 74.8, 72.0, 3.0, 11), Source.PHONE, "GNSS", t),
                gpsAccelMs2 = Reading(0.9, Source.PHONE, "GNSS", t),
            )
        }
        store.state.value
    }

    private val trail = (0 until 60).map { i -> (25.76171 - (60 - i) * 0.00001) to (-80.19180 - (60 - i) * 0.00002) }

    private fun portrait(tab: Tab, u: UnitPrefs = UnitPrefs()) {
        val s = simulatedState()
        val now = System.currentTimeMillis()
        paparazzi.snapshot {
            VLinkTheme {
                Column(Modifier.fillMaxSize().background(C.bg)) {
                    val (title, dot) = linkTitle(s)
                    ScreenHeader(if (tab == Tab.DRIVE) title else tab.title, linkSubtitle(s), dot, {}) { EnginePill(s) }
                    Box(Modifier.fillMaxWidth().weight(1f)) {
                        when (tab) {
                            Tab.DRIVE -> DriveScreen(s, u, now)
                            Tab.TIRES -> TiresScreen(s, u, now)
                            Tab.VEHICLE -> VehicleScreen(s, u, trail)
                        }
                    }
                    BottomTabs(Tab.entries, tab) {}
                }
            }
        }
    }

    @Test fun drivePortrait() = portrait(Tab.DRIVE)
    @Test fun tiresPortrait() = portrait(Tab.TIRES)
    @Test fun vehiclePortrait() = portrait(Tab.VEHICLE)
    @Test fun driveMetric() = portrait(Tab.DRIVE, UnitPrefs(UnitSystem.METRIC, TempUnit.C))

    @Test fun driveLandscape() {
        paparazzi.unsafeUpdateConfig(DeviceConfig.PIXEL_5.copy(orientation = ScreenOrientation.LANDSCAPE))
        val s = simulatedState()
        paparazzi.snapshot { VLinkTheme { Box(Modifier.fillMaxSize().background(C.bg)) { DriveLandscape(s, UnitPrefs()) } } }
    }

    @Test fun tiresLandscape() {
        paparazzi.unsafeUpdateConfig(DeviceConfig.PIXEL_5.copy(orientation = ScreenOrientation.LANDSCAPE))
        val s = simulatedState()
        paparazzi.snapshot { VLinkTheme { Box(Modifier.fillMaxSize().background(C.bg)) { TiresLandscape(s, UnitPrefs(), System.currentTimeMillis()) } } }
    }
}
