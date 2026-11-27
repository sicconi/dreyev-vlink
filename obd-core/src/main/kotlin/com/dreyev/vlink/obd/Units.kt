package com.dreyev.vlink.obd

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

enum class UnitSystem { IMPERIAL, METRIC }
enum class TempUnit { F, C }

/** Display preferences. Defaults are US (Imperial, °F). */
data class UnitPrefs(
    val system: UnitSystem = UnitSystem.IMPERIAL,
    val temp: TempUnit = TempUnit.F,
) {
    val imperial get() = system == UnitSystem.IMPERIAL

    // --- conversions from internal metric ---
    fun speed(kmh: Double) = if (imperial) kmh / 1.609344 else kmh
    val speedUnit get() = if (imperial) "mph" else "km/h"

    fun distance(km: Double) = if (imperial) km / 1.609344 else km
    val distanceUnit get() = if (imperial) "mi" else "km"

    fun tirePressure(kpa: Double) = if (imperial) kpa * 0.1450377 else kpa / 100.0
    val tirePressureUnit get() = if (imperial) "psi" else "bar"

    fun brakePressure(bar: Double) = if (imperial) bar * 14.50377 else bar
    val brakePressureUnit get() = if (imperial) "psi" else "bar"
    /** Full-scale value for the brake pressure gauge (≈ 80 bar). */
    val brakePressureMax get() = if (imperial) 1200.0 else 80.0

    fun force(kn: Double) = if (imperial) kn * 224.8089 else kn
    val forceUnit get() = if (imperial) "lbf" else "kN"
    val forceMax get() = if (imperial) 4000.0 else 18.0

    fun accelMs2(ms2: Double) = if (imperial) ms2 * 3.28084 else ms2
    val accelUnit get() = if (imperial) "ft/s²" else "m/s²"

    fun altitude(m: Double) = if (imperial) m * 3.28084 else m
    val altitudeUnit get() = if (imperial) "ft" else "m"

    fun temperature(c: Double) = if (temp == TempUnit.F) c * 9.0 / 5.0 + 32.0 else c
    val tempUnit get() = if (temp == TempUnit.F) "°F" else "°C"

    /** Speedometer full scale. */
    val speedMax get() = if (imperial) 160.0 else 260.0

    // --- formatted strings ---
    fun fmtSpeed(kmh: Double?, decimals: Int = 0) = kmh?.let { fmt(speed(it), decimals) } ?: NA
    fun fmtTemp(c: Double?) = c?.let { "${fmt(temperature(it), 0)} $tempUnit" } ?: NA
    fun fmtTire(kpa: Double?) = kpa?.let { fmt(tirePressure(it), if (imperial) 1 else 2) } ?: NA
    fun fmtDistance(km: Double?) = km?.let { group(distance(it), 1) } ?: NA
    fun fmtAltitude(m: Double?) = m?.let { "${fmt(altitude(it), 0)} $altitudeUnit" } ?: NA
    fun fmtForce(kn: Double?) = kn?.let { "${fmt(force(it), if (imperial) 0 else 1)} $forceUnit" } ?: NA

    companion object {
        const val NA = "N/A"
        fun fmt(v: Double, decimals: Int): String = String.format(Locale.US, "%.${decimals}f", v)
        fun group(v: Double, decimals: Int): String = String.format(Locale.US, "%,.${decimals}f", v)
        fun signedG(g: Double?): String = g?.let { (if (it >= 0) "+" else "") + fmt(it, 2) + " g" } ?: NA
        fun duration(s: Long): String {
            val h = s / 3600; val m = (s / 60) % 60; val sec = s % 60
            return String.format(Locale.US, "%02d:%02d:%02d", h, m, sec)
        }
        fun steering(deg: Double?): String = deg?.let {
            val r = abs(it).roundToLong()
            "$r° " + when { it > 1 -> "right"; it < -1 -> "left"; else -> "center" }
        } ?: NA
        fun compass(deg: Double): String {
            val dirs = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
            return dirs[(((deg % 360) + 360) % 360 / 45.0).roundToLong().toInt() % 8]
        }
    }
}
