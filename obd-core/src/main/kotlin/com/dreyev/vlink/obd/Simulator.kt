package com.dreyev.vlink.obd

import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * A fake ELM327 that answers like a real adapter on a CAN car. Used for "Demo mode"
 * (try the app without a car) and for unit tests. It runs the full parsing and polling
 * stack, so if the demo works, the data path works.
 */
class SimulatedElm327(
    private val clock: () -> Long = System::currentTimeMillis,
    private val latencyMs: Long = 25,
    /** Answer like an electric car: no engine RPM, fuel type PID 51 = electric. */
    private val electric: Boolean = false,
) : ObdTransport() {
    override val name = "VLink simulator"
    override val kind = "Simulator"
    private var header = "7DF"
    private var echo = true
    private val t0 = clock()

    override suspend fun open() {}
    override suspend fun close() { markClosed() }

    override suspend fun write(bytes: ByteArray) {
        val cmd = String(bytes, Charsets.US_ASCII).trim().uppercase().replace(" ", "")
        if (latencyMs > 0) delay(latencyMs)
        val reply = respond(cmd)
        val text = (if (echo) "$cmd\r" else "") + reply + "\r\r>"
        deliver(text.toByteArray(Charsets.US_ASCII))
    }

    /** Simulated vehicle at time [t] seconds (same motion as the approved mockup). */
    data class Sim(
        val speedKmh: Double, val rpm: Double, val pedal: Double, val load: Double,
        val brakeBar: Double, val steer: Double, val latG: Double, val longG: Double,
        val turnL: Boolean, val turnR: Boolean, val beltP: Boolean,
    )

    fun sim(t: Double): Sim {
        val brakeRaw = max(0.0, (-sin(t * 0.25 + 1.2) - 0.55) / 0.45)
        val mph = max(0.0, 42 + 18 * sin(t * 0.25) + 3 * sin(t * 1.1))
        var thr = (28 + 34 * sin(t * 0.25 + 1.2) + 8 * sin(t * 1.7)).coerceIn(0.0, 100.0)
        if (brakeRaw > 0) thr = 0.0
        val rpm = 800 + mph * 36 + 250 * sin(t * 0.9) + thr * 6
        val steer = 110 * sin(t * 0.6)
        val blink = (t * 2.5).toInt() % 2 == 0
        return Sim(
            speedKmh = mph * 1.609344, rpm = rpm, pedal = thr, load = 15 + thr * 0.75,
            brakeBar = brakeRaw * 65, steer = steer, latG = 0.38 * sin(t * 0.6),
            longG = 0.18 * cos(t * 0.25) - brakeRaw * 0.45,
            turnL = steer < -45 && blink, turnR = steer > 45 && blink, beltP = sin(t * 0.07) > -0.3,
        )
    }

    private fun hx(vararg v: Int) = v.joinToString("") { String.format("%02X", it and 0xFF) }
    private fun u16(v: Int) = intArrayOf((v shr 8) and 0xFF, v and 0xFF)

    private fun respond(cmd: String): String {
        val t = (clock() - t0) / 1000.0 + 3.0
        val s = sim(t)
        if (cmd.startsWith("AT")) {
            return when {
                cmd == "ATZ" -> { echo = true; header = "7DF"; "\r\rELM327 v1.5" }
                cmd == "ATE0" -> { echo = false; "OK" }
                cmd == "ATI" -> "ELM327 v1.5"
                cmd == "ATRV" -> "14.2V"
                cmd == "ATDPN" -> "A6"
                cmd.startsWith("ATSH") -> { header = cmd.removePrefix("ATSH"); "OK" }
                else -> "OK"
            }
        }
        val mode = cmd.take(2)
        val pid = cmd.drop(2)
        if (header == "7DF" && mode == "01") {
            return when (pid) {
                "00" -> if (electric) "4100182B8013" else "4100183B8013"   // 04,05,(0C),0D,0F,10,11,1C,1F,20
                "51" -> if (electric) "415108" else "415101"
                "0C" -> if (electric) "NO DATA" else "410C" + hx(*u16((s.rpm * 4).roundToInt()))
                "20" -> "412080000001"   // 21 … 40
                "40" -> "414044808001"   // 42,46,49,51 … 60
                "60" -> "416000000001"
                "80" -> "418000000001"
                "A0" -> "41A004000000"   // A6
                "0D" -> "410D" + hx(min(255, s.speedKmh.roundToInt()))
                "04" -> "4104" + hx((s.load * 2.55).roundToInt())
                "11" -> "4111" + hx((s.pedal * 2.55).roundToInt())
                "49" -> "4149" + hx((s.pedal * 2.55).roundToInt())
                "46" -> "4146" + hx(23 + 40)
                "1F" -> "411F" + hx(*u16((761 + t).toInt()))
                "A6" -> {
                    val km10 = ((77591.4 + t * 67.6 / 3600) * 10).toLong()
                    "41A6" + hx((km10 shr 24).toInt(), (km10 shr 16).toInt(), (km10 shr 8).toInt(), km10.toInt())
                }
                else -> "NO DATA"
            }
        }
        if (header == "7DF" && cmd == "0902") {
            return "014\r0:490201314847\r1:434D3832363333\r2:41303034333532"
        }
        if (cmd == "3E00" && (header == "7E0" || header == "7B0" || header == "750")) return "7E00"
        if (mode == "22" && (header == "7E0" || header == "7B0" || header == "750")) {
            val ws = { k: Double -> "62$pid" + hx(*u16((s.speedKmh * k * 100).roundToInt())) }
            return when (pid) {
                "F001" -> "62F001" + hx(*u16((s.brakeBar * 100).roundToInt()))
                "F002" -> "62F002" + hx(if (s.brakeBar > 1.3) 1 else 0)
                "F003" -> "62F003" + hx(*u16((s.steer * 10).roundToInt() and 0xFFFF))
                "F010" -> ws(1 + s.latG * 0.015)
                "F011" -> ws(1 - s.latG * 0.015)
                "F012" -> ws(1 + s.latG * 0.012)
                "F013" -> ws(1 - s.latG * 0.012)
                "F020" -> "62F020" + hx(*u16(((35.2 + 0.1 * sin(t * 0.05)) / 0.1450377 * 10).roundToInt()))
                "F021" -> "62F021" + hx(*u16(((35.0 + 0.1 * sin(t * 0.04)) / 0.1450377 * 10).roundToInt()))
                "F022" -> "62F022" + hx(*u16((30.6 / 0.1450377 * 10).roundToInt()))
                "F023" -> "62F023" + hx(*u16((34.9 / 0.1450377 * 10).roundToInt()))
                "F030" -> "62F030" + hx(31 + 40)
                "F031" -> "62F031" + hx(32 + 40)
                "F032" -> "62F032" + hx(30 + 40)
                "F033" -> "62F033" + hx(31 + 40)
                "F040" -> "62F040" + hx((if (s.turnL) 1 else 0) or (if (s.turnR) 2 else 0))
                "F041" -> "62F041" + hx(1 or (if (s.beltP) 2 else 0))
                "F050" -> "62F050" + hx(*u16((s.longG * 1000).roundToInt() and 0xFFFF))
                "F051" -> "62F051" + hx(*u16((s.latG * 1000).roundToInt() and 0xFFFF))
                else -> "7F2231"
            }
        }
        return "NO DATA"
    }

    companion object {
        /** Profile that matches what the simulator answers (also a worked example of the JSON format). */
        const val DEMO_PROFILE_JSON = """
{
  "id": "demo",
  "name": "Demo car (simulator)",
  "notes": "Matches the built-in simulator. Use as a worked example for writing a real profile.",
  "wmi": ["1HG"],
  "brakes": { "frontPistonAreaCm2": 25.5, "rearPistonAreaCm2": 11.3 },
  "signals": [
    { "signal": "brakePressure",   "header": "7B0", "request": "22F001", "formula": "(A*256+B)/100", "unit": "bar", "intervalMs": 150 },
    { "signal": "brakePedal",      "header": "7B0", "request": "22F002", "formula": "A", "intervalMs": 200 },
    { "signal": "steeringAngle",   "header": "7B0", "request": "22F003", "formula": "signed(A*256+B,16)/10", "unit": "deg", "intervalMs": 150 },
    { "signal": "wheelSpeedFL",    "header": "7B0", "request": "22F010", "formula": "(A*256+B)/100", "unit": "km/h", "intervalMs": 500 },
    { "signal": "wheelSpeedFR",    "header": "7B0", "request": "22F011", "formula": "(A*256+B)/100", "unit": "km/h", "intervalMs": 500 },
    { "signal": "wheelSpeedRL",    "header": "7B0", "request": "22F012", "formula": "(A*256+B)/100", "unit": "km/h", "intervalMs": 500 },
    { "signal": "wheelSpeedRR",    "header": "7B0", "request": "22F013", "formula": "(A*256+B)/100", "unit": "km/h", "intervalMs": 500 },
    { "signal": "tirePressureFL",  "header": "750", "request": "22F020", "formula": "(A*256+B)/10", "unit": "kPa", "intervalMs": 5000 },
    { "signal": "tirePressureFR",  "header": "750", "request": "22F021", "formula": "(A*256+B)/10", "unit": "kPa", "intervalMs": 5000 },
    { "signal": "tirePressureRL",  "header": "750", "request": "22F022", "formula": "(A*256+B)/10", "unit": "kPa", "intervalMs": 5000 },
    { "signal": "tirePressureRR",  "header": "750", "request": "22F023", "formula": "(A*256+B)/10", "unit": "kPa", "intervalMs": 5000 },
    { "signal": "tireTempFL",      "header": "750", "request": "22F030", "formula": "A-40", "unit": "C", "intervalMs": 10000 },
    { "signal": "tireTempFR",      "header": "750", "request": "22F031", "formula": "A-40", "unit": "C", "intervalMs": 10000 },
    { "signal": "tireTempRL",      "header": "750", "request": "22F032", "formula": "A-40", "unit": "C", "intervalMs": 10000 },
    { "signal": "tireTempRR",      "header": "750", "request": "22F033", "formula": "A-40", "unit": "C", "intervalMs": 10000 },
    { "signal": "turnLeft",        "header": "7E0", "request": "22F040", "formula": "bit(A,0)", "intervalMs": 200 },
    { "signal": "turnRight",       "header": "7E0", "request": "22F040", "formula": "bit(A,1)", "intervalMs": 200 },
    { "signal": "seatbeltDriver",  "header": "7E0", "request": "22F041", "formula": "bit(A,0)", "intervalMs": 2000 },
    { "signal": "seatbeltPassenger","header": "7E0", "request": "22F041", "formula": "bit(A,1)", "intervalMs": 2000 },
    { "signal": "accelLong",       "header": "7B0", "request": "22F050", "formula": "signed(A*256+B,16)/1000", "unit": "g", "intervalMs": 150 },
    { "signal": "accelLat",        "header": "7B0", "request": "22F051", "formula": "signed(A*256+B,16)/1000", "unit": "g", "intervalMs": 150 }
  ]
}
"""
    }
}
