package com.dreyev.vlink.obd

import kotlin.test.Test
import kotlin.test.assertEquals

class UnitsTest {
    private val us = UnitPrefs()
    private val metric = UnitPrefs(UnitSystem.METRIC, TempUnit.C)

    @Test fun defaultsAreUs() {
        assertEquals(UnitSystem.IMPERIAL, us.system)
        assertEquals(TempUnit.F, us.temp)
    }

    @Test fun conversions() {
        assertEquals("47", us.fmtSpeed(75.6))
        assertEquals("76", metric.fmtSpeed(75.6))
        assertEquals("74 °F", us.fmtTemp(23.3))
        assertEquals("23 °C", metric.fmtTemp(23.3))
        assertEquals("35.2", us.fmtTire(242.7))
        assertEquals("2.43", metric.fmtTire(242.7))
        assertEquals("48,213.6", us.fmtDistance(77592.2))
        assertEquals(580.0, us.brakePressure(40.0), 1.0)
    }

    @Test fun caliper() {
        assertEquals(16.6, BrakeEstimator.caliperKn(65.0, 25.5), 0.1)
    }

    @Test fun labels() {
        assertEquals("110° left", UnitPrefs.steering(-110.4))
        assertEquals("+0.21 g", UnitPrefs.signedG(0.214))
        assertEquals("E", UnitPrefs.compass(88.0))
    }
}
