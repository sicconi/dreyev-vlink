package com.dreyev.vlink

import com.dreyev.vlink.obd.Accel3
import com.dreyev.vlink.obd.Elm327
import com.dreyev.vlink.obd.GpsFix
import com.dreyev.vlink.obd.ManufacturerProfile
import com.dreyev.vlink.obd.ObdPoller
import com.dreyev.vlink.obd.Reading
import com.dreyev.vlink.obd.SimulatedElm327
import com.dreyev.vlink.obd.Source
import com.dreyev.vlink.obd.VehicleState
import com.dreyev.vlink.obd.VehicleStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/** Dashboard states shared by the phone and Android Auto screenshot tests. */
object TestStates {
    fun simulated(): VehicleState = runBlocking {
        val store = VehicleStore()
        val sim = SimulatedElm327(latencyMs = 1)
        val demo = ManufacturerProfile.fromJson(SimulatedElm327.DEMO_PROFILE_JSON)
        val job = launch(Dispatchers.Default) { ObdPoller(Elm327(sim), store, sim.name, sim.kind, true, { _ -> demo }).run() }
        delay(3_500)
        // Wait for powertrain detection too, so the Vehicle card's "Detected:" line is always there
        // (it raced the fixed delay and made vehiclePortrait flaky).
        val until = System.currentTimeMillis() + 8_000
        while (store.state.value.powertrainNote == null && System.currentTimeMillis() < until) delay(100)
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

    val trail = (0 until 60).map { i -> (25.76171 - (60 - i) * 0.00001) to (-80.19180 - (60 - i) * 0.00002) }

    fun ev(s: VehicleState): VehicleState {
        val t = System.currentTimeMillis()
        return s.copy(
            isEv = true, rpm = null, engineLoadPct = null, pedalPct = null, brakePressureBar = null, brakePedalOn = null,
            caliperFrontKn = null, caliperRearKn = null, beltDriver = null, beltPassenger = null, turnLeft = null, turnRight = null,
            carAccel = null, profileName = "Hyundai Ioniq 5 (2022–2024, E-GMP)",
            engine = Reading(com.dreyev.vlink.obd.EngineState.ON, Source.MFR, "READY", t),
            socPct = Reading(78.5, Source.MFR, "7E4", t), hvVoltageV = Reading(712.0, Source.MFR, "7E4", t),
            hvCurrentA = Reading(64.0, Source.MFR, "7E4", t), hvPowerKw = Reading(45.6, Source.MFR, "7E4", t),
            rearMotorKw = Reading(36.0, Source.MFR, "7E3", t),
            motorRpmRear = Reading(4200.0, Source.MFR, "7E4", t), motorRpmFront = Reading(4180.0, Source.MFR, "7E4", t),
            speedKmh = s.speedKmh?.copy(source = Source.MFR, tag = "7B3"),
            tires = s.tires.mapValues { (_, ts) -> ts.copy(wheelSpeedKmh = null, caliperForceKn = null) },
        )
    }
}
