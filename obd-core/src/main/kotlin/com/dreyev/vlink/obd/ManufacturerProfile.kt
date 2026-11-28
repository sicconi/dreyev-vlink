package com.dreyev.vlink.obd

import org.json.JSONArray
import org.json.JSONObject

/** Signals that only manufacturer-specific PIDs can provide. */
enum class MfrSignal(val id: String) {
    BRAKE_PEDAL("brakePedal"),             // boolean (non-zero = pressed)
    BRAKE_PRESSURE("brakePressure"),       // bar
    STEERING_ANGLE("steeringAngle"),       // degrees, + = right
    STEERING_ANGLE_ALT("steeringAngleAlt"), // degrees; second source, used only while steeringAngle is missing
    WHEEL_SPEED_FL("wheelSpeedFL"), WHEEL_SPEED_FR("wheelSpeedFR"),   // km/h
    WHEEL_SPEED_RL("wheelSpeedRL"), WHEEL_SPEED_RR("wheelSpeedRR"),
    TIRE_PRESSURE_FL("tirePressureFL"), TIRE_PRESSURE_FR("tirePressureFR"), // kPa (gauge)
    TIRE_PRESSURE_RL("tirePressureRL"), TIRE_PRESSURE_RR("tirePressureRR"),
    TIRE_TEMP_FL("tireTempFL"), TIRE_TEMP_FR("tireTempFR"),               // °C
    TIRE_TEMP_RL("tireTempRL"), TIRE_TEMP_RR("tireTempRR"),
    TURN_LEFT("turnLeft"), TURN_RIGHT("turnRight"),                       // boolean
    BELT_DRIVER("seatbeltDriver"), BELT_PASSENGER("seatbeltPassenger"),   // boolean, non-zero = buckled
    ACCEL_LONG("accelLong"), ACCEL_LAT("accelLat"),                       // g (or m/s² with unit "m/s2")
    ODOMETER("odometer"),                                                 // km
    VEHICLE_SPEED("vehicleSpeed"),                                        // km/h (when PID 0D is missing)
    AMBIENT_TEMP("ambientTemp"),                                          // °C (when PID 46 is missing)
    ACCEL_PEDAL("accelPedal"),                                            // %
    // Electric vehicles
    READY("ready"),                                                       // boolean: car is on / ready to drive
    MOTOR_RPM_FRONT("motorRpmFront"), MOTOR_RPM_REAR("motorRpmRear"),     // rpm
    BATTERY_SOC("batterySoc"),
    REAR_MOTOR_POWER("rearMotorPower"),                                   // kW, + traction / − regen                                            // % (dashboard SOC)
    BATTERY_CURRENT("batteryCurrent"),                                    // A, + = discharging
    BATTERY_VOLTAGE("batteryVoltage"),                                    // V
    AUX_BATTERY_VOLTAGE("auxBatteryVoltage");                             // V (12 V battery)

    companion object {
        fun byId(id: String) = entries.firstOrNull { it.id.equals(id, true) }
    }
}

/**
 * One manufacturer PID. Example (JSON):
 * { "signal":"brakePressure", "header":"7B0", "request":"22 1234",
 *   "formula":"(A*256+B)/100", "unit":"bar", "intervalMs":150 }
 * [unit] lets a profile give values in other units; they are converted to the internal metric unit.
 */
data class MfrPid(
    val signal: MfrSignal,
    val header: String?,
    val request: String,
    val formula: Formula,
    val responsePrefix: String? = null,
    val unit: String? = null,
    val intervalMs: Long = 500,
) {
    fun toMetric(v: Double): Double = when (unit?.lowercase()) {
        null, "", "bar", "kpa", "c", "°c", "km/h", "kmh", "deg", "g", "km", "bool", "%", "rpm", "a", "v", "kw" -> v
        "psi" -> if (signal.id.startsWith("tirePressure")) v / 0.1450377 else v / 14.50377
        "mbar" -> if (signal.id.startsWith("tirePressure")) v / 10.0 else v / 1000.0
        "f", "°f" -> (v - 32.0) * 5.0 / 9.0
        "mph" -> v * 1.609344
        "m/s2", "m/s²" -> v / 9.80665
        "mi" -> v * 1.609344
        else -> v
    }
}

data class BrakeGeometry(
    /** Total caliper piston area per front corner, cm² (single 57 mm piston ≈ 25.5 cm²). */
    val frontPistonAreaCm2: Double = 25.5,
    /** Total caliper piston area per rear corner, cm² (single 38 mm piston ≈ 11.3 cm²). */
    val rearPistonAreaCm2: Double = 11.3,
)

/**
 * A per-make (or per-car) set of manufacturer PIDs, loaded from JSON so new cars can be
 * added without rebuilding the app. See app/src/main/assets/profiles/README.md.
 */
data class ManufacturerProfile(
    val id: String,
    val name: String,
    val notes: String = "",
    val wmi: List<String> = emptyList(),
    val pids: List<MfrPid> = emptyList(),
    val brakes: BrakeGeometry = BrakeGeometry(),
    /** Extra AT commands to send once after init, e.g. "ATSP6". */
    val initCommands: List<String> = emptyList(),
    /** "ice" (default) or "ev". EV profiles show power, SOC and READY instead of RPM, load and ENGINE. */
    val powertrain: String = "ice",
) {
    val isEv get() = powertrain.equals("ev", true)

    fun matchesVin(vin: String?): Boolean = vin != null && wmi.any { vin.uppercase().startsWith(it.uppercase()) }

    companion object {
        val NONE = ManufacturerProfile("none", "Standard OBD-II only", "No manufacturer PIDs; MFR gauges show N/A.")

        fun fromJson(text: String): ManufacturerProfile {
            val o = JSONObject(text)
            val pids = mutableListOf<MfrPid>()
            val arr = o.optJSONArray("signals") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val p = arr.getJSONObject(i)
                val sig = MfrSignal.byId(p.getString("signal"))
                    ?: throw IllegalArgumentException("Unknown signal '${p.getString("signal")}'")
                pids += MfrPid(
                    signal = sig,
                    header = p.optString("header").ifBlank { null },
                    request = p.getString("request").replace(" ", "").uppercase(),
                    formula = Formula(p.getString("formula")),
                    responsePrefix = p.optString("responsePrefix").ifBlank { null }?.replace(" ", "")?.uppercase(),
                    unit = p.optString("unit").ifBlank { null },
                    intervalMs = p.optLong("intervalMs", 500),
                )
            }
            val b = o.optJSONObject("brakes")
            val wmiArr = o.optJSONArray("wmi") ?: JSONArray()
            val init = o.optJSONArray("initCommands") ?: JSONArray()
            return ManufacturerProfile(
                id = o.getString("id"),
                name = o.getString("name"),
                notes = o.optString("notes"),
                wmi = (0 until wmiArr.length()).map { wmiArr.getString(it) },
                pids = pids,
                brakes = if (b == null) BrakeGeometry() else BrakeGeometry(
                    b.optDouble("frontPistonAreaCm2", 25.5), b.optDouble("rearPistonAreaCm2", 11.3),
                ),
                initCommands = (0 until init.length()).map { init.getString(it) },
                powertrain = o.optString("powertrain", "ice"),
            )
        }
    }
}
