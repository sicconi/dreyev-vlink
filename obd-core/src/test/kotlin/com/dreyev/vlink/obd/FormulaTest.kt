package com.dreyev.vlink.obd

import kotlin.test.Test
import kotlin.test.assertEquals

class FormulaTest {
    private fun f(s: String, vararg b: Int) = Formula(s).eval(b)

    @Test fun arithmetic() {
        assertEquals(1726.0, f("(A*256+B)/4", 0x1A, 0xF8))
        assertEquals(23.0, f("A-40", 63))
        assertEquals(-1.5, f("-A/2", 3))
    }

    @Test fun signedAndBits() {
        assertEquals(-110.0, f("signed(A*256+B,16)/10", 0xFB, 0xB4))
        assertEquals(1.0, f("bit(A,1)", 0b10))
        assertEquals(0.0, f("bit(A,0)", 0b10))
        assertEquals(0x10.toDouble(), f("A & 0x10", 0x3F))
        assertEquals(3.0, f("A >> 4", 0x3F))
    }

    @Test fun bytesNeeded() {
        assertEquals(2, Formula("(A*256+B)/4").bytesNeeded)
        assertEquals(4, Formula("D").bytesNeeded)
    }
}
