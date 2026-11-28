package com.dreyev.vlink

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
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
import com.dreyev.vlink.ui.ThemeMode
import com.dreyev.vlink.ui.TiresLandscape
import com.dreyev.vlink.ui.TiresScreen
import com.dreyev.vlink.ui.VLinkTheme
import com.dreyev.vlink.ui.MapScreen
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

    private fun portrait(tab: Tab, u: UnitPrefs = UnitPrefs(), theme: ThemeMode = ThemeMode.DARK) {
        val s = TestStates.simulated()
        val now = System.currentTimeMillis()
        paparazzi.snapshot {
            VLinkTheme(theme) {
                Column(Modifier.fillMaxSize().background(C.bg)) {
                    val (title, dot) = linkTitle(s)
                    ScreenHeader(if (tab == Tab.DRIVE) title else tab.title, linkSubtitle(s), dot, {}) { EnginePill(s) }
                    Box(Modifier.fillMaxWidth().weight(1f)) {
                        when (tab) {
                            Tab.DRIVE -> DriveScreen(s, u, now)
                            Tab.TIRES -> TiresScreen(s, u, now)
                            Tab.VEHICLE -> VehicleScreen(s, u, TestStates.trail)
                            Tab.MAP -> MapScreen(s, u, TestStates.trail) {}
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
    @Test fun mapPortrait() = portrait(Tab.MAP)
    @Test fun driveLight() = portrait(Tab.DRIVE, theme = ThemeMode.LIGHT)
    @Test fun tiresLight() = portrait(Tab.TIRES, theme = ThemeMode.LIGHT)
    @Test fun vehicleLight() = portrait(Tab.VEHICLE, theme = ThemeMode.LIGHT)
    @Test fun driveMetric() = portrait(Tab.DRIVE, UnitPrefs(UnitSystem.METRIC, TempUnit.C))

    @Test fun driveIoniq5() {
        val s = TestStates.ev(TestStates.simulated())
        paparazzi.snapshot {
            VLinkTheme {
                Column(Modifier.fillMaxSize().background(C.bg)) {
                    ScreenHeader("VLink connected", "Bluetooth Classic · ELM327 · 14 Hz", C.green, {}) { EnginePill(s) }
                    Box(Modifier.fillMaxWidth().weight(1f)) { DriveScreen(s, UnitPrefs(), System.currentTimeMillis()) }
                    BottomTabs(Tab.entries, Tab.DRIVE) {}
                }
            }
        }
    }

    @Test fun driveIoniq5Regen() {
        val t = System.currentTimeMillis()
        val s = TestStates.ev(TestStates.simulated()).copy(
            hvPowerKw = Reading(-38.0, Source.MFR, "7E4", t), rearMotorKw = Reading(-30.0, Source.MFR, "7E3", t),
            motorRpmFront = Reading(0.0, Source.MFR, "7E4", t),
        )
        paparazzi.snapshot {
            VLinkTheme {
                Column(Modifier.fillMaxSize().background(C.bg)) {
                    ScreenHeader("VLink connected", "Bluetooth Classic · ELM327 · 14 Hz", C.green, {}) { EnginePill(s) }
                    Box(Modifier.fillMaxWidth().weight(1f)) { DriveScreen(s, UnitPrefs(), t) }
                    BottomTabs(Tab.entries, Tab.DRIVE) {}
                }
            }
        }
    }

    @Test fun driveLandscapeIoniq5() {
        paparazzi.unsafeUpdateConfig(DeviceConfig.PIXEL_5.copy(orientation = ScreenOrientation.LANDSCAPE))
        val s = TestStates.ev(TestStates.simulated())
        paparazzi.snapshot { VLinkTheme { Box(Modifier.fillMaxSize().background(C.bg)) { DriveLandscape(s, UnitPrefs()) } } }
    }

    @Test fun driveLandscape() {
        paparazzi.unsafeUpdateConfig(DeviceConfig.PIXEL_5.copy(orientation = ScreenOrientation.LANDSCAPE))
        val s = TestStates.simulated()
        paparazzi.snapshot { VLinkTheme { Box(Modifier.fillMaxSize().background(C.bg)) { DriveLandscape(s, UnitPrefs()) } } }
    }

    @Test fun tiresLandscape() {
        paparazzi.unsafeUpdateConfig(DeviceConfig.PIXEL_5.copy(orientation = ScreenOrientation.LANDSCAPE))
        val s = TestStates.simulated()
        paparazzi.snapshot { VLinkTheme { Box(Modifier.fillMaxSize().background(C.bg)) { TiresLandscape(s, UnitPrefs(), System.currentTimeMillis()) } } }
    }
}
