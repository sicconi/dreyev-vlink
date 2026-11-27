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
        b[50] = 0b100                       // AY bit 2 → READY
        assertEquals(-10.0, value(MfrSignal.BATTERY_CURRENT, b))
        assertEquals(360.0, value(MfrSignal.BATTERY_VOLTAGE, b))
        assertEquals(1.0, value(MfrSignal.READY, b))
        val s = IntArray(20); s[12] = 0x4C; s[13] = 0xFF   // M=0x4C, N=0xFF → signed(0xFF4C)/10 = -18.0°
        assertEquals(-18.0, value(MfrSignal.STEERING_ANGLE, s))
    }
}
