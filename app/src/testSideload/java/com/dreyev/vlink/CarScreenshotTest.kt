package com.dreyev.vlink

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import com.android.resources.ScreenOrientation
import com.dreyev.vlink.auto.CarDashRenderer
import com.dreyev.vlink.auto.CarPage
import com.dreyev.vlink.obd.UnitPrefs
import com.dreyev.vlink.obd.VehicleState
import org.junit.Rule
import org.junit.Test

/** Android Auto screens (sideload flavor only). */
class CarScreenshotTest {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_5, maxPercentDifference = 0.1)

    // ---- Android Auto car screen (1280×720 head unit; right 110 px kept clear for the action strip) ----

    private fun car(page: CarPage, s: VehicleState) {
        paparazzi.unsafeUpdateConfig(
            DeviceConfig.PIXEL_5.copy(
                screenWidth = 1280, screenHeight = 720, xdpi = 160, ydpi = 160,
                density = Density.MEDIUM, orientation = ScreenOrientation.LANDSCAPE,
            )
        )
        val renderer = CarDashRenderer()
        val view = object : android.view.View(paparazzi.context) {
            override fun onDraw(canvas: android.graphics.Canvas) {
                renderer.draw(canvas, android.graphics.Rect(0, 0, width - 110, height), page, s, UnitPrefs(), TestStates.trail)
            }
        }
        paparazzi.snapshot(view)
    }

    @Test fun carDrive() = car(CarPage.DRIVE, TestStates.simulated())
    @Test fun carTires() = car(CarPage.TIRES, TestStates.simulated())
    @Test fun carMap() = car(CarPage.MAP, TestStates.simulated())
    @Test fun carVehicle() = car(CarPage.VEHICLE, TestStates.simulated())
    @Test fun carDriveIoniq5() = car(CarPage.DRIVE, TestStates.ev(TestStates.simulated()))
    @Test fun carVehicleIoniq5() = car(CarPage.VEHICLE, TestStates.ev(TestStates.simulated()))
}
