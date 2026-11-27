package com.dreyev.vlink.obd

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Single source of truth for the dashboard. The OBD poller and the phone sensors both
 * write here; the phone UI and the Android Auto module both read [state].
 */
class VehicleStore {
    private val _state = MutableStateFlow(VehicleState())
    val state: StateFlow<VehicleState> = _state.asStateFlow()

    fun update(f: (VehicleState) -> VehicleState) = _state.update(f)

    fun updateLink(f: (LinkInfo) -> LinkInfo) = _state.update { it.copy(link = f(it.link)) }

    fun updateTire(c: Corner, f: (TireState) -> TireState) = _state.update {
        it.copy(tires = it.tires + (c to f(it.tires[c] ?: TireState())))
    }

    /** Clear everything that came from the car (keeps phone GPS / IMU). */
    fun clearCarData(link: LinkInfo = LinkInfo()) = _state.update {
        VehicleState(link = link, phoneAccel = it.phoneAccel, gps = it.gps, gpsAccelMs2 = it.gpsAccelMs2)
    }
}

/** Longitudinal acceleration from the change in OBD speed (EST), lightly smoothed. */
class SpeedDerivative(private val alpha: Double = 0.35) {
    private var lastKmh: Double? = null
    private var lastT: Long = 0
    private var filtered: Double = 0.0

    /** Returns g, or null until two samples exist. */
    fun add(kmh: Double, tMs: Long): Double? {
        val prev = lastKmh
        val dt = (tMs - lastT) / 1000.0
        lastKmh = kmh; lastT = tMs
        if (prev == null || dt <= 0.05 || dt > 3.0) return null
        val g = ((kmh - prev) / 3.6) / dt / 9.80665
        filtered += alpha * (g - filtered)
        return filtered
    }

    fun reset() { lastKmh = null; filtered = 0.0 }
}

object BrakeEstimator {
    /** Clamp force (kN) on one caliper = pressure × total piston area. 1 bar × 1 cm² = 0.01 kN. */
    fun caliperKn(pressureBar: Double, pistonAreaCm2: Double): Double =
        maxOf(0.0, pressureBar) * pistonAreaCm2 * 0.01
}
