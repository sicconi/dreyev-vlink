package com.dreyev.vlink.obd

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ParsingTest {
    private fun data(r: ObdResult) = assertIs<ObdResult.Data>(r).bytes.toList()

    @Test fun singleFrame() {
        assertEquals(listOf(0x3C), data(Elm327.parse("410D3C\r\r", "410D")))
    }

    @Test fun spacesAndEchoAndSearching() {
        assertEquals(listOf(0x1A, 0xF8), data(Elm327.parse("010C\rSEARCHING...\r41 0C 1A F8\r", "410C")))
    }

    @Test fun multipleEcusFirstWins() {
        assertEquals(listOf(0x3C), data(Elm327.parse("410D3C\r410D3D", "410D")))
    }

    @Test fun noData() {
        assertIs<ObdResult.NoData>(Elm327.parse("NO DATA\r", "410D"))
        assertIs<ObdResult.Failure>(Elm327.parse("?\r", "410D"))
        assertIs<ObdResult.Failure>(Elm327.parse("CAN ERROR", "410D"))
    }

    @Test fun vinMultiFrameCan() {
        val r = Elm327.parse("014\r0:490201314847\r1:434D3832363333\r2:41303034333532", "4902")
        assertEquals("1HGCM82633A004352", Vin.decode(assertIs<ObdResult.Data>(r).bytes))
    }

    @Test fun vinLegacyMultiLine() {
        val raw = "49020100000031\r4902024847434D\r49020338323633\r49020433413030\r49020534333532"
        val r = Elm327.parse(raw, "4902")
        val vin = Vin.decode(assertIs<ObdResult.Data>(r).bytes)
        assertEquals("1HGCM82633A004352", vin)
    }

    @Test fun vinDescribe() {
        assertEquals("2003 Honda", Vin.describe("1HGCM82633A004352"))
    }

    @Test fun supportedBitmap() {
        val s = StandardPids.supported(0x00, intArrayOf(0x18, 0x3B, 0x80, 0x13))
        assertTrue(0x0D in s && 0x0C in s && 0x04 in s && 0x11 in s && 0x1F in s && 0x20 in s)
    }

    @Test fun decoders() {
        assertEquals(1726.0, StandardPids.rpm(intArrayOf(0x1A, 0xF8)))
        assertEquals(23.0, StandardPids.ambientC(intArrayOf(63)))
        assertEquals(77591.4, StandardPids.odometerKm(intArrayOf(0x00, 0x0B, 0xD6, 0xEA)), 0.01)
    }

    @Test fun defaultPrefix() {
        assertEquals("410D", Elm327.defaultPrefix("010D"))
        assertEquals("62F001", Elm327.defaultPrefix("22 F001"))
        assertEquals("7B8", Elm327.responseHeaderFor("7B0"))
    }
}
