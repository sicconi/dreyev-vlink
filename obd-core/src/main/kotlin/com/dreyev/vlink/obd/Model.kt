package com.dreyev.vlink.obd

/**
 * Where a value came from. Every value shown in the UI carries one of these tags.
 *  OBD   = standard SAE J1979 PID
 *  MFR   = manufacturer-specific PID / CAN frame (needs a per-make profile)
 *  PHONE = phone GPS or motion sensors
 *  EST   = calculated from other signals, not measured by the car
 */
enum class Source { OBD, MFR, PHONE, EST }

/** One measured value. [tag] is a short detail shown next to the source, e.g. the PID ("0D"). */
data class Reading<T>(
    val value: T,
    val source: Source,
    val tag: String = "",
    val timeMs: Long = System.currentTimeMillis(),
) {
    fun isFresh(now: Long = System.currentTimeMillis(), maxAgeMs: Long = 2_000): Boolean =
        now - timeMs <= maxAgeMs

    val label: String get() = if (tag.isBlank()) source.name else "${source.name} $tag"
}

enum class Corner(val short: String, val label: String) {
    FL("FL", "Front left"), FR("FR", "Front right"), RL("RL", "Rear left"), RR("RR", "Rear right")
}

/** TPMS for one tire. Pressure in kPa (gauge), temperature in °C. */
data class TireState(
    val pressureKpa: Reading<Double>? = null,
    val tempC: Reading<Double>? = null,
    val wheelSpeedKmh: Reading<Double>? = null,
    val caliperForceKn: Reading<Double>? = null,
)

data class Accel3(val longG: Double, val latG: Double, val vertG: Double)

data class GpsFix(
    val lat: Double,
    val lon: Double,
    val altitudeM: Double?,
    val speedKmh: Double?,
    val headingDeg: Double?,
    val accuracyM: Double?,
    val satellites: Int?,
)

enum class EngineState { ON, OFF, UNKNOWN }

/** What the app learned about the car on connect, used to pick a profile and layout. */
data class CarIdentity(val vin: String?, val isEv: Boolean, val evidence: String = "")

enum class LinkState { DISCONNECTED, CONNECTING, INITIALIZING, CONNECTED, ERROR }

data class LinkInfo(
    val state: LinkState = LinkState.DISCONNECTED,
    val deviceName: String? = null,
    val transport: String? = null,          // "Bluetooth Classic", "BLE", "Simulator"
    val elmVersion: String? = null,
    val protocol: String? = null,
    val pollHz: Double = 0.0,
    val message: String? = null,
    val simulated: Boolean = false,
)

/**
 * The complete picture of the car at one moment. All values are metric internally
 * (km/h, kPa, bar, °C, km, m, g); [Units] converts for display.
 * A null field means "not available" and is shown as N/A.
 */
data class VehicleState(
    val link: LinkInfo = LinkInfo(),

    // Standard OBD
    val speedKmh: Reading<Double>? = null,
    val rpm: Reading<Double>? = null,
    val engineLoadPct: Reading<Double>? = null,
    val pedalPct: Reading<Double>? = null,           // PID 49 (pedal D) or 11 (throttle) fallback
    val ambientC: Reading<Double>? = null,
    val runTimeS: Reading<Long>? = null,
    val odometerKm: Reading<Double>? = null,
    val vin: Reading<String>? = null,
    val batteryV: Reading<Double>? = null,
    val engine: Reading<EngineState>? = null,

    // Manufacturer-specific
    val brakePedalOn: Reading<Boolean>? = null,
    val brakePressureBar: Reading<Double>? = null,
    val steeringDeg: Reading<Double>? = null,        // + = right
    val tires: Map<Corner, TireState> = Corner.entries.associateWith { TireState() },
    val turnLeft: Reading<Boolean>? = null,
    val turnRight: Reading<Boolean>? = null,
    val beltDriver: Reading<Boolean>? = null,         // true = buckled
    val beltPassenger: Reading<Boolean>? = null,
    val carAccel: Reading<Accel3>? = null,            // from ESC / airbag module (vert usually NaN)

    // Phone
    val phoneAccel: Reading<Accel3>? = null,
    val gps: Reading<GpsFix>? = null,
    val gpsAccelMs2: Reading<Double>? = null,

    // Estimated
    val speedDerivedLongG: Reading<Double>? = null,
    val caliperFrontKn: Reading<Double>? = null,
    val caliperRearKn: Reading<Double>? = null,

    // Electric vehicles (MFR)
    val isEv: Boolean = false,
    val motorRpmFront: Reading<Double>? = null,
    val motorRpmRear: Reading<Double>? = null,
    val socPct: Reading<Double>? = null,
    val hvCurrentA: Reading<Double>? = null,
    val hvVoltageV: Reading<Double>? = null,
    val hvPowerKw: Reading<Double>? = null,

    val profileName: String? = null,
    /** e.g. "Electric · fuel type PID 51 = electric" */
    val powertrainNote: String? = null,
) {
    /** Highest motor speed for EVs (front or rear), else engine RPM. */
    val motorRpm: Reading<Double>? get() =
        listOfNotNull(motorRpmFront, motorRpmRear).maxByOrNull { kotlin.math.abs(it.value) }?.let { it.copy(value = kotlin.math.abs(it.value)) }

    /** Best longitudinal acceleration: car ESC first, then phone. */
    fun longitudinal(now: Long = System.currentTimeMillis()): Reading<Double>? =
        pickAxis(now) { it.longG }

    fun lateral(now: Long = System.currentTimeMillis()): Reading<Double>? =
        pickAxis(now) { it.latG }

    /** Vertical: phone only (car modules rarely report it). Value is total g (≈1.0 at rest). */
    fun vertical(): Reading<Double>? = phoneAccel?.let {
        Reading(it.value.vertG, Source.PHONE, "", it.timeMs)
    }

    private fun pickAxis(now: Long, f: (Accel3) -> Double): Reading<Double>? {
        carAccel?.takeIf { it.isFresh(now, 1_500) && !f(it.value).isNaN() }?.let {
            return Reading(f(it.value), Source.MFR, "ESC", it.timeMs)
        }
        phoneAccel?.let { return Reading(f(it.value), Source.PHONE, "", it.timeMs) }
        return null
    }

    /** Engine on/off decision. */
    val engineOn: Boolean get() = engine?.value == EngineState.ON
}
