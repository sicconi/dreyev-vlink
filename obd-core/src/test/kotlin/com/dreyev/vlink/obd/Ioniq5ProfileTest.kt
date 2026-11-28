package com.dreyev.vlink.obd

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Ioniq5ProfileTest {
    private val profile = ManufacturerProfile.fromJson(
        File("../app/src/main/assets/profiles/hyundai-ioniq5.json").readText()
    )

    private fun value(signal: MfrSignal, bytes: IntArray): Double {
        val p = profile.pids.first { it.signal == signal }
        return p.toMetric(p.formula.eval(bytes))
    }

    @Test fun parsesAndMatchesVin() {
        assertTrue(profile.isEv)
        assertTrue(profile.matchesVin("KM8KN4AE0NU000000"))
        assertEquals("Hyundai", Vin.make("KM8KN4AE0NU000000"))
    }

    @Test fun twoLetterBytes() {
        val b = IntArray(60) { it }
        assertEquals(29.0, Formula("AD").eval(b))
        assertEquals(50.0, Formula("ay").eval(b))
        assertEquals(53.0, Formula("BB").eval(b))
        assertEquals(57, Formula("BD*256+BE").bytesNeeded)
    }

    @Test fun tpmsFromMultiFrameReply() {
        // 62 C0 0B, then A..: E=175 (35.0 psi), F=80 (30 °C) for FL; J/K FR; O/P RL; T/U RR
        val data = IntArray(24)
        data[4] = 175; data[5] = 80; data[9] = 170; data[10] = 81; data[14] = 150; data[15] = 79; data[19] = 174; data[20] = 82
        val hex = (listOf(0x62, 0xC0, 0x0B) + data.toList()).joinToString("") { String.format("%02X", it) }
        // ISO-TP framing as the ELM prints it with headers off
        val first = hex.substring(0, 12)
        val rest = hex.substring(12).chunked(14)
        val raw = "01B\r0:$first\r" + rest.mapIndexed { i, c -> "${i + 1}:$c" }.joinToString("\r")
        val r = Elm327.parse(raw, "62C00B") as ObdResult.Data
        assertEquals(241.3, value(MfrSignal.TIRE_PRESSURE_FL, r.bytes), 0.1)   // 35.0 psi in kPa
        assertEquals(30.0, value(MfrSignal.TIRE_TEMP_FL, r.bytes))
        assertEquals(30.0 / 0.1450377, value(MfrSignal.TIRE_PRESSURE_RL, r.bytes), 0.1)
        assertEquals(32.0, value(MfrSignal.TIRE_TEMP_RR, r.bytes))
    }

    @Test fun batteryAndSteering() {
        val b = IntArray(60)
        b[10] = 0xFF; b[11] = 0x9C          // K,L → -10.0 A (regen)
        b[12] = 0x0E; b[13] = 0x10          // M,N → 360.0 V
        b[51] = 0x01; b[52] = 0x68          // AZ,BA → inverter capacitor 360 V → HV on (READY)
        assertEquals(-10.0, value(MfrSignal.BATTERY_CURRENT, b))
        assertEquals(360.0, value(MfrSignal.BATTERY_VOLTAGE, b))
        assertEquals(1.0, value(MfrSignal.READY, b))
        val s = IntArray(20); s[12] = 0x4C; s[13] = 0xFF   // M=0x4C, N=0xFF → signed(0xFF4C)/10 = -18.0°
        assertEquals(-18.0, value(MfrSignal.STEERING_ANGLE, s))
    }

    // ---- Real 2022 Ioniq 5 captures (OBDb/Hyundai-IONIQ-5 test cases, CC BY-SA 4.0) ----

    /** Turn "738 10 22 62 F0 10 FF FF 00"-style CAN lines (headers on) into the data after 62xxxx. */
    private fun fromCan(vararg lines: String): IntArray {
        val payload = ArrayList<Int>()
        var len = 0
        for (l in lines) {
            val b = Elm327.hexToInts(l.replace(" ", "").drop(3))
            when (b[0] shr 4) {
                0 -> { len = b[0] and 0x0F; payload += b.drop(1) }
                1 -> { len = ((b[0] and 0x0F) shl 8) or b[1]; payload += b.drop(2) }
                2 -> payload += b.drop(1)
            }
        }
        return payload.take(len).drop(3).toIntArray()
    }

    @Test fun chassis730FromCarCapture() {
        val b = fromCan(
            "738102262F010FFFF00", "7382100010409040904", "7382206041300847E0F",
            "73823814E80000001FC", "73824362B052E0D9113",
        )
        assertEquals(1.9, value(MfrSignal.STEERING_ANGLE, b), 1e-6)
        assertEquals(32.28125, value(MfrSignal.WHEEL_SPEED_FL, b), 1e-6)
        assertEquals(32.03125, value(MfrSignal.WHEEL_SPEED_FR, b), 1e-6)
        assertEquals(32.1875, value(MfrSignal.WHEEL_SPEED_RL, b), 1e-6)
        assertEquals(32.28125, value(MfrSignal.WHEEL_SPEED_RR, b), 1e-6)
        assertEquals(-0.04838 / 9.80665, value(MfrSignal.ACCEL_LAT, b), 1e-5)
        assertEquals(0.03461 / 9.80665, value(MfrSignal.ACCEL_LONG, b), 1e-5)
    }

    @Test fun steeringFromMdpsCapture() {
        val b = fromCan("7DC1016620101FFF800", "7DC21007E7CFFFFC9FF", "7DC22FC012401651010", "7DC232701AAAAAAAAAA")
        assertEquals(-5.5, value(MfrSignal.STEERING_ANGLE_ALT, b), 1e-6)
        val c = fromCan("7DC1016620101FFF800", "7DC21007D7C000013FF", "7DC22F4FE67040A1010", "7DC232701AAAAAAAAAA")
        assertEquals(1.9, value(MfrSignal.STEERING_ANGLE_ALT, c), 1e-6)
    }

    @Test fun odometerInMilesOnUsCars() {
        // US cars leave the km field at 0 and fill the miles field.
        val b = fromCan("7CE 10 0F 62 B0 02 D0 00 00", "7CE 21 00 FF AE 00 00 00 01", "7CE 22 B0 70 AA AA AA AA AA")
        assertEquals(110704 * 1.609344, value(MfrSignal.ODOMETER, b), 0.01)
    }

    @Test fun brakeSwitch() {
        assertEquals(1.0, value(MfrSignal.BRAKE_PEDAL, fromCan("7A8100B62B008040108", "7A8210000001000AAAA")))
        assertEquals(0.0, value(MfrSignal.BRAKE_PEDAL, fromCan("7A8100B62B008040108", "7A8210000000000AAAA")))
    }

    @Test fun acceleratorPedal() {
        val b = fromCan("7EA102262E004FC0000", "7EA210000080004E308", "7EA2267039D75050000", "7EA2300000000000000", "7EA24000000FFF80000")
        assertEquals(4.44336, value(MfrSignal.ACCEL_PEDAL, b), 1e-4)
    }

    @Test fun readyFromInverterVoltage() {
        val b = fromCan(
            "7EC103E620101EFFBE7", "7EC21EF1A0000000000", "7EC220028182F222021", "7EC23212121200047AC",
            "7EC24ADAB0800008D00", "7EC2505C8D20005A2A8", "7EC26000409370003C6", "7EC27E80247423D0002",
            "7EC286BFF4DFF4D05F0",
        )
        assertEquals(1.0, value(MfrSignal.READY, b))         // capacitor 619 V while driving
        assertEquals(-179.0, value(MfrSignal.MOTOR_RPM_REAR, b))
    }
}
