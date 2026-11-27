package com.dreyev.vlink.obd

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * Connects to the adapter, discovers what the car supports, then polls PIDs in a
 * priority schedule. An ELM327 over Bluetooth manages ~10–20 requests/s in total, so
 * speed/RPM/pedals/brake are polled most often and VIN/odometer/temperature rarely.
 */
class ObdPoller(
    private val elm: Elm327,
    private val store: VehicleStore,
    private val transportName: String,
    private val transportKind: String,
    private val simulated: Boolean,
    /** Chooses the manufacturer profile once the VIN is known (null VIN if unreadable). */
    private val pickProfile: (vin: String?) -> ManufacturerProfile,
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {},
) {
    private class Item(val key: String, var intervalMs: Long, val action: suspend () -> ObdResult) {
        var lastRun = 0L
        var misses = 0
        val baseInterval = intervalMs
    }

    private val accel = SpeedDerivative()
    private var supported: Set<Int> = emptySet()
    private var profile: ManufacturerProfile = ManufacturerProfile.NONE
    private var queryCount = 0
    private var rateWindowStart = 0L

    suspend fun run() {
        store.updateLink { it.copy(state = LinkState.INITIALIZING, deviceName = transportName, transport = transportKind, simulated = simulated, message = "Initializing adapter…") }
        val info = elm.initialize()
        log("ELM ${info.version}, ${info.protocol}, ${info.voltage} V")
        info.voltage?.let { v -> store.update { it.copy(batteryV = Reading(v, Source.OBD, "ATRV", clock())) } }

        supported = discoverSupported()
        log("supported PIDs: ${supported.sorted().joinToString { StandardPids.hex(it) }}")

        val vin = readVin()
        profile = pickProfile(vin)
        for (c in profile.initCommands) elm.command(c)
        store.update { it.copy(profileName = profile.name) }
        store.updateLink {
            it.copy(state = LinkState.CONNECTED, elmVersion = info.version, protocol = info.protocol, message = null)
        }

        val items = buildSchedule()
        rateWindowStart = clock()
        while (currentCoroutineContext().isActive) {
            val now = clock()
            val due = items.filter { now - it.lastRun >= it.intervalMs }
                .maxByOrNull { (now - it.lastRun).toDouble() / it.intervalMs }
            if (due == null) { delay(10); continue }
            due.lastRun = now
            val r = due.action()
            queryCount++
            when (r) {
                is ObdResult.Data -> { due.misses = 0; due.intervalMs = due.baseInterval }
                else -> {
                    due.misses++
                    // After 3 misses, treat as unsupported but re-check every 30 s.
                    if (due.misses >= 3) due.intervalMs = maxOf(due.baseInterval, 30_000)
                }
            }
            if (now - rateWindowStart >= 2_000) {
                val hz = queryCount * 1000.0 / (now - rateWindowStart)
                store.updateLink { it.copy(pollHz = hz) }
                queryCount = 0; rateWindowStart = now
            }
        }
    }

    private suspend fun discoverSupported(): Set<Int> {
        val out = mutableSetOf<Int>()
        var base = 0x00
        while (base <= 0xA0) {
            val r = elm.query("01" + StandardPids.hex(base))
            if (r !is ObdResult.Data) break
            val s = StandardPids.supported(base, r.bytes)
            out += s
            if (base + 0x20 !in s) break
            base += 0x20
        }
        return out
    }

    private suspend fun readVin(): String? {
        val r = elm.query("0902", timeoutMs = 3_000)
        val vin = (r as? ObdResult.Data)?.let { Vin.decode(it.bytes) }
        if (vin != null) store.update { it.copy(vin = Reading(vin, Source.OBD, "09 02", clock())) }
        return vin
    }

    private fun std(pid: Int, intervalMs: Long, apply: (IntArray, Long) -> Unit): Item? {
        if (supported.isNotEmpty() && pid !in supported) return null
        return Item("01" + StandardPids.hex(pid), intervalMs) {
            val r = elm.query("01" + StandardPids.hex(pid))
            if (r is ObdResult.Data && r.bytes.isNotEmpty()) {
                try { apply(r.bytes, clock()) } catch (e: IndexOutOfBoundsException) { log("short reply for ${StandardPids.hex(pid)}") }
            }
            r
        }
    }

    private fun buildSchedule(): List<Item> {
        val items = mutableListOf<Item>()
        std(StandardPids.SPEED, 100) { b, t ->
            val kmh = StandardPids.speedKmh(b)
            val g = accel.add(kmh, t)
            store.update {
                it.copy(
                    speedKmh = Reading(kmh, Source.OBD, "0D", t),
                    speedDerivedLongG = g?.let { v -> Reading(v, Source.EST, "Δ speed", t) } ?: it.speedDerivedLongG,
                )
            }
        }?.let(items::add)

        // RPM also drives engine on/off.
        items += Item("010C", 150) {
            val r = elm.query("010C")
            val t = clock()
            when (r) {
                is ObdResult.Data -> if (r.bytes.size >= 2) {
                    val rpm = StandardPids.rpm(r.bytes)
                    store.update {
                        it.copy(
                            rpm = Reading(rpm, Source.OBD, "0C", t),
                            engine = Reading(if (rpm > 300) EngineState.ON else EngineState.OFF, Source.OBD, "0C", t),
                        )
                    }
                }
                is ObdResult.NoData -> store.update { it.copy(engine = Reading(EngineState.OFF, Source.OBD, "no ECU reply", t)) }
                is ObdResult.Failure -> Unit
            }
            r
        }

        val pedalPid = if (supported.isEmpty() || StandardPids.PEDAL_D in supported) StandardPids.PEDAL_D else StandardPids.THROTTLE
        std(pedalPid, 150) { b, t ->
            store.update { it.copy(pedalPct = Reading(StandardPids.percent(b), Source.OBD, StandardPids.hex(pedalPid), t)) }
        }?.let(items::add)
        std(StandardPids.LOAD, 400) { b, t ->
            store.update { it.copy(engineLoadPct = Reading(StandardPids.percent(b), Source.OBD, "04", t)) }
        }?.let(items::add)
        std(StandardPids.RUNTIME, 1_000) { b, t ->
            store.update { it.copy(runTimeS = Reading(StandardPids.runtimeS(b), Source.OBD, "1F", t)) }
        }?.let(items::add)
        std(StandardPids.AMBIENT, 10_000) { b, t ->
            store.update { it.copy(ambientC = Reading(StandardPids.ambientC(b), Source.OBD, "46", t)) }
        }?.let(items::add)

        val hasMfrOdo = profile.pids.any { it.signal == MfrSignal.ODOMETER }
        if (!hasMfrOdo || StandardPids.ODOMETER in supported) {
            std(StandardPids.ODOMETER, 30_000) { b, t ->
                store.update { it.copy(odometerKm = Reading(StandardPids.odometerKm(b), Source.OBD, "A6", t)) }
            }?.let(items::add)
        }

        items += Item("ATRV", 5_000) {
            val v = try { elm.voltage() } catch (e: ElmTimeoutException) { null }
            if (v != null) {
                store.update { it.copy(batteryV = Reading(v, Source.OBD, "ATRV", clock())) }
                ObdResult.Data(intArrayOf())
            } else ObdResult.NoData("no voltage")
        }

        for (p in profile.pids) {
            if (p.signal == MfrSignal.ODOMETER && StandardPids.ODOMETER in supported) continue
            items += Item("MFR ${p.signal.id}", p.intervalMs) {
                val r = elm.query(p.request, header = p.header, prefix = p.responsePrefix)
                if (r is ObdResult.Data) {
                    try {
                        if (r.bytes.size >= p.formula.bytesNeeded) applyMfr(p, p.toMetric(p.formula.eval(r.bytes)), clock())
                    } catch (e: Exception) { log("formula ${p.signal.id}: ${e.message}") }
                }
                r
            }
        }
        return items
    }

    private fun applyMfr(p: MfrPid, v: Double, t: Long) {
        val tag = p.header ?: ""
        fun num() = Reading(v, Source.MFR, tag, t)
        fun bool() = Reading(v != 0.0, Source.MFR, tag, t)
        when (p.signal) {
            MfrSignal.BRAKE_PEDAL -> store.update { it.copy(brakePedalOn = bool()) }
            MfrSignal.BRAKE_PRESSURE -> {
                val front = BrakeEstimator.caliperKn(v, profile.brakes.frontPistonAreaCm2)
                val rear = BrakeEstimator.caliperKn(v, profile.brakes.rearPistonAreaCm2)
                store.update { s ->
                    s.copy(
                        brakePressureBar = num(),
                        caliperFrontKn = Reading(front, Source.EST, "", t),
                        caliperRearKn = Reading(rear, Source.EST, "", t),
                        // If the car has no brake switch PID, infer pedal from pressure.
                        brakePedalOn = if (profile.pids.none { it.signal == MfrSignal.BRAKE_PEDAL })
                            Reading(v > 2.0, Source.EST, "from pressure", t) else s.brakePedalOn,
                        tires = s.tires.mapValues { (c, ts) ->
                            ts.copy(caliperForceKn = Reading(if (c == Corner.FL || c == Corner.FR) front else rear, Source.EST, "", t))
                        },
                    )
                }
            }
            MfrSignal.STEERING_ANGLE -> store.update { it.copy(steeringDeg = num()) }
            MfrSignal.WHEEL_SPEED_FL -> store.updateTire(Corner.FL) { it.copy(wheelSpeedKmh = num()) }
            MfrSignal.WHEEL_SPEED_FR -> store.updateTire(Corner.FR) { it.copy(wheelSpeedKmh = num()) }
            MfrSignal.WHEEL_SPEED_RL -> store.updateTire(Corner.RL) { it.copy(wheelSpeedKmh = num()) }
            MfrSignal.WHEEL_SPEED_RR -> store.updateTire(Corner.RR) { it.copy(wheelSpeedKmh = num()) }
            MfrSignal.TIRE_PRESSURE_FL -> store.updateTire(Corner.FL) { it.copy(pressureKpa = num()) }
            MfrSignal.TIRE_PRESSURE_FR -> store.updateTire(Corner.FR) { it.copy(pressureKpa = num()) }
            MfrSignal.TIRE_PRESSURE_RL -> store.updateTire(Corner.RL) { it.copy(pressureKpa = num()) }
            MfrSignal.TIRE_PRESSURE_RR -> store.updateTire(Corner.RR) { it.copy(pressureKpa = num()) }
            MfrSignal.TIRE_TEMP_FL -> store.updateTire(Corner.FL) { it.copy(tempC = num()) }
            MfrSignal.TIRE_TEMP_FR -> store.updateTire(Corner.FR) { it.copy(tempC = num()) }
            MfrSignal.TIRE_TEMP_RL -> store.updateTire(Corner.RL) { it.copy(tempC = num()) }
            MfrSignal.TIRE_TEMP_RR -> store.updateTire(Corner.RR) { it.copy(tempC = num()) }
            MfrSignal.TURN_LEFT -> store.update { it.copy(turnLeft = bool()) }
            MfrSignal.TURN_RIGHT -> store.update { it.copy(turnRight = bool()) }
            MfrSignal.BELT_DRIVER -> store.update { it.copy(beltDriver = bool()) }
            MfrSignal.BELT_PASSENGER -> store.update { it.copy(beltPassenger = bool()) }
            MfrSignal.ACCEL_LONG -> store.update {
                val prev = it.carAccel?.value
                it.copy(carAccel = Reading(Accel3(v, prev?.latG ?: Double.NaN, Double.NaN), Source.MFR, "ESC", t))
            }
            MfrSignal.ACCEL_LAT -> store.update {
                val prev = it.carAccel?.value
                it.copy(carAccel = Reading(Accel3(prev?.longG ?: Double.NaN, v, Double.NaN), Source.MFR, "ESC", t))
            }
            MfrSignal.ODOMETER -> store.update { it.copy(odometerKm = num()) }
        }
    }
}
